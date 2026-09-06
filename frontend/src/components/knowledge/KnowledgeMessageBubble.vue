<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ArrowDown, CopyDocument, Delete, Select } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { renderAnalysisMarkdown } from '@/lib/markdown';
import {
  degradationReasonLabel,
  knowledgeIntentLabel,
  knowledgeUserStatusLabel,
} from '@/utils/knowledgeDisplay';
import type { KnowledgeRunModelCall, KnowledgeRunProcess, KnowledgeSource } from '@/types/knowledge';

const props = defineProps<{
  role: 'user' | 'assistant';
  content: string;
  status?: string;
  answerStatus?: string;
  intent?: string;
  answerBoundary?: string;
  sources?: KnowledgeSource[];
  fallbackUsed?: boolean;
  degraded?: boolean;
  degradationReasons?: string[];
  process?: KnowledgeRunProcess;
  deletable?: boolean;
  deleteTestId?: string;
  copyTestId?: string;
}>();

const emit = defineEmits<{
  delete: [];
  loadProcess: [];
}>();

const renderedContent = computed(() => (
  props.role === 'assistant'
    ? renderAnalysisMarkdown(props.content)
    : props.content
));

const showSources = ref(false);
const showProcess = ref(false);
const copied = ref(false);
let copiedTimer: ReturnType<typeof setTimeout> | undefined;

/** 复制的是原始 markdown（含 [n] 引注），跟用户在页面上读到的答案一致。 */
async function copyContent() {
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error('当前环境不支持剪贴板');
    }
    await navigator.clipboard.writeText(props.content ?? '');
    copied.value = true;
    if (copiedTimer !== undefined) {
      clearTimeout(copiedTimer);
    }
    copiedTimer = setTimeout(() => {
      copied.value = false;
      copiedTimer = undefined;
    }, 1_600);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '复制失败，请手动选择内容复制');
  }
}

onBeforeUnmount(() => {
  if (copiedTimer !== undefined) {
    clearTimeout(copiedTimer);
    copiedTimer = undefined;
  }
});

const processDisplayTouched = ref(false);
const processClockMs = ref(Date.now());
let processTimer: ReturnType<typeof setInterval> | undefined;

const processStatusLabel = computed(() => {
  const labels: Record<KnowledgeRunProcess['status'], string> = {
    processing: '处理中',
    processed: '已处理',
    failed: '处理失败',
    cancelled: '已取消',
  };
  return props.process ? labels[props.process.status] : '';
});

const processDurationMs = computed(() => {
  const startedAtMs = finiteTimestamp(props.process?.startedAtMs);
  const finishedAtMs = finiteTimestamp(props.process?.finishedAtMs);
  if (startedAtMs !== undefined) {
    const endAtMs = props.process?.status === 'processing'
      ? processClockMs.value
      : finishedAtMs;
    if (endAtMs !== undefined) {
      return Math.max(0, endAtMs - startedAtMs);
    }
  }
  return props.process?.durationMs;
});
const processDurationLabel = computed(() => formatDuration(processDurationMs.value));
const processDetailVisible = computed(() => (
  showProcess.value
  || (props.process?.status === 'processing' && !processDisplayTouched.value)
));

watch(
  () => [props.process?.status, props.process?.startedAtMs, props.process?.finishedAtMs] as const,
  () => {
    stopProcessTimer();
    processClockMs.value = props.process?.finishedAtMs ?? Date.now();
    if (props.process?.status === 'processing'
      && finiteTimestamp(props.process.startedAtMs) !== undefined
      && typeof window !== 'undefined') {
      processTimer = window.setInterval(() => {
        processClockMs.value = Date.now();
      }, 1_000);
    }
  },
  { immediate: true },
);

onBeforeUnmount(stopProcessTimer);

const answerStatusLabel = computed(() => {
  const labels: Record<string, string> = {
    answered_with_evidence: '有证据',
    partial_answer: '部分证据',
    creative_answer: '创作建议',
    needs_data: '需补数据',
    needs_chapter_evidence: '需章节证据',
    out_of_scope: '超出范围',
  };
  if (props.answerStatus && labels[props.answerStatus]) {
    return labels[props.answerStatus];
  }
  if (props.status) {
    const normalized = props.status.trim().toLowerCase();
    const label = knowledgeUserStatusLabel(props.status);
    if (!['answered', 'streaming'].includes(normalized) && !['已回答', '正在生成回答'].includes(label)) {
      return label;
    }
  }
  return '';
});

const intentLabel = computed(() => {
  return props.intent ? knowledgeIntentLabel(props.intent) : '';
});

