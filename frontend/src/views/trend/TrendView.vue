<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { analysisApi } from '@/api/analysis';
import { crawlerApi } from '@/api/crawler';
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
import { buildPreviewText, extractTrendSummary } from '@/lib/trend-display';
import { getErrorPayload } from '@/lib/http-error';
import type { HotBook, InsightCard, SnapshotThemeComparison, ThemeTableItem, ThemeWordCloudItem, VisualData } from '@/types/data';
import type { RankBoardCatalog, UserRankPreference } from '@/types/crawler';
import type { TrendAnalysisResult } from '@/types/trend';

const PLATFORM = 'fanqie' as const;

const PHASE_LABELS: Record<string, string> = {
  idle: '待命',
  preparing: '准备中',
  streaming: '分析中',
  'fallback-blocking': '阻塞回退中',
  done: '已完成',
  error: '失败',
  aborted: '已停止',
};

const boardCatalog = ref<RankBoardCatalog[]>([]);
const selectedChannelCode = ref('');
const selectedBoardCode = ref('');
const contextLoading = ref(false);
const contextError = ref('');
const visualLoading = ref(false);
const visualError = ref('');
const visualData = ref<VisualData | null>(null);

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
  initialContext: {
    platform: PLATFORM,
  },
});

const activeChannel = computed(() => boardCatalog.value.find((item) => item.channelCode === selectedChannelCode.value));
const activeBoard = computed(() => activeChannel.value?.boards.find((item) => item.boardCode === selectedBoardCode.value) ?? null);
const currentBoardName = computed(() => visualData.value?.boardName || activeBoard.value?.boardName || '');
const latestSnapshotTime = computed(() => visualData.value?.latestSnapshots?.[0]?.snapshotTime ?? '');
const representativeBook = computed(
  () => visualData.value?.hotBooks?.[0]?.bookName || visualData.value?.latestSnapshots?.[0]?.topBookName || '',
);
const isRunning = computed(() =>
  ['preparing', 'streaming', 'fallback-blocking'].includes(trend.state.phase),
);
const phaseLabel = computed(() => PHASE_LABELS[trend.state.phase] ?? PHASE_LABELS.idle);

const displaySummary = computed(() => {
  if (trend.state.result) {
    return extractTrendSummary(trend.state.result.resultJson, trend.state.result.resultContent);
  }

  return visualData.value?.trendPreview || visualData.value?.comparisonSummary || '';
});

const displayContent = computed(() => {
  return trend.state.result?.resultContent || visualData.value?.detailContent || visualData.value?.trendPreview || '';
});

const displayPhase = computed(() => {
  if (['preparing', 'streaming', 'fallback-blocking', 'done', 'error'].includes(trend.state.phase)) {
    return trend.state.phase;
  }

  return displaySummary.value || displayContent.value ? 'done' : trend.state.phase;
});

const displayMeta = computed(() => ({
  traceId: trend.state.result?.traceId ?? trend.state.traceId,
  modelName: trend.state.result?.modelName,
}));

const tagCloudItems = computed<ThemeWordCloudItem[]>(() => visualData.value?.historicalWordCloud ?? []);

const wordCloudOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 24, right: 16, top: 24, bottom: 24, containLabel: true },
  xAxis: { type: 'value' },
  yAxis: {
    type: 'category',
    data: tagCloudItems.value.map((item) => item.name).reverse(),
  },
  series: [
    {
      type: 'bar',
      data: tagCloudItems.value.map((item) => item.value).reverse(),
      barMaxWidth: 22,
    },
  ],
}));

const themeTableOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['40%', '70%'],
      data: (visualData.value?.themeTable ?? []).map((item) => ({
        name: item.theme,
        value: item.count,
      })),
    },
  ],
}));

const snapshotOption = computed(() => {
  const rows = [...(visualData.value?.latestSnapshots ?? [])].reverse();

  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 24, right: 16, top: 24, bottom: 32, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map((item) => item.snapshotTime),
      axisLabel: { showMaxLabel: true, hideOverlap: true },
    },
    yAxis: { type: 'value' },
    series: [
      {
        type: 'line',
        smooth: true,
        areaStyle: {},
        data: rows.map((item) => item.bookCount),
      },
    ],
  };
});

function resolveInitialSelection(preference: UserRankPreference | null) {
  const firstChannel = boardCatalog.value[0];
  const firstBoard = firstChannel?.boards[0];

  if (!firstChannel || !firstBoard) {
    return null;
  }

  const preferredChannel = preference
    ? boardCatalog.value.find((item) => item.channelCode === preference.channelCode)
    : null;
  const preferredBoard = preferredChannel?.boards.find((item) => item.boardCode === preference.boardCode);

  return {
    channelCode: preferredChannel?.channelCode ?? firstChannel.channelCode,
    boardCode: preferredBoard?.boardCode ?? preferredChannel?.boards[0]?.boardCode ?? firstBoard.boardCode,
  };
}

