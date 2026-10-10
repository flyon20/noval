<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Document, Refresh } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus';
import { knowledgeApi } from '@/api/knowledge';
import type { ProjectExtractionCandidate } from '@/types/knowledge';

const props = defineProps<{
  projectId: number | null;
  workId: number | null;
}>();

const emit = defineEmits<{
  reviewed: [candidate: ProjectExtractionCandidate];
  error: [message: string];
  evidenceNavigate: [chapterId: number];
}>();

type ReviewDecision = 'CONFIRMED' | 'REJECTED' | 'SUPERSEDED';

interface ReviewRequestState {
  requestId: number;
  decision: ReviewDecision;
}

const loading = ref(false);
const reviewingRequests = ref<Map<number, ReviewRequestState>>(new Map());
const errorMessage = ref('');
const reviewErrors = ref<Record<number, string>>({});
const statusFilter = ref('PENDING');
const candidates = ref<ProjectExtractionCandidate[]>([]);
const editPayload = ref<Record<number, string>>({});
const reviewNote = ref<Record<number, string>>({});
let loadGeneration = 0;
let reviewScopeGeneration = 0;
let nextReviewRequestId = 0;

const statusOptions = [
  { value: 'PENDING', label: '待审核' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'SUPERSEDED', label: '已替代' },
];

watch(
  () => [props.projectId, props.workId, statusFilter.value] as const,
  () => {
    reviewScopeGeneration++;
    reviewingRequests.value = new Map();
    reviewErrors.value = {};
    void loadCandidates();
  },
  { immediate: true },
);

function statusLabel(status?: string) {
  const key = String(status || '').toUpperCase();
  return statusOptions.find((item) => item.value === key)?.label || '未知状态';
}

function entityTypeLabel(entityType?: string) {
  const labels: Record<string, string> = {
    CHARACTER: '人物',
    PERSON: '人物',
    SETTING: '设定',
    WORLD_SETTING: '世界设定',
    FORESHADOWING: '伏笔',
    FORESHADOW: '伏笔',
    TIMELINE: '时间线',
    EVENT: '事件',
    RELATIONSHIP: '关系',
  };
  return labels[String(entityType || '').toUpperCase()] || '未分类资料';
}

function payloadPreview(candidate: ProjectExtractionCandidate) {
  const raw = editPayload.value[candidate.candidateId] ?? candidate.payloadJson ?? '';
  if (!raw) {
    return '（无载荷）';
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function evidenceText(candidate: ProjectExtractionCandidate) {
  return candidate.evidenceRefsJson || '暂无章节证据';
}

function evidenceChapterId(candidate: ProjectExtractionCandidate) {
  if (candidate.chapterId) {
    return candidate.chapterId;
  }
  const raw = candidate.evidenceRefsJson;
  if (!raw) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    const parsedId = findEvidenceChapterId(parsed);
    if (parsedId) {
      return parsedId;
    }
  } catch {
    const match = raw.match(/(?:chapter(?:Id)?|章节)\D*(\d+)/i);
    const parsedId = Number.parseInt(match?.[1] || '', 10);
    return parsedId > 0 ? parsedId : null;
  }
  return null;
}

function findEvidenceChapterId(value: unknown): number | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findEvidenceChapterId(item);
      if (found) return found;
    }
    return null;
  }
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const record = value as Record<string, unknown>;
  for (const key of ['chapterId', 'sourceChapterId', 'evidenceChapterId']) {
    const candidate = Number(record[key]);
    if (Number.isInteger(candidate) && candidate > 0) {
      return candidate;
    }
  }
  for (const nested of Object.values(record)) {
    const found = findEvidenceChapterId(nested);
    if (found) return found;
  }
  return null;
}

function isReviewing(candidateId: number) {
  return reviewingRequests.value.has(candidateId);
}

function isReviewingDecision(candidateId: number, decision: ReviewDecision) {
  return reviewingRequests.value.get(candidateId)?.decision === decision;
}

function beginReview(candidateId: number, decision: ReviewDecision) {
  const requestId = ++nextReviewRequestId;
  const next = new Map(reviewingRequests.value);
  next.set(candidateId, { requestId, decision });
  reviewingRequests.value = next;
  const nextErrors = { ...reviewErrors.value };
  delete nextErrors[candidateId];
  reviewErrors.value = nextErrors;
  return requestId;
}

