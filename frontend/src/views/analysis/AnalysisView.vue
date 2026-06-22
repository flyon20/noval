<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { Close, FullScreen } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { analysisApi } from '@/api/analysis';
import { dataApi } from '@/api/data';
import { systemConfigApi, userConfigApi } from '@/api/config';
import AnalysisContextBar from '@/components/analysis/AnalysisContextBar.vue';
import AnalysisEmptyState from '@/components/analysis/AnalysisEmptyState.vue';
import AnalysisModeTabs from '@/components/analysis/AnalysisModeTabs.vue';
import AnalysisResultCard from '@/components/analysis/AnalysisResultCard.vue';
import AnalysisToolbar from '@/components/analysis/AnalysisToolbar.vue';
import { useAnalysisRun } from '@/composables/useAnalysisRun';
import { useMobileDrawerBack } from '@/composables/useMobileDrawerBack';
import { useMobileEdgeSwipeClose } from '@/composables/useMobileEdgeSwipeClose';
import { buildAnalysisDisplayContent } from '@/lib/analysis-display';
import type { AnalysisResult, AnalysisType } from '@/types/analysis';
import type { AiModelOption } from '@/types/config';
import type { AnalysisHistorySummary } from '@/types/data';

const route = useRoute();
const router = useRouter();

const ANALYSIS_MODES: AnalysisType[] = ['deconstruct', 'structure', 'plot'];
const RESTORE_HISTORY_PAGE_SIZE = 20;
const MAX_RESTORE_HISTORY_PAGES = 10;

const availableModels = ref<AiModelOption[]>([]);
const selectedModel = ref('');
const windowWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth);
const resultDrawerVisible = ref(false);
const resultDrawerFullscreen = ref(true);
const isMobile = computed(() => windowWidth.value <= 768);
const resultDrawerSize = computed(() => (isMobile.value && resultDrawerFullscreen.value ? '100%' : '92%'));
const resultDrawerClass = computed(() => (
  resultDrawerFullscreen.value ? 'analysis-result-drawer is-fullscreen' : 'analysis-result-drawer'
));
const resultDrawerSwipe = useMobileEdgeSwipeClose(closeResultDrawer, { mobileWidth: 768 });
useMobileDrawerBack({
  isOpen: () => resultDrawerVisible.value,
  close: closeResultDrawer,
  mobileWidth: 768,
  isMobile: () => typeof window !== 'undefined' && window.innerWidth <= 768,
});

async function loadModelPreferences() {
  try {
    const [modelsRes, prefRes] = await Promise.all([
      systemConfigApi.getModelOptions(),
      userConfigApi.get('ai.preferred-model'),
    ]);
    availableModels.value = modelsRes.data.data ?? [];
    const preferred = prefRes.data.data?.configValue;
    if (preferred && availableModels.value.some((item) => item.modelKey === preferred)) {
      selectedModel.value = preferred;
    } else if (availableModels.value.length > 0) {
      selectedModel.value = availableModels.value[0].modelKey;
    }
  } catch {
    try {
      const fallback = await systemConfigApi.getAvailableModels();
      availableModels.value = (fallback.data.data ?? []).map((modelKey, index) => ({
        modelKey,
        displayName: modelKey,
        providerType: 'openai-compatible',
        isDefault: index === 0,
      }));
      selectedModel.value = availableModels.value[0]?.modelKey ?? '';
    } catch {
      // non-critical
    }
  }
}

async function handleModelChange(model: string) {
  selectedModel.value = model;
  try {
    await userConfigApi.update({ configKey: 'ai.preferred-model', configValue: model });
  } catch {
    // non-critical
  }
}

const modeLabelMap: Record<AnalysisType, string> = {
  deconstruct: '拆文分析',
  structure: '结构分析',
  plot: '情节分析',
};

function buildAnalysisStreamingContent(content?: string | null) {
  return (content ?? '')
    .replace(/\[(analysis[- ]progress|chunk-progress)\][^\n\r]*/giu, ' ')
    .replace(/\n{3,}/gu, '\n\n')
    .trim();
}

