<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { renderAnalysisMarkdown } from '@/lib/markdown';

const props = defineProps<{
  phase: 'idle' | 'preparing' | 'streaming' | 'fallback-blocking' | 'done' | 'error' | 'aborted';
  streamingText?: string;
  resultContent?: string;
  resultMeta?: {
    traceId?: string;
    modelName?: string;
    tokenUsed?: number;
    analysisModeLabel?: string;
    analysisDetailLabel?: string;
  };
  errorMessage?: string;
}>();

const contentRef = ref<HTMLElement | null>(null);
const cursorVisible = computed(() => props.phase === 'streaming');
const finalHtml = computed(() => (props.resultContent ? renderAnalysisMarkdown(props.resultContent) : ''));
const showError = computed(() => props.phase === 'error' && props.errorMessage);
const sanitizedPartialText = computed(() => (
  (props.streamingText ?? '')
    .replace(/\[analysis[- ]progress\][^\n\r]*/giu, ' ')
    .replace(/\n{3,}/gu, '\n\n')
    .trim()
));
const partialVisible = computed(() => Boolean(sanitizedPartialText.value) && (props.phase === 'error' || props.phase === 'aborted'));
const displayText = computed(() => {
  if (props.phase === 'done' && props.resultContent) {
    return props.resultContent;
  }
  return props.streamingText ?? '';
});

watch(
  () => [props.phase, props.streamingText, props.resultContent],
  async () => {
    await nextTick();

    if (contentRef.value) {
      contentRef.value.scrollTop = contentRef.value.scrollHeight;
    }
  },
  { flush: 'post' },
);
</script>

<template>
  <div class="analysis-result-card" :data-phase="props.phase" data-test="analysis-result-card">
    <div v-if="props.phase === 'preparing' || props.phase === 'streaming'" class="analysis-result__status">
      <span>正在分析中...</span>
    </div>

    <template v-if="showError">
      <div class="analysis-result__error">
        <p>分析失败：{{ props.errorMessage }}</p>
        <p v-if="props.resultMeta?.traceId" class="analysis-result__trace">traceId: {{ props.resultMeta.traceId }}</p>
      </div>
    </template>
    <template v-else-if="props.phase === 'streaming'">
      <div ref="contentRef" class="analysis-result__stream">
        <p>{{ displayText }}</p>
        <span v-if="cursorVisible" class="analysis-result__cursor"></span>
      </div>
    </template>
    <template v-else-if="props.phase === 'done'">
      <div ref="contentRef" class="analysis-result__done" v-html="finalHtml" />
      <div class="analysis-result__meta">
        <span v-if="props.resultMeta?.analysisModeLabel">{{ props.resultMeta.analysisModeLabel }}</span>
        <span v-if="props.resultMeta?.analysisDetailLabel">{{ props.resultMeta.analysisDetailLabel }}</span>
        <span v-if="props.resultMeta?.modelName">模型：{{ props.resultMeta.modelName }}</span>
        <span v-if="props.resultMeta?.tokenUsed !== undefined">总 Token：{{ props.resultMeta.tokenUsed }}</span>
        <span v-if="props.resultMeta?.traceId" class="analysis-result__trace">traceId: {{ props.resultMeta.traceId }}</span>
      </div>
    </template>
    <template v-else-if="props.phase === 'fallback-blocking'">
      <div class="analysis-result__status">流式不可用，已回退到阻塞接口，请稍候。</div>
    </template>
    <template v-else-if="props.phase === 'aborted'">
      <div class="analysis-result__status">本次生成已停止，你可以重新发起分析。</div>
    </template>
    <template v-else>
      <div class="analysis-result__empty">还没有开始分析，选择模式后会自动发起请求。</div>
    </template>

    <div v-if="partialVisible" ref="contentRef" class="analysis-result__partial">
      <p class="analysis-result__partial-title">已保留的片段</p>
      <p>{{ sanitizedPartialText }}</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.analysis-result-card {
  border: 1px solid var(--color-border);
  border-radius: 1.25rem;
  padding: 1.25rem;
  background:
    linear-gradient(160deg, rgba(255, 255, 255, 0.28), rgba(255, 255, 255, 0.12)),
    color-mix(in srgb, var(--color-surface) 92%, transparent);
  backdrop-filter: blur(18px) saturate(1.18);
  -webkit-backdrop-filter: blur(18px) saturate(1.18);
  min-height: 240px;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  transition: box-shadow 0.2s ease, border-color 0.2s ease, background 0.2s ease;
  box-shadow: var(--shadow-card);
}