function finishReview(candidateId: number, requestId: number) {
  if (reviewingRequests.value.get(candidateId)?.requestId !== requestId) {
    return;
  }
  const next = new Map(reviewingRequests.value);
  next.delete(candidateId);
  reviewingRequests.value = next;
}

async function loadCandidates() {
  const projectId = props.projectId;
  const generation = ++loadGeneration;
  if (!projectId) {
    candidates.value = [];
    return;
  }
  candidates.value = [];
  loading.value = true;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.listExtractionCandidates(projectId, {
      workId: props.workId || undefined,
      status: statusFilter.value || undefined,
      limit: 50,
    });
    if (generation !== loadGeneration || projectId !== props.projectId) {
      return;
    }
    candidates.value = response.data.data ?? [];
    for (const item of candidates.value) {
      if (editPayload.value[item.candidateId] == null) {
        editPayload.value[item.candidateId] = item.payloadJson || '';
      }
    }
  } catch {
    if (generation === loadGeneration && projectId === props.projectId) {
      candidates.value = [];
      errorMessage.value = '提取候选加载失败';
      emit('error', errorMessage.value);
    }
  } finally {
    if (generation === loadGeneration && projectId === props.projectId) {
      loading.value = false;
    }
  }
}

async function requestReview(
  candidate: ProjectExtractionCandidate,
  decision: ReviewDecision,
) {
  const projectId = props.projectId;
  const workId = props.workId;
  if (!projectId || isReviewing(candidate.candidateId)) {
    return;
  }
  const scopeGeneration = reviewScopeGeneration;
  const requestId = beginReview(candidate.candidateId, decision);
  const commands = {
    CONFIRMED: '确认此提取结果',
    SUPERSEDED: '用当前编辑内容替代原结果',
    REJECTED: '拒绝此提取结果',
  };
  try {
    await ElMessageBox.confirm(commands[decision], '审核确认', {
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      type: decision === 'REJECTED' ? 'warning' : 'info',
    });
  } catch {
    finishReview(candidate.candidateId, requestId);
    return;
  }
  if (scopeGeneration !== reviewScopeGeneration || projectId !== props.projectId || workId !== props.workId) {
    finishReview(candidate.candidateId, requestId);
    return;
  }
  try {
    const response = await knowledgeApi.reviewExtractionCandidate(projectId, candidate.candidateId, {
      decision,
      payloadJson: editPayload.value[candidate.candidateId] || candidate.payloadJson,
      reviewNote: reviewNote.value[candidate.candidateId] || undefined,
    });
    if (
      scopeGeneration !== reviewScopeGeneration
      || projectId !== props.projectId
      || workId !== props.workId
      || reviewingRequests.value.get(candidate.candidateId)?.requestId !== requestId
    ) {
      return;
    }
    const next = response.data.data;
    candidates.value = candidates.value.map((item) => (item.candidateId === next.candidateId ? next : item));
    emit('reviewed', next);
  } catch {
    if (
      scopeGeneration === reviewScopeGeneration
      && projectId === props.projectId
      && workId === props.workId
      && reviewingRequests.value.get(candidate.candidateId)?.requestId === requestId
    ) {
      reviewErrors.value = {
        ...reviewErrors.value,
        [candidate.candidateId]: '审核提交失败',
      };
      emit('error', '审核提交失败');
    }
  } finally {
    finishReview(candidate.candidateId, requestId);
  }
}

const pendingCount = computed(() => candidates.value.filter((item) => String(item.status).toUpperCase() === 'PENDING').length);
</script>