const answerBoundaryLabel = computed(() => {
  const labels: Record<string, string> = {
    market_evidence: '市场证据',
    market_evidence_plus_author_inference: '市场证据+作者推演',
    book_evidence_plus_craft_extraction: '作品证据+技法提炼',
    creative_inference: '创作推演',
    outline_generation: '大纲生成',
    needs_more_data: '需要补数据',
    out_of_scope: '范围外',
    structured_fact: '结构化事实',
    evidence_grounded: '证据回答',
    evidence_plus_author_inference: '证据+作者推演',
    project_knowledge: '作品知识证据',
  };
  return props.answerBoundary ? labels[props.answerBoundary] || props.answerBoundary : '';
});

const showDegradedNotice = computed(() => props.role === 'assistant' && (props.degraded || props.fallbackUsed));

const degradedReasonLabel = computed(() => (props.degradationReasons ?? [])
  .map((reason) => degradationReasonLabel(reason))
  .filter(Boolean)
  .join('；'));

function sourceLabel(source: KnowledgeSource, index: number) {
  if ((source.sourceType || '').toUpperCase() === 'RANK') {
    return `[${index + 1}] ${source.rankNo ? `#${source.rankNo}` : '榜单'}`;
  }
  if (source.chapterNo) {
    return `[${index + 1}] 第 ${source.chapterNo} 章`;
  }
  if (source.analysisType) {
    return `[${index + 1}] ${source.analysisType}`;
  }
  return `[${index + 1}] 来源`;
}

function isRankSource(source: KnowledgeSource) {
  return (source.sourceType || '').toUpperCase() === 'RANK';
}

function toggleProcess() {
  const nextVisible = !processDetailVisible.value;
  processDisplayTouched.value = true;
  showProcess.value = nextVisible;
  if (nextVisible) {
    emit('loadProcess');
  }
}

function formatDuration(durationMs?: number) {
  if (!Number.isFinite(durationMs) || Number(durationMs) < 0) {
    return '';
  }
  const milliseconds = Number(durationMs);
  if (milliseconds < 1000) {
    return `${Math.round(milliseconds)} 毫秒`;
  }
  const seconds = milliseconds / 1000;
  if (seconds >= 60) {
    const totalSeconds = Math.floor(seconds);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const remainingSeconds = totalSeconds % 60;
    return [
      hours ? `${hours} 小时` : '',
      minutes ? `${minutes} 分钟` : '',
      `${remainingSeconds} 秒`,
    ].filter(Boolean).join(' ');
  }
  return `${Number.isInteger(seconds) ? seconds.toFixed(0) : seconds.toFixed(1)} 秒`;
}

function finiteTimestamp(value?: number) {
  return Number.isFinite(value) && Number(value) >= 0 ? Number(value) : undefined;
}

function stopProcessTimer() {
  if (processTimer !== undefined) {
    clearInterval(processTimer);
    processTimer = undefined;
  }
}

function modelCallStatusLabel(status: KnowledgeRunModelCall['status']) {
  if (status === 'succeeded') {
    return '成功';
  }
  if (status === 'failed') {
    return '失败';
  }
  return '状态未知';
}

function modelCallMeta(call: KnowledgeRunModelCall) {
  const parts = [
    call.reasoningMode === 'deep' ? '深度' : call.reasoningMode === 'fast' ? '快速' : '',
    call.providerRequestCount && call.providerRequestCount > 1
      ? `${call.providerRequestCount} 次请求`
      : '',
  ];
  return parts.filter(Boolean).join(' · ');
}

function formatTokens(tokenUsed?: number) {
  return Number.isFinite(tokenUsed) && Number(tokenUsed) >= 0
    ? `${Math.round(Number(tokenUsed))} Token`
    : '';
}

function providerWireLabel(call: KnowledgeRunModelCall) {
  if (call.wireApi === 'responses') return 'Responses API';
  if (call.wireApi === 'chat_completions' && call.providerTransportFallback) {
    return 'Chat compatibility fallback';
  }
  return call.wireApi === 'chat_completions' ? 'Chat Completions' : '';
}

function providerUsageLabel(call: KnowledgeRunModelCall) {
  const usage = call.usage;
  if (!usage) return '';
  const input = usage.inputTokens ?? usage.promptTokens;
  const output = usage.outputTokens ?? usage.completionTokens;
  const reasoning = usage.reasoningTokens;
  const cached = usage.cachedInputTokens ?? usage.promptCacheHitTokens;
  const missed = usage.promptCacheMissTokens;
  const parts = [
    input != null ? `上下文 ${input}` : '',
    output != null ? `输出 ${output}` : '',
    reasoning != null ? `推理 ${reasoning}` : '',
    cached != null ? `缓存命中 ${cached}` : '',
    missed != null ? `未命中 ${missed}` : '',
  ].filter(Boolean);
  if (!parts.length) {
    // 中继一个用量字段都没回时，页面上必须看得出是"没上报"而不是"用了 0"。
    return call.usageReported === true || usage.usageReported === true ? '' : '用量未上报';
  }
  // 上游一个缓存字段都没回时，写"缓存命中 0"和写"不知道"在页面上长得一模一样，
  // 但处置方向相反：一个该去查中继有没有透传用量，一个该去查前缀被谁改写了。
  const cacheReported = call.cacheUsageReported === true || usage.cacheUsageReported === true;
  const suffix = cached == null && missed == null && !cacheReported ? ' · 缓存未上报' : '';
  return `${parts.join(' · ')} Token${suffix}`;
}