interface PersistedAnalysisContext {
  platform: 'fanqie';
  bookId: number;
  chapterCount: number;
  bookTitle?: string;
  author?: string;
  activeMode?: AnalysisType;
}

function parseAnalysisType(value: unknown): AnalysisType {
  return value === 'structure' || value === 'plot' ? value : 'deconstruct';
}

function buildRouteContext(): PersistedAnalysisContext | null {
  const bookId = Number(route.query.bookId);
  const chapterCount = Number(route.query.chapterCount);
  const platform = route.query.platform;

  if (
    platform !== 'fanqie' ||
    !Number.isInteger(bookId) ||
    bookId <= 0 ||
    !Number.isInteger(chapterCount) ||
    chapterCount < 1 ||
    chapterCount > 10
  ) {
    return null;
  }

  return {
    platform: 'fanqie' as const,
    bookId,
    chapterCount,
    bookTitle: typeof route.query.bookName === 'string' ? route.query.bookName : undefined,
    author: typeof route.query.author === 'string' ? route.query.author : undefined,
  };
}

function parsePersistedAnalysisContext(value: string | null | undefined): PersistedAnalysisContext | null {
  if (!value) {
    return null;
  }

  try {
    const parsed = JSON.parse(value) as Partial<PersistedAnalysisContext>;
    if (
      parsed.platform !== 'fanqie'
      || typeof parsed.bookId !== 'number'
      || !Number.isInteger(parsed.bookId)
      || parsed.bookId <= 0
      || typeof parsed.chapterCount !== 'number'
      || !Number.isInteger(parsed.chapterCount)
      || parsed.chapterCount < 1
      || parsed.chapterCount > 10
    ) {
      return null;
    }

    return {
      platform: 'fanqie',
      bookId: parsed.bookId,
      chapterCount: parsed.chapterCount,
      bookTitle: typeof parsed.bookTitle === 'string' ? parsed.bookTitle : undefined,
      author: typeof parsed.author === 'string' ? parsed.author : undefined,
      activeMode: parseAnalysisType(parsed.activeMode),
    };
  } catch {
    return null;
  }
}

const persistedContext = ref<PersistedAnalysisContext | null>(null);
const contextReady = ref(false);
const pageContext = computed(() => buildRouteContext() ?? persistedContext.value);

const preferredMode = computed(() => parseAnalysisType(route.query.mode));
const activeMode = ref<AnalysisType>(preferredMode.value);
const hasStarted = ref(false);
const analysis = useAnalysisRun({
  context: () =>
    pageContext.value ?? {
      platform: 'fanqie',
      bookId: 0,
      chapterCount: 1,
    },
  runMode(mode, payload, callbacks) {
    if (mode === 'structure') {
      return analysisApi.streamStructure(payload, callbacks);
    }

    if (mode === 'plot') {
      return analysisApi.streamPlot(payload, callbacks);
    }

    return analysisApi.streamDeconstruct(payload, callbacks);
  },
  async copyText(text) {
    if (!navigator.clipboard?.writeText) {
      throw new Error('Clipboard API is not available');
    }

    await navigator.clipboard.writeText(text);
  },
});

function resolveAnalysisModeLabel(result: AnalysisResult | null) {
  const resultJson = result?.resultJson;
  const analysisMode = typeof resultJson?.analysisMode === 'string' ? resultJson.analysisMode : '';
  const segmentCount = typeof resultJson?.segmentCount === 'number' ? resultJson.segmentCount : null;

  if (!result) {
    return undefined;
  }

  if (analysisMode === 'chunk_merge') {
    return segmentCount && segmentCount > 1
      ? `分析方式：分段汇总 · ${segmentCount} 段`
      : '分析方式：分段汇总';
  }

  return '分析方式：单次分析';
}

