<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { analysisApi } from '@/api/analysis';
import { dataApi } from '@/api/data';
import AnalysisResultCard from '@/components/analysis/AnalysisResultCard.vue';
import AnalysisToolbar from '@/components/analysis/AnalysisToolbar.vue';
import TrendChartCard from '@/components/trend/TrendChartCard.vue';
import TrendComparisonList from '@/components/trend/TrendComparisonList.vue';
import TrendContextBar from '@/components/trend/TrendContextBar.vue';
import TrendSnapshotTable from '@/components/trend/TrendSnapshotTable.vue';
import TrendSummaryCards from '@/components/trend/TrendSummaryCards.vue';
import TrendTagCloud from '@/components/trend/TrendTagCloud.vue';
import { useTrendRun } from '@/composables/useTrendRun';
import type { VisualData } from '@/types/data';

const CATEGORY_OPTIONS = [
  { label: '男频热门 A', value: 'male-hot-a' },
  { label: '男频热门 B', value: 'male-hot-b' },
  { label: '男频新书 A', value: 'male-new-a' },
] as const;

const visualData = ref<VisualData | null>(null);
const visualLoading = ref(false);
const visualError = ref('');

const trend = useTrendRun({
  runTrend(payload, callbacks) {
    return analysisApi.streamTrend(payload, callbacks);
  },
  async copyText(text) {
    if (!navigator.clipboard?.writeText) {
      throw new Error('当前浏览器不支持剪贴板复制');
    }

    await navigator.clipboard.writeText(text);
  },
});

const isRunning = computed(() =>
  ['preparing', 'streaming', 'fallback-blocking'].includes(trend.state.phase),
);

const pieOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['42%', '72%'],
      data: visualData.value?.analysisTypeDistribution ?? [],
    },
  ],
}));

const lineOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 24, right: 16, top: 24, bottom: 24, containLabel: true },
  xAxis: {
    type: 'category',
    data: visualData.value?.analysisDailyTrend.map((item) => item.date) ?? [],
    boundaryGap: false,
  },
  yAxis: { type: 'value' },
  series: [
    {
      type: 'line',
      smooth: true,
      areaStyle: {},
      data: visualData.value?.analysisDailyTrend.map((item) => item.value) ?? [],
    },
  ],
}));

const barOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 24, right: 16, top: 24, bottom: 24, containLabel: true },
  xAxis: {
    type: 'category',
    data: visualData.value?.rankCategoryDistribution.map((item) => item.name) ?? [],
  },
  yAxis: { type: 'value' },
  series: [
    {
      type: 'bar',
      barMaxWidth: 32,
      data: visualData.value?.rankCategoryDistribution.map((item) => item.value) ?? [],
    },
  ],
}));

async function loadVisualData() {
  visualLoading.value = true;
  visualError.value = '';

  try {
    const response = await dataApi.getVisual('fanqie');
    visualData.value = response.data.data;
  } catch (error) {
    visualError.value = error instanceof Error ? error.message : '可视化数据加载失败';
  } finally {
    visualLoading.value = false;
  }
}

async function initializePage() {
  await Promise.allSettled([loadVisualData(), trend.runTrend(trend.state.category)]);
}

async function handleCategoryChange(category: string) {
  if (category === trend.state.category) {
    return;
  }

  await trend.runTrend(category).catch(() => undefined);
}

async function handleRerun() {
  await trend.rerunTrend().catch(() => undefined);
}

function handleStop() {
  trend.stopTrend();
}

async function handleCopy() {
  try {
    await trend.copyResult();
    ElMessage.success('趋势结果已复制');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '复制失败，请稍后重试');
  }
}

onMounted(() => {
  void initializePage();
});

onBeforeUnmount(() => {
  trend.stopTrend();
});
</script>