/** 选中的模型没有对应 profile 时会静默落到默认档，缓存和计费都记在实际模型上。 */
function routedModelLabel(call: KnowledgeRunModelCall) {
  if (!call.modelSubstituted || !call.routedModel) return '';
  return `实际路由 ${call.routedModel}`;
}

/** 前缀太短供应商根本不缓存，指纹换了就是被改写了——这两条决定该查哪一头。 */
function cachePrefixLabel(call: KnowledgeRunModelCall) {
  const summary = call.requestSummary;
  if (!summary) return '';
  const parts = [
    summary.cachePrefixChars != null ? `缓存前缀 ${summary.cachePrefixChars} 字符` : '',
    summary.cacheAffinityPresent === true
      ? '带缓存亲和键'
      : summary.cacheAffinityPresent === false ? '无缓存亲和键' : '',
    summary.cachePrefixFingerprint ? `前缀指纹 ${summary.cachePrefixFingerprint.slice(0, 8)}` : '',
  ].filter(Boolean);
  return parts.join(' · ');
}

const promptCacheLabel = computed(() => {
  const summary = props.process?.promptCache;
  if (!summary) return '';
  if (!summary.measured || summary.hitRatioPercent == null) {
    return `前缀缓存未上报（共 ${summary.calls} 次调用）`;
  }
  return `前缀缓存命中率 ${summary.hitRatioPercent}%`
    + `（${summary.reportingCalls}/${summary.calls} 次上报 · 命中 ${summary.hitTokens} / 未命中 ${summary.missTokens} Token）`;
});

function requestSummaryLabel(call: KnowledgeRunModelCall) {
  const summary = call.requestSummary;
  if (!summary) return '';
  return `请求 ${summary.messageCount ?? 0} 条消息 · ${summary.messageChars ?? 0} 字符 · ${summary.toolSchemaCount ?? 0} 个工具定义`;
}

function responseSummaryLabel(call: KnowledgeRunModelCall) {
  const summary = call.responseSummary;
  if (!summary) return '';
  return `返回 ${summary.outputChars ?? 0} 字符 · ${summary.toolCallCount ?? 0} 个工具调用${summary.emptyResponse ? ' · 空返回' : ''}`;
}

const FAILURE_CLASS_LABELS: Record<string, string> = {
  CONNECT_ERROR: '连接失败',
  TIMEOUT: '请求超时',
  HTTP_401: '凭证被拒 401',
  HTTP_402: '额度不足 402',
  HTTP_403: '凭证被拒 403',
  HTTP_404: '模型不存在 404',
  HTTP_429: '触发限流 429',
  HTTP_500: '上游错误 500',
  HTTP_502: '网关错误 502',
  HTTP_503: '服务不可用 503',
  HTTP_504: '网关超时 504',
};

function failureClassLabel(call: KnowledgeRunModelCall) {
  const failureClass = call.failureClass;
  if (!failureClass) return '';
  return FAILURE_CLASS_LABELS[failureClass] ?? failureClass;
}