function ensureVisualShell() {
  if (visualData.value) {
    return visualData.value;
  }

  visualData.value = {
    platform: PLATFORM,
    channelCode: selectedChannelCode.value,
    boardCode: selectedBoardCode.value,
    boardName: currentBoardName.value || activeBoard.value?.boardName || '',
    sourceSnapshotCount: 0,
    historyAnalysisCount: 0,
    latestSnapshots: [],
    historicalWordCloud: [],
    themeTable: [],
    hotBooks: [],
    insightCards: [],
    comparisonSummary: '',
    snapshotComparisons: [],
    trendPreview: '',
    detailContent: '',
  };

  return visualData.value;
}

function mergeVisualFromResult(result: TrendAnalysisResult) {
  const current = ensureVisualShell();
  const resultJson = result.resultJson as Record<string, unknown>;

  current.platform = result.platform;
  current.channelCode = result.channelCode;
  current.boardCode = result.boardCode;
  current.boardName = result.boardName;
  current.sourceSnapshotCount = result.sourceSnapshotCount ?? current.sourceSnapshotCount;
  current.historyAnalysisCount = typeof resultJson.historyAnalysisCount === 'number'
    ? resultJson.historyAnalysisCount
    : current.historyAnalysisCount;
  current.historicalWordCloud = Array.isArray(resultJson.historicalWordCloud)
    ? (resultJson.historicalWordCloud as ThemeWordCloudItem[])
    : current.historicalWordCloud;
  current.themeTable = Array.isArray(resultJson.themeTable)
    ? (resultJson.themeTable as ThemeTableItem[])
    : current.themeTable;
  current.hotBooks = Array.isArray(resultJson.hotBooks)
    ? (resultJson.hotBooks as HotBook[])
    : current.hotBooks;
  current.insightCards = Array.isArray(resultJson.insightCards)
    ? (resultJson.insightCards as InsightCard[])
    : current.insightCards;
  current.snapshotComparisons = Array.isArray(resultJson.snapshotComparisons)
    ? (resultJson.snapshotComparisons as SnapshotThemeComparison[])
    : current.snapshotComparisons;
  current.comparisonSummary = typeof resultJson.comparisonSummary === 'string'
    ? resultJson.comparisonSummary
    : current.comparisonSummary;
  current.trendPreview = typeof resultJson.summary === 'string'
    ? resultJson.summary
    : current.trendPreview;
  current.detailContent = result.resultContent || current.detailContent;
  visualData.value = {
    ...current,
  };
}

async function loadVisualData() {
  if (!selectedChannelCode.value || !selectedBoardCode.value) {
    return;
  }

  visualLoading.value = true;
  visualError.value = '';

  try {
    const response = await dataApi.getVisual({
      platform: PLATFORM,
      channelCode: selectedChannelCode.value,
      boardCode: selectedBoardCode.value,
    });
    visualData.value = response.data.data;
  } catch (error) {
    visualError.value = getErrorPayload(error).message ?? '趋势可视化数据加载失败';
  } finally {
    visualLoading.value = false;
  }
}

async function maybeSavePreference(channelCode: string, boardCode: string) {
  if (!('savePreference' in crawlerApi) || typeof crawlerApi.savePreference !== 'function') {
    return;
  }

  try {
    await crawlerApi.savePreference({
      platform: PLATFORM,
      channelCode,
      boardCode,
    });
  } catch {
    // Ignore preference persistence errors so the page stays responsive.
  }
}

async function initializePage() {
  contextLoading.value = true;
  contextError.value = '';

  try {
    const [boardsResponse, preferenceResponse] = await Promise.all([
      crawlerApi.getBoards({ platform: PLATFORM }),
      crawlerApi.getPreference({ platform: PLATFORM }).catch(() => null),
    ]);

    boardCatalog.value = boardsResponse.data.data;
    const selection = resolveInitialSelection(preferenceResponse?.data.data ?? null);

    if (!selection) {
      visualData.value = null;
      return;
    }

    selectedChannelCode.value = selection.channelCode;
    selectedBoardCode.value = selection.boardCode;
    trend.setContext({
      platform: PLATFORM,
      channelCode: selection.channelCode,
      boardCode: selection.boardCode,
    });
    await loadVisualData();
  } catch (error) {
    contextError.value = getErrorPayload(error).message ?? '趋势榜单上下文加载失败';
  } finally {
    contextLoading.value = false;
  }
}

