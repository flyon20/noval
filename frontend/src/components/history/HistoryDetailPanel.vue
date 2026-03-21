<script setup lang="ts">
import { computed } from 'vue';
import { renderAnalysisMarkdown } from '@/lib/markdown';
import type { AnalysisHistoryItem } from '@/types/data';

const props = defineProps<{
  item: AnalysisHistoryItem | null;
}>();

const detailHtml = computed(() => (props.item ? renderAnalysisMarkdown(props.item.resultContent) : ''));
const hasResultJson = computed(() => Boolean(props.item && Object.keys(props.item.resultJson ?? {}).length > 0));
</script>

<template>
  <div class="history-detail" data-test="history-detail">
    <div v-if="!props.item" class="history-detail__empty">
      请选择一条历史记录查看详情。
    </div>
    <div v-else class="history-detail__content-wrap">
      <div class="history-detail__meta">
        <p><strong>{{ props.item.bookName ?? '未命名作品' }}</strong></p>
        <p>{{ props.item.analysisType }} · {{ props.item.chapterCount }} 章</p>
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
  border: 1px solid var(--color-border);
  border-radius: 1.25rem;
  padding: 1rem;
  background: rgba(255, 255, 255, 0.92);
  max-height: 100%;
  overflow: auto;
  box-shadow: var(--shadow-soft);
}

.history-detail__content-wrap {
  display: grid;
  gap: 1rem;
}

.history-detail__meta {
  display: grid;
  gap: 0.25rem;
}

.history-detail__meta p {
  margin: 0;
}

.history-detail__content {
  line-height: 1.75;
}

.history-detail__json {
  background: rgba(35, 65, 58, 0.06);
  border-radius: 0.75rem;
  padding: 0.75rem;
  overflow: auto;
  font-size: 0.85rem;
}

.history-detail__json h4 {
  margin: 0 0 0.5rem;
}

.history-detail__json pre {
  margin: 0;
}

.history-detail__empty {
  padding: 2rem;
  text-align: center;
  color: var(--color-text-muted);
}
</style>