/** 只在真的重试或换过 key 时出现，正常调用不该被这行噪音占位。 */
function failoverLabel(call: KnowledgeRunModelCall) {
  const parts = [
    call.attemptIndex && call.attemptIndex > 1 ? `第 ${call.attemptIndex} 次尝试` : '',
    call.profileKeyUsed ? `使用 ${call.profileKeyUsed}` : '',
    failureClassLabel(call),
  ].filter(Boolean);
  const retried = (call.attemptIndex ?? 1) > 1 || Boolean(call.failureClass);
  return retried ? parts.join(' · ') : '';
}
/** 压缩前后动辄十万级，不加千位分隔读不出量级差；不用 toLocaleString 是为了不依赖 ICU。 */
function formatTokenAmount(value?: number) {
  if (!Number.isFinite(value) || Number(value) < 0) {
    return '';
  }
  return String(Math.round(Number(value))).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/**
 * 降级原因在气泡页脚已经有一个徽标，但那里只说"降级了"，脱离了过程上下文。
 * 链路面板里再列一次，是为了让"第几步降的、降之前压过没有"能一眼串起来。
 * 未知 code 走 degradationReasonLabel 的通用兜底，原始 code 不直接甩给用户。
 */
const processDegradationLabel = computed(() => {
  // 顶层 prop 由消息级字段喂，process 内那份由 resultJson 喂，两者到齐时间不同。
  // 面板取并集里更具体的那份，保证它永远不比页脚徽标少说。
  const reasons = props.process?.degradationReasons?.length
    ? props.process.degradationReasons
    : props.degradationReasons ?? [];
  const labels: string[] = [];
  for (const reason of reasons) {
    const label = degradationReasonLabel(reason);
    if (label && !labels.includes(label)) {
      labels.push(label);
    }
  }
  return labels.join(' · ');
});

/**
 * 压缩细节压成一行。工具条那个上下文气泡只说"已压缩／未压缩"，压前压后差多少、
 * 保留了几轮、摘要吃掉几条，一直只能去查 `ai_chat_run.result_json`——这行补的是那个缺口。
 * 注意 worker 顶层 `contextCompaction` 还带着 `compactedSummary`（压缩后的会话正文），
 * 映射层已经把它挡在外面，这里拿到的只有计数字段。
 */
const processCompactionLabel = computed(() => {
  const compaction = props.process?.contextCompaction;
  if (!compaction) {
    return '';
  }
  const head = compaction.status === 'reused' ? '复用上一代压缩摘要' : '上下文已自动压缩';
  const parts: string[] = [];
  if (compaction.thresholdTokens !== undefined) {
    parts.push(`阈值 ${formatTokenAmount(compaction.thresholdTokens)}`);
  }
  if (compaction.beforeInputTokens !== undefined && compaction.afterInputTokens !== undefined) {
    parts.push(`${formatTokenAmount(compaction.beforeInputTokens)} → ${formatTokenAmount(compaction.afterInputTokens)} tokens`);
  }
  if (compaction.retainedTurnCount !== undefined) {
    parts.push(`保留 ${compaction.retainedTurnCount} 轮`);
  }
  if (compaction.summarizedMessageCount !== undefined) {
    parts.push(`摘要 ${compaction.summarizedMessageCount} 条`);
  }
  if (compaction.generation) {
    parts.push(`第 ${compaction.generation} 代`);
  }
  return parts.length ? `${head} · ${parts.join(' · ')}` : head;
});
</script>

<template>
  <article class="knowledge-message" :class="`is-${role}`">
    <div v-if="role === 'assistant' && process" class="knowledge-message__process">
      <button
        class="knowledge-message__process-toggle"
        type="button"
        data-test="knowledge-process-toggle"
        :aria-expanded="processDetailVisible"
        @click="toggleProcess"
      >
        <span :class="`is-${process.status}`">{{ processStatusLabel }}</span>
        <small
          v-if="process.status === 'processing' && process.currentStep"
          class="knowledge-message__process-current"
          data-test="knowledge-process-current"
          aria-live="polite"
        >
          {{ process.currentStep.label }}
        </small>
        <small
          v-if="processDurationLabel"
          class="knowledge-message__process-duration"
          data-test="knowledge-process-duration"
        >{{ processDurationLabel }}</small>
        <el-icon :class="{ 'is-expanded': processDetailVisible }" :size="15"><ArrowDown /></el-icon>
      </button>
      <div v-if="processDetailVisible" class="knowledge-message__process-detail" data-test="knowledge-process-detail">
        <p v-if="process.loading">正在加载处理记录...</p>
        <small
          v-if="processDegradationLabel"
          class="knowledge-message__process-degradation"
          data-test="knowledge-process-degradation"
        >降级原因：{{ processDegradationLabel }}</small>
        <small
          v-if="processCompactionLabel"
          class="knowledge-message__process-compaction"
          data-test="knowledge-process-compaction"
        >{{ processCompactionLabel }}</small>
        <dl
          v-if="!process.loading && process.operationalSummaries?.length"
          class="knowledge-message__process-summaries"
        >
          <div
            v-for="summary in process.operationalSummaries"
            :key="summary.id"
            data-test="knowledge-process-summary"
          >
            <dt>{{ summary.label }}</dt>
            <dd>{{ summary.detail }}</dd>
          </div>
        </dl>
        <ol v-if="!process.loading && process.steps.length">
          <li
            v-for="step in process.steps"
            :key="step.id"
            :class="`is-${step.status}`"
            data-test="knowledge-process-step"
          >
            <span>{{ step.label }}</span>
            <small v-if="formatDuration(step.durationMs)">{{ formatDuration(step.durationMs) }}</small>
          </li>
        </ol>
        <small v-if="process.modelCallCount != null" class="knowledge-message__model-count">
          模型调用 {{ process.modelCallCount }} 次
        </small>
        <small
          v-if="promptCacheLabel"
          class="knowledge-message__prompt-cache"
          data-test="knowledge-prompt-cache"
        >{{ promptCacheLabel }}</small>
        <div v-if="process.modelCalls?.length" class="knowledge-message__model-calls">
          <strong>模型调用记录</strong>
          <ol>
            <li
              v-for="call in process.modelCalls"
              :key="call.id"
              class="knowledge-message__model-call"
              data-test="knowledge-model-call"
            >
              <span class="knowledge-message__model-call-identity">
                <b>{{ call.label }}</b>
                <small v-if="call.model">{{ call.model }}</small>
                <small
                  v-if="routedModelLabel(call)"
                  class="is-substituted"
                  data-test="knowledge-model-call-routed"
                >{{ routedModelLabel(call) }}</small>
              </span>
              <span class="knowledge-message__model-call-metrics">
                <small :class="`is-${call.status}`">{{ modelCallStatusLabel(call.status) }}</small>
                <small v-if="formatDuration(call.durationMs)">{{ formatDuration(call.durationMs) }}</small>
                <small v-if="formatTokens(call.tokenUsed)">{{ formatTokens(call.tokenUsed) }}</small>
                <small v-if="modelCallMeta(call)">{{ modelCallMeta(call) }}</small>
                <small v-if="providerWireLabel(call)">{{ providerWireLabel(call) }}</small>
                <small
                  v-if="providerUsageLabel(call)"
                  data-test="knowledge-model-call-usage"
                >{{ providerUsageLabel(call) }}</small>
              </span>
              <span
                v-if="failoverLabel(call)"
                class="knowledge-message__model-call-failover"
                data-test="knowledge-model-call-failover"
              >{{ failoverLabel(call) }}</span>
              <span
                v-if="call.requestSummary || call.responseSummary"
                class="knowledge-message__model-call-summary"
              >
                <small v-if="requestSummaryLabel(call)">{{ requestSummaryLabel(call) }}</small>
                <small v-if="responseSummaryLabel(call)">{{ responseSummaryLabel(call) }}</small>
                <small
                  v-if="cachePrefixLabel(call)"
                  data-test="knowledge-model-call-cache-prefix"
                >{{ cachePrefixLabel(call) }}</small>
                <small v-if="call.requestSummary?.bodyRedacted">请求正文已省略</small>
                <small v-if="call.responseSummary?.bodyRedacted">返回正文已省略</small>
              </span>
            </li>
          </ol>
        </div>
      </div>
    </div>

    <div v-if="role === 'assistant'" class="knowledge-message__markdown" v-html="renderedContent" />
    <p v-else>{{ renderedContent }}</p>

    <footer class="knowledge-message__meta">
      <span class="knowledge-message__actions">
        <button
          class="knowledge-message__action"
          :class="{ 'is-copied': copied }"
          type="button"
          :data-test="copyTestId"
          :aria-label="copied ? '已复制' : '复制内容'"
          :title="copied ? '已复制' : '复制内容'"
          @click="copyContent"
        >
          <el-icon :size="14">
            <Select v-if="copied" />
            <CopyDocument v-else />
          </el-icon>
        </button>
        <button
          v-if="deletable"
          class="knowledge-message__action is-danger"
          type="button"
          :data-test="deleteTestId"
          aria-label="删除消息"
          title="删除消息"
          @click="$emit('delete')"
        >
          <el-icon :size="14"><Delete /></el-icon>
        </button>
      </span>
      <template v-if="role === 'assistant'">
        <button
          v-if="sources?.length"
          class="knowledge-message__sources-toggle"
          type="button"
          @click="showSources = !showSources"
        >
          引用来源 {{ sources.length }}
        </button>
        <span v-if="showDegradedNotice" class="knowledge-message__degraded">
          降级回答
          <small v-if="degradedReasonLabel">{{ degradedReasonLabel }}</small>
        </span>
        <span v-if="answerStatusLabel" class="knowledge-message__status">{{ answerStatusLabel }}</span>
        <span v-if="intentLabel" class="knowledge-message__badge">{{ intentLabel }}</span>
        <span v-if="answerBoundaryLabel" class="knowledge-message__badge">{{ answerBoundaryLabel }}</span>
      </template>
    </footer>

    <ol v-if="role === 'assistant' && sources?.length && showSources" class="knowledge-message__sources">
      <li v-for="(source, index) in sources" :key="source.chunkId ?? `${source.title}-${index}`">
        <template v-if="isRankSource(source)">
          <div class="knowledge-message__rank-source">
            <strong>{{ sourceLabel(source, index) }}</strong>
            <span>{{ source.bookName || source.title || '未命名作品' }}</span>
            <small v-if="source.author">{{ source.author }}</small>
          </div>
          <p v-if="source.title || source.category">{{ [source.title, source.category].filter(Boolean).join(' · ') }}</p>
        </template>
        <template v-else>
        <strong>{{ sourceLabel(source, index) }}</strong>
        <span>{{ source.title || source.bookName || '未命名来源' }}</span>
        <p v-if="source.preview">{{ source.preview }}</p>
        </template>
      </li>
    </ol>
  </article>
</template>

<style scoped lang="scss">
.knowledge-message {
  width: fit-content;
  max-width: min(78%, 720px);
  display: grid;
  gap: 0.45rem;
  border-radius: 8px;
  line-height: 1.75;
  position: relative;
}

.knowledge-message p {
  margin: 0;
  white-space: pre-wrap;
}

.knowledge-message.is-user {
  align-self: flex-end;
  padding: 0.75rem 0.9rem;
  border: 1px solid color-mix(in srgb, var(--color-primary) 13%, var(--color-border));
  color: var(--color-text);
  background: color-mix(in srgb, var(--color-primary) 8%, var(--color-surface-strong));
}

/* 回答横跨整栏：长答案里的表格 min-width 560px，78% 的窄栏放不下会横向溢出。 */
.knowledge-message.is-assistant {
  align-self: stretch;
  width: 100%;
  max-width: 100%;
  padding-bottom: 0.85rem;
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 58%, transparent);
  color: var(--color-text);
  background: transparent;
}

