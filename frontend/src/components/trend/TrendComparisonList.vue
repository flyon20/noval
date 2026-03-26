<script setup lang="ts">
import type { InsightCard, SnapshotThemeComparison } from '@/types/data';

defineProps<{
  summary?: string | null;
  insightCards: InsightCard[];
  comparisons?: SnapshotThemeComparison[];
}>();
</script>

<template>
  <article class="trend-comparison-list" data-test="trend-comparison-list">
    <header class="trend-comparison-list__header">
      <h3>趋势洞察</h3>
      <p>{{ summary || '结构化洞察' }}</p>
    </header>

    <section class="trend-comparison-list__group">
      <div class="trend-comparison-list__group-head">
        <h4>洞察卡片</h4>
        <span>{{ insightCards.length }} 项</span>
      </div>
      <ul v-if="insightCards.length" class="trend-comparison-list__items">
        <li v-for="item in insightCards" :key="`${item.label}-${item.value}`">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <em>{{ item.note }}</em>
        </li>
      </ul>
      <p v-else class="trend-comparison-list__empty">暂无洞察</p>
    </section>

    <section class="trend-comparison-list__group">
      <div class="trend-comparison-list__group-head">
        <h4>历史快照对比</h4>
        <span>{{ comparisons?.length ?? 0 }} 条</span>
      </div>
      <ul v-if="comparisons?.length" class="trend-comparison-list__items">
        <li v-for="item in comparisons" :key="item.snapshotTime">
          <span>{{ item.snapshotTime }}</span>
          <strong>
            {{ item.topTheme }}
            <template v-if="typeof item.topThemeRatio === 'number'">
              · {{ item.topThemeRatio }}%
            </template>
          </strong>
          <em>
            {{ item.change }}
            <template v-if="item.leadBookName">
              · 代表书 {{ item.leadBookName }}
            </template>
          </em>
        </li>
      </ul>
      <p v-else class="trend-comparison-list__empty">暂无对比</p>
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

.trend-comparison-list__group-head {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.trend-comparison-list__header h3,
.trend-comparison-list__header p,
.trend-comparison-list__group h4,
.trend-comparison-list__empty {
  margin: 0;
}

.trend-comparison-list__header p,
.trend-comparison-list__empty {
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
  gap: 0.2rem;
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
  line-height: 1.65;
}
</style>