function resolveAnalysisDetailLabel(result: AnalysisResult | null) {
  const resultJson = result?.resultJson;
  const segmentCount = typeof resultJson?.segmentCount === 'number' ? resultJson.segmentCount : null;
  const requestedChapterCount = typeof resultJson?.requestedChapterCount === 'number'
    ? resultJson.requestedChapterCount
    : pageContext.value?.chapterCount;
  const actualChapterCount = typeof resultJson?.actualChapterCount === 'number'
    ? resultJson.actualChapterCount
    : typeof resultJson?.inputChapterCount === 'number'
      ? resultJson.inputChapterCount
      : requestedChapterCount;

  if (!result) {
    return undefined;
  }

  const parts = [
    typeof actualChapterCount === 'number' && typeof requestedChapterCount === 'number' && actualChapterCount < requestedChapterCount
      ? `抓取章节：${actualChapterCount}/${requestedChapterCount}`
      : typeof actualChapterCount === 'number'
        ? `章节数：${actualChapterCount}`
        : '',
    segmentCount && segmentCount > 1 ? `分段数：${segmentCount}` : '',
  ].filter(Boolean);

  return parts.length ? parts.join(' · ') : undefined;
}

function resolvePhaseLabel(mode: AnalysisType) {
  const phase = analysis.state.modes[mode].phase;
  if (phase === 'done') {
    return '已完成';
  }
  if (phase === 'streaming') {
    return '流式输出中';
  }
  if (phase === 'fallback-blocking') {
    return '阻塞回退中';
  }
  if (phase === 'preparing') {
    return '准备分析';
  }
  if (phase === 'error') {
    return '分析失败';
  }
  if (phase === 'aborted') {
    return '已停止';
  }
  return '等待开始';
}

function hasModeStarted(mode: AnalysisType) {
  const modeState = analysis.state.modes[mode];
  return modeState.phase !== 'idle'
    || Boolean(modeState.result)
    || Boolean(modeState.streamingText)
    || Boolean(modeState.errorMessage);
}

const analysisPanels = computed(() => {
  return ANALYSIS_MODES.map((mode) => {
    const modeState = analysis.state.modes[mode];
    const result = modeState.result;
    const running = ['preparing', 'streaming', 'fallback-blocking'].includes(modeState.phase);
    const displayResultContent = result
      ? buildAnalysisDisplayContent(mode, {
        resultContent: result.resultContent,
        resultJson: result.resultJson,
      })
      : '';
    const displayStreamingText = buildAnalysisStreamingContent(modeState.streamingText);

    return {
      mode,
      title: modeLabelMap[mode],
      phaseLabel: resolvePhaseLabel(mode),
      running,
      state: modeState,
      result,
      displayResultContent,
      displayStreamingText,
      meta: {
        analysisModeLabel: modeState.phase === 'done' ? resolveAnalysisModeLabel(result) : undefined,
        analysisDetailLabel: modeState.phase === 'done' ? resolveAnalysisDetailLabel(result) : undefined,
        traceId: result?.traceId ?? modeState.traceId,
        modelName: result?.modelName,
        tokenUsed: result?.tokenUsed,
      },
    };
  });
});

const activePanel = computed(
  () => analysisPanels.value.find((panel) => panel.mode === activeMode.value) ?? analysisPanels.value[0],
);

const resultReaderAvailable = computed(() => {
  const panel = activePanel.value;
  return Boolean(panel && hasModeStarted(panel.mode));
});

const tabStatuses = computed(
  () =>
    Object.fromEntries(
      analysisPanels.value.map((panel) => [
        panel.mode,
        {
          phaseLabel: panel.phaseLabel,
          tone:
            panel.state.phase === 'error'
              ? 'error'
              : panel.running
                ? 'running'
                : panel.state.phase === 'done'
                  ? 'done'
                  : 'idle',
        },
      ]),
    ) as Partial<
      Record<
        AnalysisType,
        {
          phaseLabel: string;
          tone: 'idle' | 'running' | 'done' | 'error';
        }
      >
    >,
);

watch(
  preferredMode,
  (mode) => {
    if (buildRouteContext()) {
      activeMode.value = mode;
    }
  },
  { immediate: true },
);

async function persistCurrentContext(context: PersistedAnalysisContext | null = pageContext.value) {
  if (!context) {
    return;
  }

  try {
    await userConfigApi.update({
      configKey: 'analysis.current-context',
      configValue: JSON.stringify({
        ...context,
        activeMode: activeMode.value,
      }),
    });
  } catch {
    // non-critical
  }
}