/* codex 那种小按钮：视觉 28px，触控热区靠 ::after 放大，不再是悬在气泡外的 44px 圆钮。 */
.knowledge-message__actions {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 0.75rem;
}

.knowledge-message__action {
  position: relative;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 6px;
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
  opacity: 0.7;
  transition: opacity 140ms ease, color 140ms ease, background 140ms ease;
}

.knowledge-message__action:hover,
.knowledge-message__action:focus-visible {
  opacity: 1;
  color: var(--color-text);
  background: color-mix(in srgb, var(--color-text) 8%, transparent);
}

.knowledge-message__action:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--color-primary) 55%, transparent);
  outline-offset: 1px;
}

.knowledge-message__action.is-danger:hover,
.knowledge-message__action.is-danger:focus-visible {
  color: var(--color-danger, #b42318);
  background: color-mix(in srgb, var(--color-danger, #b42318) 10%, transparent);
}

.knowledge-message__action.is-copied {
  color: var(--el-color-success);
  opacity: 1;
}

.knowledge-message__markdown :deep(.analysis-result__markdown) {
  display: grid;
  gap: 0.65rem;
}

.knowledge-message__markdown {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
  font-size: 1rem;
  line-height: 1.65;
}

.knowledge-message__markdown :deep(h1),
.knowledge-message__markdown :deep(h2),
.knowledge-message__markdown :deep(h3),
.knowledge-message__markdown :deep(h4),
.knowledge-message__markdown :deep(h5),
.knowledge-message__markdown :deep(h6) {
  margin: 0.7rem 0 0.3rem;
  color: var(--color-text);
  line-height: 1.35;
}

.knowledge-message__markdown :deep(h1) {
  font-size: 1.45rem;
}

.knowledge-message__markdown :deep(h2) {
  font-size: 1.25rem;
}

.knowledge-message__markdown :deep(h3) {
  font-size: 1.1rem;
}

.knowledge-message__markdown :deep(h4),
.knowledge-message__markdown :deep(h5),
.knowledge-message__markdown :deep(h6) {
  font-size: 1rem;
}

.knowledge-message__markdown :deep(.analysis-result__markdown > :first-child) {
  margin-top: 0;
}

.knowledge-message__markdown :deep(.analysis-result__markdown > :last-child) {
  margin-bottom: 0;
}

.knowledge-message__markdown :deep(p),
.knowledge-message__markdown :deep(li),
.knowledge-message__markdown :deep(blockquote) {
  color: color-mix(in srgb, var(--color-text) 94%, transparent);
}

.knowledge-message__markdown :deep(ul),
.knowledge-message__markdown :deep(ol) {
  margin: 0.35rem 0;
}

.knowledge-message__markdown :deep(blockquote) {
  margin: 0.65rem 0;
  padding: 0.55rem 0.75rem;
  border-left: 3px solid color-mix(in srgb, var(--color-accent) 72%, transparent);
  background: color-mix(in srgb, var(--color-glass) 72%, transparent);
}

.knowledge-message__markdown :deep(pre) {
  max-width: 100%;
  overflow: auto;
  padding: 0.75rem;
  border: 1px solid color-mix(in srgb, var(--color-border-strong) 85%, transparent);
  border-radius: 6px;
  background: color-mix(in srgb, var(--color-surface-strong) 86%, var(--color-bg-secondary));
}

.knowledge-message__markdown :deep(pre code) {
  padding: 0;
  background: transparent;
}

.knowledge-message__markdown :deep(.analysis-result__table-scroll) {
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  overscroll-behavior-inline: contain;
}

.knowledge-message__markdown :deep(.analysis-result__table-scroll:focus-visible) {
  outline: 2px solid color-mix(in srgb, var(--color-primary) 55%, transparent);
  outline-offset: 2px;
}

.knowledge-message__markdown :deep(table) {
  width: 100%;
  min-width: 560px;
  border-collapse: collapse;
  font-size: 0.86rem;
}

.knowledge-message__markdown :deep(th),
.knowledge-message__markdown :deep(td) {
  padding: 0.55rem 0.65rem;
  border-right: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
  text-align: left;
  vertical-align: top;
}

.knowledge-message__markdown :deep(th) {
  color: var(--color-text);
  background: color-mix(in srgb, var(--color-surface) 88%, var(--color-border));
  font-weight: 650;
}

.knowledge-message__markdown :deep(tr:last-child td) {
  border-bottom: 0;
}

.knowledge-message__markdown :deep(th:last-child),
.knowledge-message__markdown :deep(td:last-child) {
  border-right: 0;
}

.knowledge-message__process {
  display: grid;
  gap: 0.4rem;
}

.knowledge-message__process-toggle {
  width: fit-content;
  max-width: 100%;
  min-height: 30px;
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem;
  padding: 0;
  border: 0;
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
  font-size: 0.8rem;
  text-align: left;
}

.knowledge-message__process-toggle > span {
  color: var(--color-text-muted);
  font-weight: 650;
}

.knowledge-message__process-toggle > span.is-processing {
  color: var(--el-color-primary);
}

.knowledge-message__process-toggle > span.is-failed {
  color: var(--el-color-danger);
}

.knowledge-message__process-duration {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.knowledge-message__process-toggle .el-icon {
  transition: transform 140ms ease;
}

.knowledge-message__process-toggle .el-icon.is-expanded {
  transform: rotate(180deg);
}

.knowledge-message__process-toggle:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--color-primary) 55%, transparent);
  outline-offset: 3px;
}

.knowledge-message__process-detail {
  display: grid;
  gap: 0.45rem;
  padding-left: 0.75rem;
  border-left: 2px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 0.78rem;
}

.knowledge-message__process-detail ol {
  display: grid;
  gap: 0.3rem;
  padding: 0;
  margin: 0;
  list-style: none;
}

.knowledge-message__process-detail li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
}

