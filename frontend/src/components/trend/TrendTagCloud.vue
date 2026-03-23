<script setup lang="ts">
import { computed } from 'vue';
import type { ThemeWordCloudItem } from '@/types/data';

const props = defineProps<{
  items: ThemeWordCloudItem[];
}>();

const normalizedItems = computed(() => {
  const max = Math.max(...props.items.map((item) => item.value), 1);

  return props.items.map((item) => ({
    ...item,
    fontSize: 0.95 + (item.value / max) * 0.85,
  }));
});
</script>

<template>
  <article class="trend-tag-cloud" data-test="trend-tag-cloud">
    <header class="trend-tag-cloud__header">
      <h3>趋势标签云</h3>
      <p>优先展示接口返回的主题词；如果接口暂时没有词云，就自动用现有分析类型和榜单覆盖数据补齐。</p>
    </header>

    <div v-if="normalizedItems.length" class="trend-tag-cloud__body">
      <span
        v-for="item in normalizedItems"
        :key="item.name"
        class="trend-tag-cloud__tag"
        :style="{ fontSize: `${item.fontSize}rem` }"
      >
        {{ item.name }}
      </span>
    </div>
    <p v-else class="trend-tag-cloud__empty">暂无可用标签，将在积累更多趋势样本后展示。</p>
  </article>
</template>

<style scoped lang="scss">
.trend-tag-cloud {
  display: grid;
  gap: 0.9rem;
  padding: 1rem;
  border-radius: 1.25rem;
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
}

.trend-tag-cloud__header {
  display: grid;
  gap: 0.25rem;
}

.trend-tag-cloud__header h3,
.trend-tag-cloud__header p,
.trend-tag-cloud__empty {
  margin: 0;
}

.trend-tag-cloud__header p,
.trend-tag-cloud__empty {
  color: var(--color-text-muted);
  line-height: 1.6;
}

.trend-tag-cloud__body {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.trend-tag-cloud__tag {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  padding: 0.65rem 0.95rem;
  border-radius: 999px;
  background: rgba(35, 65, 58, 0.08);
  color: var(--color-text);
  line-height: 1;
}
</style>
