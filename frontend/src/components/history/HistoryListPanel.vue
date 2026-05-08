<script setup lang="ts">
import type { AnalysisHistoryItem } from '@/types/data';

const props = defineProps<{
  items: AnalysisHistoryItem[];
  loading?: boolean;
}>();

const emit = defineEmits<{
  select: [AnalysisHistoryItem];
}>();

function handleSelect(item: AnalysisHistoryItem) {
  emit('select', item);
}
</script>

<template>
  <section class="history-list" :data-loading="props.loading ? 'true' : 'false'">
    <div v-if="props.loading" class="history-list__loading">历史记录加载中...</div>

    <template v-else>
      <article v-for="item in props.items" :key="item.id" class="history-list__item">
        <button
          class="history-list__trigger"
          type="button"
          :data-test="`history-item-${item.id}`"
          @click="handleSelect(item)"
        >
          <div class="history-list__header">
            <strong>{{ item.bookName ?? '未命名作品' }}</strong>
            <span>{{ item.analysisType }}</span>
          </div>
          <p class="history-list__summary">模型：{{ item.modelName }} · 章节：{{ item.chapterCount }}</p>
          <p class="history-list__meta">{{ item.createdAt }}</p>
        </button>
      </article>

      <div v-if="!props.items.length" class="history-list__empty">暂无历史记录</div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.history-list {
  display: grid;
  gap: 0.8rem;
}

.history-list__item {
  border: 1px solid var(--color-border);
  border-radius: 1.1rem;
  background:
    linear-gradient(
      155deg,
      color-mix(in srgb, var(--color-surface-strong) 98%, transparent),
      color-mix(in srgb, var(--color-surface) 94%, transparent)
    );
  box-shadow: var(--shadow-soft);
  color: var(--color-text);
}

.history-list__trigger {
  width: 100%;
  min-height: 44px;
  display: grid;
  gap: 0.45rem;
  padding: 1rem;
  border: 0;
  border-radius: inherit;
  background: transparent;
  text-align: left;
  font: inherit;
  color: inherit;
  cursor: pointer;
}

.history-list__trigger:hover {
  background: color-mix(in srgb, var(--color-primary-soft) 76%, transparent);
}

.history-list__header {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.1rem;
  font-size: 1rem;
}

.history-list__summary,
.history-list__meta {
  margin: 0;
}

.history-list__summary,
.history-list__meta {
  color: var(--color-text-muted);
}

.history-list__loading,
.history-list__empty {
  padding: 2rem;
  text-align: center;
  color: var(--color-text-muted);
}
</style>