.analysis-result-card:hover {
  box-shadow: var(--shadow-glow);
  border-color: color-mix(in srgb, var(--color-accent) 26%, var(--color-border));
}

.analysis-result__status {
  font-size: 0.95rem;
  color: var(--color-text-muted);
}

.analysis-result__stream {
  overflow: auto;
  font-size: 1rem;
  line-height: 1.7;
  white-space: pre-line;
  font-family: var(--font-body);
  color: var(--color-text);
}

.analysis-result__cursor {
  display: inline-block;
  width: 1px;
  height: 1.2em;
  background: var(--color-primary);
  animation: blink 1s step-end infinite;
  margin-left: 0.1rem;
}

.analysis-result__done {
  overflow: auto;
  line-height: 1.8;
  color: var(--color-text);
}

.analysis-result__meta {
  display: flex;
  gap: 1rem;
  font-size: 0.85rem;
  color: var(--color-text-muted);
  flex-wrap: wrap;
}

.analysis-result__trace {
  color: var(--color-danger);
}

.analysis-result__error {
  color: var(--color-danger);
  font-weight: 500;
}

.analysis-result__partial {
  padding-top: 0.75rem;
  border-top: 1px dashed var(--color-border);
  color: var(--color-text-muted);
  line-height: 1.7;
  white-space: pre-line;
}

.analysis-result__partial-title {
  margin: 0 0 0.35rem;
  color: var(--color-text);
  font-weight: 600;
}

.analysis-result__empty {
  color: var(--color-text-muted);
}

.analysis-result__done :deep(.analysis-result__markdown) {
  color: var(--color-text);
}

.analysis-result__done :deep(.analysis-result__markdown > *:first-child) {
  margin-top: 0;
}

.analysis-result__done :deep(.analysis-result__markdown > *:last-child) {
  margin-bottom: 0;
}

.analysis-result__done :deep(.analysis-result__markdown h1),
.analysis-result__done :deep(.analysis-result__markdown h2),
.analysis-result__done :deep(.analysis-result__markdown h3),
.analysis-result__done :deep(.analysis-result__markdown h4) {
  color: var(--color-text);
  line-height: 1.35;
}

.analysis-result__done :deep(.analysis-result__markdown p),
.analysis-result__done :deep(.analysis-result__markdown li),
.analysis-result__done :deep(.analysis-result__markdown blockquote) {
  color: color-mix(in srgb, var(--color-text) 94%, transparent);
}

.analysis-result__done :deep(.analysis-result__markdown ul),
.analysis-result__done :deep(.analysis-result__markdown ol) {
  padding-left: 1.2rem;
}

.analysis-result__done :deep(.analysis-result__markdown blockquote) {
  margin: 1rem 0;
  padding: 0.85rem 1rem;
  border-left: 3px solid color-mix(in srgb, var(--color-accent) 72%, transparent);
  border-radius: 0.9rem;
  background: color-mix(in srgb, var(--color-glass) 72%, transparent);
}

.analysis-result__done :deep(.analysis-result__markdown code) {
  padding: 0.16rem 0.42rem;
  border-radius: 0.5rem;
  background: color-mix(in srgb, var(--color-primary-soft) 88%, transparent);
  color: var(--color-text);
}

.analysis-result__done :deep(.analysis-result__markdown pre) {
  overflow: auto;
  padding: 1rem;
  border-radius: 1rem;
  border: 1px solid color-mix(in srgb, var(--color-border-strong) 85%, transparent);
  background: color-mix(in srgb, var(--color-surface-strong) 86%, var(--color-bg-secondary));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.analysis-result__done :deep(.analysis-result__markdown pre code) {
  padding: 0;
  background: transparent;
}

@media (max-width: 768px) {
  .analysis-result-card {
    font-size: 0.9rem;
  }

  .analysis-result__stream {
    max-height: 52vh;
    overflow-y: auto;
    font-size: 0.875rem;
    line-height: 1.65;
  }

  .analysis-result__done {
    max-height: 55vh;
    overflow-y: auto;
    font-size: 0.875rem;
    line-height: 1.7;
  }

  .analysis-result__meta {
    font-size: 0.78rem;
    gap: 0.5rem;
  }
}

@keyframes blink {
  0%,
  50% {
    opacity: 1;
  }
  50%,
  100% {
    opacity: 0;
  }
}
</style>
