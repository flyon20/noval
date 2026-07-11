<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import type { AgentTracePage, AgentTraceSummary, GoldenCandidateDraft } from '@/types/knowledge';
import LangGraphRuntimeGraph from '@/components/knowledge/trace/LangGraphRuntimeGraph.vue';
import TaskGraphDisplay from '@/components/knowledge/trace/TaskGraphDisplay.vue';
import ToolRunsTable from '@/components/knowledge/trace/ToolRunsTable.vue';
import EvidencePackSummary from '@/components/knowledge/trace/EvidencePackSummary.vue';
import PerspectiveResultsList from '@/components/knowledge/trace/PerspectiveResultsList.vue';

defineOptions({
  name: 'AdminAgentTraceView',
});

const pageSize = 20;
const traces = ref<AgentTraceSummary[]>([]);
const selected = ref<AgentTraceSummary | null>(null);
const detailOpen = ref(false);
const pageState = ref<AgentTracePage>({
  page: 1,
  pageSize,
  total: 0,
  hasNext: false,
  items: [],
});
const loading = ref(false);
const detailLoading = ref(false);
const creatingGolden = ref(false);
const goldenDraft = ref<GoldenCandidateDraft | null>(null);
const goldenMessage = ref('');
const statusFilter = ref('');
const keyword = ref('');
const activeNames = ref([
  'langGraphRuntime',
  'taskGraph',
  'toolRuns',
  'evidencePack',
  'intentDecision',
  'sourcePolicy',
  'contextUsed',
  'memoryUsed',
  'memoryDiagnostics',
  'retrievalDiagnostics',
  'projectKnowledge',
  'supervisorDecision',
  'memoryCandidates',
  'mcpToolCalls',
  'toolPermissionDecisions',
  'evidenceContract',
  'snapshotArbitration',
  'agentHandoffs',
  'expertRouter',
  'finalAnswerBoundary',
]);

const hasPrev = computed(() => pageState.value.page > 1);
const hasNext = computed(() => pageState.value.hasNext);

onMounted(() => loadTraces(1));

async function loadTraces(page = pageState.value.page) {
  loading.value = true;
  try {
    const query = buildQuery(page);
    const response = await knowledgeApi.listAgentTraces(query);
    const data = response.data.data ?? emptyPage(page);
    pageState.value = {
      page: data.page,
      pageSize: data.pageSize,
      total: data.total,
      hasNext: data.hasNext,
      items: data.items ?? [],
    };
    traces.value = pageState.value.items;
    selected.value = null;
    detailOpen.value = false;
  } finally {
    loading.value = false;
  }
}

async function selectTrace(trace: AgentTraceSummary, focusDetail = true) {
  detailLoading.value = true;
  goldenDraft.value = null;
  goldenMessage.value = '';
  detailOpen.value = focusDetail;
  try {
    const response = await knowledgeApi.getAgentTrace(trace.id);
    selected.value = response.data.data;
  } finally {
    detailLoading.value = false;
  }
}

function closeDetail() {
  detailOpen.value = false;
}

async function createGoldenCandidate() {
  if (!selected.value) {
    return;
  }
  creatingGolden.value = true;
  goldenMessage.value = '';
  try {
    const response = await knowledgeApi.createGoldenCandidate(selected.value.id);
    goldenDraft.value = response.data.data ?? null;
    goldenMessage.value = 'Golden 候选已创建';
  } finally {
    creatingGolden.value = false;
  }
}

function buildQuery(page: number) {
  return {
    page,
    pageSize,
    ...(statusFilter.value ? { status: statusFilter.value } : {}),
    ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
  };
}

function emptyPage(page: number): AgentTracePage {
  return {
    page,
    pageSize,
    total: 0,
    hasNext: false,
    items: [],
  };
}

function search() {
  void loadTraces(1);
}

function prevPage() {
  if (hasPrev.value) {
    void loadTraces(pageState.value.page - 1);
  }
}

function nextPage() {
  if (hasNext.value) {
    void loadTraces(pageState.value.page + 1);
  }
}

function hasJsonSection(value?: string) {
  return Boolean(value && value.trim() && value.trim() !== 'null');
}

