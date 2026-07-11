<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import type {
  RuntimeSkill,
  SkillCandidate,
  SkillCandidateCreatePayload,
  SkillCandidatePage,
  SkillEvalResult,
} from '@/types/knowledge';

defineOptions({
  name: 'AdminSkillGovernanceView',
});

const pageSize = 20;
const runtimeSkills = ref<RuntimeSkill[]>([]);
const candidates = ref<SkillCandidate[]>([]);
const candidatePage = ref<SkillCandidatePage>({
  page: 1,
  pageSize,
  total: 0,
  hasNext: false,
  items: [],
});
const statusFilter = ref('');
const loading = ref(false);
const creatingSkill = ref(false);
const statusMessage = ref('');
const errorMessage = ref('');
const reviewingIds = ref<Set<number>>(new Set());
const reviewNotes = reactive<Record<number, string>>({});
const uploadForm = reactive<SkillCandidateCreatePayload>({
  skillId: '',
  title: '',
  content: '',
  evalResultJson: '',
});
const selectedFileName = ref('');

const hasPrev = computed(() => candidatePage.value.page > 1);
const hasNext = computed(() => candidatePage.value.hasNext);

onMounted(() => loadDashboard(1));

async function loadDashboard(page = candidatePage.value.page) {
  loading.value = true;
  try {
    const response = await knowledgeApi.getSkillDashboard({
      page,
      pageSize,
      ...(statusFilter.value ? { status: statusFilter.value } : {}),
    });
    const data = response.data.data;
    runtimeSkills.value = data?.runtimeSkills ?? [];
    candidatePage.value = data?.candidates ?? emptyPage(page);
    candidates.value = candidatePage.value.items ?? [];
  } finally {
    loading.value = false;
  }
}

async function createCandidate() {
  const skillId = uploadForm.skillId.trim();
  const title = uploadForm.title.trim();
  const content = uploadForm.content.trim();
  if (!skillId || !title || !content) {
    errorMessage.value = '请填写技能 ID、标题和内容';
    return;
  }
  creatingSkill.value = true;
  statusMessage.value = '';
  errorMessage.value = '';
  try {
    await knowledgeApi.createSkillCandidate({
      skillId,
      title,
      content,
      ...(uploadForm.evalResultJson?.trim() ? { evalResultJson: uploadForm.evalResultJson.trim() } : {}),
    });
    uploadForm.skillId = '';
    uploadForm.title = '';
    uploadForm.content = '';
    uploadForm.evalResultJson = '';
    statusMessage.value = '技能候选已创建';
    await loadDashboard(1);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '技能候选创建失败';
  } finally {
    creatingSkill.value = false;
  }
}

async function handleSkillFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  selectedFileName.value = file.name;
  try {
    const content = await readTextFile(file);
    uploadForm.content = content;
    if (!uploadForm.title.trim()) {
      uploadForm.title = inferTitleFromMarkdown(content, file.name);
    }
    if (!uploadForm.skillId.trim()) {
      uploadForm.skillId = inferSkillIdFromFilename(file.name);
    }
    errorMessage.value = '';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Markdown 文件读取失败';
  } finally {
    input.value = '';
  }
}

function readTextFile(file: File): Promise<string> {
  const text = file.text;
  if (typeof text === 'function') {
    return text.call(file);
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('Markdown 文件读取失败'));
    reader.readAsText(file, 'utf-8');
  });
}

