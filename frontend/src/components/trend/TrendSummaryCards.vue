<script setup lang="ts">
defineProps<{
  sourceSnapshotCount?: number;
  comparisonSummary?: string | null;
  phase: 'idle' | 'preparing' | 'streaming' | 'fallback-blocking' | 'done' | 'error' | 'aborted';
}>();
</script>

<template>
  <section class="trend-summary">
    <article class="trend-summary__card">
      <p class="trend-summary__label">来源快照数</p>
      <strong class="trend-summary__value" data-test="trend-summary-snapshot-count">
        {{ sourceSnapshotCount ?? 0 }}
      </strong>
      <p class="trend-summary__meta">用于本次趋势判断的榜单快照样本数。</p>
    </article>

    <article class="trend-summary__card trend-summary__card--wide">
      <p class="trend-summary__label">趋势摘要</p>
      <p class="trend-summary__copy">
        {{ comparisonSummary || '可视化数据加载后，这里会展示近几次快照的主题变化总结。' }}
      </p>
      <span class="trend-summary__badge">当前阶段：{{ phase }}</span>
    </article>
  </section>
</template>

<style scoped lang="scss">
.trend-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

.trend-summary__card {
  display: grid;
  gap: 0.5rem;
  padding: 1.1rem 1.15rem;
  border-radius: 1.2rem;
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
}

.trend-summary__card--wide {
  background:
    linear-gradient(135deg, rgba(255, 249, 240, 0.95), rgba(245, 248, 242, 0.9)),
    rgba(255, 255, 255, 0.92);
}

.trend-summary__label,
.trend-summary__meta,
.trend-summary__copy {
  margin: 0;
}

.trend-summary__label {
  color: var(--color-text-muted);
  font-size: 0.88rem;
}

.trend-summary__value {
  font-size: clamp(2rem, 4vw, 3.1rem);
  line-height: 1;
}

.trend-summary__meta,
.trend-summary__copy {
  color: var(--color-text-muted);
  line-height: 1.7;
}

.trend-summary__badge {
  justify-self: start;
  padding: 0.4rem 0.75rem;
  border-radius: 999px;
  background: rgba(35, 65, 58, 0.08);
  color: var(--color-text);
  font-size: 0.82rem;
}

@media (max-width: 760px) {
  .trend-summary {
    grid-template-columns: 1fr;
  }
}
</style>
