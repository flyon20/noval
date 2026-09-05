<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import LangGraphRuntimeGraph from '@/components/knowledge/trace/LangGraphRuntimeGraph.vue';
import type {
  AgentEvalCaseResult,
  AgentEvalRun,
  AgentEvalRunRequest,
  AgentExpertProfile,
  AgentCacheTokenStats,
  AgentRuntimeConfig,
  AgentTraceSummary,
} from '@/types/knowledge';
import {
  capabilityLabel,
  expertLabel,
  knowledgeDomainLabel,
  knowledgeStatusLabel,
} from '@/utils/knowledgeDisplay';

defineOptions({
  name: 'AdminAgentGovernanceView',
});

type RuntimeValue = string | number | boolean;
type RuntimeRow = {
  key: keyof AgentRuntimeConfig;
  label: string;
  type: 'text' | 'number' | 'boolean' | 'reasoning';
  effect?: string;
};

const runtimeRows: RuntimeRow[] = [
  { key: 'reasoningModeDefault', label: '默认推理模式', type: 'reasoning' },
  { key: 'maxParallelSpecialists', label: '最大并行专家数', type: 'number' },
  { key: 'maxTotalInputTokens', label: '最大输入 Token', type: 'number' },
  { key: 'contextCompactionThresholdPercent', label: '上下文压缩触发比例(%)', type: 'number' },
  { key: 'runTokenBudgetPercent', label: '单轮 Token 预算比例(%)', type: 'number' },
  { key: 'maxFinalOutputTokensFast', label: '快速回答 Token 上限', type: 'number' },
  { key: 'maxFinalOutputTokensDeep', label: '深度回答 Token 上限', type: 'number' },
  { key: 'enableIntentCache', label: '意图缓存', type: 'boolean' },
  { key: 'enableTaskGraphCache', label: '任务图缓存', type: 'boolean' },
  { key: 'enableToolCache', label: '工具缓存', type: 'boolean' },
  { key: 'enableEvidenceCache', label: '证据缓存', type: 'boolean' },
  { key: 'enableSpecialistCache', label: '专家缓存', type: 'boolean' },
  { key: 'specialistMcpEnabled', label: '专家工具调用', type: 'boolean' },
  { key: 'maxPromptCharsPerExpert', label: '单专家提示字符上限', type: 'number' },
  { key: 'maxSkillPromptChars', label: '技能提示字符', type: 'number' },
  { key: 'maxEvidenceItems', label: '最大证据条数', type: 'number' },
];

const topologyNodes = [
  '上下文',
  '意图识别',
  '任务图',
  '工具与证据',
  '专家',
  '回答',
  '记忆与 Trace',
];

const runtimeForm = reactive<Record<string, RuntimeValue>>({});
const evalRunForm = reactive<AgentEvalRunRequest>({
  suiteName: 'agent-runtime',
  runnerName: 'admin-trigger',
  evaluatorName: 'rule-based',
  modelName: '',
  caseLimit: 50,
});
const experts = ref<AgentExpertProfile[]>([]);
const evalRuns = ref<AgentEvalRun[]>([]);
const evalCaseResults = ref<AgentEvalCaseResult[]>([]);
const latestTrace = ref<AgentTraceSummary | null>(null);
const selectedEvalRunId = ref<number | null>(null);
const stats = ref<AgentCacheTokenStats>({
  traceCount: 0,
  cacheHits: 0,
  cacheMisses: 0,
  totalTokens: 0,
  promptPrefixStableRate: 0,
  tokenByNode: {},
  tokenByExpert: {},
});
const loading = ref(false);
const savingRuntimeKey = ref('');
const savingExpertName = ref('');
const startingEval = ref(false);
const loadingEvalCases = ref(false);
const operatingEvalRunId = ref<number | null>(null);
const statusMessage = ref('');
const errorMessage = ref('');

const enabledExpertCount = computed(() => experts.value.filter((expert) => expert.enabled !== false).length);
const tokenNodeRows = computed(() => Object.entries(stats.value.tokenByNode ?? {}).slice(0, 6));
const tokenExpertRows = computed(() => Object.entries(stats.value.tokenByExpert ?? {}).slice(0, 6));
const promptStabilityLabel = computed(() => `${((stats.value.promptPrefixStableRate ?? 0) * 100).toFixed(2)}%`);
const latestTraceResultJson = computed(() => latestTrace.value?.resultJson ?? '');