function inferTitleFromMarkdown(content: string, filename: string) {
  const heading = content.split(/\r?\n/).find((line) => /^#\s+/.test(line.trim()));
  if (heading) {
    return heading.replace(/^#\s+/, '').trim();
  }
  return filename.replace(/\.(md|markdown|txt)$/i, '').replace(/[-_]+/g, ' ').trim();
}

function inferSkillIdFromFilename(filename: string) {
  const base = filename.replace(/\.(md|markdown|txt)$/i, '');
  return base
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    || 'uploaded-skill';
}

function emptyPage(page: number): SkillCandidatePage {
  return {
    page,
    pageSize,
    total: 0,
    hasNext: false,
    items: [],
  };
}

function search() {
  void loadDashboard(1);
}

function prevPage() {
  if (hasPrev.value) {
    void loadDashboard(candidatePage.value.page - 1);
  }
}

function nextPage() {
  if (hasNext.value) {
    void loadDashboard(candidatePage.value.page + 1);
  }
}

async function review(candidate: SkillCandidate, decision: 'APPROVED' | 'REJECTED') {
  reviewingIds.value = new Set(reviewingIds.value).add(candidate.id);
  try {
    const note = reviewNotes[candidate.id]?.trim();
    const response = await knowledgeApi.reviewSkillCandidate(candidate.id, {
      decision,
      ...(note ? { note } : {}),
    });
    updateCandidateRow(response.data.data);
  } finally {
    const next = new Set(reviewingIds.value);
    next.delete(candidate.id);
    reviewingIds.value = next;
  }
}

async function publishCandidate(candidate: SkillCandidate) {
  reviewingIds.value = new Set(reviewingIds.value).add(candidate.id);
  try {
    const response = await knowledgeApi.publishSkillCandidate(candidate.id);
    updateCandidateRow(response.data.data);
  } finally {
    const next = new Set(reviewingIds.value);
    next.delete(candidate.id);
    reviewingIds.value = next;
  }
}

async function disableCandidate(candidate: SkillCandidate) {
  reviewingIds.value = new Set(reviewingIds.value).add(candidate.id);
  try {
    const response = await knowledgeApi.disableSkillCandidate(candidate.id);
    updateCandidateRow(response.data.data);
  } finally {
    const next = new Set(reviewingIds.value);
    next.delete(candidate.id);
    reviewingIds.value = next;
  }
}

async function rollbackCandidate(candidate: SkillCandidate) {
  reviewingIds.value = new Set(reviewingIds.value).add(candidate.id);
  try {
    await knowledgeApi.rollbackSkillCandidate(candidate.id);
    await loadDashboard(candidatePage.value.page);
  } finally {
    const next = new Set(reviewingIds.value);
    next.delete(candidate.id);
    reviewingIds.value = next;
  }
}

function updateCandidateRow(updated: SkillCandidate) {
  candidates.value = candidates.value.map((item) => (item.id === updated.id ? updated : item));
  candidatePage.value = {
    ...candidatePage.value,
    items: candidates.value,
  };
}

function statusType(status: string) {
  if (status === 'APPROVED') return 'success';
  if (status === 'PUBLISHED') return 'success';
  if (status === 'REJECTED') return 'danger';
  if (status === 'DISABLED') return 'danger';
  if (status === 'ROLLED_BACK') return 'info';
  if (status === 'PENDING') return 'warning';
  return 'info';
}

function percent(value?: number) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '-';
  }
  return `${Math.round(value * 100)}%`;
}

function evalMetrics(candidate: SkillCandidate): SkillEvalResult | null {
  if (candidate.evalResult) {
    return candidate.evalResult;
  }
  if (
    candidate.requiredToolPassRate !== undefined ||
    candidate.evidencePassRate !== undefined ||
    candidate.faithfulnessPassRate !== undefined
  ) {
    return {
      requiredToolPassRate: candidate.requiredToolPassRate,
      evidencePassRate: candidate.evidencePassRate,
      faithfulnessPassRate: candidate.faithfulnessPassRate,
    };
  }
  if (candidate.evalResultJson) {
    try {
      const parsed = JSON.parse(candidate.evalResultJson) as SkillEvalResult & { metrics?: SkillEvalResult };
      return parsed.metrics ?? parsed;
    } catch {
      return null;
    }
  }
  return null;
}
</script>