async function restorePersistedResults(context: PersistedAnalysisContext) {
  analysis.resetAllAnalyses();
  hasStarted.value = false;

  try {
    const latestByMode = new Map<AnalysisType, AnalysisResult>();
    const historyItems: AnalysisHistorySummary[] = [];
    let page = 1;
    let hasNext = true;

    while (hasNext && page <= MAX_RESTORE_HISTORY_PAGES && latestByMode.size < ANALYSIS_MODES.length) {
      const response = await dataApi.getHistory({
        platform: context.platform,
        bookId: context.bookId,
        chapterCount: context.chapterCount,
        page,
        pageSize: RESTORE_HISTORY_PAGE_SIZE,
      });
      const pageData = response.data.data;
      const pageItems = pageData?.items ?? [];
      historyItems.push(...pageItems);

      for (const item of pageItems) {
        const mode = parseAnalysisType(item.analysisType);
        if (latestByMode.has(mode)) {
          continue;
        }

        try {
          const detailResponse = await dataApi.getHistoryDetail(item.id);
          const detail = detailResponse.data.data;
          if (!detail) {
            continue;
          }
          latestByMode.set(mode, {
            id: item.id,
            bookId: item.bookId,
            analysisType: mode,
            modelName: detail.modelName,
            resultContent: detail.resultContent,
            resultJson: detail.resultJson,
            tokenUsed: typeof detail.resultJson?.tokenUsed === 'number' ? detail.resultJson.tokenUsed as number : 0,
          });
        } catch {
          // Ignore a stale or broken detail row and keep restoring other modes.
        }
      }

      hasNext = Boolean(pageData?.hasNext) && pageItems.length > 0;
      page += 1;
    }

    if (!latestByMode.size) {
      return;
    }

    analysis.hydrateModes(Object.fromEntries(latestByMode.entries()) as Partial<Record<AnalysisType, AnalysisResult>>);
    hasStarted.value = true;

    if (!context.bookTitle) {
      const bookName = historyItems.find((item) => typeof item.bookName === 'string' && item.bookName)?.bookName ?? undefined;
      if (bookName && persistedContext.value && persistedContext.value.bookId === context.bookId) {
        persistedContext.value = {
          ...persistedContext.value,
          bookTitle: bookName,
        };
        await persistCurrentContext(persistedContext.value);
      }
    }
  } catch {
    // non-critical
  }
}

async function initializeAnalysisPage() {
  const routeContext = buildRouteContext();
  if (routeContext) {
    persistedContext.value = routeContext;
    activeMode.value = preferredMode.value;
    await persistCurrentContext(routeContext);
    await restorePersistedResults(routeContext);
    contextReady.value = true;
    return;
  }

  try {
    const response = await userConfigApi.get('analysis.current-context');
    const restored = parsePersistedAnalysisContext(response.data.data?.configValue ?? null);
    persistedContext.value = restored;
    if (restored?.activeMode) {
      activeMode.value = restored.activeMode;
    }
    if (restored) {
      await restorePersistedResults(restored);
    }
  } catch {
    persistedContext.value = null;
  } finally {
    contextReady.value = true;
  }
}

function updateWindowWidth() {
  windowWidth.value = window.innerWidth;
  if (!isMobile.value) {
    resultDrawerVisible.value = false;
  }
}

onMounted(() => {
  if (typeof window !== 'undefined') {
    window.addEventListener('resize', updateWindowWidth);
  }

  void initializeAnalysisPage();
  void loadModelPreferences();
});

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', updateWindowWidth);
  }
  analysis.stopAllAnalyses();
});

watch(activeMode, () => {
  if (!contextReady.value || !pageContext.value) {
    return;
  }
  void persistCurrentContext();
});

watch(
  () => {
    const routeContext = buildRouteContext();
    return routeContext
      ? `${routeContext.platform}:${routeContext.bookId}:${routeContext.chapterCount}:${route.query.mode ?? ''}`
      : '';
  },
  async (routeContextKey, previousKey) => {
    if (!contextReady.value || !routeContextKey || routeContextKey === previousKey) {
      return;
    }
    const routeContext = buildRouteContext();
    if (!routeContext) {
      return;
    }
    persistedContext.value = routeContext;
    activeMode.value = preferredMode.value;
    await persistCurrentContext(routeContext);
    await restorePersistedResults(routeContext);
  },
);