function hasRuntimeGraph(value?: string) {
  if (!value) return false;
  try {
    const parsed = JSON.parse(value) as { trace?: { nodes?: unknown } };
    return Array.isArray(parsed.trace?.nodes) && parsed.trace.nodes.length > 0;
  } catch {
    return false;
  }
}

function formatJson(value?: string) {
  if (!value) return '';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function parseObject(value?: string): Record<string, any> {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function traceHealth(trace?: AgentTraceSummary | null): Record<string, string> {
  const result = parseObject(trace?.resultJson);
  const resultTrace = result.trace && typeof result.trace === 'object' ? result.trace as Record<string, any> : {};
  const health = resultTrace.health && typeof resultTrace.health === 'object'
    ? resultTrace.health as Record<string, any>
    : result.health && typeof result.health === 'object'
      ? result.health as Record<string, any>
      : {};
  return {
    status: trace?.status || '-',
    model: String(health.model || (result.fallbackUsed ? 'fallback_used' : '-')),
    tools: String(health.tools || '-'),
    evidence: String(health.evidence || '-'),
    memory: String(health.memory || '-'),
    experts: String(health.experts || '-'),
  };
}

function projectKnowledge(trace?: AgentTraceSummary | null): Record<string, any> {
  const result = parseObject(trace?.resultJson);
  const resultTrace = result.trace && typeof result.trace === 'object' ? result.trace as Record<string, any> : {};
  const project = resultTrace.projectKnowledge && typeof resultTrace.projectKnowledge === 'object'
    ? resultTrace.projectKnowledge as Record<string, any>
    : result.projectKnowledge && typeof result.projectKnowledge === 'object'
      ? result.projectKnowledge as Record<string, any>
      : {};
  return project;
}

function hasProjectKnowledge(trace?: AgentTraceSummary | null) {
  return Object.keys(projectKnowledge(trace)).length > 0;
}

function projectKnowledgeItems(trace: AgentTraceSummary | null, key: string): Record<string, any>[] {
  const value = projectKnowledge(trace)[key];
  return Array.isArray(value) ? value.filter((item) => item && typeof item === 'object') : [];
}

function projectKnowledgeItemLabel(item: Record<string, any>) {
  const prefix = item.chapterNo ? `第${item.chapterNo}章 ` : '';
  return `${prefix}${item.title || item.characterName || item.summary || item.status || '未命名'}`;
}

function healthBlocks(trace?: AgentTraceSummary | null) {
  const health = traceHealth(trace);
  return [
    { key: 'status', label: '状态', value: health.status },
    { key: 'model', label: '模型', value: health.model },
    { key: 'tools', label: '工具', value: health.tools },
    { key: 'evidence', label: '证据', value: health.evidence },
    { key: 'memory', label: '记忆', value: health.memory },
    { key: 'experts', label: '专家', value: health.experts },
  ];
}

function healthTone(value?: string) {
  const normalized = String(value || '').toLowerCase();
  if (['answered', 'succeeded', 'loaded', 'none'].includes(normalized)) return 'ok';
  if (['fallback_used', 'blocked', 'skipped', 'degraded'].includes(normalized)) return 'warn';
  if (['failed', 'unavailable', 'not_called'].includes(normalized)) return 'bad';
  return 'idle';
}
</script>

<template>
  <main
    class="admin-agent-trace"
    :class="{
      'admin-agent-trace--focus-detail': detailOpen,
      'admin-agent-trace--list-only': !detailOpen,
    }"
  >
    <aside class="admin-agent-trace__list">
      <header class="trace-list-header">
        <div>
          <h1>智能体 Trace</h1>
          <p>共 {{ pageState.total }} 条</p>
        </div>
        <el-select v-model="statusFilter" clearable size="small" placeholder="状态" class="trace-filter">
          <el-option label="answered" value="answered" />
          <el-option label="failed" value="failed" />
          <el-option label="needs_clarification" value="needs_clarification" />
        </el-select>
        <el-input v-model="keyword" clearable size="small" placeholder="Trace / 问题" @keyup.enter="search" />
        <el-button size="small" type="primary" data-test="trace-search" @click="search">搜索</el-button>
      </header>

      <div v-loading="loading" class="trace-list-scroll">
        <button
          v-for="trace in traces"
          :key="trace.id"
          type="button"
          class="trace-row"
          :class="{ 'trace-row--active': selected?.id === trace.id }"
          data-test="trace-row"
          @click="selectTrace(trace)"
        >
          <span class="trace-row__top">
            <span class="trace-row__id">{{ trace.traceId }}</span>
            <el-tag v-if="trace.status" size="small" type="info">{{ trace.status }}</el-tag>
          </span>
          <span v-if="trace.question" class="trace-row__question">{{ trace.question }}</span>
          <span class="trace-row__meta">
            <span v-if="trace.userId">U{{ trace.userId }}</span>
            <span v-if="trace.projectId">P{{ trace.projectId }}</span>
            <span v-if="trace.createdAt">{{ trace.createdAt }}</span>
          </span>
          <span class="trace-row__health" data-test="trace-health-blocks">
            <span
              v-for="block in healthBlocks(trace)"
              :key="`${trace.id}-${block.key}`"
              class="trace-health-block"
              :class="`trace-health-block--${healthTone(block.value)}`"
            >
              <span>{{ block.label }}</span>
              <strong>{{ block.value }}</strong>
            </span>
          </span>
        </button>
        <el-empty v-if="!loading && !traces.length" description="暂无 Trace" />
      </div>

      <footer class="trace-pagination">
        <el-button size="small" :disabled="!hasPrev" data-test="trace-prev-page" @click="prevPage">上一页</el-button>
        <span>{{ pageState.page }}</span>
        <el-button size="small" :disabled="!hasNext" data-test="trace-next-page" @click="nextPage">下一页</el-button>
      </footer>
    </aside>

    <section
      v-if="detailOpen"
      v-loading="detailLoading"
      class="admin-agent-trace__detail"
      data-test="agent-trace-detail"
    >
      <template v-if="selected">
        <div class="trace-header">
          <div class="trace-header__main">
            <h2 class="trace-header__id">{{ selected.traceId }}</h2>
            <p v-if="selected.conversationId">{{ selected.conversationId }}</p>
          </div>
          <div class="trace-header__tags">
            <el-button
              v-if="detailOpen"
              size="small"
              data-test="trace-back-to-list"
              @click="closeDetail"
            >
              返回列表
            </el-button>
            <el-button
              size="small"
              type="primary"
              :loading="creatingGolden"
              data-test="create-golden-candidate"
              @click="createGoldenCandidate"
            >
              生成 Golden 候选
            </el-button>
            <el-tag v-if="selected.status" type="info">{{ selected.status }}</el-tag>
            <el-tag v-if="selected.snapshotTime" type="success">快照 {{ selected.snapshotTime }}</el-tag>
          </div>
        </div>

        <section v-if="goldenMessage || goldenDraft" class="trace-golden-status">
          <strong>{{ goldenMessage }}</strong>
          <span v-if="goldenDraft?.status">{{ goldenDraft.status }}</span>
          <span v-if="goldenDraft?.traceId">{{ goldenDraft.traceId }}</span>
        </section>

        <section v-if="selected.question" class="trace-question">
          <h3>问题</h3>
          <p>{{ selected.question }}</p>
        </section>

        <section class="trace-overview" aria-label="Trace overview">
          <div>
            <span>状态</span>
            <strong>{{ selected.status || '-' }}</strong>
          </div>
          <div>
            <span>用户</span>
            <strong>{{ selected.userId ? `U${selected.userId}` : '-' }}</strong>
          </div>
          <div>
            <span>项目</span>
            <strong>{{ selected.projectId ? `P${selected.projectId}` : '-' }}</strong>
          </div>
          <div>
            <span>创建时间</span>
            <strong>{{ selected.createdAt || '-' }}</strong>
          </div>
          <div>
            <span>快照</span>
            <strong>{{ selected.snapshotTime || '-' }}</strong>
          </div>
        </section>

        <section class="trace-health-summary" data-test="trace-health-summary" aria-label="Trace 健康">
          <header>
            <h3>Trace 健康</h3>
            <p>来自运行时 Trace 的模型、工具、证据、记忆和专家状态。</p>
          </header>
          <div class="trace-health-summary__grid">
            <span
              v-for="block in healthBlocks(selected)"
              :key="`selected-${block.key}`"
              class="trace-health-block trace-health-block--large"
              :class="`trace-health-block--${healthTone(block.value)}`"
            >
              <span>{{ block.label }}</span>
              <strong>{{ block.value }}</strong>
            </span>
          </div>
        </section>

        <el-collapse v-model="activeNames" class="trace-sections">
          <el-collapse-item
            v-if="hasRuntimeGraph(selected.resultJson)"
            title="LangGraph 运行图"
            name="langGraphRuntime"
          >
            <LangGraphRuntimeGraph :result-json="selected.resultJson" />
          </el-collapse-item>

          <el-collapse-item title="任务图" name="taskGraph">
            <TaskGraphDisplay :task-graph-json="selected.taskGraph" />
          </el-collapse-item>

          <el-collapse-item title="工具调用" name="toolRuns">
            <ToolRunsTable :tool-runs-json="selected.toolRuns" />
          </el-collapse-item>

          <el-collapse-item title="证据包" name="evidencePack">
            <EvidencePackSummary :evidence-pack-json="selected.evidencePack" />
          </el-collapse-item>

          <el-collapse-item title="多视角结果" name="perspectiveResults">
            <PerspectiveResultsList :perspective-results-json="selected.perspectiveResults" />
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.intentDecision)" title="意图决策" name="intentDecision">
            <pre class="trace-raw-json">{{ formatJson(selected.intentDecision) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.sourcePolicy)" title="来源策略" name="sourcePolicy">
            <pre class="trace-raw-json">{{ formatJson(selected.sourcePolicy) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.contextUsed)" title="上下文使用" name="contextUsed">
            <pre class="trace-raw-json">{{ formatJson(selected.contextUsed) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.memoryUsed)" title="记忆使用" name="memoryUsed">
            <pre class="trace-raw-json">{{ formatJson(selected.memoryUsed) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.memoryDiagnostics)"
            title="记忆诊断"
            name="memoryDiagnostics"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.memoryDiagnostics) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.retrievalDiagnostics)"
            title="检索诊断"
            name="retrievalDiagnostics"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.retrievalDiagnostics) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasProjectKnowledge(selected)"
            title="作品知识库"
            name="projectKnowledge"
          >
            <section class="trace-project-knowledge" data-test="trace-project-knowledge">
              <div class="trace-project-knowledge__scope">
                <span>项目</span>
                <strong>{{ projectKnowledge(selected).projectId ? `P${projectKnowledge(selected).projectId}` : '-' }}</strong>
                <span>作品</span>
                <strong>{{ projectKnowledge(selected).workId ? `W${projectKnowledge(selected).workId}` : '-' }}</strong>
              </div>
              <div
                v-for="section in [
                  { key: 'retrievedChapters', label: '检索章节' },
                  { key: 'retrievedChunks', label: '检索片段' },
                  { key: 'matchedForeshadowings', label: '命中伏笔' },
                  { key: 'matchedWorldRules', label: '命中设定' },
                  { key: 'matchedTimelineEvents', label: '命中时间线' },
                  { key: 'matchedCharacterStates', label: '命中人物状态' },
                ]"
                :key="section.key"
                class="trace-project-knowledge__section"
              >
                <h4>{{ section.label }}</h4>
                <ul v-if="projectKnowledgeItems(selected, section.key).length">
                  <li
                    v-for="(item, index) in projectKnowledgeItems(selected, section.key)"
                    :key="`${section.key}-${index}`"
                  >
                    <span>{{ projectKnowledgeItemLabel(item) }}</span>
                    <small v-if="item.status">{{ item.status }}</small>
                  </li>
                </ul>
                <p v-else>未命中</p>
              </div>
            </section>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.supervisorDecision)"
            title="监督决策"
            name="supervisorDecision"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.supervisorDecision) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.memoryCandidates)"
            title="记忆候选"
            name="memoryCandidates"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.memoryCandidates) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.mcpToolCalls)" title="MCP 工具调用" name="mcpToolCalls">
            <pre class="trace-raw-json">{{ formatJson(selected.mcpToolCalls) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.toolPermissionDecisions)"
            title="工具权限"
            name="toolPermissionDecisions"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.toolPermissionDecisions) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.evidenceContract)"
            title="证据契约"
            name="evidenceContract"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.evidenceContract) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.selectedSnapshotGroup) || hasJsonSection(selected.rejectedSnapshotGroups)"
            title="快照仲裁"
            name="snapshotArbitration"
          >
            <h4 class="trace-section-subtitle">选中快照组</h4>
            <pre v-if="hasJsonSection(selected.selectedSnapshotGroup)" class="trace-raw-json">{{ formatJson(selected.selectedSnapshotGroup) }}</pre>
            <h4 class="trace-section-subtitle">被拒快照组</h4>
            <pre v-if="hasJsonSection(selected.rejectedSnapshotGroups)" class="trace-raw-json">{{ formatJson(selected.rejectedSnapshotGroups) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.specialistAgentResults)"
            title="专家交接"
            name="agentHandoffs"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.specialistAgentResults) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.selectedExperts) || hasJsonSection(selected.expertRouter)"
            title="专家路由"
            name="expertRouter"
          >
            <h4 class="trace-section-subtitle">已选专家</h4>
            <pre v-if="hasJsonSection(selected.selectedExperts)" class="trace-raw-json">{{ formatJson(selected.selectedExperts) }}</pre>
            <h4 class="trace-section-subtitle">路由决策</h4>
            <pre v-if="hasJsonSection(selected.expertRouter)" class="trace-raw-json">{{ formatJson(selected.expertRouter) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.finalAnswerBoundary)"
            title="最终回答边界"
            name="finalAnswerBoundary"
          >
            <pre class="trace-raw-json">{{ formatJson(selected.finalAnswerBoundary) }}</pre>
          </el-collapse-item>

          <el-collapse-item v-if="selected.resultJson" title="原始 JSON" name="raw">
            <pre class="trace-raw-json">{{ formatJson(selected.resultJson) }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
    </section>
    <section v-else class="admin-agent-trace__placeholder" data-test="agent-trace-detail">
      <el-empty description="请选择一条 Trace 记录" />
    </section>
  </main>
</template>

<style scoped>
.admin-agent-trace {
  height: calc(100dvh - 4rem);
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(22rem, 28rem) minmax(0, 1fr);
  gap: 0.875rem;
  padding: 0.875rem;
  overflow: hidden;
}

.admin-agent-trace--focus-detail {
  grid-template-columns: minmax(0, 1fr);
}

.admin-agent-trace--focus-detail .admin-agent-trace__list {
  display: none;
}

.admin-agent-trace--list-only {
  grid-template-columns: minmax(0, 1fr);
}

.admin-agent-trace--list-only .admin-agent-trace__placeholder {
  display: none;
}

.admin-agent-trace__list,
.admin-agent-trace__detail,
.admin-agent-trace__placeholder {
  min-height: 0;
  min-width: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.admin-agent-trace__list {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
}

.trace-list-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.5rem;
  padding: 0.875rem;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.trace-list-header > div,
.trace-list-header :deep(.el-input) {
  grid-column: 1 / -1;
}

.trace-list-header h1 {
  margin: 0;
  font-size: 1rem;
  line-height: 1.3;
}

.trace-list-header p {
  margin: 0.125rem 0 0;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.trace-filter {
  width: 100%;
}

.trace-list-scroll {
  min-height: 0;
  overflow: auto;
  padding: 0.5rem;
}

.trace-row {
  width: 100%;
  display: grid;
  gap: 0.375rem;
  padding: 0.75rem;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.trace-row:hover,
.trace-row--active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}

.trace-row__top,
.trace-row__meta {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.trace-row__id {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 600;
  font-size: 0.875rem;
}

.trace-row__question {
  display: -webkit-box;
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-size: 0.8125rem;
  line-height: 1.5;
  overflow-wrap: anywhere;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.trace-row__meta {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}

.trace-row__health,
.trace-health-summary__grid {
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}

.trace-health-block {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0 0.45rem;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-lighter);
  font-size: 0.72rem;
  line-height: 1;
}

.trace-health-block span {
  color: var(--el-text-color-secondary);
}

.trace-health-block strong {
  font-weight: 650;
}

.trace-health-block--ok {
  border-color: color-mix(in srgb, var(--el-color-success) 34%, var(--el-border-color));
  background: color-mix(in srgb, var(--el-color-success-light-9) 74%, var(--el-bg-color));
}

.trace-health-block--warn {
  border-color: color-mix(in srgb, var(--el-color-warning) 42%, var(--el-border-color));
  background: color-mix(in srgb, var(--el-color-warning-light-9) 76%, var(--el-bg-color));
}

.trace-health-block--bad {
  border-color: color-mix(in srgb, var(--el-color-danger) 36%, var(--el-border-color));
  background: color-mix(in srgb, var(--el-color-danger-light-9) 76%, var(--el-bg-color));
}

.trace-health-block--large {
  min-height: 32px;
  padding: 0 0.65rem;
  font-size: 0.8rem;
}

.trace-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 0.75rem;
  border-top: 1px solid var(--el-border-color-lighter);
}

.admin-agent-trace__detail {
  display: grid;
  align-content: start;
  gap: 1rem;
  overflow: auto;
  padding: 1rem;
}

.trace-header {
  position: sticky;
  top: -1rem;
  z-index: 2;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 0 0 0.75rem;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.trace-header__main {
  min-width: 0;
}

.trace-header__id {
  margin: 0;
  font-size: 1.125rem;
  overflow-wrap: anywhere;
}

.trace-header__main p {
  margin: 0.25rem 0 0;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
  overflow-wrap: anywhere;
}

.trace-header__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.5rem;
}

.trace-question {
  display: grid;
  gap: 0.5rem;
  padding: 0.875rem;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.trace-golden-status {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--el-color-success-light-5);
  border-radius: 6px;
  background: var(--el-color-success-light-9);
  color: var(--el-color-success-dark-2);
  font-size: 0.8125rem;
}

.trace-question h3,
.trace-question p {
  margin: 0;
}

.trace-question h3 {
  font-size: 0.875rem;
}

.trace-question p {
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.trace-overview {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0.5rem;
}

.trace-overview div {
  min-width: 0;
  display: grid;
  gap: 0.25rem;
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.trace-overview span {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.trace-overview strong {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 0.875rem;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-health-summary {
  display: grid;
  gap: 0.65rem;
  padding: 0.85rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.trace-health-summary h3,
.trace-health-summary p {
  margin: 0;
}

.trace-health-summary h3 {
  font-size: 0.95rem;
}

.trace-health-summary p {
  color: var(--el-text-color-secondary);
  font-size: 0.8rem;
}

.trace-sections {
  min-width: 0;
  border: none;
}

.trace-raw-json {
  margin: 0;
  max-height: 400px;
  overflow: auto;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 0.8125rem;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.trace-section-subtitle {
  margin: 0.75rem 0 0.375rem;
  font-size: 0.8125rem;
}

.trace-project-knowledge {
  display: grid;
  gap: 0.75rem;
}

.trace-project-knowledge__scope {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 0.5rem;
  align-items: center;
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.trace-project-knowledge__scope span,
.trace-project-knowledge__section p,
.trace-project-knowledge__section small {
  color: var(--el-text-color-secondary);
  font-size: 0.78rem;
}

.trace-project-knowledge__scope strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-project-knowledge__section {
  display: grid;
  gap: 0.35rem;
}

.trace-project-knowledge__section h4,
.trace-project-knowledge__section p,
.trace-project-knowledge__section ul {
  margin: 0;
}

.trace-project-knowledge__section h4 {
  font-size: 0.82rem;
}

.trace-project-knowledge__section ul {
  display: grid;
  gap: 0.35rem;
  padding: 0;
  list-style: none;
}

.trace-project-knowledge__section li {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.48rem 0.6rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.trace-project-knowledge__section li span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 920px) {
  .admin-agent-trace {
    height: auto;
    min-height: calc(100dvh - 4rem);
    grid-template-columns: minmax(0, 1fr);
    overflow: auto;
  }

  .admin-agent-trace__list {
    min-height: 28rem;
  }

  .trace-header {
    position: static;
    flex-direction: column;
  }

  .trace-header__tags {
    justify-content: flex-start;
  }

  .trace-list-header {
    grid-template-columns: minmax(0, 1fr);
  }

  .trace-overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