onMounted(() => {
  void loadGovernance();
});

async function loadGovernance() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const [runtimeResponse, expertResponse, statsResponse, evalRunResponse, traceResponse] = await Promise.all([
      knowledgeApi.getAgentRuntimeConfig(),
      knowledgeApi.listAgentExperts(),
      knowledgeApi.getAgentCacheTokenStats(),
      knowledgeApi.listAgentEvalRuns(),
      knowledgeApi.listAgentTraces({ page: 1, pageSize: 1 }),
    ]);
    applyRuntimeConfig(runtimeResponse.data.data ?? {});
    experts.value = (expertResponse.data.data ?? []).map(normalizeExpert);
    stats.value = statsResponse.data.data ?? stats.value;
    evalRuns.value = evalRunResponse.data.data ?? [];
    const latestSummary = traceResponse.data.data?.items?.[0] ?? null;
    if (latestSummary) {
      const detailResponse = await knowledgeApi.getAgentTrace(latestSummary.id);
      latestTrace.value = detailResponse.data.data ?? latestSummary;
    } else {
      latestTrace.value = null;
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function refreshEvalRuns() {
  const evalRunResponse = await knowledgeApi.listAgentEvalRuns();
  evalRuns.value = evalRunResponse.data.data ?? [];
}

function applyRuntimeConfig(config: AgentRuntimeConfig) {
  for (const row of runtimeRows) {
    const value = config[row.key];
    if (value !== undefined && value !== null) {
      runtimeForm[row.key] = value as RuntimeValue;
    }
  }
}

function normalizeExpert(expert: AgentExpertProfile): AgentExpertProfile {
  return {
    ...expert,
    enabled: expert.enabled !== false,
    priority: expert.priority ?? 100,
    maxTokens: expert.maxTokens ?? 900,
    maxToolCalls: expert.maxToolCalls ?? 3,
    capabilityIds: expert.capabilityIds ?? [],
    defaultSkillIds: expert.defaultSkillIds ?? [],
    requestedToolCapabilities: expert.requestedToolCapabilities ?? [],
    outputContract: expert.outputContract ?? null,
    executionKind: expert.executionKind
      ?? (expert.category === 'Delegated'
        ? 'DELEGATED'
        : expert.category === 'Deterministic'
          ? 'DETERMINISTIC'
          : 'INLINE'),
    triggerIntents: expert.triggerIntents ?? [],
    triggerTasks: expert.triggerTasks ?? [],
    promptVersion: expert.promptVersion ?? 'default',
    category: expert.category ?? 'Skill',
    expectedQualityGain: expert.expectedQualityGain ?? 0,
    qualityGainVerified: expert.qualityGainVerified ?? false,
    qualityGainSource: expert.qualityGainSource ?? 'unverified',
    qualityGainEvalRunId: expert.qualityGainEvalRunId,
    latencyCost: expert.latencyCost ?? 0,
    tokenCost: expert.tokenCost ?? 0,
    resourceCost: expert.resourceCost ?? 0,
  };
}

function runtimeValue(key: keyof AgentRuntimeConfig) {
  return runtimeForm[key] ?? '';
}

function runtimeEffect(row: RuntimeRow) {
  const effects: Record<string, string> = {
    reasoningModeDefault: '请求未指定推理模式时由 worker 使用；实际模型和模式可在 Agent Trace 核对。',
    maxParallelSpecialists: 'worker 按该值限制并行专家数量，避免专家调用失控。',
    maxTotalInputTokens: '模型上下文治理上限，对所有模型统一生效（不再被 per-model 能力表压回去）。worker 由此推导压缩阈值和单轮 Token 预算。',
    contextCompactionThresholdPercent: '输入达到「最大输入 Token × 该比例」时自动压缩历史与工具结果；压缩目标比例按原档差同步下移。取值 50–95。',
    runTokenBudgetPercent: '单轮问答全部模型调用的 Token 总预算 = 最大输入 Token × 该比例。设得过低会让证据较多的问答第一刀就吃光预算并降级。取值 50–400。',
    maxFinalOutputTokensFast: '0 表示按模型窗口和任务类型自动计算；正数只作为快速模式最终回答 Token 上限。',
    maxFinalOutputTokensDeep: '0 表示按模型窗口和任务类型自动计算；正数只作为深度模式最终回答 Token 上限。',
    enableIntentCache: '开启后 worker 可复用稳定意图判断，提高重复问题缓存命中。',
    enableTaskGraphCache: '开启后 worker 可复用稳定任务图规划，减少重复规划开销。',
    enableToolCache: '开启后 worker 可复用可缓存工具结果，降低重复工具调用。',
    enableEvidenceCache: '开启后 worker 可复用证据裁决结果，提升同类问题响应速度。',
    enableSpecialistCache: '开启后 worker 可复用专家输出，减少重复专家模型调用。',
    specialistMcpEnabled: '开启后，仅具备用户与项目作用域、工具权限和调用预算的 Delegated 专家可调用 MCP 工具。',
    maxPromptCharsPerExpert: '0 表示按模型窗口和实际专家数自动分配；正数只作为每个专家完整提示上下文的治理上限。',
    maxSkillPromptChars: '0 表示不设置独立 Skill 字符上限；正数仅作为管理员治理上限，完整提示仍受模型总上下文预算控制。',
    maxEvidenceItems: 'worker 会按该值限制进入回答和专家上下文的证据条数。',
  };
  return row.effect || effects[String(row.key)] || 'worker 会把该配置应用到后续 AI 问答请求。';
}

async function saveRuntime(row: RuntimeRow) {
  savingRuntimeKey.value = String(row.key);
  statusMessage.value = '';
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.updateAgentRuntimeConfig(row.key, {
      value: String(runtimeValue(row.key)),
    });
    applyRuntimeConfig(response.data.data ?? {});
    statusMessage.value = '已保存';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存失败';
  } finally {
    savingRuntimeKey.value = '';
  }
}