async function handleRerun(mode: AnalysisType) {
  if (!pageContext.value) {
    return;
  }

  const modeStarted = hasModeStarted(mode);

  if (!hasStarted.value || !modeStarted) {
    hasStarted.value = true;
    activeMode.value = mode;
    await analysis.runAnalysis(mode).catch(() => undefined);
    return;
  }

  await analysis.rerunAnalysis(mode).catch(() => undefined);
}

function handleStop(mode: AnalysisType) {
  analysis.stopAnalysis(mode);
}

function openResultDrawer() {
  if (!isMobile.value || !resultReaderAvailable.value) {
    return;
  }
  resultDrawerFullscreen.value = true;
  resultDrawerVisible.value = true;
}

function closeResultDrawer() {
  resultDrawerVisible.value = false;
}

function toggleResultDrawerFullscreen() {
  resultDrawerFullscreen.value = !resultDrawerFullscreen.value;
}

async function handleCopy(mode: AnalysisType) {
  try {
    const panel = analysisPanels.value.find((item) => item.mode === mode);
    const text = panel?.displayResultContent || panel?.displayStreamingText || panel?.result?.resultContent || '';

    if (!text) {
      return;
    }

    if (!navigator.clipboard?.writeText) {
      throw new Error('Clipboard API is not available');
    }

    await navigator.clipboard.writeText(text);
    ElMessage.success(`${modeLabelMap[mode]}结果已复制`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '复制失败，请稍后重试');
  }
}

async function goBack() {
  await router.push('/rank');
}
</script>

