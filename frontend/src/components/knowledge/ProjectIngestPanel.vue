<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Close, Delete, Document, FolderOpened, Refresh, RefreshLeft, Upload } from '@element-plus/icons-vue';
import { knowledgeApi } from '@/api/knowledge';
import type {
  ProjectDocumentBatch,
  ProjectDocumentKind,
  ProjectDocumentQuestion,
} from '@/types/knowledge';

const props = defineProps<{
  projectId: number | null;
  workId: number | null;
}>();

const emit = defineEmits<{
  submitted: [batch: ProjectDocumentBatch];
  ready: [batch: ProjectDocumentBatch];
  error: [message: string];
}>();

const POLL_MS = 2_000;
const TERMINAL_STATUSES = new Set(['READY', 'RETRYABLE_FAILED', 'TERMINAL_FAILED', 'CANCELLED']);
const ACCEPTED_EXTENSIONS = ['.txt', '.md', '.markdown', '.zip'];

const kindOptions: Array<{ value: ProjectDocumentKind; label: string }> = [
  { value: 'AUTO', label: '智能分类' },
  { value: 'NOVEL_TEXT', label: '章节正文' },
  { value: 'OUTLINE', label: '故事大纲' },
  { value: 'CHAPTER_OUTLINE', label: '分章细纲' },
  { value: 'CHARACTER_PROFILE', label: '人物设定' },
  { value: 'WORLD_SETTING', label: '世界设定' },
  { value: 'TIMELINE', label: '时间线' },
  { value: 'FORESHADOWING_NOTE', label: '伏笔记录' },
  { value: 'REFERENCE', label: '参考资料' },
  { value: 'READER_FEEDBACK', label: '读者反馈' },
];

const declaredKind = ref<ProjectDocumentKind>('AUTO');
const selectedFiles = ref<File[]>([]);
const relativePaths = ref<string[]>([]);
const batches = ref<ProjectDocumentBatch[]>([]);
const questions = ref<ProjectDocumentQuestion[]>([]);
const questionAnswers = ref<Record<number, ProjectDocumentKind>>({});
const submitting = ref(false);
const loading = ref(false);
const answeringQuestionId = ref<number | null>(null);
const retryingBatchId = ref<number | null>(null);
const cancellingBatchId = ref<number | null>(null);
const discardingBatchId = ref<number | null>(null);
const localError = ref('');
const uploadKey = ref(createIdempotencyKey());
const notifiedReadyBatchIds = new Set<number>();
let pollTimer: ReturnType<typeof setInterval> | null = null;
let scopeGeneration = 0;

const activeBatch = computed(() => (
  batches.value.find((batch) => !isTerminal(batch.status))
  ?? batches.value.find((batch) => normalizeStatus(batch.status) !== 'CANCELLED')
  ?? null
));
const canSubmit = computed(() => Boolean(
  props.projectId && props.workId && selectedFiles.value.length && !submitting.value,
));
const selectedSize = computed(() => selectedFiles.value.reduce((sum, file) => sum + file.size, 0));
const pendingQuestions = computed(() => questions.value.filter((question) => normalizeStatus(question.status) === 'PENDING'));
const hasRunningBatch = computed(() => batches.value.some((batch) => !isTerminal(batch.status)));
const shouldOpenHistory = computed(() => (
  batches.value.length > 0
  && !activeBatch.value
  && batches.value.some((batch) => normalizeStatus(batch.status) === 'CANCELLED')
));

watch(
  () => [props.projectId, props.workId] as const,
  () => {
    scopeGeneration++;
    stopPolling();
    selectedFiles.value = [];
    relativePaths.value = [];
    batches.value = [];
    questions.value = [];
    questionAnswers.value = {};
    localError.value = '';
    uploadKey.value = createIdempotencyKey();
    void loadBatches();
  },
  { immediate: true },
);

watch(hasRunningBatch, (running) => {
  if (running) {
    startPolling();
  } else {
    stopPolling();
  }
});

onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange));

onBeforeUnmount(() => {
  scopeGeneration++;
  stopPolling();
  document.removeEventListener('visibilitychange', onVisibilityChange);
});

