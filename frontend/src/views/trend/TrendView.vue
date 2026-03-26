<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { analysisApi } from '@/api/analysis';
import { crawlerApi } from '@/api/crawler';
import { systemConfigApi, userConfigApi } from '@/api/config';
import { dataApi } from '@/api/data';
import { DEFAULT_RANK_FETCH_COUNT } from '@/constants/crawler';
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
  buildPreviewText,
  buildTrendDisplayModel,
  buildTrendStreamingPreviewText,
  localizeTrendText,
} from '@/lib/trend-display';
import { getErrorPayload } from '@/lib/http-error';
import type { SnapshotThemeComparison, ThemeWordCloudItem, VisualData } from '@/types/data';
import type { AiModelOption } from '@/types/config';
import type { RankBoardCatalog, RankFetchCount, UserRankPreference } from '@/types/crawler';
import type { TrendAnalysisResult } from '@/types/trend';

const PLATFORM = 'fanqie' as const;
const VISUAL_POLL_INTERVAL_MS = 12000;
const VISUAL_RETRY_INTERVAL_MS = 3000;

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
const selectedRankFetchCount = ref<RankFetchCount>(DEFAULT_RANK_FETCH_COUNT);
const availableModels = ref<AiModelOption[]>([]);
const selectedModel = ref('');
const contextLoading = ref(false);
const contextError = ref('');
const visualLoading = ref(false);
const visualError = ref('');
const visualData = ref<VisualData | null>(null);
const isMobileViewport = ref(false);
let visualPollTimer: ReturnType<typeof setTimeout> | null = null;

interface PersistedTrendContext {
  platform: 'fanqie';
  channelCode: string;
  boardCode: string;
  boardName?: string;
}

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
const structuredTrend = computed(() => buildTrendDisplayModel({
  resultJson: trend.state.result?.resultJson,
  resultContent: trend.state.result?.resultContent ?? visualData.value?.detailContent ?? visualData.value?.trendPreview,
  detailContent: visualData.value?.detailContent,
  comparisonSummary: visualData.value?.comparisonSummary,
  boardSummary: visualData.value?.boardSummary,
  trendPreview: visualData.value?.trendPreview,
  insightCards: visualData.value?.insightCards,
  themeDistribution: visualData.value?.themeDistribution,
  themeTable: visualData.value?.themeTable,
  hotBooks: visualData.value?.hotBooks,
}));

const displaySummary = computed(() => structuredTrend.value.summaryText);
const displayBoardSummary = computed(() => structuredTrend.value.boardSummary || displaySummary.value);
const displayContent = computed(() => structuredTrend.value.detailContent);
const themeDistributionRows = computed(() => (
  structuredTrend.value.themeDistribution.length
    ? structuredTrend.value.themeDistribution
    : structuredTrend.value.themeTable.map((item) => ({
      theme: item.theme,
      count: item.count,
      ratio: item.ratio ?? null,
    }))
));

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
const displayStreamingText = computed(() => buildTrendStreamingPreviewText(trend.state.streamingText));

const tagCloudItems = computed<ThemeWordCloudItem[]>(() => (visualData.value?.historicalWordCloud ?? []).map((item) => ({
  ...item,
  name: localizeTrendText(item.name),
})));
const availableSnapshotCount = computed(() => Math.max(
  visualData.value?.latestSnapshots?.length ?? 0,
  visualData.value?.sourceSnapshotCount ?? 0,
  trend.state.result?.sourceSnapshotCount ?? 0,
  0,
));
const visualSummaryText = computed(() => {
  if (availableSnapshotCount.value > 0) {
    return `图表只围绕当前榜单展示，优先显示当前可用的 ${availableSnapshotCount.value} 次快照和已落库的结构化趋势结果，手机端会自动切成单列。`;
  }

  return '图表只围绕当前榜单展示，待抓到快照后这里会自动补上结构化趋势数据，手机端会自动切成单列。';
});
const snapshotChartTitle = computed(() => {
  if (availableSnapshotCount.value > 0) {
    return `最近 ${availableSnapshotCount.value} 次快照书籍数`;
  }

  return '快照书籍数';
});
const snapshotChartSubtitle = computed(() => {
  if (availableSnapshotCount.value > 0) {
    return `先观察最近 ${availableSnapshotCount.value} 次榜单快照的样本规模，不再等待凑满三次。`;
  }

  return '当前还没有可用快照，待抓取后这里会展示样本规模变化。';
});

const themeTableOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: {
    type: 'scroll',
    bottom: 0,
    icon: 'circle',
    itemWidth: 10,
    itemHeight: 10,
  },
  series: [
    {
      type: 'pie',
      radius: ['42%', '70%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      label: { show: false },
      labelLine: { show: false },
      emphasis: {
        scale: true,
        label: { show: false },
      },
      data: themeDistributionRows.value.map((item) => ({
        name: localizeTrendText(item.theme),
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

function syncViewportMode() {
  isMobileViewport.value = window.innerWidth <= 760;
}

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

function parsePersistedTrendContext(value: string | null | undefined): PersistedTrendContext | null {
  if (!value) {
    return null;
  }

  try {
    const parsed = JSON.parse(value) as Partial<PersistedTrendContext>;
    if (
      parsed.platform !== 'fanqie'
      || typeof parsed.channelCode !== 'string'
      || !parsed.channelCode
      || typeof parsed.boardCode !== 'string'
      || !parsed.boardCode
    ) {
      return null;
    }

    return {
      platform: 'fanqie',
      channelCode: parsed.channelCode,
      boardCode: parsed.boardCode,
      boardName: typeof parsed.boardName === 'string' ? parsed.boardName : undefined,
    };
  } catch {
    return null;
  }
}

function resolveTrendSelection(
  preference: UserRankPreference | null,
  persistedContext: PersistedTrendContext | null,
) {
  const firstChannel = boardCatalog.value[0];
  const firstBoard = firstChannel?.boards[0];

  if (!firstChannel || !firstBoard) {
    return null;
  }

  const persistedChannel = persistedContext
    ? boardCatalog.value.find((item) => item.channelCode === persistedContext.channelCode)
    : null;
  const persistedBoard = persistedChannel?.boards.find((item) => item.boardCode === persistedContext.boardCode);
  if (persistedChannel && persistedBoard) {
    return {
      channelCode: persistedChannel.channelCode,
      boardCode: persistedBoard.boardCode,
    };
  }

  return resolveInitialSelection(preference);
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
    boardSummary: '',
    historicalWordCloud: [],
    themeDistribution: [],
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
  const structured = buildTrendDisplayModel({
    resultJson,
    resultContent: result.resultContent,
  });
  const hasThemeDistribution = Object.prototype.hasOwnProperty.call(resultJson, 'themeDistribution');
  const hasThemeTable = Object.prototype.hasOwnProperty.call(resultJson, 'themeTable');
  const hasHotBooks = Object.prototype.hasOwnProperty.call(resultJson, 'hotBooks');
  const hasInsightCards = Object.prototype.hasOwnProperty.call(resultJson, 'insightCards');

  current.platform = result.platform;
  current.channelCode = result.channelCode;
  current.boardCode = result.boardCode;
  current.boardName = result.boardName;
  current.sourceSnapshotCount = result.sourceSnapshotCount ?? current.sourceSnapshotCount;
  current.historyAnalysisCount = typeof resultJson.historyAnalysisCount === 'number'
    ? resultJson.historyAnalysisCount
    : current.historyAnalysisCount;
  current.boardSummary = structured.boardSummary || current.boardSummary;
  current.historicalWordCloud = Array.isArray(resultJson.historicalWordCloud)
    ? (resultJson.historicalWordCloud as ThemeWordCloudItem[])
    : current.historicalWordCloud;
  current.themeDistribution = hasThemeDistribution ? structured.themeDistribution : current.themeDistribution;
  current.themeTable = hasThemeTable ? structured.themeTable : current.themeTable;
  current.hotBooks = hasHotBooks ? structured.hotBooks : current.hotBooks;
  current.insightCards = hasInsightCards ? structured.insightCards : current.insightCards;
  current.snapshotComparisons = Array.isArray(resultJson.snapshotComparisons)
    ? (resultJson.snapshotComparisons as SnapshotThemeComparison[])
    : current.snapshotComparisons;
  current.comparisonSummary = structured.comparisonSummary || current.comparisonSummary;
  current.trendPreview = structured.previewText || current.trendPreview;
  current.detailContent = structured.detailContent || current.detailContent;
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
    scheduleVisualPoll();
  } catch (error) {
    visualError.value = getErrorPayload(error).message ?? '趋势可视化数据加载失败';
    scheduleVisualPoll(VISUAL_RETRY_INTERVAL_MS);
  } finally {
    visualLoading.value = false;
  }
}

function clearVisualPollTimer() {
  if (visualPollTimer) {
    clearTimeout(visualPollTimer);
    visualPollTimer = null;
  }
}

function scheduleVisualPoll(delay = VISUAL_POLL_INTERVAL_MS) {
  clearVisualPollTimer();

  if (!selectedChannelCode.value || !selectedBoardCode.value) {
    return;
  }

  visualPollTimer = setTimeout(() => {
    void pollVisualData();
  }, delay);
}

async function pollVisualData() {
  if (!selectedChannelCode.value || !selectedBoardCode.value) {
    return;
  }

  if (contextLoading.value || visualLoading.value || isRunning.value) {
    scheduleVisualPoll();
    return;
  }

  try {
    const response = await dataApi.getVisual({
      platform: PLATFORM,
      channelCode: selectedChannelCode.value,
      boardCode: selectedBoardCode.value,
    });
    visualData.value = response.data.data;
    if (visualError.value) {
      visualError.value = '';
    }
    scheduleVisualPoll();
  } catch {
    scheduleVisualPoll(VISUAL_RETRY_INTERVAL_MS);
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
      rankFetchCount: selectedRankFetchCount.value,
    });
  } catch {
    // ignore preference persistence errors
  }
}

async function loadModelPreferences() {
  try {
    const [modelsResponse, preferenceResponse] = await Promise.all([
      systemConfigApi.getModelOptions(),
      userConfigApi.get('ai.preferred-model'),
    ]);
    availableModels.value = modelsResponse.data.data ?? [];
    const preferredModel = preferenceResponse.data.data?.configValue;
    if (preferredModel && availableModels.value.some((item) => item.modelKey === preferredModel)) {
      selectedModel.value = preferredModel;
    } else {
      selectedModel.value = availableModels.value[0]?.modelKey ?? '';
    }
  } catch {
    selectedModel.value = '';
  }
}

async function handleModelChange(modelKey: string) {
  selectedModel.value = modelKey;
  try {
    await userConfigApi.update({ configKey: 'ai.preferred-model', configValue: modelKey });
  } catch {
    // ignore preference persistence errors
  }
}

async function persistTrendContext(channelCode: string, boardCode: string) {
  const boardName = boardCatalog.value
    .find((item) => item.channelCode === channelCode)
    ?.boards.find((item) => item.boardCode === boardCode)
    ?.boardName;
  try {
    await userConfigApi.update({
      configKey: 'trend.current-context',
      configValue: JSON.stringify({
        platform: PLATFORM,
        channelCode,
        boardCode,
        boardName,
      }),
    });
  } catch {
    // non-critical
  }
}

async function initializePage() {
  contextLoading.value = true;
  contextError.value = '';

  try {
    const [boardsResponse, preferenceResponse, trendContextResponse] = await Promise.all([
      crawlerApi.getBoards({ platform: PLATFORM }),
      crawlerApi.getPreference({ platform: PLATFORM }).catch(() => null),
      userConfigApi.get('trend.current-context').catch(() => null),
    ]);

    boardCatalog.value = boardsResponse.data.data;
    const selection = resolveTrendSelection(
      preferenceResponse?.data.data ?? null,
      parsePersistedTrendContext(trendContextResponse?.data.data?.configValue ?? null),
    );

    if (!selection) {
      visualData.value = null;
      return;
    }

    selectedChannelCode.value = selection.channelCode;
    selectedBoardCode.value = selection.boardCode;
    selectedRankFetchCount.value = preferenceResponse?.data.data?.rankFetchCount ?? DEFAULT_RANK_FETCH_COUNT;
    trend.setContext({
      platform: PLATFORM,
      channelCode: selection.channelCode,
      boardCode: selection.boardCode,
    });
    await persistTrendContext(selection.channelCode, selection.boardCode);
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
  await persistTrendContext(payload.channelCode, payload.boardCode);
  await maybeSavePreference(payload.channelCode, payload.boardCode);
  await loadVisualData();
}

function isUserAbortedTrendRun(error: unknown) {
  return error instanceof Error && error.message === 'Analysis stream aborted';
}

async function handleRerun() {
  try {
    const result = await trend.rerunTrend();
    if (result) {
      mergeVisualFromResult(result);
    }
  } catch (error) {
    if (isUserAbortedTrendRun(error)) {
      return;
    }

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
  syncViewportMode();
  window.addEventListener('resize', syncViewportMode);
  void initializePage();
  void loadModelPreferences();
});

onBeforeUnmount(() => {
  clearVisualPollTimer();
  trend.stopTrend();
  window.removeEventListener('resize', syncViewportMode);
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
          </div>
          <div class="trend-page__toolbar-side">
            <el-select
              v-if="availableModels.length > 0"
              :model-value="selectedModel"
              class="trend-page__model-select"
              placeholder="选择模型"
              @update:model-value="handleModelChange"
            >
              <el-option
                v-for="model in availableModels"
                :key="model.modelKey"
                :label="`${model.displayName} (${model.modelKey})`"
                :value="model.modelKey"
              />
            </el-select>

            <AnalysisToolbar
              :disabling="!selectedChannelCode || !selectedBoardCode"
              :running="isRunning"
              primary-label="开始分析"
              @copy="handleCopy"
              @rerun="handleRerun"
              @stop="handleStop"
            />
          </div>
        </div>

        <div data-test="trend-result-panel">
          <TrendResultPreview
            :error-message="trend.state.errorMessage"
            :phase="displayPhase"
            :comparison-summary="structuredTrend.comparisonSummary"
            :result-content="displayContent"
            :result-meta="displayMeta"
            :result-summary="displaySummary"
            :key-points="structuredTrend.keyPoints"
            :streaming-text="displayStreamingText"
          />
        </div>

        <div
          v-if="displayBoardSummary || structuredTrend.themeTable.length || structuredTrend.hotBooks.length"
          class="trend-page__result-support"
          data-test="trend-result-support-grid"
        >
          <article v-if="displayBoardSummary" class="trend-page__support-card trend-page__support-card--summary">
            <div class="trend-page__support-card-head">
              <h3>榜单摘要</h3>
              <span>{{ currentBoardName || '当前榜单' }}</span>
            </div>
            <p class="trend-page__support-summary">
              {{ buildPreviewText(displayBoardSummary, 220) }}
            </p>
          </article>

          <article v-if="structuredTrend.themeTable.length" class="trend-page__support-card">
            <div class="trend-page__support-card-head">
              <h3>题材表</h3>
              <span>{{ structuredTrend.themeTable.length }} 项</span>
            </div>
            <div
              v-if="!isMobileViewport"
              class="trend-page__support-table-wrap"
              data-test="trend-result-theme-table"
            >
              <el-table
                :data="structuredTrend.themeTable.slice(0, 6)"
                size="small"
                stripe
                table-layout="fixed"
                empty-text="暂无题材数据"
              >
                <el-table-column label="题材" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ row.theme }}
                  </template>
                </el-table-column>
                <el-table-column label="频次" width="120">
                  <template #default="{ row }">
                    {{ row.count }}
                    <template v-if="typeof row.ratio === 'number'">
                      · {{ row.ratio }}%
                    </template>
                  </template>
                </el-table-column>
                <el-table-column label="趋势" min-width="120" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ row.trend || '稳定' }}
                  </template>
                </el-table-column>
                <el-table-column label="代表作" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ row.representativeBooks?.[0]?.bookName || '--' }}
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <ul
              v-else
              class="trend-page__support-list trend-page__support-list--cards"
              data-test="trend-result-theme-cards"
            >
              <li
                v-for="item in structuredTrend.themeTable.slice(0, 6)"
                :key="`${item.theme}-${item.trend}`"
              >
                <div class="trend-page__support-copy">
                  <strong>{{ item.theme }}</strong>
                  <span>
                    {{ item.count }}
                    <template v-if="typeof item.ratio === 'number'">
                      · {{ item.ratio }}%
                    </template>
                  </span>
                </div>
                <em>{{ item.trend || '稳定' }}</em>
                <p v-if="item.representativeBooks?.[0]" class="trend-page__support-meta">
                  代表作：{{ item.representativeBooks[0].bookName }}
                </p>
              </li>
            </ul>
          </article>

          <article v-if="structuredTrend.hotBooks.length" class="trend-page__support-card">
            <div class="trend-page__support-card-head">
              <h3>代表热书</h3>
              <span>{{ structuredTrend.hotBooks.length }} 本</span>
            </div>
            <ul class="trend-page__support-list" data-test="trend-result-hot-books">
              <li
                v-for="item in structuredTrend.hotBooks.slice(0, 4)"
                :key="`${item.bookName}-${item.rankLabel || 'rankless'}`"
              >
                <div class="trend-page__support-copy">
                  <strong>{{ item.bookName }}</strong>
                  <span>
                    {{ item.rankLabel || '当前样本热书' }}
                    <template v-if="item.theme">
                      · {{ item.theme }}
                    </template>
                  </span>
                </div>
                <em>{{ item.reason || item.author || '已进入当前榜单重点观察范围。' }}</em>
              </li>
            </ul>
          </article>
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
          :summary="buildPreviewText(displayBoardSummary || displaySummary, 180)"
        />
        <TrendComparisonList
          :comparisons="(visualData?.snapshotComparisons ?? []).map((item) => ({
            ...item,
            topTheme: localizeTrendText(item.topTheme),
            change: localizeTrendText(item.change),
            leadBookName: item.leadBookName,
          }))"
          :insight-cards="structuredTrend.insightCards"
          :summary="structuredTrend.comparisonSummary || displayBoardSummary || displaySummary"
        />
      </aside>
    </div>

    <section class="trend-page__visual" data-test="trend-visual-section">
      <header class="trend-page__visual-header">
        <div>
          <p class="trend-page__visual-eyebrow">趋势图谱</p>
          <h3 class="trend-page__visual-title">榜单图表</h3>
        </div>
        <p class="trend-page__visual-summary">图表</p>
      </header>

      <TrendTagCloud :items="tagCloudItems" />
      <TrendChartCard
        title="题材分布"
        :height="260"
        :option="themeTableOption"
      />
      <TrendChartCard
        :title="snapshotChartTitle"
        :height="260"
        :option="snapshotOption"
      />
      <TrendSnapshotTable
        :sample-count="availableSnapshotCount"
        :snapshots="visualData?.latestSnapshots ?? []"
      />
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
  min-width: 0;
  overflow-x: hidden;
}