<template>
  <section class="analysis-page">
    <template v-if="pageContext">
      <header class="analysis-page__hero">
        <AnalysisContextBar
          analysis-type="单书并行分析总览"
          :author="pageContext.author"
          :book-id="pageContext.bookId"
          :book-title="pageContext.bookTitle"
          :chapter-count="pageContext.chapterCount"
          :platform="pageContext.platform"
        />
      </header>

      <section class="analysis-page__panel">
        <div class="analysis-page__controls">
          <div class="analysis-page__summary">
            <p class="analysis-page__summary-eyebrow">Parallel Panels</p>
            <h3 class="analysis-page__summary-title">三个版块独立运行，互不阻塞</h3>
            <p class="analysis-page__summary-copy">只有点击对应卡片的“停止生成”才会中断该版块。</p>
          </div>

          <el-select
            v-if="availableModels.length > 0"
            :model-value="selectedModel"
            class="analysis-page__model-select"
            placeholder="选择模型"
            data-test="analysis-model-select"
            @update:model-value="handleModelChange"
          >
            <el-option
              v-for="model in availableModels"
              :key="model.modelKey"
              :label="`${model.displayName} (${model.modelKey})`"
              :value="model.modelKey"
            />
          </el-select>
        </div>

        <div class="analysis-page__tab-strip">
          <AnalysisModeTabs
            v-model="activeMode"
            :status-by-mode="tabStatuses"
          />
        </div>

        <article
          v-if="activePanel"
          class="analysis-mode-panel"
          :data-mode="activePanel.mode"
          data-test="analysis-mode-panel"
        >
          <div class="analysis-mode-panel__header">
            <div class="analysis-mode-panel__title-wrap">
              <p class="analysis-mode-panel__eyebrow">{{ activePanel.phaseLabel }}</p>
              <h3 class="analysis-mode-panel__title">{{ activePanel.title }}</h3>
              <el-button
                class="analysis-mode-panel__reader-button"
                type="primary"
                plain
                :disabled="!resultReaderAvailable"
                data-test="analysis-result-open-reader"
                @click="openResultDrawer"
              >
                阅读
              </el-button>
            </div>

            <AnalysisToolbar
              :disabling="!pageContext"
              :primary-label="hasStarted ? undefined : '\u5f00\u59cb\u5206\u6790'"
              :running="activePanel.running"
              @copy="handleCopy(activePanel.mode)"
              @rerun="handleRerun(activePanel.mode)"
              @stop="handleStop(activePanel.mode)"
            />
          </div>

          <AnalysisResultCard
            :error-message="activePanel.state.errorMessage"
            :phase="activePanel.state.phase"
            :result-content="activePanel.displayResultContent"
            :result-meta="activePanel.meta"
            :streaming-text="activePanel.displayStreamingText"
          />
        </article>

        <el-drawer
          v-model="resultDrawerVisible"
          :class="resultDrawerClass"
          :append-to-body="true"
          :size="resultDrawerSize"
          direction="rtl"
          :with-header="false"
          :close-on-click-modal="true"
        >
          <div
            class="analysis-result-drawer__shell"
            @touchstart.passive="resultDrawerSwipe.onTouchStart"
            @touchend.passive="resultDrawerSwipe.onTouchEnd"
            @pointerdown.passive="resultDrawerSwipe.onPointerStart"
            @pointerup.passive="resultDrawerSwipe.onPointerEnd"
          >
            <div class="analysis-result-drawer__bar">
              <div class="analysis-result-drawer__title-wrap">
                <p class="analysis-result-drawer__eyebrow">Reading Mode</p>
                <h3>{{ activePanel?.title ?? '分析结果' }}</h3>
              </div>
              <div class="analysis-result-drawer__actions">
                <el-button
                  class="analysis-result-drawer__action"
                  circle
                  :aria-label="resultDrawerFullscreen ? '退出全屏阅读' : '全屏阅读'"
                  data-test="analysis-result-drawer-fullscreen"
                  @click="toggleResultDrawerFullscreen"
                >
                  <el-icon><FullScreen /></el-icon>
                </el-button>
                <el-button
                  class="analysis-result-drawer__action"
                  circle
                  aria-label="关闭分析结果"
                  data-test="analysis-result-drawer-close"
                  @click="closeResultDrawer"
                >
                  <el-icon><Close /></el-icon>
                </el-button>
              </div>
            </div>

            <AnalysisResultCard
              v-if="activePanel"
              :error-message="activePanel.state.errorMessage"
              :phase="activePanel.state.phase"
              :result-content="activePanel.displayResultContent"
              :result-meta="activePanel.meta"
              :streaming-text="activePanel.displayStreamingText"
            />
          </div>
        </el-drawer>
      </section>
    </template>

    <AnalysisEmptyState v-else @go-back="goBack" />
  </section>
</template>

<style scoped lang="scss">
.analysis-page {
  display: grid;
  gap: 1.5rem;
  min-width: 0;
}

.analysis-page__hero,
.analysis-page__panel {
  display: grid;
  gap: 1rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  border-radius: 1.35rem;
  background:
    linear-gradient(160deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-surface) 90%, transparent);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(1.12);
  -webkit-backdrop-filter: blur(18px) saturate(1.12);
}

.analysis-page__hero {
  grid-template-columns: 1fr;
  padding: 1.25rem;
  background:
    radial-gradient(circle at top right, rgba(92, 124, 250, 0.18), transparent 26%),
    radial-gradient(circle at top left, rgba(255, 147, 186, 0.14), transparent 22%),
    linear-gradient(180deg, color-mix(in srgb, var(--color-surface) 96%, transparent), color-mix(in srgb, var(--color-bg-secondary) 92%, transparent));
}

.analysis-page__panel {
  padding: 1.25rem;
}

.analysis-page__controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
  min-width: 0;
}

.analysis-page__summary {
  display: grid;
  gap: 0.25rem;
}

.analysis-page__summary-eyebrow {
  margin: 0;
  font-size: 0.82rem;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}

.analysis-page__summary-title {
  margin: 0;
  font-size: 1.25rem;
}

.analysis-page__summary-copy {
  margin: 0;
  color: var(--color-text-muted);
}

.analysis-page__model-select {
  width: 220px;
}