function normalizeStatus(value?: string) {
  return String(value || '').toUpperCase();
}

function isTerminal(value?: string) {
  return TERMINAL_STATUSES.has(normalizeStatus(value));
}

function isWaitingConfirmation(batch?: ProjectDocumentBatch | null) {
  return normalizeStatus(batch?.status) === 'WAITING_CONFIRMATION';
}

function statusLabel(batch: ProjectDocumentBatch) {
  if (batch.statusLabel) {
    return batch.statusLabel;
  }
  return {
    STORED: '等待解析',
    PARSING: '正在解析',
    WAITING_CONFIRMATION: '等待确认',
    PARSED_PENDING_INDEX: '正在建立索引',
    READY: '可用于 AI',
    RETRYABLE_FAILED: '可重试',
    TERMINAL_FAILED: '处理失败',
    CANCELLED: '已取消',
  }[normalizeStatus(batch.status)] || batch.status;
}

function stageLabel(batch: ProjectDocumentBatch) {
  const parsed = Math.max(0, batch.parsedFiles || 0);
  const indexed = Math.max(0, batch.indexedFiles || 0);
  const total = Math.max(0, batch.totalFiles || 0);
  return `${parsed}/${total} 已解析 · ${indexed}/${total} 已入库`;
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function selectFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = '';
  if (!files.length) return;
  const unsupported = files.find((file) => !ACCEPTED_EXTENSIONS.some((extension) => file.name.toLowerCase().endsWith(extension)));
  if (unsupported) {
    showError(`${unsupported.name} 的格式不受支持`);
    return;
  }
  selectedFiles.value = files;
  relativePaths.value = files.map((file) => normalizeRelativePath(file.webkitRelativePath || file.name));
  uploadKey.value = createIdempotencyKey();
  localError.value = '';
}

function normalizeRelativePath(path: string) {
  return path.replace(/\\/g, '/').replace(/^\/+/, '') || 'document.txt';
}

function clearSelection() {
  selectedFiles.value = [];
  relativePaths.value = [];
  uploadKey.value = createIdempotencyKey();
}

async function submitBatch() {
  if (!canSubmit.value || !props.projectId || !props.workId) return;
  const generation = scopeGeneration;
  const projectId = props.projectId;
  const workId = props.workId;
  submitting.value = true;
  localError.value = '';
  try {
    const response = await knowledgeApi.createProjectDocumentBatch(
      projectId,
      workId,
      selectedFiles.value,
      relativePaths.value,
      declaredKind.value,
      uploadKey.value,
    );
    if (!scopeMatches(generation, projectId, workId)) return;
    const batch = response.data.data;
    mergeBatch(batch);
    clearSelection();
    emit('submitted', batch);
    startPolling();
    await refreshBatch(batch.batchId, generation, projectId, workId);
  } catch (error) {
    if (scopeMatches(generation, projectId, workId)) {
      showError(requestError(error, '资料上传失败'));
    }
  } finally {
    if (scopeMatches(generation, projectId, workId)) submitting.value = false;
  }
}

async function loadBatches(options: { quiet?: boolean } = {}) {
  if (!props.projectId || !props.workId) return;
  const generation = scopeGeneration;
  const projectId = props.projectId;
  const workId = props.workId;
  if (!options.quiet) loading.value = true;
  try {
    const response = await knowledgeApi.listProjectDocumentBatches(projectId, workId, 20);
    if (!scopeMatches(generation, projectId, workId)) return;
    batches.value = response.data.data || [];
    notifyReady(batches.value);
    const current = activeBatch.value;
    if (isWaitingConfirmation(current) && current?.pendingQuestions) {
      await loadQuestions(current.batchId, generation, projectId, workId);
    } else {
      questions.value = [];
    }
  } catch (error) {
    if (!options.quiet && scopeMatches(generation, projectId, workId)) {
      showError(requestError(error, '导入记录加载失败'));
    }
  } finally {
    if (scopeMatches(generation, projectId, workId)) loading.value = false;
  }
}