async function saveExpert(expert: AgentExpertProfile) {
  savingExpertName.value = expert.expertName;
  statusMessage.value = '';
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.updateAgentExpert(expert.expertName, {
      enabled: expert.enabled !== false,
      priority: Number(expert.priority ?? 100),
      maxTokens: Number(expert.maxTokens ?? 900),
      maxToolCalls: Number(expert.maxToolCalls ?? 3),
      capabilityIds: expert.capabilityIds ?? [],
      defaultSkillIds: expert.defaultSkillIds ?? [],
      requestedToolCapabilities: expert.requestedToolCapabilities ?? [],
      outputContract: expert.outputContract ?? undefined,
      executionKind: expert.executionKind
        ?? (expert.category === 'Delegated'
          ? 'DELEGATED'
          : expert.category === 'Deterministic'
            ? 'DETERMINISTIC'
            : 'INLINE'),
      triggerIntents: expert.triggerIntents ?? [],
      triggerTasks: expert.triggerTasks ?? [],
      promptVersion: expert.promptVersion ?? 'default',
      evalSuiteId: expert.evalSuiteId,
      category: expert.category ?? 'Skill',
      expectedQualityGain: 0,
      latencyCost: Number(expert.latencyCost ?? 0),
      tokenCost: Number(expert.tokenCost ?? 0),
      resourceCost: Number(expert.resourceCost ?? 0),
    });
    experts.value = experts.value.map((item) =>
      item.expertName === expert.expertName ? normalizeExpert(response.data.data) : item,
    );
    statusMessage.value = '已保存';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存失败';
  } finally {
    savingExpertName.value = '';
  }
}

async function startEvalRun() {
  startingEval.value = true;
  statusMessage.value = '';
  errorMessage.value = '';
  try {
    await knowledgeApi.runAgentEval({
      suiteName: evalRunForm.suiteName || 'agent-runtime',
      runnerName: evalRunForm.runnerName || 'admin-trigger',
      evaluatorName: evalRunForm.evaluatorName || 'rule-based',
      modelName: evalRunForm.modelName || undefined,
      caseLimit: Number(evalRunForm.caseLimit ?? 50),
    });
    statusMessage.value = 'Eval 任务已启动';
    await refreshEvalRuns();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Eval 启动失败';
  } finally {
    startingEval.value = false;
  }
}

