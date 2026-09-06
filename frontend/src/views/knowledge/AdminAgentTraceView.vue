<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import type { AgentTraceListItem, AgentTracePage, AgentTraceSummary, GoldenCandidateDraft } from '@/types/knowledge';
import LangGraphRuntimeGraph from '@/components/knowledge/trace/LangGraphRuntimeGraph.vue';
import TaskGraphDisplay from '@/components/knowledge/trace/TaskGraphDisplay.vue';
import ToolRunsTable from '@/components/knowledge/trace/ToolRunsTable.vue';
import EvidencePackSummary from '@/components/knowledge/trace/EvidencePackSummary.vue';
import PerspectiveResultsList from '@/components/knowledge/trace/PerspectiveResultsList.vue';
import { knowledgeStatusLabel } from '@/utils/knowledgeDisplay';

defineOptions({
  name: 'AdminAgentTraceView',
});

const pageSize = 20;
const traces = ref<AgentTraceListItem[]>([]);
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
  'skillActivation',
  'sourcePolicy',
  'contextUsed',
  'memoryUsed',
  'memoryDiagnostics',
  'retrievalDiagnostics',
  'resourceDiagnostics',
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
  'harnessIntelligence',
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

async function selectTrace(trace: AgentTraceListItem, focusDetail = true) {
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

const intelligence = computed(() => {
  const result = parseObject(selected.value?.resultJson);
  const value = parseObject(result.harnessIntelligence);
  if (!Object.keys(value).length) return null;
  const validation = parseObject(value.validation);
  const statuses: Record<string, string> = {
    passed: '规则通过', failed: '规则未通过', unknown: '未判定', not_run: '未运行',
  };
  const count = (raw: unknown) => typeof raw === 'number' && Number.isSafeInteger(raw) && raw >= 0 ? raw : 0;
  return {
    validation: statuses[String(validation.status)] || '未判定',
    revised: value.revised === true,
    repairUsed: value.repairUsed === true,
    noProgressCount: count(value.noProgressCount),
    skillReloadCount: count(value.skillReloadCount),
    pendingTaskCount: count(parseObject(value.taskProgress).pendingTaskCount),
  };
});

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

function parseObject(value?: unknown): Record<string, any> {
  if (!value) return {};
  if (typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, any>;
  }
  if (typeof value !== 'string') return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function skillMediation(trace?: AgentTraceSummary | null): Record<string, any> {
  return parseObject(trace?.skillMediation);
}

function skillMediationRecords(trace?: AgentTraceSummary | null): Record<string, any>[] {
  const records = skillMediation(trace).records;
  return Array.isArray(records) ? records.filter((record) => record && typeof record === 'object') : [];
}

function skillBomItems(trace?: AgentTraceSummary | null): Record<string, any>[] {
  const skills = parseObject(trace?.skillBom).skills;
  return Array.isArray(skills) ? skills.filter((skill) => skill && typeof skill === 'object') : [];
}

function skillReason(record: Record<string, any>) {
  const reasons = [...(record.candidateReasons ?? []), ...(record.rejectionReasons ?? [])]
    .map((reason) => String(reason || '').trim())
    .filter(Boolean);
  return [...new Set(reasons)].join(', ') || '-';
}

function skillStateLabel(value: unknown) {
  const state = String(value || '').trim().toUpperCase();
  if (state === 'ACTIVATED') return '已激活';
  if (state === 'REJECTED') return '已拒绝';
  if (state === 'ELIGIBLE') return '可激活';
  return state || '-';
}

function skillStateType(value: unknown) {
  const state = String(value || '').trim().toUpperCase();
  if (state === 'ACTIVATED') return 'success';
  if (state === 'REJECTED') return 'warning';
  return 'info';
}

function traceHealth(trace?: AgentTraceListItem | null): Record<string, string> {
  const compactHealth = trace?.healthSummary ?? {};
  const result = parseObject((trace as AgentTraceSummary | undefined)?.resultJson);
  const resultTrace = result.trace && typeof result.trace === 'object' ? result.trace as Record<string, any> : {};
  const health = resultTrace.health && typeof resultTrace.health === 'object'
    ? resultTrace.health as Record<string, any>
    : result.health && typeof result.health === 'object'
      ? result.health as Record<string, any>
      : {};
  return {
    status: trace?.status || '-',
    model: String(compactHealth.model || health.model || (result.fallbackUsed ? 'fallback_used' : '-')),
    tools: String(compactHealth.tools || health.tools || '-'),
    evidence: String(compactHealth.evidence || health.evidence || '-'),
    memory: String(compactHealth.memory || health.memory || '-'),
    experts: String(compactHealth.experts || health.experts || '-'),
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
  return `${prefix}${item.title || item.characterName || item.summary || knowledgeStatusLabel(item.status, '未命名')}`;
}


function resourceDiagnostics(trace?: AgentTraceSummary | null): Record<string, any> {
  const result = parseObject(trace?.resultJson);
  const resultTrace = result.trace && typeof result.trace === 'object' ? result.trace as Record<string, any> : {};
  const fromTrace = resultTrace.resourceDiagnostics && typeof resultTrace.resourceDiagnostics === 'object'
    ? resultTrace.resourceDiagnostics as Record<string, any>
    : {};
  const fromRoot = result.resourceDiagnostics && typeof result.resourceDiagnostics === 'object'
    ? result.resourceDiagnostics as Record<string, any>
    : {};
  return Object.keys(fromRoot).length > 0 ? fromRoot : fromTrace;
}

function hasResourceDiagnostics(trace?: AgentTraceSummary | null) {
  return Object.keys(resourceDiagnostics(trace)).length > 0;
}

function providerCalls(trace?: AgentTraceSummary | null): Record<string, any>[] {
  const result = parseObject(trace?.resultJson);
  const resultCalls = Array.isArray(result.providerCalls) ? result.providerCalls : [];
  const resultTrace = result.trace && typeof result.trace === 'object' ? result.trace as Record<string, any> : {};
  const traceCalls = Array.isArray(resultTrace.providerCalls) ? resultTrace.providerCalls : [];
  const calls = resultCalls.length ? resultCalls : traceCalls;
  return calls.filter((call): call is Record<string, any> => Boolean(call && typeof call === 'object'));
}

function providerRequestSummary(call: Record<string, any>) {
  const summary = call.requestSummary && typeof call.requestSummary === 'object'
    ? call.requestSummary as Record<string, any>
    : {};
  return Object.keys(summary).length ? summary : null;
}

function providerResponseSummary(call: Record<string, any>) {
  const summary = call.responseSummary && typeof call.responseSummary === 'object'
    ? call.responseSummary as Record<string, any>
    : {};
  return Object.keys(summary).length ? summary : null;
}

function providerModel(call: Record<string, any>) {
  const value = String(call.actualModel || call.model || call.requestedModel || '').replace(/[\r\n\t]+/g, ' ').trim();
  if (!value || /^(?:[a-z]:[\\/]|\\\\|\/|file:)/i.test(value) || value.includes('\\')) return '-';
  return value.slice(0, 80);
}

function providerCallStatus(call: Record<string, any>) {
  return knowledgeStatusLabel(call.status || 'unknown');
}

function providerRequestLabel(call: Record<string, any>) {
  const summary = providerRequestSummary(call);
  if (!summary) return '无请求摘要';
  const reasoning = summary.reasoningRequested ? '已请求推理' : '常规模式';
  return `请求 ${summary.messageCount ?? 0} 条消息 · ${summary.messageChars ?? 0} 字符 · ${summary.toolSchemaCount ?? 0} 个工具定义 · ${reasoning}`;
}

function providerResponseLabel(call: Record<string, any>) {
  const summary = providerResponseSummary(call);
  if (!summary) return '无返回摘要';
  return `返回 ${summary.outputChars ?? 0} 字符 · ${summary.toolCallCount ?? 0} 个工具调用 · ${summary.emptyResponse ? '空返回' : '非空返回'}`;
}

function providerWireLabel(call: Record<string, any>) {
  const wire = String(call.wireApi || '').trim().toLowerCase().replace(/-/g, '_');
  if (wire === 'responses') return 'Responses API';
  if (wire === 'chat_completions' && call.providerTransportFallback) {
    return 'Chat compatibility fallback';
  }
  return wire === 'chat_completions' ? 'Chat Completions' : '';
}

function providerUsageLabel(call: Record<string, any>) {
  const usage = call.usage && typeof call.usage === 'object'
    ? call.usage as Record<string, any>
    : {};
  // 这里读的是原始记录，没上报时 _usage_summary 留下的是一排 0。0 和"不知道"
  // 在页面上长得一样但处置相反，所以没有上报标志时把 0 当占位丢掉。
  const usageReported = call.usageReported === true || usage.usageReported === true;
  const cacheReported = call.cacheUsageReported === true || usage.cacheUsageReported === true;
  const kept = (value: any, reported: boolean) => {
    const parsed = Number(value);
    if (value === null || value === undefined || !Number.isFinite(parsed) || parsed < 0) {
      return undefined;
    }
    return reported || parsed > 0 ? parsed : undefined;
  };
  const input = kept(usage.inputTokens, usageReported) ?? kept(usage.promptTokens, usageReported);
  const output = kept(usage.outputTokens, usageReported) ?? kept(usage.completionTokens, usageReported);
  const reasoning = kept(usage.reasoningTokens, usageReported);
  const cached = kept(usage.cachedInputTokens, cacheReported)
    ?? kept(usage.promptCacheHitTokens, cacheReported);
  const missed = kept(usage.promptCacheMissTokens, cacheReported);
  const parts = [
    input != null ? `上下文 ${input}` : '',
    output != null ? `输出 ${output}` : '',
    reasoning != null ? `推理 ${reasoning}` : '',
    cached != null ? `缓存命中 ${cached}` : '',
    missed != null ? `未命中 ${missed}` : '',
  ].filter(Boolean);
  if (!parts.length) {
    return usageReported ? '' : '用量未上报';
  }
  const suffix = cached == null && missed == null && !cacheReported ? ' · 缓存未上报' : '';
  return `${parts.join(' · ')} Token${suffix}`;
}

function conversationContinuity(trace?: AgentTraceSummary | null) {
  const result = parseObject(trace?.resultJson);
  const budget = parseObject(result.contextBudget);
  const continuity = parseObject(budget.conversationContinuity);
  return Object.keys(continuity).length ? continuity : null;
}

function healthBlocks(trace?: AgentTraceListItem | null) {
  const health = traceHealth(trace);
  return [
    { key: 'status', label: '状态', rawValue: health.status, value: knowledgeStatusLabel(health.status) },
    { key: 'model', label: '模型', rawValue: health.model, value: knowledgeStatusLabel(health.model) },
    { key: 'tools', label: '工具', rawValue: health.tools, value: knowledgeStatusLabel(health.tools) },
    { key: 'evidence', label: '证据', rawValue: health.evidence, value: knowledgeStatusLabel(health.evidence) },
    { key: 'memory', label: '记忆', rawValue: health.memory, value: knowledgeStatusLabel(health.memory) },
    { key: 'experts', label: '专家', rawValue: health.experts, value: knowledgeStatusLabel(health.experts) },
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
          <el-option label="已回答" value="answered" />
          <el-option label="失败" value="failed" />
          <el-option label="需要补充信息" value="needs_clarification" />
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
            <el-tag v-if="trace.status" size="small" type="info">{{ knowledgeStatusLabel(trace.status) }}</el-tag>
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
              :class="`trace-health-block--${healthTone(block.rawValue)}`"
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
            <el-tag v-if="selected.status" type="info">{{ knowledgeStatusLabel(selected.status) }}</el-tag>
            <el-tag v-if="selected.snapshotTime" type="success">快照 {{ selected.snapshotTime }}</el-tag>
          </div>
        </div>

        <section v-if="goldenMessage || goldenDraft" class="trace-golden-status">
          <strong>{{ goldenMessage }}</strong>
          <span v-if="goldenDraft?.status">{{ knowledgeStatusLabel(goldenDraft.status) }}</span>
          <span v-if="goldenDraft?.traceId">{{ goldenDraft.traceId }}</span>
        </section>

        <section v-if="selected.question" class="trace-question">
          <h3>问题</h3>
          <p>{{ selected.question }}</p>
        </section>

        <section class="trace-overview" aria-label="Trace overview">
          <div>
            <span>状态</span>
            <strong>{{ knowledgeStatusLabel(selected.status) }}</strong>
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
              :class="`trace-health-block--${healthTone(block.rawValue)}`"
            >
              <span>{{ block.label }}</span>
              <strong>{{ block.value }}</strong>
            </span>
          </div>
        </section>

        <section
          v-if="providerCalls(selected).length"
          class="trace-provider-ledger"
          data-test="trace-provider-ledger"
          aria-label="模型调用账本"
        >
          <header>
            <h3>模型调用账本</h3>
            <p>每次真实 Provider 请求均可追踪；请求与返回正文已脱敏省略。</p>
          </header>
          <div class="trace-provider-ledger__table" role="table" aria-label="模型调用请求与返回摘要">
            <div class="trace-provider-ledger__row trace-provider-ledger__row--header" role="row">
              <span role="columnheader">阶段 / 模型</span>
              <span role="columnheader">状态 / 用量</span>
              <span role="columnheader">请求摘要</span>
              <span role="columnheader">返回摘要</span>
            </div>
            <div
              v-for="(call, index) in providerCalls(selected)"
              :key="`${call.node || 'call'}-${index}`"
              class="trace-provider-ledger__row"
              data-test="trace-provider-call"
              role="row"
            >
              <span role="cell">
                <strong>{{ call.node || `第 ${index + 1} 次模型调用` }}</strong>
                <small>{{ providerModel(call) }}</small>
                <small v-if="providerWireLabel(call)">{{ providerWireLabel(call) }}</small>
              </span>
              <span role="cell">
                <strong>{{ providerCallStatus(call) }}</strong>
                <small v-if="providerUsageLabel(call)" data-test="trace-provider-usage">{{ providerUsageLabel(call) }}</small>
                <small>{{ call.durationMs ?? '-' }} 毫秒 · {{ call.tokenUsed ?? '-' }} Token</small>
              </span>
              <span role="cell">
                <small>{{ providerRequestLabel(call) }}</small>
                <small v-if="providerRequestSummary(call)?.bodyRedacted">请求正文已省略</small>
              </span>
              <span role="cell">
                <small>{{ providerResponseLabel(call) }}</small>
                <small v-if="providerResponseSummary(call)?.bodyRedacted">返回正文已省略</small>
              </span>
            </div>
          </div>
          <div v-if="conversationContinuity(selected)" class="trace-provider-ledger__continuity" data-test="trace-conversation-continuity">
            <strong>会话连续性</strong>
            <span>
              携带 {{ conversationContinuity(selected)?.historyIncludedCount ?? 0 }}/{{ conversationContinuity(selected)?.historyTotalCount ?? 0 }} 条历史消息，
              {{ conversationContinuity(selected)?.historyIncludedChars ?? 0 }} 字符；上下文摘要 {{ conversationContinuity(selected)?.contextSummaryChars ?? 0 }} 字符。
            </span>
            <el-tag v-if="conversationContinuity(selected)?.historyTruncated" size="small" type="warning">历史已裁剪</el-tag>
          </div>
        </section>

        <el-collapse v-model="activeNames" class="trace-sections">
          <el-collapse-item v-if="intelligence" title="Harness 质量闭环" name="harnessIntelligence">
            <dl data-test="harness-intelligence">
              <dt>终稿规则验证</dt><dd data-test="harness-validation-status">{{ intelligence.validation }}</dd>
              <dt>修订状态</dt><dd>{{ intelligence.revised ? '已修订' : '未修订' }}</dd>
              <dt>语义验证</dt><dd>未判定</dd>
              <dt>证据修复周期</dt><dd>{{ intelligence.repairUsed ? '已使用' : '未使用' }}</dd>
              <dt>无进展停止</dt><dd>{{ intelligence.noProgressCount }}</dd>
              <dt>Skill 补载次数</dt><dd>{{ intelligence.skillReloadCount }}</dd>
              <dt>待验证任务</dt><dd>{{ intelligence.pendingTaskCount }}</dd>
            </dl>
          </el-collapse-item>
          <el-collapse-item
            v-if="hasRuntimeGraph(selected.resultJson)"
            title="LangGraph 运行图"
            name="langGraphRuntime"
          >
            <LangGraphRuntimeGraph :result-json="selected.resultJson" />
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.taskGraph)" title="任务图" name="taskGraph">
            <TaskGraphDisplay :task-graph-json="selected.taskGraph" />
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.toolRuns)" title="工具调用" name="toolRuns">
            <ToolRunsTable :tool-runs-json="selected.toolRuns" />
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.evidencePack)" title="证据包" name="evidencePack">
            <EvidencePackSummary :evidence-pack-json="selected.evidencePack" />
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.perspectiveResults)" title="多视角结果" name="perspectiveResults">
            <PerspectiveResultsList :perspective-results-json="selected.perspectiveResults" />
          </el-collapse-item>

          <el-collapse-item v-if="hasJsonSection(selected.intentDecision)" title="意图决策" name="intentDecision">
            <pre class="trace-raw-json">{{ formatJson(selected.intentDecision) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasJsonSection(selected.skillMediation) || hasJsonSection(selected.skillBom)"
            title="技能激活"
            name="skillActivation"
          >
            <section class="trace-skill-mediation" data-test="trace-skill-mediation">
              <div class="trace-skill-summary">
                <span>候选 <strong>{{ skillMediation(selected).candidateCount ?? 0 }}</strong></span>
                <span>可激活 <strong>{{ skillMediation(selected).eligibleCount ?? 0 }}</strong></span>
                <span>已激活 <strong>{{ skillMediation(selected).activatedCount ?? 0 }}</strong></span>
                <span>已拒绝 <strong>{{ skillMediation(selected).rejectedCount ?? 0 }}</strong></span>
              </div>
              <div v-if="skillMediationRecords(selected).length" class="trace-table-wrap">
                <el-table :data="skillMediationRecords(selected)" size="small" class="trace-table">
                  <el-table-column label="Skill" min-width="220">
                    <template #default="{ row }">
                      <strong>{{ row.skillId || '-' }}</strong>
                      <small class="trace-skill-version">v{{ row.version || '-' }}</small>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="100">
                    <template #default="{ row }">
                      <el-tag :type="skillStateType(row.state)" size="small">{{ skillStateLabel(row.state) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="原因" min-width="220">
                    <template #default="{ row }">
                      <span class="trace-skill-reasons">{{ skillReason(row) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="正文" width="100">
                    <template #default="{ row }">{{ row.bodyInjected ? '已注入' : '未注入' }}</template>
                  </el-table-column>
                </el-table>
              </div>
              <p v-else class="trace-skill-empty">无技能激活记录</p>
              <h4 class="trace-section-subtitle">Runtime Skill-BOM</h4>
              <ul v-if="skillBomItems(selected).length" class="trace-skill-bom">
                <li v-for="skill in skillBomItems(selected)" :key="`${skill.skillId}-${skill.version}`">
                  <span>{{ skill.skillId || '-' }}@{{ skill.version || '-' }}</span>
                  <small>{{ knowledgeStatusLabel(skill.status) }}</small>
                </li>
              </ul>
              <p v-else class="trace-skill-empty">BOM 为空</p>
            </section>
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
            <pre class="trace-raw-json" data-test="trace-retrieval-diagnostics">{{ formatJson(selected.retrievalDiagnostics) }}</pre>
          </el-collapse-item>

          <el-collapse-item
            v-if="hasResourceDiagnostics(selected)"
            title="资源诊断"
            name="resourceDiagnostics"
          >
            <pre class="trace-raw-json" data-test="trace-resource-diagnostics">{{ JSON.stringify(resourceDiagnostics(selected), null, 2) }}</pre>
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
                    <small v-if="item.status">{{ knowledgeStatusLabel(item.status) }}</small>
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
    <section v-else class="admin-agent-trace__placeholder" data-test="agent-trace-placeholder">
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

.trace-provider-ledger {
  display: grid;
  gap: 0.6rem;
  padding: 0.85rem 0;
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.trace-provider-ledger header h3,
.trace-provider-ledger header p {
  margin: 0;
}

.trace-provider-ledger header h3 {
  color: var(--el-text-color-primary);
  font-size: 0.95rem;
}

.trace-provider-ledger header p {
  margin-top: 0.2rem;
  color: var(--el-text-color-secondary);
  font-size: 0.78rem;
}

.trace-provider-ledger__table {
  display: grid;
  gap: 0.35rem;
  overflow-x: auto;
}

.trace-provider-ledger__row {
  min-width: 48rem;
  display: grid;
  grid-template-columns: minmax(9rem, 1fr) minmax(8rem, 0.8fr) minmax(14rem, 1.4fr) minmax(14rem, 1.4fr);
  gap: 0.6rem;
  padding: 0.55rem 0.65rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  font-size: 0.78rem;
}

.trace-provider-ledger__row--header {
  border: 0;
  border-radius: 0;
  color: var(--el-text-color-secondary);
  background: transparent;
  font-size: 0.72rem;
}

.trace-provider-ledger__row > span {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 0.2rem;
}

.trace-provider-ledger__row small {
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}

.trace-provider-ledger__continuity {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.45rem;
  color: var(--el-text-color-secondary);
  font-size: 0.78rem;
}

.trace-provider-ledger__continuity strong {
  color: var(--el-text-color-primary);
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

.trace-skill-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1.25rem;
  margin-bottom: 0.75rem;
  color: var(--el-text-color-secondary);
}

.trace-skill-summary strong {
  margin-left: 0.25rem;
  color: var(--el-text-color-primary);
}

.trace-skill-version {
  display: block;
  margin-top: 0.2rem;
  color: var(--el-text-color-secondary);
}

.trace-skill-reasons {
  overflow-wrap: anywhere;
}

.trace-table-wrap {
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
  -webkit-overflow-scrolling: touch;
}

.trace-table {
  min-width: 40rem;
}

.trace-skill-bom {
  display: grid;
  gap: 0.4rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace-skill-bom li {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.45rem 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.trace-skill-bom small,
.trace-skill-empty {
  color: var(--el-text-color-secondary);
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
  min-width: 0;
  overflow-wrap: anywhere;
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
  flex-wrap: wrap;
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

@media (max-width: 560px) {
  .admin-agent-trace {
    margin-inline: -0.875rem;
    padding: 0.75rem;
  }

  .admin-agent-trace__list,
  .admin-agent-trace__detail {
    border-radius: 6px;
  }

  .trace-project-knowledge__scope {
    grid-template-columns: minmax(4rem, auto) minmax(0, 1fr);
  }

  .trace-project-knowledge__scope strong {
    white-space: normal;
  }

  .trace-health-summary__grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