async function refreshBatch(batchId: number, generation = scopeGeneration, projectId = props.projectId, workId = props.workId) {
  if (!projectId || !workId) return;
  try {
    const response = await knowledgeApi.getProjectDocumentBatch(projectId, batchId);
    if (!scopeMatches(generation, projectId, workId)) return;
    const batch = response.data.data;
    mergeBatch(batch);
    notifyReady([batch]);
    if (isWaitingConfirmation(batch) && batch.pendingQuestions) {
      await loadQuestions(batchId, generation, projectId, workId);
    } else if (activeBatch.value?.batchId === batchId) {
      questions.value = [];
    }
  } catch (error) {
    if (scopeMatches(generation, projectId, workId)) {
      showError(requestError(error, '导入进度刷新失败'));
    }
  }
}

async function loadQuestions(batchId: number, generation: number, projectId: number, workId: number) {
  const response = await knowledgeApi.listProjectDocumentQuestions(projectId, batchId);
  if (!scopeMatches(generation, projectId, workId)) return;
  questions.value = (response.data.data || []).filter((question) => normalizeStatus(question.status) === 'PENDING');
  for (const question of questions.value) {
    questionAnswers.value[question.questionId] ||= firstQuestionOption(question) || 'REFERENCE';
  }
}

async function answerQuestion(question: ProjectDocumentQuestion) {
  if (!props.projectId || !activeBatch.value || !isWaitingConfirmation(activeBatch.value)
    || activeBatch.value.batchId !== question.batchId) return;
  const answer = questionAnswers.value[question.questionId];
  if (!answer) return;
  const generation = scopeGeneration;
  const projectId = props.projectId;
  const workId = props.workId;
  answeringQuestionId.value = question.questionId;
  try {
    await knowledgeApi.answerProjectDocumentQuestion(projectId, question.batchId, question.questionId, answer);
    if (!scopeMatches(generation, projectId, workId)) return;
    await refreshBatch(question.batchId, generation, projectId, workId);
  } catch (error) {
    if (scopeMatches(generation, projectId, workId)) showError(requestError(error, '分类确认失败'));
  } finally {
    if (scopeMatches(generation, projectId, workId)) answeringQuestionId.value = null;
  }
}

async function retryBatch(batch: ProjectDocumentBatch) {
  if (!props.projectId || !props.workId) return;
  const generation = scopeGeneration;
  const projectId = props.projectId;
  const workId = props.workId;
  retryingBatchId.value = batch.batchId;
  try {
    const response = await knowledgeApi.retryProjectDocumentBatch(projectId, batch.batchId);
    if (!scopeMatches(generation, projectId, workId)) return;
    mergeBatch(response.data.data);
    startPolling();
  } catch (error) {
    if (scopeMatches(generation, projectId, workId)) showError(requestError(error, '批次重试失败'));
  } finally {
    if (scopeMatches(generation, projectId, workId)) retryingBatchId.value = null;
  }
}

async function cancelBatch(batch: ProjectDocumentBatch) {
  if (!props.projectId || !props.workId) return;
  const generation = scopeGeneration;
  const projectId = props.projectId;
  const workId = props.workId;
  cancellingBatchId.value = batch.batchId;
  try {
    const response = await knowledgeApi.cancelProjectDocumentBatch(projectId, batch.batchId);
    if (!scopeMatches(generation, projectId, workId)) return;
    mergeBatch(response.data.data);
    if (questions.value.some((question) => question.batchId === batch.batchId)) {
      questions.value = [];
    }
  } catch (error) {
    if (scopeMatches(generation, projectId, workId)) showError(requestError(error, '取消批次失败'));
  } finally {
    if (scopeMatches(generation, projectId, workId)) cancellingBatchId.value = null;
  }
}