async function loadEvalCaseResults(run: AgentEvalRun) {
  if (!run.id) {
    return;
  }
  loadingEvalCases.value = true;
  selectedEvalRunId.value = run.id;
  errorMessage.value = '';
  try {
    const response = await knowledgeApi.listAgentEvalCaseResults(run.id);
    evalCaseResults.value = response.data.data ?? [];
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载 Eval 用例失败';
  } finally {
    loadingEvalCases.value = false;
  }
}

async function cancelEvalRun(run: AgentEvalRun) {
  if (!run.id) {
    return;
  }
  operatingEvalRunId.value = run.id;
  statusMessage.value = '';
  errorMessage.value = '';
  try {
    await knowledgeApi.cancelAgentEvalRun(run.id);
    statusMessage.value = 'Eval 任务已更新';
    await refreshEvalRuns();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '取消 Eval 失败';
  } finally {
    operatingEvalRunId.value = null;
  }
}

async function retryEvalRun(run: AgentEvalRun) {
  if (!run.id) {
    return;
  }
  operatingEvalRunId.value = run.id;
  statusMessage.value = '';
  errorMessage.value = '';
  try {
    await knowledgeApi.retryAgentEvalRun(run.id);
    statusMessage.value = 'Eval 任务已更新';
    await refreshEvalRuns();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '重试 Eval 失败';
  } finally {
    operatingEvalRunId.value = null;
  }
}

function evalProgressLabel(run: AgentEvalRun) {
  const total = run.progressTotal ?? run.totalCases ?? 0;
  const current = run.progressCurrent ?? 0;
  return total > 0 ? `${current} / ${total}` : '-';
}

function retryLabel(run: AgentEvalRun) {
  const retryCount = run.retryCount ?? 0;
  return `重试 ${retryCount}`;
}

function evalProgressMessage(run: AgentEvalRun) {
  const message = String(run.progressMessage ?? '').trim();
  const completed = /^case\s+(\d+)\/(\d+)\s+completed$/i.exec(message);
  if (completed) {
    return `已完成用例 ${completed[1]} / ${completed[2]}`;
  }
  return message;
}

function isEvalRunCancellable(run: AgentEvalRun) {
  return ['QUEUED', 'RUNNING', 'CANCELLING'].includes(String(run.status ?? '').toUpperCase());
}

function isEvalRunRetryable(run: AgentEvalRun) {
  const status = String(run.status ?? '').toUpperCase();
  const retryCount = run.retryCount ?? 0;
  const maxRetries = run.maxRetries ?? 3;
  return ['FAILED', 'CANCELLED'].includes(status) && retryCount < maxRetries;
}
</script>

