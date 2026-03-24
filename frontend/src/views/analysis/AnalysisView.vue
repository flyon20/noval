<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { analysisApi } from '@/api/analysis';
import { systemConfigApi, userConfigApi } from '@/api/config';
import AnalysisContextBar from '@/components/analysis/AnalysisContextBar.vue';
import AnalysisEmptyState from '@/components/analysis/AnalysisEmptyState.vue';
import AnalysisModeTabs from '@/components/analysis/AnalysisModeTabs.vue';
import AnalysisResultCard from '@/components/analysis/AnalysisResultCard.vue';
import AnalysisToolbar from '@/components/analysis/AnalysisToolbar.vue';
import { useAnalysisRun } from '@/composables/useAnalysisRun';
import type { AnalysisResult, AnalysisType } from '@/types/analysis';

const route = useRoute();
const router = useRouter();

const ANALYSIS_MODES: AnalysisType[] = ['deconstruct', 'structure', 'plot'];

const availableModels = ref<string[]>([]);
const selectedModel = ref('');

async function loadModelPreferences() {
  try {
    const [modelsRes, prefRes] = await Promise.all([
      systemConfigApi.getAvailableModels(),
      userConfigApi.get('ai.preferred-model'),
    ]);
    availableModels.value = modelsRes.data.data ?? [];
    const preferred = prefRes.data.data?.configValue;
    if (preferred && availableModels.value.includes(preferred)) {
      selectedModel.value = preferred;
    } else if (availableModels.value.length > 0) {
      selectedModel.value = availableModels.value[0];
    }
  } catch {
    // non-critical
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

function parseAnalysisType(value: unknown): AnalysisType {
  return value === 'structure' || value === 'plot' ? value : 'deconstruct';
}

const pageContext = computed(() => {
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
});

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
  const inputChapterCount =
    typeof resultJson?.inputChapterCount === 'number' ? resultJson.inputChapterCount : pageContext.value?.chapterCount;

  if (!result) {
    return undefined;
  }

  const parts = [
    typeof inputChapterCount === 'number' ? `章节数：${inputChapterCount}` : '',
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

const analysisPanels = computed(() => {
  return ANALYSIS_MODES.map((mode) => {
    const modeState = analysis.state.modes[mode];
    const result = modeState.result;
    const running = ['preparing', 'streaming', 'fallback-blocking'].includes(modeState.phase);

    return {
      mode,
      title: modeLabelMap[mode],
      phaseLabel: resolvePhaseLabel(mode),
      running,
      state: modeState,
      result,
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
    activeMode.value = mode;
  },
  { immediate: true },
);

watch(
  () =>
    pageContext.value
      ? `${pageContext.value.bookId}:${pageContext.value.platform}:${pageContext.value.chapterCount}`
      : '',
  (contextKey) => {
    hasStarted.value = false;
    analysis.resetAllAnalyses();
    if (!contextKey || !pageContext.value) {
      return;
    }
  },
  { immediate: true },
);

onMounted(() => {
  void loadModelPreferences();
});

onBeforeUnmount(() => {
  analysis.stopAllAnalyses();
});

async function handleRerun(mode: AnalysisType) {
  if (!pageContext.value) {
    return;
  }

  if (!hasStarted.value) {
    hasStarted.value = true;
    activeMode.value = mode;
    await analysis.runAllAnalyses().catch(() => undefined);
    return;
  }

  await analysis.rerunAnalysis(mode).catch(() => undefined);
}

function handleStop(mode: AnalysisType) {
  analysis.stopAnalysis(mode);
}

async function handleCopy(mode: AnalysisType) {
  try {
    await analysis.copyResult(mode);
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
            v-if="availableModels.length > 1"
            :model-value="selectedModel"
            class="analysis-page__model-select"
            placeholder="选择模型"
            data-test="analysis-model-select"
            @update:model-value="handleModelChange"
          >
            <el-option
              v-for="model in availableModels"
              :key="model"
              :label="model"
              :value="model"
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
            :result-content="activePanel.result?.resultContent"
            :result-meta="activePanel.meta"
            :streaming-text="activePanel.state.streamingText"
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
}

.analysis-page__hero,
.analysis-page__panel {
  display: grid;
  gap: 1rem;
  border: 1px solid var(--color-border);
  border-radius: 1.35rem;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-soft);
}

.analysis-page__hero {
  grid-template-columns: 1fr;
  padding: 1.25rem;
  background:
    radial-gradient(circle at top right, rgba(210, 136, 61, 0.16), transparent 26%),
    linear-gradient(180deg, rgba(255, 251, 245, 0.96), rgba(248, 244, 236, 0.92));
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