async function discardBatch(batch: ProjectDocumentBatch) {
  if (!props.projectId || !props.workId || normalizeStatus(batch.status) !== 'CANCELLED') return;
  try {
    await ElMessageBox.confirm(
      `将删除批次 #${batch.batchId} 的上传文件和未入库解析结果，此操作无法撤销。`,
      '删除误传记录',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '保留记录',
        type: 'warning',
        distinguishCancelAndClose: true,
      },
    );
  } catch {
    return;
  }

  const generation = scopeGeneration;
  const projectId = props.projectId;
  const workId = props.workId;
  discardingBatchId.value = batch.batchId;
  localError.value = '';
  try {
    await knowledgeApi.discardProjectDocumentBatch(projectId, batch.batchId);
    if (!scopeMatches(generation, projectId, workId)) return;
    batches.value = batches.value.filter((item) => item.batchId !== batch.batchId);
    questions.value = questions.value.filter((question) => question.batchId !== batch.batchId);
    ElMessage.success('误传批次已删除');
  } catch (error) {
    if (scopeMatches(generation, projectId, workId)) {
      showError(requestError(error, '批次删除失败'));
    }
  } finally {
    if (scopeMatches(generation, projectId, workId)) discardingBatchId.value = null;
  }
}

function mergeBatch(batch: ProjectDocumentBatch) {
  const index = batches.value.findIndex((item) => item.batchId === batch.batchId);
  if (index >= 0) {
    batches.value.splice(index, 1, batch);
  } else {
    batches.value.unshift(batch);
  }
}

function notifyReady(items: ProjectDocumentBatch[]) {
  for (const batch of items) {
    if (normalizeStatus(batch.status) !== 'READY' || notifiedReadyBatchIds.has(batch.batchId)) continue;
    notifiedReadyBatchIds.add(batch.batchId);
    emit('ready', batch);
  }
}

function questionOptions(question: ProjectDocumentQuestion) {
  try {
    const parsed = JSON.parse(question.optionsJson || '[]');
    return Array.isArray(parsed) ? parsed.filter((value): value is ProjectDocumentKind => typeof value === 'string') : [];
  } catch {
    return [];
  }
}

function firstQuestionOption(question: ProjectDocumentQuestion) {
  return questionOptions(question)[0];
}

function kindLabel(value: string) {
  return kindOptions.find((option) => option.value === value)?.label || value;
}

function startPolling() {
  if (pollTimer || typeof document === 'undefined') return;
  pollTimer = setInterval(() => {
    if (document.visibilityState !== 'hidden') void loadBatches({ quiet: true });
  }, POLL_MS);
}

function stopPolling() {
  if (!pollTimer) return;
  clearInterval(pollTimer);
  pollTimer = null;
}

function onVisibilityChange() {
  if (document.visibilityState !== 'hidden') void loadBatches({ quiet: true });
}