async function handleContextSelect(payload: { channelCode: string; boardCode: string }) {
  if (!payload.channelCode || !payload.boardCode) {
    return;
  }

  if (
    payload.channelCode === selectedChannelCode.value
    && payload.boardCode === selectedBoardCode.value
  ) {
    return;
  }

  selectedChannelCode.value = payload.channelCode;
  selectedBoardCode.value = payload.boardCode;
  trend.setContext({
    platform: PLATFORM,
    channelCode: payload.channelCode,
    boardCode: payload.boardCode,
  });
  await maybeSavePreference(payload.channelCode, payload.boardCode);
  await loadVisualData();
}

async function handleRerun() {
  try {
    const result = await trend.rerunTrend();
    if (result) {
      mergeVisualFromResult(result);
    }
  } catch (error) {
    ElMessage.error(getErrorPayload(error).message ?? '趋势分析失败');
  }
}

function handleStop() {
  trend.stopTrend();
}

async function handleCopy() {
  try {
    await trend.copyResult(displayContent.value);
    ElMessage.success('趋势结果已复制');
  } catch (error) {
    ElMessage.error(getErrorPayload(error).message ?? '复制失败，请稍后再试');
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
      :active-board-code="selectedBoardCode"
      :active-channel-code="selectedChannelCode"
      :channels="boardCatalog"
      :loading="contextLoading"
      :platform="PLATFORM"
      :running="isRunning"
      @select="handleContextSelect"
    />

    <div class="trend-page__hero">
      <section class="trend-page__result">
        <div class="trend-page__toolbar">
          <div class="trend-page__toolbar-copy">
            <p class="trend-page__toolbar-title">榜单分析</p>
            <p class="trend-page__toolbar-subtitle">默认只展示历史可视化和摘要，点击按钮后才会开始新的流式趋势分析。</p>
          </div>
          <AnalysisToolbar
            :disabling="!selectedChannelCode || !selectedBoardCode"
            :running="isRunning"
            primary-label="开始分析"
            @copy="handleCopy"
            @rerun="handleRerun"
            @stop="handleStop"
          />
        </div>

        <div data-test="trend-result-panel">
          <TrendResultPreview
            :error-message="trend.state.errorMessage"
            :phase="displayPhase"
            :result-content="displayContent"
            :result-meta="displayMeta"
            :result-summary="displaySummary"
            :streaming-text="trend.state.streamingText"
          />
        </div>
      </section>

      <aside class="trend-page__insights">
        <TrendSummaryCards
          :board-name="currentBoardName"
          :history-analysis-count="visualData?.historyAnalysisCount ?? 0"
          :latest-snapshot-time="latestSnapshotTime"
          :phase-label="phaseLabel"
          :representative-book="representativeBook"
          :source-snapshot-count="trend.state.result?.sourceSnapshotCount ?? visualData?.sourceSnapshotCount ?? 0"
          :summary="buildPreviewText(displaySummary, 180)"
        />
        <TrendTagCloud :items="tagCloudItems" />
        <TrendComparisonList
          :comparisons="visualData?.snapshotComparisons ?? []"
          :insight-cards="visualData?.insightCards ?? []"
          :summary="visualData?.comparisonSummary ?? displaySummary"
        />
      </aside>
    </div>

    <section class="trend-page__visual" data-test="trend-visual-section">
      <header class="trend-page__visual-header">
        <div>
          <p class="trend-page__visual-eyebrow">趋势图谱</p>
          <h3 class="trend-page__visual-title">榜单图表</h3>
        </div>
        <p class="trend-page__visual-summary">
          图表只围绕当前榜单展示，优先用历史三次快照和已落库的结构化趋势结果，手机端会自动切成单列。
        </p>
      </header>

      <TrendChartCard
        title="历史题材词云"
        subtitle="展示该榜单最近三次趋势样本里最稳定出现的题材关键词。"
        :height="260"
        :option="wordCloudOption"
      />
      <TrendChartCard
        title="题材分布"
        subtitle="把趋势结果中的主题表直接转成图表，避免前端再做模糊推断。"
        :height="260"
        :option="themeTableOption"
      />
      <TrendChartCard
        title="快照书籍数"
        subtitle="观察最近三次榜单快照的样本规模是否稳定。"
        :height="260"
        :option="snapshotOption"
      />
      <TrendSnapshotTable :snapshots="visualData?.latestSnapshots ?? []" />
    </section>

    <el-alert
      v-if="contextError"
      title="趋势页上下文加载失败"
      :description="contextError"
      type="warning"
      show-icon
      :closable="false"
    />
    <el-alert
      v-else-if="visualError"
      title="趋势可视化加载失败"
      :description="visualError"
      type="warning"
      show-icon
      :closable="false"
    />
    <el-alert
      v-else-if="(contextLoading || visualLoading) && !visualData"
      title="趋势数据加载中"
      description="正在读取榜单上下文和最近的结构化趋势数据。"
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
  grid-template-columns: minmax(0, 1.3fr) minmax(320px, 0.7fr);
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

  .trend-page__toolbar {
    padding: 0.95rem;
  }
}
</style>
