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
      <h3>主题标签云</h3>
      <p>按热度映射字号，快速观察当前主题重心。</p>
    </header>

    <div class="trend-tag-cloud__body">
      <span
        v-for="item in normalizedItems"
        :key="item.name"
        class="trend-tag-cloud__tag"
        :style="{ fontSize: `${item.fontSize}rem` }"
      >
        {{ item.name }}
      </span>
    </div>
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
.trend-tag-cloud__header p {
  margin: 0;
}

.trend-tag-cloud__header p {
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