<template>
  <section class="trend-page">
    <TrendContextBar
      :active-category="trend.state.category"
      :categories="[...CATEGORY_OPTIONS]"
      :platform="trend.state.platform"
      :running="isRunning"
      @select="handleCategoryChange"
    />

    <div class="trend-page__hero">
      <section class="trend-page__result">
        <div class="trend-page__toolbar">
          <div class="trend-page__toolbar-copy">
            <p class="trend-page__toolbar-title">趋势页</p>
            <p class="trend-page__toolbar-subtitle">查看趋势结果与图表。</p>
          </div>
          <AnalysisToolbar
            :disabling="false"
            :running="isRunning"
            @copy="handleCopy"
            @rerun="handleRerun"
            @stop="handleStop"
          />
        </div>

        <div data-test="trend-result-panel">
          <AnalysisResultCard
            :error-message="trend.state.errorMessage"
            :phase="trend.state.phase"
            :result-content="trend.state.result?.resultContent"
            :result-meta="{
              traceId: trend.state.result?.traceId ?? trend.state.traceId,
              modelName: trend.state.result?.modelName,
            }"
            :streaming-text="trend.state.streamingText"
          />
        </div>
      </section>

      <aside class="trend-page__insights">
        <TrendSummaryCards
          :comparison-summary="visualData?.comparisonSummary"
          :phase="trend.state.phase"
          :source-snapshot-count="trend.state.result?.sourceSnapshotCount"
        />
        <TrendTagCloud :items="visualData?.wordCloud ?? []" />
        <TrendComparisonList
          :comparisons="visualData?.snapshotComparisons ?? []"
          :summary="visualData?.comparisonSummary"
          :themes="visualData?.themeTable ?? []"
        />
      </aside>
    </div>

    <section class="trend-page__visual" data-test="trend-visual-section">
      <header class="trend-page__visual-header">
        <div>
          <p class="trend-page__visual-eyebrow">Charts</p>
          <h3 class="trend-page__visual-title">趋势图表</h3>
        </div>
        <p class="trend-page__visual-summary">
          {{ visualData?.comparisonSummary || '图表区域会汇总最近快照的主题变化。' }}
        </p>
      </header>

      <TrendChartCard
        title="分析类型分布"
        subtitle="观察历史分析结果在不同分析类型间的分布。"
        :option="pieOption"
        :height="280"
      />
      <TrendChartCard
        title="分析日趋势"
        subtitle="近几天分析记录的数量变化。"
        :option="lineOption"
        :height="280"
      />
      <TrendChartCard
        title="榜单分类分布"
        subtitle="当前可视化样本覆盖到的榜单分类。"
        :option="barOption"
        :height="280"
      />
      <TrendSnapshotTable :snapshots="visualData?.latestSnapshots ?? []" />
    </section>

    <el-alert
      v-if="visualError"
      title="可视化数据加载失败"
      :description="visualError"
      type="warning"
      show-icon
      :closable="false"
    />
    <el-alert
      v-else-if="visualLoading && !visualData"
      title="可视化数据加载中"
      description="正在获取图表与快照数据。"
      type="info"
      show-icon
      :closable="false"
    />
  </section>
</template>

<style scoped lang="scss">
.trend-page {
  display: grid;
  gap: 1.25rem;
}

.trend-page__hero {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.65fr);
  gap: 1rem;
  align-items: start;
}

.trend-page__result,
.trend-page__insights {
  display: grid;
  gap: 1rem;
}

.trend-page__toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 1.1rem;
  border-radius: 1.25rem;
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
  flex-wrap: wrap;
}

.trend-page__toolbar-title,
.trend-page__toolbar-subtitle {
  margin: 0;
}

.trend-page__toolbar-title {
  font-size: 1rem;
  font-weight: 700;
}

.trend-page__toolbar-subtitle {
  margin-top: 0.25rem;
  color: var(--color-text-muted);
  line-height: 1.6;
}

.trend-page__visual {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

.trend-page__visual-header {
  display: grid;
  grid-column: 1 / -1;
  gap: 0.4rem;
  padding: 1rem 1.1rem;
  border-radius: 1.25rem;
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
}

.trend-page__visual-eyebrow,
.trend-page__visual-title,
.trend-page__visual-summary {
  margin: 0;
}

.trend-page__visual-eyebrow {
  color: var(--color-text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.trend-page__visual-title {
  font-size: 1rem;
}

.trend-page__visual-summary {
  color: var(--color-text-muted);
  line-height: 1.7;
}

@media (max-width: 1100px) {
  .trend-page__hero {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .trend-page__visual {
    grid-template-columns: 1fr;
  }
}
</style>