<template>
  <main class="admin-skill-governance">
    <section class="runtime-skills">
      <header class="section-header">
        <div>
          <h1>Agent 技能</h1>
          <p>运行时已启用 {{ runtimeSkills.length }} 个技能包</p>
        </div>
      </header>

      <div v-if="runtimeSkills.length" class="runtime-skill-grid">
        <article v-for="skill in runtimeSkills" :key="skill.skillId" class="runtime-skill">
          <div class="runtime-skill__title">
            <strong>{{ skill.skillId }}</strong>
            <el-tag v-if="skill.version" size="small" type="info">{{ skill.version }}</el-tag>
          </div>
          <div v-if="skill.intents?.length" class="tag-row">
            <el-tag v-for="intent in skill.intents" :key="intent" size="small">{{ intent }}</el-tag>
          </div>
          <div v-if="skill.triggers?.length" class="tag-row">
            <el-tag v-for="trigger in skill.triggers" :key="trigger" size="small" type="success">{{ trigger }}</el-tag>
          </div>
        </article>
      </div>
      <el-empty v-else description="暂无运行时技能" />
    </section>

    <section class="skill-upload">
      <header class="section-header">
        <div>
          <h2>上传技能</h2>
          <p>创建候选后进入审核和发布流程，发布后 worker 才会加载。</p>
        </div>
      </header>
      <div class="skill-upload__status" aria-live="polite">
        <span v-if="statusMessage" class="skill-upload__ok">{{ statusMessage }}</span>
        <span v-if="errorMessage" class="skill-upload__error">{{ errorMessage }}</span>
      </div>
      <label class="skill-upload__file">
        <span>Markdown 文件</span>
        <input
          data-test="skill-md-file"
          type="file"
          accept=".md,.markdown,text/markdown,text/plain"
          :disabled="creatingSkill"
          @change="handleSkillFileChange"
        />
        <small>{{ selectedFileName || '选择 .md 后会自动填入技能 ID、标题和内容' }}</small>
      </label>
      <form class="skill-upload__form" @submit.prevent="createCandidate">
        <label>
          <span>技能 ID</span>
          <input
            v-model="uploadForm.skillId"
            class="skill-upload__input"
            data-test="skill-upload-id"
            type="text"
            placeholder="webnovel-opening-hook"
            :disabled="creatingSkill"
          />
        </label>
        <label>
          <span>标题</span>
          <input
            v-model="uploadForm.title"
            class="skill-upload__input"
            data-test="skill-upload-title"
            type="text"
            placeholder="开篇钩子强化"
            :disabled="creatingSkill"
          />
        </label>
        <label class="skill-upload__wide">
          <span>技能内容</span>
          <textarea
            v-model="uploadForm.content"
            class="skill-upload__textarea"
            data-test="skill-upload-content"
            rows="7"
            placeholder="# Skill&#10;适用场景、触发条件、证据要求和输出约束..."
            :disabled="creatingSkill"
          />
        </label>
        <label class="skill-upload__wide">
          <span>Eval 指标 JSON（可选）</span>
          <textarea
            v-model="uploadForm.evalResultJson"
            class="skill-upload__textarea skill-upload__textarea--short"
            data-test="skill-upload-eval-json"
            rows="3"
            placeholder='{"requiredToolPassRate":1,"evidencePassRate":0.95,"faithfulnessPassRate":0.95}'
            :disabled="creatingSkill"
          />
        </label>
        <el-button
          type="primary"
          native-type="button"
          :loading="creatingSkill"
          data-test="skill-upload-submit"
          @click="createCandidate"
        >
          创建候选
        </el-button>
      </form>
    </section>

    <section class="skill-candidates">
      <header class="section-header section-header--tools">
        <div>
          <h2>技能候选</h2>
          <p>共 {{ candidatePage.total }} 条</p>
        </div>
        <div class="candidate-tools">
          <el-select v-model="statusFilter" clearable size="small" placeholder="状态" class="candidate-status">
            <el-option label="PENDING" value="PENDING" />
            <el-option label="APPROVED" value="APPROVED" />
            <el-option label="REJECTED" value="REJECTED" />
          </el-select>
          <el-button size="small" type="primary" data-test="skill-search" @click="search">搜索</el-button>
        </div>
      </header>

      <el-table v-loading="loading" :data="candidates" size="small" class="candidate-table">
        <el-table-column prop="skillId" label="技能" min-width="180" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Eval 指标" min-width="190">
          <template #default="{ row }">
            <div class="eval-cell">
              <span>{{ row.evalStatus }}</span>
              <template v-if="evalMetrics(row)">
                <small>工具 {{ percent(evalMetrics(row)?.requiredToolPassRate) }}</small>
                <small>证据 {{ percent(evalMetrics(row)?.evidencePassRate) }}</small>
                <small>忠实度 {{ percent(evalMetrics(row)?.faithfulnessPassRate) }}</small>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="审核备注" min-width="220">
          <template #default="{ row }">
            <el-input v-model="reviewNotes[row.id]" size="small" placeholder="可选审核备注" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              size="small"
              data-test="approve-skill"
              :loading="reviewingIds.has(row.id)"
              @click="review(row, 'APPROVED')"
            >
              通过
            </el-button>
            <el-button
              v-if="row.status === 'PENDING'"
              size="small"
              data-test="reject-skill"
              :loading="reviewingIds.has(row.id)"
              @click="review(row, 'REJECTED')"
            >
              拒绝
            </el-button>
            <el-button
              v-if="row.status === 'APPROVED'"
              size="small"
              type="primary"
              data-test="publish-skill"
              :loading="reviewingIds.has(row.id)"
              @click="publishCandidate(row)"
            >
              发布
            </el-button>
            <el-button
              v-if="row.status === 'PUBLISHED'"
              size="small"
              data-test="disable-skill"
              :loading="reviewingIds.has(row.id)"
              @click="disableCandidate(row)"
            >
              停用
            </el-button>
            <el-button
              v-if="row.status === 'PUBLISHED'"
              size="small"
              data-test="rollback-skill"
              :loading="reviewingIds.has(row.id)"
              @click="rollbackCandidate(row)"
            >
              回滚
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span>暂无待审核候选</span>
        </template>
      </el-table>

      <footer class="candidate-pagination">
        <el-button size="small" :disabled="!hasPrev" data-test="skill-prev-page" @click="prevPage">上一页</el-button>
        <span>{{ candidatePage.page }}</span>
        <el-button size="small" :disabled="!hasNext" data-test="skill-next-page" @click="nextPage">下一页</el-button>
      </footer>
    </section>
  </main>
