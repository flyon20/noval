<script setup lang="ts">
import { ElMessage } from 'element-plus';
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
import { buildAnalysisDisplayContent } from '@/lib/analysis-display';
import type { AnalysisResult, AnalysisType } from '@/types/analysis';
import type { AiModelOption } from '@/types/config';

const route = useRoute();
const router = useRouter();

const ANALYSIS_MODES: AnalysisType[] = ['deconstruct', 'structure', 'plot'];

const availableModels = ref<AiModelOption[]>([]);
const selectedModel = ref('');

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
    const response = await dataApi.getHistory({
      platform: context.platform,
      bookId: context.bookId,
      limit: 20,
    });
    const historyItems = (response.data.data ?? []).filter((item) => item.chapterCount === context.chapterCount);
    const latestByMode = new Map<AnalysisType, AnalysisResult>();

    for (const item of historyItems) {
      const mode = parseAnalysisType(item.analysisType);
      if (latestByMode.has(mode)) {
        continue;
      }
      latestByMode.set(mode, {
        id: item.id,
        bookId: item.bookId,
        analysisType: mode,
        modelName: item.modelName,
        resultContent: item.resultContent,
        resultJson: item.resultJson,
        tokenUsed: typeof item.resultJson?.tokenUsed === 'number' ? item.resultJson.tokenUsed as number : 0,
      });
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

onMounted(() => {
  void initializeAnalysisPage();
  void loadModelPreferences();
});

onBeforeUnmount(() => {
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
  display: grid;
  gap: 0.75rem;
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

  .analysis-page__model-select {
    width: 100%;
  }
}
</style>
