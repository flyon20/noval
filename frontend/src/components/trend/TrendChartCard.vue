<script setup lang="ts">
import { computed } from 'vue';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { use } from 'echarts/core';
import VChart from 'vue-echarts';

use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

const props = withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
    option?: Record<string, unknown>;
    height?: number;
  }>(),
  {
    subtitle: '',
    option: () => ({}),
    height: 260,
  },
);

const hasSeries = computed(() => {
  const series = (props.option as { series?: unknown }).series;
  return Array.isArray(series) && series.length > 0;
});

function canUseCanvas() {
  if (typeof window === 'undefined' || typeof ResizeObserver === 'undefined') {
    return false;
  }

  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext?.('2d'));
  } catch {
    return false;
  }
}

const shouldRenderChart = computed(() => hasSeries.value && canUseCanvas());
</script>

<template>
  <article class="trend-chart-card">
    <header class="trend-chart-card__header">
      <div>
        <h3 class="trend-chart-card__title">{{ title }}</h3>
        <p v-if="subtitle" class="trend-chart-card__subtitle">{{ subtitle }}</p>
      </div>
      <slot name="badge" />
    </header>

    <div class="trend-chart-card__body">
      <VChart
        v-if="shouldRenderChart"
        class="trend-chart-card__chart"
        :option="option"
        :autoresize="true"
        :style="{ height: `${height}px` }"
      />
      <div v-else-if="hasSeries" class="trend-chart-card__fallback">
        <span>当前环境不支持图表渲染</span>
      </div>
      <div v-else class="trend-chart-card__empty">
        暂无图表数据
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
.trend-chart-card {
  display: grid;
  gap: 0.9rem;
  padding: 1rem 1rem 0.75rem;
  border-radius: 1.25rem;
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
}

.trend-chart-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.trend-chart-card__title,
.trend-chart-card__subtitle {
  margin: 0;
}

.trend-chart-card__title {
  font-size: 1rem;
}

.trend-chart-card__subtitle {
  margin-top: 0.3rem;
  color: var(--color-text-muted);
  line-height: 1.6;
  font-size: 0.88rem;
}

.trend-chart-card__body {
  min-height: 220px;
}

.trend-chart-card__chart {
  width: 100%;
}

.trend-chart-card__fallback,
.trend-chart-card__empty {
  min-height: 220px;
  display: grid;
  place-items: center;
  padding: 1rem;
  border-radius: 1rem;
  color: var(--color-text-muted);
  background: rgba(35, 65, 58, 0.05);
  text-align: center;
}
</style>