.knowledge-message__process-detail li.is-failed {
  color: var(--el-color-danger);
}

.knowledge-message__process-detail li.is-running,
.knowledge-message__process-current {
  color: var(--el-color-primary);
}

.knowledge-message__process-detail p,
.knowledge-message__process-detail small {
  margin: 0;
}

.knowledge-message__model-count {
  color: var(--color-text-muted);
}

.knowledge-message__prompt-cache {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

/* 跟页脚"降级回答"徽标共用 warning 语义色，让两处指向同一件事。 */
.knowledge-message__process-degradation {
  color: var(--el-color-warning-dark-2);
}

.knowledge-message__process-compaction {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.knowledge-message__process-summaries {
  display: grid;
  gap: 0.3rem;
  padding: 0;
  margin: 0;
}

.knowledge-message__process-summaries > div {
  display: grid;
  grid-template-columns: 5.5rem minmax(0, 1fr);
  gap: 0.45rem;
  align-items: baseline;
}

.knowledge-message__process-summaries dt {
  color: var(--color-text);
  font-weight: 600;
}

.knowledge-message__process-summaries dd {
  min-width: 0;
  margin: 0;
}

.knowledge-message__model-calls {
  display: grid;
  gap: 0.35rem;
}

.knowledge-message__model-calls > strong {
  color: var(--color-text);
  font-size: 0.78rem;
}

.knowledge-message__model-call {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 0.35rem 1rem;
}

.knowledge-message__model-call-identity,
.knowledge-message__model-call-metrics {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.25rem 0.45rem;
}

.knowledge-message__model-call-summary {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem 0.6rem;
  color: var(--color-text-muted);
}

/* 重试轨迹说明确实有一次失败，用既有的 danger token 才能跟暗色主题一起走。 */
.knowledge-message__model-call-failover {
  grid-column: 1 / -1;
  color: var(--color-danger);
}

.knowledge-message__model-call-identity b {
  color: var(--color-text);
  font-weight: 600;
}

.knowledge-message__model-call-identity small {
  overflow-wrap: anywhere;
}

/* 模型被静默换掉是要人去查配置的事，用 warning token 让它跟普通模型名分开。 */
.knowledge-message__model-call-identity small.is-substituted {
  color: var(--el-color-warning);
}

.knowledge-message__model-call-metrics {
  justify-content: flex-end;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.knowledge-message__model-call-metrics .is-succeeded {
  color: var(--el-color-success);
}

.knowledge-message__model-call-metrics .is-failed {
  color: var(--el-color-danger);
}

.knowledge-message__markdown :deep(p),
.knowledge-message__markdown :deep(ul),
.knowledge-message__markdown :deep(ol),
.knowledge-message__markdown :deep(blockquote) {
  margin: 0;
}

.knowledge-message__markdown :deep(ul),
.knowledge-message__markdown :deep(ol) {
  padding-left: 1.2rem;
}

.knowledge-message__markdown :deep(code) {
  padding: 0.1rem 0.25rem;
  border-radius: 4px;
  background: color-mix(in srgb, var(--color-primary) 10%, var(--color-surface));
}

.knowledge-message__meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  min-height: 28px;
}

.knowledge-message.is-user .knowledge-message__meta {
  justify-content: flex-end;
}

.knowledge-message__sources-toggle {
  min-height: 28px;
  padding: 0 0.55rem;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-text-muted);
  background: var(--color-surface);
  cursor: pointer;
  font-size: 0.78rem;
}

.knowledge-message__status {
  color: var(--color-text-muted);
  font-size: 0.78rem;
}

.knowledge-message__degraded {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0 0.5rem;
  border: 1px solid color-mix(in srgb, var(--el-color-warning) 42%, var(--color-border));
  border-radius: 999px;
  color: var(--el-color-warning-dark-2);
  background: color-mix(in srgb, var(--el-color-warning-light-9) 72%, var(--color-surface));
  font-size: 0.76rem;
  line-height: 1;
  white-space: nowrap;
}

.knowledge-message__degraded small {
  color: inherit;
  font-size: 0.72rem;
}

.knowledge-message__badge {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  padding: 0 0.5rem;
  border: 1px solid color-mix(in srgb, var(--color-primary) 22%, var(--color-border));
  border-radius: 999px;
  color: color-mix(in srgb, var(--color-primary) 78%, var(--color-text));
  background: color-mix(in srgb, var(--color-primary) 8%, var(--color-surface));
  font-size: 0.76rem;
  line-height: 1;
  white-space: nowrap;
}

.knowledge-message__sources {
  max-width: 640px;
  max-height: 180px;
  overflow: auto;
  display: grid;
  gap: 0.45rem;
  padding: 0.65rem 0.75rem;
  margin: 0;
  list-style: none;
  border-left: 2px solid var(--color-border);
  color: var(--color-text-muted);
  background: color-mix(in srgb, var(--color-surface) 76%, transparent);
}

.knowledge-message__sources li {
  display: grid;
  gap: 0.15rem;
}

.knowledge-message__rank-source {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 0.5rem;
}

.knowledge-message__rank-source span {
  overflow: hidden;
  color: var(--color-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-message__rank-source small {
  color: var(--color-text-muted);
  font-size: 0.76rem;
}

.knowledge-message__sources strong {
  color: var(--color-text);
  font-size: 0.82rem;
}

.knowledge-message__sources span,
.knowledge-message__sources p {
  margin: 0;
  font-size: 0.8rem;
  line-height: 1.5;
}

@media (max-width: 768px) {
  .knowledge-message {
    max-width: 88%;
    padding: 0.65rem 0.8rem;
    font-size: 0.95rem;
    line-height: 1.65;
  }

  .knowledge-message__actions {
    gap: 0.9rem;
  }

  .knowledge-message__action {
    width: 32px;
    height: 32px;
    opacity: 1;
  }

  /* 热区左右只放 4px：相邻两键间距 0.9rem，扩完仍留 6px 空档，点不串。 */
  .knowledge-message__action::after {
    content: '';
    position: absolute;
    inset: -6px -4px;
  }

  .knowledge-message__process-toggle {
    min-height: 44px;
  }

  .knowledge-message.is-assistant {
    width: 100%;
    max-width: 100%;
    padding-top: 0.2rem;
    padding-left: 0;
    padding-right: 0;
    padding-bottom: 0.65rem;
    border-bottom: 1px solid color-mix(in srgb, var(--color-border) 64%, transparent);
  }

  .knowledge-message__markdown :deep(.analysis-result__markdown) {
    gap: 0.55rem;
  }

  .knowledge-message__markdown :deep(h1) {
    font-size: 1.3rem;
    line-height: 1.35;
  }

  .knowledge-message__markdown :deep(h2) {
    font-size: 1.15rem;
    line-height: 1.4;
  }

  .knowledge-message__markdown :deep(h3) {
    font-size: 1.02rem;
    line-height: 1.45;
  }

  .knowledge-message__markdown :deep(p),
  .knowledge-message__markdown :deep(li) {
    line-height: 1.7;
  }

  .knowledge-message__model-call {
    grid-template-columns: minmax(0, 1fr);
  }

  .knowledge-message__model-call-metrics {
    justify-content: flex-start;
    text-align: left;
  }
}
</style>
