<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { analysisApi } from '@/api/analysis';
import { dataApi } from '@/api/data';
import AnalysisToolbar from '@/components/analysis/AnalysisToolbar.vue';
import TrendChartCard from '@/components/trend/TrendChartCard.vue';
import TrendComparisonList from '@/components/trend/TrendComparisonList.vue';
import TrendContextBar from '@/components/trend/TrendContextBar.vue';
import TrendResultPreview from '@/components/trend/TrendResultPreview.vue';
import TrendSnapshotTable from '@/components/trend/TrendSnapshotTable.vue';
import TrendSummaryCards from '@/components/trend/TrendSummaryCards.vue';
import TrendTagCloud from '@/components/trend/TrendTagCloud.vue';
import { useTrendRun } from '@/composables/useTrendRun';
import {
  buildFallbackTagCloud,
  buildPreviewText,
  extractTrendSummary,
  formatTrendRequestCategoryLabel,
  groupRankCategories,
  toAnalysisTypeRanking,
} from '@/lib/trend-display';
import type { VisualData } from '@/types/data';

const CATEGORY_OPTIONS = [
  { label: '男频热门 A', value: 'male-hot-a' },
  { label: '男频热门 B', value: 'male-hot-b' },
  { label: '男频新书 A', value: 'male-new-a' },
] as const;

const PHASE_LABELS = {
  idle: '待命',
  preparing: '准备中',
  streaming: '流式分析中',
  'fallback-blocking': '回退阻塞接口',
  done: '已完成',
  error: '分析失败',
  aborted: '已停止',
} as const;

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
const trendSummaryText = computed(() =>
  extractTrendSummary(trend.state.result?.resultJson, trend.state.result?.resultContent ?? trend.state.streamingText),
);
const analysisTypeRanking = computed(() => toAnalysisTypeRanking(visualData.value?.analysisTypeDistribution ?? []));
const categoryRanking = computed(() => groupRankCategories(visualData.value?.rankCategoryDistribution ?? []));
const analysisTypeChartData = computed(() =>
  analysisTypeRanking.value.map((item) => ({
    name: item.label,
    value: item.value,
  })),
);
const rankCategoryChartData = computed(() =>
  categoryRanking.value.map((item) => ({
    name: item.label,
    value: item.value,
  })),
);
const comparisonSummary = computed(
  () => visualData.value?.comparisonSummary?.trim() || trendSummaryText.value || '',
);
const summaryPreview = computed(() => buildPreviewText(comparisonSummary.value, 180));
const currentCategoryLabel = computed(() => formatTrendRequestCategoryLabel(trend.state.category));
const historyAnalysisCount = computed(() =>
  analysisTypeChartData.value.reduce((total, item) => total + item.value, 0),
);
const coveredCategoryCount = computed(() => categoryRanking.value.length);
const latestSnapshotTime = computed(() => {
  const snapshots = visualData.value?.latestSnapshots ?? [];

  return snapshots
    .map((item) => item.crawlTime)
    .filter(Boolean)
    .sort((left, right) => right.localeCompare(left))[0] ?? '';
});
const tagCloudItems = computed(() => {
  const items = visualData.value?.wordCloud ?? [];

  if (items.length) {
    return items;
  }

  return buildFallbackTagCloud(visualData.value?.analysisTypeDistribution ?? [], categoryRanking.value);
});
const phaseLabel = computed(() => PHASE_LABELS[trend.state.phase]);

const pieOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['42%', '72%'],
      data: analysisTypeChartData.value,
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
  grid: { left: 24, right: 16, top: 24, bottom: 48, containLabel: true },
  xAxis: {
    type: 'category',
    data: rankCategoryChartData.value.map((item) => item.name),
    axisLabel: {
      interval: 0,
      rotate: rankCategoryChartData.value.length > 3 ? 18 : 0,
    },
  },
  yAxis: { type: 'value' },
  series: [
    {
      type: 'bar',
      barMaxWidth: 32,
      data: rankCategoryChartData.value.map((item) => item.value),
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
            <p class="trend-page__toolbar-subtitle">先看 300 字预览，再按需展开完整趋势分析。</p>
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
          <TrendResultPreview
            :error-message="trend.state.errorMessage"
            :phase="trend.state.phase"
            :result-content="trend.state.result?.resultContent"
            :result-meta="{
              traceId: trend.state.result?.traceId ?? trend.state.traceId,
              modelName: trend.state.result?.modelName,
            }"
            :result-summary="trendSummaryText"
            :streaming-text="trend.state.streamingText"
          />
        </div>
      </section>

      <aside class="trend-page__insights">
        <TrendSummaryCards
          :covered-category-count="coveredCategoryCount"
          :current-category-label="currentCategoryLabel"
          :history-analysis-count="historyAnalysisCount"
          :latest-snapshot-time="latestSnapshotTime"
          :phase-label="phaseLabel"
          :source-snapshot-count="trend.state.result?.sourceSnapshotCount"
          :summary="summaryPreview"
        />
        <TrendTagCloud :items="tagCloudItems" />
        <TrendComparisonList
          :analysis-types="analysisTypeRanking"
          :categories="categoryRanking"
          :comparisons="visualData?.snapshotComparisons ?? []"
          :summary="summaryPreview"
        />
      </aside>
    </div>

    <section class="trend-page__visual" data-test="trend-visual-section">
      <header class="trend-page__visual-header">
        <div>
          <p class="trend-page__visual-eyebrow">趋势图谱</p>
          <h3 class="trend-page__visual-title">趋势图表</h3>
        </div>
        <p class="trend-page__visual-summary">
          {{ summaryPreview || '图表区域会优先使用已抓到的快照和历史分析结果，自动补齐当前能拿到的趋势信息。' }}
        </p>
      </header>

      <TrendChartCard
        title="分析类型分布"
        subtitle="观察历史分析结果在不同分析类型之间的覆盖情况。"
        :option="pieOption"
        :height="280"
      />
      <TrendChartCard
        title="分析日趋势"
        subtitle="最近几天趋势分析记录的数量变化。"
        :option="lineOption"
        :height="280"
      />
      <TrendChartCard
        title="榜单分类分布"
        subtitle="把原始分类码聚合后，展示当前样本主要覆盖了哪些中文榜单。"
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
      description="正在获取趋势图表和最新快照数据。"
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
