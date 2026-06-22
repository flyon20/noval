<script setup lang="ts">
import { computed } from 'vue';
import { renderAnalysisMarkdown } from '@/lib/markdown';
import type { AnalysisHistoryDetail } from '@/types/data';

const props = defineProps<{
  item: AnalysisHistoryDetail | null;
  loading?: boolean;
  error?: string;
}>();

const detailHtml = computed(() => (props.item ? renderAnalysisMarkdown(props.item.resultContent) : ''));
const hasResultJson = computed(() => Boolean(props.item && Object.keys(props.item.resultJson ?? {}).length > 0));

function analysisTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    deconstruct: '拆文',
    structure: '结构',
    plot: '情节',
    theme: '趋势',
  };
  return value ? labels[value] ?? value : '-';
}
</script>

<template>
  <div class="history-detail" data-test="history-detail">
    <div v-if="props.loading" class="history-detail__empty">
      详情加载中...
    </div>
    <div v-else-if="props.error" class="history-detail__error">
      {{ props.error }}
    </div>
    <div v-else-if="!props.item" class="history-detail__empty">
      请选择一条历史记录查看详情。
    </div>
    <div v-else class="history-detail__content-wrap">
      <div class="history-detail__meta">
        <p><strong>{{ props.item.bookName ?? '未命名作品' }}</strong></p>
        <p>{{ analysisTypeLabel(props.item.analysisType) }} · {{ props.item.chapterCount }} 章</p>
        <p>模型：{{ props.item.modelName }}</p>
        <p>生成时间：{{ props.item.createdAt }}</p>
      </div>
      <div class="history-detail__content" v-html="detailHtml"></div>
      <div v-if="hasResultJson" class="history-detail__json">
        <h4>Result JSON</h4>
        <pre>{{ JSON.stringify(props.item?.resultJson, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.history-detail {
  min-width: 0;
  width: 100%;
  height: 100%;
  border: 1px solid var(--color-border);
  border-radius: 1rem;
  padding: 1rem;
  background:
    linear-gradient(
      160deg,
      color-mix(in srgb, var(--color-surface-strong) 98%, transparent),
      color-mix(in srgb, var(--color-surface) 94%, transparent)
    );
  overflow-y: auto;
  overflow-x: hidden;
  box-shadow: var(--shadow-soft);
  color: var(--color-text);
  -webkit-overflow-scrolling: touch;
}

.history-detail__content-wrap {
  min-width: 0;
  display: grid;
  gap: 1rem;
}

.history-detail__meta {
  min-width: 0;
  display: grid;
  gap: 0.25rem;
  border-bottom: 1px solid var(--color-border);
  padding-bottom: 0.75rem;
}

.history-detail__meta p {
  margin: 0;
  overflow-wrap: anywhere;
}

.history-detail__content {
  min-width: 0;
  max-width: 100%;
  line-height: 1.75;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.history-detail__content :deep(*) {
  max-width: 100%;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.history-detail__content :deep(pre),
.history-detail__content :deep(code) {
  white-space: pre-wrap;
}

.history-detail__content :deep(table) {
  display: block;
  width: 100%;
  overflow-x: auto;
}

.history-detail__json {
  min-width: 0;
  background: color-mix(in srgb, var(--color-primary-soft) 82%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  border-radius: 0.75rem;
  padding: 0.75rem;
  overflow: hidden;
  font-size: 0.85rem;
}

.history-detail__json h4 {
  margin: 0 0 0.5rem;
}

.history-detail__json pre {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.history-detail__empty {
  padding: 2rem;
  text-align: center;
  color: var(--color-text-muted);
}

.history-detail__error {
  padding: 1rem;
  border: 1px solid color-mix(in srgb, var(--color-danger) 32%, transparent);
  border-radius: 0.75rem;
  color: var(--color-danger);
  background: color-mix(in srgb, var(--color-danger) 8%, transparent);
}

@media (max-width: 960px) {
  .history-detail {
    border-radius: 0.85rem;
    padding: 0.85rem;
  }
}
</style>