function scopeMatches(generation: number, projectId: number, workId: number) {
  return generation === scopeGeneration && props.projectId === projectId && props.workId === workId;
}

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `document-batch-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function showError(message: string) {
  localError.value = message;
  emit('error', message);
}

function requestError(error: unknown, fallback: string) {
  const response = (error as { response?: { status?: number; data?: { message?: string } } })?.response;
  const message = response?.data?.message
    || (error as { message?: string })?.message;
  if (response?.status === 409) {
    return `${fallback}：批次状态已变化，请刷新后重试`;
  }
  if ((response?.status || 0) >= 500 || /internal server error/i.test(String(message || ''))) {
    return `${fallback}：服务暂时不可用，请稍后重试`;
  }
  return String(message || `${fallback}，请稍后重试`);
}
</script>

<template>
  <div class="project-ingest-panel" data-test="project-ingest-panel">
    <div class="project-ingest-panel__controls">
      <el-select v-model="declaredKind" data-test="ingest-kind" aria-label="资料类型" :disabled="submitting">
        <el-option v-for="option in kindOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <label class="project-ingest-panel__picker" data-test="ingest-file-input">
        <input type="file" multiple accept=".txt,.md,.markdown,.zip" :disabled="!workId || submitting" @change="selectFiles" />
        <el-icon><Document /></el-icon>
        选择文件
      </label>
      <label class="project-ingest-panel__picker" data-test="ingest-directory-input">
        <input type="file" multiple webkitdirectory directory :disabled="!workId || submitting" @change="selectFiles" />
        <el-icon><FolderOpened /></el-icon>
        选择文件夹
      </label>
      <el-button
        type="primary"
        :icon="Upload"
        data-test="ingest-submit"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="submitBatch"
      >
        上传
      </el-button>
      <el-tooltip content="刷新" placement="top">
        <el-button circle :icon="Refresh" data-test="ingest-refresh" :loading="loading" :disabled="!workId" @click="loadBatches()" />
      </el-tooltip>
    </div>

    <section v-if="selectedFiles.length" class="project-ingest-panel__selection" data-test="ingest-selection">
      <div>
        <strong>{{ selectedFiles.length }} 个文件</strong>
        <small>{{ formatBytes(selectedSize) }}</small>
      </div>
      <span>{{ relativePaths[0] }}<template v-if="relativePaths.length > 1"> 等</template></span>
      <el-tooltip content="清除" placement="top">
        <el-button text circle :icon="Close" aria-label="清除已选文件" @click="clearSelection" />
      </el-tooltip>
    </section>

    <p v-if="localError" class="project-ingest-panel__error" data-test="ingest-error" role="alert">{{ localError }}</p>

    <section v-if="activeBatch" class="project-ingest-panel__batch" data-test="ingest-active-batch">
      <header>
        <div>
          <strong>{{ statusLabel(activeBatch) }}</strong>
          <small>{{ stageLabel(activeBatch) }}</small>
        </div>
        <span>#{{ activeBatch.batchId }}</span>
      </header>
      <el-progress :percentage="Math.max(0, Math.min(100, activeBatch.progress || 0))" :stroke-width="8" />
      <p v-if="activeBatch.errorSummary" class="project-ingest-panel__error">{{ activeBatch.errorSummary }}</p>
      <div class="project-ingest-panel__batch-actions">
        <el-button
          v-if="normalizeStatus(activeBatch.status) === 'RETRYABLE_FAILED'"
          :icon="RefreshLeft"
          :loading="retryingBatchId === activeBatch.batchId"
          data-test="ingest-retry"
          @click="retryBatch(activeBatch)"
        >
          重试
        </el-button>
        <el-button
          v-if="!isTerminal(activeBatch.status) && normalizeStatus(activeBatch.status) !== 'PARSED_PENDING_INDEX'"
          :icon="Close"
          :loading="cancellingBatchId === activeBatch.batchId"
          data-test="ingest-cancel"
          @click="cancelBatch(activeBatch)"
        >
          取消
        </el-button>
      </div>
      <details class="project-ingest-panel__diag">
        <summary>诊断信息</summary>
        <code>
          stage={{ activeBatch.stage || '-' }} attempt={{ activeBatch.attempt || 1 }}/{{ activeBatch.maxAttempts || '-' }}
          error={{ activeBatch.errorCode || '-' }}
        </code>
      </details>
    </section>

    <section
      v-if="isWaitingConfirmation(activeBatch) && pendingQuestions.length"
      class="project-ingest-panel__questions"
      data-test="ingest-questions"
    >
      <article v-for="question in pendingQuestions" :key="question.questionId" :data-test="`ingest-question-${question.questionId}`">
        <div>
          <strong>{{ question.prompt }}</strong>
          <small>{{ question.relativePath }}</small>
        </div>
        <el-select v-model="questionAnswers[question.questionId]" aria-label="确认资料类型">
          <el-option
            v-for="option in questionOptions(question)"
            :key="option"
            :label="kindLabel(option)"
            :value="option"
          />
        </el-select>
        <el-button
          type="primary"
          :loading="answeringQuestionId === question.questionId"
          @click="answerQuestion(question)"
        >
          确认
        </el-button>
      </article>
    </section>

    <details
      v-if="batches.length"
      class="project-ingest-panel__history"
      data-test="ingest-history"
      :open="shouldOpenHistory"
    >
      <summary>最近导入</summary>
      <div v-for="batch in batches" :key="batch.batchId" class="project-ingest-panel__history-row">
        <span>#{{ batch.batchId }}</span>
        <strong>{{ statusLabel(batch) }}</strong>
        <el-button
          v-if="normalizeStatus(batch.status) === 'CANCELLED'"
          class="project-ingest-panel__history-action"
          text
          type="danger"
          :icon="Delete"
          :loading="discardingBatchId === batch.batchId"
          :disabled="discardingBatchId !== null"
          :data-test="`ingest-discard-${batch.batchId}`"
          :aria-label="`删除批次 ${batch.batchId}`"
          @click="discardBatch(batch)"
        >
          删除记录
        </el-button>
        <small>{{ batch.totalFiles || 0 }} 个文件 · {{ batch.progress || 0 }}%</small>
      </div>
    </details>

    <div v-if="!batches.length && !loading" class="project-ingest-panel__empty" data-test="ingest-empty">暂无导入记录</div>
  </div>
</template>

<style scoped lang="scss">
.project-ingest-panel {
  display: grid;
  gap: 0.75rem;
}

.project-ingest-panel__controls,
.project-ingest-panel__selection,
.project-ingest-panel__batch header,
.project-ingest-panel__batch-actions,
.project-ingest-panel__questions article,
.project-ingest-panel__history-row {
  display: flex;
  align-items: center;
  gap: 0.55rem;
}

.project-ingest-panel__controls {
  flex-wrap: wrap;
}

.project-ingest-panel__controls :deep(.el-select) {
  width: min(10rem, 100%);
}

.project-ingest-panel__picker {
  position: relative;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0 0.8rem;
  border: 1px dashed color-mix(in srgb, var(--color-border) 85%, transparent);
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.85rem;
}

.project-ingest-panel__picker input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

.project-ingest-panel__controls :deep(.el-button),
.project-ingest-panel__batch-actions :deep(.el-button) {
  min-height: 44px;
}

.project-ingest-panel__selection,
.project-ingest-panel__batch,
.project-ingest-panel__questions article,
.project-ingest-panel__history {
  padding: 0.7rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 85%, transparent);
  border-radius: 6px;
}

.project-ingest-panel__selection > div,
.project-ingest-panel__batch header > div,
.project-ingest-panel__questions article > div {
  min-width: 0;
  display: grid;
  gap: 0.15rem;
}

.project-ingest-panel__selection > div,
.project-ingest-panel__batch header > div,
.project-ingest-panel__questions article > div {
  flex: 1;
}

.project-ingest-panel__selection > span,
.project-ingest-panel__selection small,
.project-ingest-panel__batch small,
.project-ingest-panel__questions small,
.project-ingest-panel__history-row small,
.project-ingest-panel__diag,
.project-ingest-panel__empty {
  color: var(--el-text-color-secondary);
  font-size: 0.8rem;
  overflow-wrap: anywhere;
}

.project-ingest-panel__batch {
  display: grid;
  gap: 0.65rem;
}

.project-ingest-panel__batch header {
  justify-content: space-between;
}

.project-ingest-panel__questions {
  display: grid;
  gap: 0.5rem;
}

.project-ingest-panel__questions article :deep(.el-select) {
  width: 9rem;
}

.project-ingest-panel__history > summary,
.project-ingest-panel__diag > summary {
  cursor: pointer;
}

.project-ingest-panel__history-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  margin-top: 0.5rem;
}

.project-ingest-panel__history-row > small {
  grid-column: 3;
}

.project-ingest-panel__history-action {
  grid-column: 4;
  grid-row: 1;
  min-height: 44px;
}

.project-ingest-panel__error {
  margin: 0;
  color: var(--el-color-danger);
  font-size: 0.82rem;
}

@media (max-width: 560px) {
  .project-ingest-panel__controls > *,
  .project-ingest-panel__picker,
  .project-ingest-panel__controls :deep(.el-select) {
    width: 100%;
    justify-content: center;
  }

  .project-ingest-panel__questions article {
    align-items: stretch;
    flex-direction: column;
  }

  .project-ingest-panel__questions article :deep(.el-select),
  .project-ingest-panel__questions article :deep(.el-button) {
    width: 100%;
    min-height: 44px;
  }

  .project-ingest-panel__history-row {
    grid-template-columns: auto minmax(0, 1fr) auto;
  }

  .project-ingest-panel__history-row > small {
    grid-column: 2 / 4;
  }

  .project-ingest-panel__history-action {
    grid-column: 3;
  }
}
</style>