<template>
  <div class="project-extraction-review" data-test="project-extraction-review">
    <div class="project-extraction-review__toolbar">
      <el-select v-model="statusFilter" data-test="extraction-status-filter" style="width: 140px">
        <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-button data-test="extraction-refresh" plain :icon="Refresh" :loading="loading" @click="loadCandidates">
        刷新
      </el-button>
      <small data-test="extraction-pending-count">待处理 {{ pendingCount }}</small>
    </div>

    <p v-if="errorMessage" class="project-extraction-review__error" data-test="extraction-error">{{ errorMessage }}</p>

    <div class="project-extraction-review__list">
      <article
        v-for="candidate in candidates"
        :key="candidate.candidateId"
        class="project-extraction-review__card"
        :data-test="`extraction-candidate-${candidate.candidateId}`"
      >
        <header>
          <strong>{{ entityTypeLabel(candidate.entityType) }}</strong>
          <span>{{ statusLabel(candidate.status) }}</span>
          <small v-if="candidate.confidence != null">置信度 {{ (candidate.confidence * 100).toFixed(0) }}%</small>
          <small v-if="candidate.generationId">Generation {{ candidate.generationId }}</small>
        </header>
        <pre class="project-extraction-review__payload" data-test="extraction-payload">{{ payloadPreview(candidate) }}</pre>
        <el-input
          v-if="String(candidate.status).toUpperCase() === 'PENDING'"
          v-model="editPayload[candidate.candidateId]"
          type="textarea"
          :rows="3"
          :disabled="isReviewing(candidate.candidateId)"
          data-test="extraction-edit-payload"
          placeholder="编辑载荷 JSON"
        />
        <div class="project-extraction-review__evidence" data-test="extraction-evidence">
          <span>证据：{{ evidenceText(candidate) }}</span>
          <el-button
            v-if="evidenceChapterId(candidate)"
            text
            size="small"
            :icon="Document"
            data-test="extraction-evidence-link"
            @click="emit('evidenceNavigate', evidenceChapterId(candidate)!)"
          >
            查看章节
          </el-button>
        </div>
        <el-input
          v-if="String(candidate.status).toUpperCase() === 'PENDING'"
          v-model="reviewNote[candidate.candidateId]"
          :disabled="isReviewing(candidate.candidateId)"
          data-test="extraction-review-note"
          placeholder="审核备注（可选）"
        />
        <p
          v-if="reviewErrors[candidate.candidateId]"
          class="project-extraction-review__error"
          data-test="extraction-review-error"
        >
          {{ reviewErrors[candidate.candidateId] }}
        </p>
        <div v-if="String(candidate.status).toUpperCase() === 'PENDING'" class="project-extraction-review__actions">
          <el-button
            type="primary"
            size="small"
            data-test="extraction-confirm"
            :loading="isReviewingDecision(candidate.candidateId, 'CONFIRMED')"
            :disabled="isReviewing(candidate.candidateId)"
            @click="requestReview(candidate, 'CONFIRMED')"
          >
            确认
          </el-button>
          <el-button
            size="small"
            data-test="extraction-edit-confirm"
            :loading="isReviewingDecision(candidate.candidateId, 'SUPERSEDED')"
            :disabled="isReviewing(candidate.candidateId)"
            @click="requestReview(candidate, 'SUPERSEDED')"
          >
            编辑并替代
          </el-button>
          <el-button
            type="danger"
            plain
            size="small"
            data-test="extraction-reject"
            :loading="isReviewingDecision(candidate.candidateId, 'REJECTED')"
            :disabled="isReviewing(candidate.candidateId)"
            @click="requestReview(candidate, 'REJECTED')"
          >
            拒绝
          </el-button>
        </div>
        <details class="project-extraction-review__diag">
          <summary>诊断信息</summary>
          <code>candidate={{ candidate.candidateId }} type={{ candidate.entityType || '-' }} gen={{ candidate.generationId || '-' }} chapter={{ candidate.chapterId || '-' }}</code>
        </details>
      </article>
      <div v-if="!candidates.length && !loading" class="project-extraction-review__empty" data-test="extraction-empty">
        当前没有可审核的提取结果
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.project-extraction-review {
  display: grid;
  gap: 0.75rem;
}

.project-extraction-review__toolbar,
.project-extraction-review__actions,
.project-extraction-review__evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
}

.project-extraction-review__card {
  display: grid;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 85%, transparent);
  border-radius: 8px;
}

.project-extraction-review__card header {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: baseline;
}

.project-extraction-review__payload {
  margin: 0;
  max-height: 10rem;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.8rem;
  background: color-mix(in srgb, var(--el-fill-color-light) 80%, transparent);
  border-radius: 0.5rem;
  padding: 0.5rem;
}

.project-extraction-review__evidence,
.project-extraction-review__empty,
.project-extraction-review__diag,
.project-extraction-review__error {
  margin: 0;
  font-size: 0.85rem;
}

.project-extraction-review__evidence {
  justify-content: space-between;
}

.project-extraction-review__evidence span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.project-extraction-review__error {
  color: var(--el-color-danger);
}
</style>
