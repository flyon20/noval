<script setup lang="ts">
import type { SnapshotThemeComparison, ThemeTableItem } from '@/types/data';

defineProps<{
  summary?: string | null;
  comparisons: SnapshotThemeComparison[];
  themes: ThemeTableItem[];
}>();
</script>

<template>
  <article class="trend-comparison-list">
    <header class="trend-comparison-list__header">
      <h3>趋势洞察</h3>
      <p>{{ summary || '暂无近几次快照对比总结。' }}</p>
    </header>

    <section class="trend-comparison-list__group">
      <h4>主题排行</h4>
      <ul class="trend-comparison-list__items">
        <li v-for="item in themes" :key="`${item.theme}-${item.trend}`">
          <span>{{ item.theme }}</span>
          <strong>{{ item.count }}</strong>
          <em>{{ item.trend }}</em>
        </li>
      </ul>
    </section>

    <section class="trend-comparison-list__group">
      <h4>快照对比</h4>
      <ul class="trend-comparison-list__items">
        <li v-for="item in comparisons" :key="item.snapshotTime">
          <span>{{ item.snapshotTime }}</span>
          <strong>{{ item.topTheme }}</strong>
          <em>{{ item.change }}</em>
        </li>
      </ul>
    </section>
  </article>
</template>

<style scoped lang="scss">
.trend-comparison-list {
  display: grid;
  gap: 1rem;
  padding: 1rem;
  border-radius: 1.25rem;
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
}

.trend-comparison-list__header,
.trend-comparison-list__group {
  display: grid;
  gap: 0.5rem;
}

.trend-comparison-list__header h3,
.trend-comparison-list__header p,
.trend-comparison-list__group h4 {
  margin: 0;
}

.trend-comparison-list__header p {
  color: var(--color-text-muted);
  line-height: 1.7;
}

.trend-comparison-list__items {
  display: grid;
  gap: 0.65rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.trend-comparison-list__items li {
  display: grid;
  gap: 0.15rem;
  padding: 0.85rem 0.95rem;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.05);
}

.trend-comparison-list__items span,
.trend-comparison-list__items em {
  color: var(--color-text-muted);
}

.trend-comparison-list__items em {
  font-style: normal;
}
</style>