.trend-page__hero {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(320px, 0.7fr);
  gap: 1rem;
  align-items: start;
  min-width: 0;
}

.trend-page__result,
.trend-page__insights {
  display: grid;
  gap: 1rem;
  min-width: 0;
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
  min-width: 0;
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

.trend-page__result-support {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 1rem;
  min-width: 0;
}

.trend-page__support-card {
  display: grid;
  gap: 0.85rem;
  padding: 1rem 1.05rem;
  border-radius: 1.2rem;
  border: 1px solid var(--color-border);
  background:
    linear-gradient(145deg, rgba(255, 250, 243, 0.94), rgba(247, 249, 243, 0.9)),
    rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
  min-width: 0;
}

.trend-page__support-card--summary {
  background:
    linear-gradient(145deg, rgba(248, 245, 238, 0.94), rgba(245, 248, 242, 0.92)),
    rgba(255, 255, 255, 0.92);
}

.trend-page__support-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.trend-page__toolbar-side {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.trend-page__model-select {
  min-width: 220px;
}

.trend-page__support-card-head h3,
.trend-page__support-list,
.trend-page__support-copy strong,
.trend-page__support-copy span,
.trend-page__support-list em,
.trend-page__support-summary,
.trend-page__support-meta {
  margin: 0;
}

.trend-page__support-card-head span,
.trend-page__support-copy span,
.trend-page__support-list em,
.trend-page__support-meta {
  color: var(--color-text-muted);
}

.trend-page__support-card-head span {
  font-size: 0.82rem;
}

.trend-page__support-summary,
.trend-page__support-meta {
  line-height: 1.7;
}

.trend-page__support-table-wrap {
  overflow-x: auto;
}

.trend-page__support-table-wrap :deep(.el-table) {
  --el-table-border-color: rgba(35, 65, 58, 0.08);
  --el-table-header-bg-color: rgba(35, 65, 58, 0.04);
  --el-table-row-hover-bg-color: rgba(35, 65, 58, 0.04);
  min-width: 560px;
}

.trend-page__support-list {
  display: grid;
  gap: 0.75rem;
  padding: 0;
  list-style: none;
}

.trend-page__support-list--cards li {
  gap: 0.5rem;
}

.trend-page__support-list li {
  display: grid;
  gap: 0.35rem;
  padding: 0.85rem 0.95rem;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.05);
}

.trend-page__support-copy {
  display: grid;
  gap: 0.18rem;
}

.trend-page__support-list em {
  font-style: normal;
  line-height: 1.65;
}

.trend-page__visual {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
  min-width: 0;
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

  .trend-page__toolbar-side,
  .trend-page__model-select {
    width: 100%;
    min-width: 0;
  }

  .trend-page__result-support {
    grid-template-columns: 1fr;
  }

  .trend-page__support-table-wrap :deep(.el-table) {
    min-width: 480px;
  }
}
</style>
