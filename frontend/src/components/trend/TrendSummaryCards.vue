<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  sourceSnapshotCount?: number;
  historyAnalysisCount?: number;
  coveredCategoryCount?: number;
  latestSnapshotTime?: string | null;
  currentCategoryLabel?: string;
  summary?: string | null;
  phaseLabel?: string;
}>();

const stats = computed(() => [
  {
    label: '来源快照数',
    value: String(props.sourceSnapshotCount ?? 0),
    note: '用于本次趋势判断的快照样本数量',
    dataTest: 'trend-summary-snapshot-count',
  },
  {
    label: '历史分析数',
    value: String(props.historyAnalysisCount ?? 0),
    note: '当前可视化结果中累计可用的分析记录数',
  },
  {
    label: '覆盖分类数',
    value: String(props.coveredCategoryCount ?? 0),
    note: '已抓到并参与趋势统计的榜单分类数',
  },
  {
    label: '最近快照',
    value: props.latestSnapshotTime || '--',
    note: '最新一次成功抓取到的榜单快照时间',
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
        <p class="trend-summary__label">趋势摘要</p>
        <div class="trend-summary__badges">
          <span class="trend-summary__badge">{{ currentCategoryLabel || '全部榜单' }}</span>
          <span class="trend-summary__badge trend-summary__badge--soft">{{ phaseLabel || '待命' }}</span>
        </div>
      </div>
      <p class="trend-summary__copy">
        {{ summary || '当前还没有可展示的趋势摘要，等分析完成或可视化数据返回后会自动补齐。' }}
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
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
}

.trend-summary__card--wide {
  grid-column: 1 / -1;
  background:
    linear-gradient(135deg, rgba(255, 249, 240, 0.95), rgba(245, 248, 242, 0.9)),
    rgba(255, 255, 255, 0.92);
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
  font-size: clamp(1.45rem, 2.8vw, 2.2rem);
  line-height: 1.2;
  overflow-wrap: anywhere;
  word-break: break-word;
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
  background: rgba(185, 104, 31, 0.12);
  color: var(--color-text);
  font-size: 0.82rem;
}

.trend-summary__badge--soft {
  background: rgba(35, 65, 58, 0.08);
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