<template>
  <main class="agent-governance" v-loading="loading">
    <header class="agent-governance__header">
      <div>
        <h1>Agent 治理</h1>
        <p>{{ enabledExpertCount }} 个启用专家</p>
      </div>
      <div class="status-line">
        <span v-if="statusMessage" class="status-line__ok">{{ statusMessage }}</span>
        <span v-if="errorMessage" class="status-line__error">{{ errorMessage }}</span>
      </div>
    </header>

    <section class="governance-section">
      <header class="section-header">
        <h2>运行拓扑</h2>
      </header>
      <ol class="runtime-topology" aria-label="Agent 运行拓扑">
        <li v-for="node in topologyNodes" :key="node">
          <span>{{ node }}</span>
        </li>
      </ol>
      <p class="runtime-policy-note">设置保存后对下一次 worker 请求生效，可在 Agent Trace 核对实际模型、Token、缓存和节点状态。</p>
    </section>

    <section class="governance-section" data-test="governance-runtime-graph">
      <header class="section-header">
        <h2>最新 LangGraph 运行图</h2>
        <span v-if="latestTrace?.traceId" class="runtime-policy-note">{{ latestTrace.traceId }}</span>
      </header>
      <LangGraphRuntimeGraph :result-json="latestTraceResultJson" />
    </section>

    <section class="governance-section">
      <header class="section-header">
        <h2>运行策略</h2>
      </header>
      <el-table :data="runtimeRows" size="small" class="governance-table">
        <el-table-column prop="label" label="配置项" min-width="230" show-overflow-tooltip />
        <el-table-column label="生效范围" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ runtimeEffect(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当前值" min-width="220">
          <template #default="{ row }">
            <select
              v-if="row.type === 'reasoning'"
              v-model="runtimeForm[row.key]"
              class="governance-input"
              :aria-label="row.label"
            >
              <option value="fast">快速模式</option>
              <option value="deep">深度模式</option>
            </select>
            <input
              v-else-if="row.type === 'number'"
              v-model.number="runtimeForm[row.key]"
              class="governance-input"
              type="number"
              :aria-label="row.label"
            />
            <input
              v-else-if="row.type === 'boolean'"
              v-model="runtimeForm[row.key]"
              class="governance-checkbox"
              type="checkbox"
              :aria-label="row.label"
            />
            <input
              v-else
              v-model="runtimeForm[row.key]"
              class="governance-input"
              type="text"
              :aria-label="row.label"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :loading="savingRuntimeKey === row.key"
              :data-test="`save-runtime-${row.key}`"
              @click="saveRuntime(row)"
            >
              保存
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="governance-section">
      <header class="section-header">
        <h2>缓存与 Token</h2>
      </header>
      <div class="metric-grid">
        <div class="metric-cell">
          <span>Trace 数</span>
          <strong>{{ stats.traceCount }}</strong>
        </div>
        <div class="metric-cell">
          <span>缓存命中</span>
          <strong>{{ stats.cacheHits }}</strong>
        </div>
        <div class="metric-cell">
          <span>缓存未命中</span>
          <strong>{{ stats.cacheMisses }}</strong>
        </div>
        <div class="metric-cell">
          <span>Token 总量</span>
          <strong>{{ stats.totalTokens }}</strong>
        </div>
        <div class="metric-cell">
          <span>提示前缀稳定率</span>
          <strong>{{ promptStabilityLabel }}</strong>
        </div>
      </div>
      <div class="token-breakdown">
        <div>
          <h3>按节点</h3>
          <p v-if="!tokenNodeRows.length">暂无节点 Token 数据</p>
          <dl v-else>
            <template v-for="[name, value] in tokenNodeRows" :key="name">
              <dt>{{ name }}</dt>
              <dd>{{ value }}</dd>
            </template>
          </dl>
        </div>
        <div>
          <h3>按专家</h3>
          <p v-if="!tokenExpertRows.length">暂无专家 Token 数据</p>
          <dl v-else>
            <template v-for="[name, value] in tokenExpertRows" :key="name">
              <dt>{{ name }}</dt>
              <dd>{{ value }}</dd>
            </template>
          </dl>
        </div>
      </div>
    </section>

    <section class="governance-section">
      <header class="section-header">
        <h2>Eval 中心</h2>
      </header>
      <div class="eval-run-form">
        <label>
          <span>套件</span>
          <input
            v-model="evalRunForm.suiteName"
            class="governance-input"
            data-test="eval-suite-name"
            type="text"
          />
        </label>
        <label>
          <span>用例数</span>
          <input
            v-model.number="evalRunForm.caseLimit"
            class="governance-input governance-input--short"
            data-test="eval-case-limit"
            type="number"
            min="1"
            max="500"
          />
        </label>
        <label>
          <span>模型</span>
          <input
            v-model="evalRunForm.modelName"
            class="governance-input"
            data-test="eval-model-name"
            type="text"
            placeholder="worker 默认"
          />
        </label>
        <el-button
          type="primary"
          :loading="startingEval"
          data-test="run-eval-suite"
          @click="startEvalRun"
        >
          运行套件
        </el-button>
      </div>
      <el-table :data="evalRuns" size="small" class="governance-table">
        <el-table-column prop="runKey" label="运行批次" min-width="190" show-overflow-tooltip />
        <el-table-column prop="suiteName" label="套件" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ knowledgeStatusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="totalCases" label="用例" width="90" />
        <el-table-column prop="passedCases" label="通过" width="90" />
        <el-table-column prop="failedCases" label="失败" width="90" />
        <el-table-column label="进度" min-width="150">
          <template #default="{ row }">
            <span>{{ evalProgressLabel(row) }}</span>
            <small v-if="evalProgressMessage(row)" class="eval-progress-message">{{ evalProgressMessage(row) }}</small>
          </template>
        </el-table-column>
        <el-table-column label="重试" width="95">
          <template #default="{ row }">
            <span>{{ retryLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
        <el-table-column prop="metricsJson" label="指标" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :loading="loadingEvalCases && selectedEvalRunId === row.id"
              :data-test="`view-eval-cases-${row.id}`"
              @click="loadEvalCaseResults(row)"
            >
              用例
            </el-button>
            <el-button
              size="small"
              :loading="operatingEvalRunId === row.id"
              :disabled="!isEvalRunCancellable(row)"
              :data-test="`cancel-eval-run-${row.id}`"
              @click="cancelEvalRun(row)"
            >
              取消
            </el-button>
            <el-button
              size="small"
              :loading="operatingEvalRunId === row.id"
              :disabled="!isEvalRunRetryable(row)"
              :data-test="`retry-eval-run-${row.id}`"
              @click="retryEvalRun(row)"
            >
              重试
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-table
        v-if="selectedEvalRunId"
        :data="evalCaseResults"
        size="small"
        class="governance-table"
        data-test="eval-case-results"
      >
        <el-table-column prop="caseKey" label="用例" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ knowledgeStatusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="意图" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ knowledgeDomainLabel(row.intent) }}</template>
        </el-table-column>
        <el-table-column label="回答模式" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ knowledgeDomainLabel(row.answerMode) }}</template>
        </el-table-column>
        <el-table-column prop="traceId" label="Trace" min-width="150" show-overflow-tooltip />
        <el-table-column prop="durationMs" label="ms" width="80" />
        <el-table-column prop="failures" label="失败原因" min-width="240" show-overflow-tooltip />
      </el-table>
    </section>

    <section class="governance-section">
      <header class="section-header">
        <h2>专家画像</h2>
      </header>
      <el-table :data="experts" size="small" class="governance-table">
        <el-table-column label="启用" width="95">
          <template #default="{ row }">
            <input
              v-model="row.enabled"
              class="governance-checkbox"
              type="checkbox"
              :data-test="`expert-enabled-${row.expertName}`"
              :aria-label="`${expertLabel(row.expertName, row.displayName)}启用`"
            />
          </template>
        </el-table-column>
        <el-table-column prop="expertName" label="专家" min-width="150" show-overflow-tooltip />
        <el-table-column label="显示名" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ expertLabel(row.expertName, row.displayName) }}</template>
        </el-table-column>
        <el-table-column label="能力类型" width="150">
          <template #default="{ row }">
            <select
              v-model="row.category"
              class="governance-input"
              :data-test="`expert-category-${row.expertName}`"
              @change="row.executionKind = row.category === 'Delegated' ? 'DELEGATED' : row.category === 'Deterministic' ? 'DETERMINISTIC' : 'INLINE'"
            >
              <option value="Skill">{{ capabilityLabel('Skill') }}</option>
              <option value="Deterministic">{{ capabilityLabel('Deterministic') }}</option>
              <option value="Delegated">{{ capabilityLabel('Delegated') }}</option>
            </select>
          </template>
        </el-table-column>
        <el-table-column label="执行形态" width="150">
          <template #default="{ row }">
            <select v-model="row.executionKind" class="governance-input" :data-test="`expert-execution-kind-${row.expertName}`">
              <option value="INLINE">INLINE</option>
              <option value="DETERMINISTIC">DETERMINISTIC</option>
              <option value="DELEGATED">DELEGATED</option>
            </select>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="110">
          <template #default="{ row }">
            <input v-model.number="row.priority" class="governance-input governance-input--short" type="number" />
          </template>
        </el-table-column>
        <el-table-column label="Token" width="110">
          <template #default="{ row }">
            <input v-model.number="row.maxTokens" class="governance-input governance-input--short" type="number" />
          </template>
        </el-table-column>
        <el-table-column label="工具次数" width="100">
          <template #default="{ row }">
            <input v-model.number="row.maxToolCalls" class="governance-input governance-input--short" type="number" />
          </template>
        </el-table-column>
        <el-table-column label="预期收益" width="110">
          <template #default="{ row }">
            <input :value="row.expectedQualityGain ?? 0" class="governance-input governance-input--short" type="number" disabled />
          </template>
        </el-table-column>
        <el-table-column label="收益依据" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.qualityGainVerified ? `Eval #${row.qualityGainEvalRunId}` : '未通过评测' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="延迟成本" width="110">
          <template #default="{ row }">
            <input v-model.number="row.latencyCost" class="governance-input governance-input--short" type="number" min="0" step="0.05" />
          </template>
        </el-table-column>
        <el-table-column label="Token 成本" width="110">
          <template #default="{ row }">
            <input v-model.number="row.tokenCost" class="governance-input governance-input--short" type="number" min="0" step="0.05" />
          </template>
        </el-table-column>
        <el-table-column label="资源成本" width="110">
          <template #default="{ row }">
            <input v-model.number="row.resourceCost" class="governance-input governance-input--short" type="number" min="0" step="0.05" />
          </template>
        </el-table-column>
        <el-table-column label="触发条件" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span>
              {{ [...(row.triggerIntents ?? []), ...(row.triggerTasks ?? [])]
                .map((value: string) => knowledgeDomainLabel(value)).join('、') || '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="请求能力" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ (row.requestedToolCapabilities ?? []).join(', ') || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提示词版本" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.promptVersion === 'default' ? '默认' : row.promptVersion }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :loading="savingExpertName === row.expertName"
              :data-test="`save-expert-${row.expertName}`"
              @click="saveExpert(row)"
            >
              保存
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </main>
</template>

<style scoped>
.agent-governance {
  display: grid;
  gap: 1rem;
  min-width: 0;
  padding: 1rem;
}

.agent-governance__header,
.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.agent-governance__header h1,
.agent-governance__header p,
.section-header h2 {
  margin: 0;
}

.agent-governance__header h1,
.section-header h2 {
  font-size: 1rem;
  line-height: 1.4;
}

.agent-governance__header p {
  margin-top: 0.25rem;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.status-line {
  min-height: 1.5rem;
  font-size: 0.8125rem;
}

.status-line__ok {
  color: var(--el-color-success);
}

.status-line__error {
  color: var(--el-color-danger);
}

.governance-section {
  min-width: 0;
  display: grid;
  gap: 0.75rem;
  padding: 1rem;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.governance-table {
  width: 100%;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
  gap: 0.75rem;
}

.runtime-topology {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(7.5rem, 1fr));
  gap: 0.625rem;
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.runtime-topology li {
  position: relative;
  min-height: 44px;
  display: grid;
  place-items: center;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-primary);
  font-size: 0.875rem;
  font-weight: 650;
  text-align: center;
}

.runtime-topology li:not(:last-child)::after {
  content: '>';
  position: absolute;
  right: -0.55rem;
  color: var(--el-text-color-secondary);
  font-weight: 700;
}

.metric-cell {
  min-width: 0;
  display: grid;
  gap: 0.25rem;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.metric-cell span,
.token-breakdown p {
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.metric-cell strong {
  font-size: 1.1rem;
  line-height: 1.2;
}

.token-breakdown {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

.eval-progress-message {
  display: block;
  margin-top: 0.125rem;
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
  line-height: 1.25;
}

.eval-run-form {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 0.75rem;
}

.eval-run-form label {
  display: grid;
  gap: 0.25rem;
  min-width: 8rem;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.token-breakdown h3 {
  margin: 0 0 0.5rem;
  font-size: 0.875rem;
}

.token-breakdown dl {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.375rem 0.75rem;
  margin: 0;
  font-size: 0.8125rem;
}

.token-breakdown dt {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--el-text-color-secondary);
}

.token-breakdown dd {
  margin: 0;
  font-weight: 650;
}

.governance-input {
  width: 100%;
  max-width: 18rem;
  min-height: 30px;
  padding: 0 0.5rem;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
}

.governance-input--short {
  max-width: 5.5rem;
}

.governance-checkbox {
  width: 1rem;
  height: 1rem;
  accent-color: var(--el-color-primary);
}

@media (max-width: 760px) {
  .agent-governance__header {
    flex-direction: column;
  }

  .metric-grid,
  .token-breakdown {
    grid-template-columns: 1fr;
  }
}
</style>
