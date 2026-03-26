<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  sourceSnapshotCount?: number;
  historyAnalysisCount?: number;
  latestSnapshotTime?: string | null;
  boardName?: string | null;
  representativeBook?: string | null;
  summary?: string | null;
  phaseLabel?: string;
}>();

const stats = computed(() => [
  {
    label: '来源快照数',
    value: String(props.sourceSnapshotCount ?? 0),
    note: '快照样本',
    dataTest: 'trend-summary-snapshot-count',
  },
  {
    label: '历史分析数',
    value: String(props.historyAnalysisCount ?? 0),
    note: '结果条数',
  },
  {
    label: '最近快照',
    value: props.latestSnapshotTime || '--',
    note: '更新时间',
  },
  {
    label: '代表作品',
    value: props.representativeBook || props.boardName || '--',
    note: '当前代表',
  },
]);
</script>

<template>
  <section class="trend-summary">
    <article v-for="item in stats" :key="item.label" class="trend-summary__card">
      <p class="trend-summary__label">{{ item.label }}</p>
      <strong class="trend-summary__value" :data-test="item.dataTest">{{ item.value }}</strong>
      <p class="trend-summary__meta">{{ item.note }}</p>
    </article>

    <article class="trend-summary__card trend-summary__card--wide">
      <div class="trend-summary__summary-head">
        <p class="trend-summary__label">榜单摘要</p>
        <div class="trend-summary__badges">
          <span class="trend-summary__badge">{{ boardName || '未选择榜单' }}</span>
          <span class="trend-summary__badge trend-summary__badge--soft">{{ phaseLabel || '待命' }}</span>
        </div>
      </div>
      <p class="trend-summary__copy">
        {{ summary || '暂无摘要' }}
      </p>
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
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  background:
    linear-gradient(155deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-surface) 90%, transparent);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(1.1);
  -webkit-backdrop-filter: blur(18px) saturate(1.1);
}

.trend-summary__card--wide {
  grid-column: 1 / -1;
  background:
    radial-gradient(circle at top right, rgba(92, 124, 250, 0.14), transparent 26%),
    linear-gradient(135deg, color-mix(in srgb, var(--color-surface) 94%, transparent), color-mix(in srgb, var(--color-bg-secondary) 88%, transparent));
}

.trend-summary__summary-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.trend-summary__badges {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
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
  font-size: clamp(1.25rem, 2.6vw, 1.95rem);
  line-height: 1.35;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.trend-summary__meta,
.trend-summary__copy {
  color: var(--color-text-muted);
  line-height: 1.7;
}

.trend-summary__badge {
  padding: 0.4rem 0.75rem;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-accent) 18%, transparent);
  color: var(--color-text);
  font-size: 0.82rem;
}

.trend-summary__badge--soft {
  background: color-mix(in srgb, var(--color-glass) 72%, transparent);
}

@media (max-width: 760px) {
  .trend-summary {
    grid-template-columns: 1fr;
  }

  .trend-summary__card--wide {
    grid-column: auto;
  }
}
</style>