.analysis-page__tab-strip {
  position: sticky;
  top: 1rem;
  z-index: 20;
  display: grid;
  gap: 0.75rem;
  padding: 0.35rem 0;
  background:
    linear-gradient(
      180deg,
      color-mix(in srgb, var(--color-surface-strong) 96%, transparent),
      color-mix(in srgb, var(--color-surface) 90%, transparent)
    );
  backdrop-filter: blur(14px) saturate(1.08);
  -webkit-backdrop-filter: blur(14px) saturate(1.08);
}

.analysis-mode-panel {
  display: grid;
  gap: 0.85rem;
  align-content: start;
  min-width: 0;
}

.analysis-mode-panel__header {
  display: grid;
  gap: 0.75rem;
}

.analysis-mode-panel__title-wrap {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
  min-width: 0;
}

.analysis-mode-panel__eyebrow {
  margin: 0;
  font-size: 0.8rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}

.analysis-mode-panel__title {
  margin: 0;
  font-size: 1.05rem;
}

.analysis-mode-panel__reader-button {
  display: none;
}

.analysis-result-drawer__shell {
  height: 100%;
  min-width: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 0.75rem;
  padding: 1rem;
  overflow: hidden;
  background: var(--color-surface);
}

.analysis-result-drawer__bar {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--color-border);
}

.analysis-result-drawer__title-wrap {
  min-width: 0;
}

.analysis-result-drawer__title-wrap h3,
.analysis-result-drawer__eyebrow {
  margin: 0;
  overflow-wrap: anywhere;
}

.analysis-result-drawer__title-wrap h3 {
  font-size: 1rem;
  line-height: 1.35;
}

.analysis-result-drawer__eyebrow {
  margin-bottom: 0.2rem;
  color: var(--color-text-muted);
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.analysis-result-drawer__actions {
  flex: 0 0 auto;
  display: flex;
  gap: 0.45rem;
}

.analysis-result-drawer__action {
  min-width: 44px;
  min-height: 44px;
}

:global(.analysis-result-drawer) {
  max-width: 760px;
}

:global(.analysis-result-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
}

:global(.analysis-result-drawer .analysis-result-card) {
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

:global(.analysis-result-drawer .analysis-result__stream),
:global(.analysis-result-drawer .analysis-result__done),
:global(.analysis-result-drawer .analysis-result__partial) {
  min-height: 0;
  max-height: none;
  overflow-y: auto;
  overflow-x: hidden;
  overflow-wrap: anywhere;
  word-break: break-word;
  -webkit-overflow-scrolling: touch;
}

:global(.analysis-result-drawer .analysis-result__done *) {
  max-width: 100%;
  overflow-wrap: anywhere;
  word-break: break-word;
}

:global(.analysis-result-drawer .analysis-result__done pre),
:global(.analysis-result-drawer .analysis-result__done code) {
  white-space: pre-wrap;
}

:global(.analysis-result-drawer .analysis-result__done table) {
  display: block;
  width: 100%;
  overflow-x: auto;
}

@media (max-width: 768px) {
  .analysis-page {
    gap: 0.75rem;
  }

  .analysis-page__hero,
  .analysis-page__panel {
    padding: 0.875rem;
    border-radius: 1rem;
    gap: 0.75rem;
  }

  .analysis-page__controls {
    gap: 0.6rem;
  }

  .analysis-page__tab-strip {
    position: static;
    z-index: auto;
    padding: 0;
    background: transparent;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }

  .analysis-page__model-select {
    width: 100%;
  }

  .analysis-mode-panel__title-wrap {
    align-items: center;
  }

  .analysis-mode-panel__reader-button {
    display: inline-flex;
    min-height: 44px;
    margin-left: auto;
  }

  :global(.analysis-result-drawer) {
    max-width: none;
  }

  :global(.analysis-result-drawer.is-fullscreen) {
    width: 100% !important;
  }

  :global(.analysis-result-drawer:not(.is-fullscreen)) {
    width: calc(100% - 32px) !important;
  }

  .analysis-result-drawer__shell {
    padding: max(0.75rem, env(safe-area-inset-top)) max(0.75rem, env(safe-area-inset-right)) max(0.9rem, env(safe-area-inset-bottom)) max(0.75rem, env(safe-area-inset-left));
  }
}
</style>