</template>

<style scoped>
.admin-skill-governance {
  display: grid;
  gap: 1rem;
  padding: 1rem;
  min-width: 0;
}

.runtime-skills,
.skill-upload,
.skill-candidates {
  min-width: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 1rem;
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}

.section-header h1,
.section-header h2,
.section-header p {
  margin: 0;
}

.section-header h1,
.section-header h2 {
  font-size: 1rem;
  line-height: 1.4;
}

.section-header p {
  margin-top: 0.25rem;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.runtime-skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
  gap: 0.75rem;
}

.skill-upload__status {
  min-height: 1.25rem;
  margin: -0.35rem 0 0.75rem;
  font-size: 0.8125rem;
}

.skill-upload__ok {
  color: var(--el-color-success);
}

.skill-upload__error {
  color: var(--el-color-danger);
}

.skill-upload__form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}

.skill-upload__file {
  display: grid;
  gap: 0.35rem;
  margin-bottom: 0.75rem;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.skill-upload__file input {
  width: min(100%, 28rem);
}

.skill-upload__file small {
  color: var(--el-text-color-secondary);
}

.skill-upload__form label {
  min-width: 0;
  display: grid;
  gap: 0.3rem;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.skill-upload__wide {
  grid-column: 1 / -1;
}

.skill-upload__input,
.skill-upload__textarea {
  width: 100%;
  min-width: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font: inherit;
}

.skill-upload__input {
  min-height: 36px;
  padding: 0 0.625rem;
}

.skill-upload__textarea {
  resize: vertical;
  min-height: 8rem;
  padding: 0.625rem;
  line-height: 1.55;
}

.skill-upload__textarea--short {
  min-height: 5rem;
}

.runtime-skill {
  min-width: 0;
  display: grid;
  gap: 0.625rem;
  padding: 0.875rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.runtime-skill__title {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.runtime-skill__title strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.tag-row,
.candidate-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.section-header--tools {
  align-items: center;
}

.candidate-tools {
  justify-content: flex-end;
}

.candidate-status {
  width: 10rem;
}

.candidate-table {
  width: 100%;
}

.eval-cell {
  display: grid;
  gap: 0.125rem;
  line-height: 1.35;
}

.eval-cell small {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.candidate-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding-top: 0.875rem;
}

@media (max-width: 720px) {
  .section-header,
  .section-header--tools {
    flex-direction: column;
    align-items: stretch;
  }

  .candidate-tools {
    justify-content: flex-start;
  }

  .skill-upload__form {
    grid-template-columns: minmax(0, 1fr);
  }

  .candidate-status {
    width: 100%;
  }
}
</style>
