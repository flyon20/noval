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
import type { AnalysisType } from '@/types/analysis';

const route = useRoute();
const router = useRouter();

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

const currentResultMode = computed(() => parseAnalysisType(route.query.mode));
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
      throw new Error('当前浏览器不支持剪贴板复制');
    }

    await navigator.clipboard.writeText(text);
  },
});

const currentResult = computed(() => analysis.state.results[analysis.state.activeMode]);
const currentAnalysisModeLabel = computed(() => {
  const resultJson = currentResult.value?.resultJson;
  const analysisMode = typeof resultJson?.analysisMode === 'string' ? resultJson.analysisMode : '';
  const segmentCount = typeof resultJson?.segmentCount === 'number' ? resultJson.segmentCount : null;

  if (analysis.state.phase !== 'done' || !currentResult.value) {
    return undefined;
  }

  if (analysisMode === 'chunk_merge') {
    return segmentCount && segmentCount > 1 ? `分析方式：分段汇总 · ${segmentCount} 段` : '分析方式：分段汇总';
  }

  return '分析方式：单次分析';
});
const currentAnalysisDetailLabel = computed(() => {
  const resultJson = currentResult.value?.resultJson;
  const segmentCount = typeof resultJson?.segmentCount === 'number' ? resultJson.segmentCount : null;
  const inputChapterCount =
    typeof resultJson?.inputChapterCount === 'number' ? resultJson.inputChapterCount : pageContext.value?.chapterCount;

  if (analysis.state.phase !== 'done' || !currentResult.value) {
    return undefined;
  }

  const parts = [
    typeof inputChapterCount === 'number' ? `章节数：${inputChapterCount}` : '',
    segmentCount && segmentCount > 1 ? `分段数：${segmentCount}` : '',
  ].filter(Boolean);

  return parts.length ? parts.join(' · ') : undefined;
});
const isRunning = computed(() =>
  ['preparing', 'streaming', 'fallback-blocking'].includes(analysis.state.phase),
);
const analysisTypeLabel = computed(() => modeLabelMap[analysis.state.activeMode]);

watch(
  () =>
    pageContext.value
      ? `${pageContext.value.bookId}:${pageContext.value.platform}:${pageContext.value.chapterCount}:${currentResultMode.value}`
      : '',
  (contextKey) => {
    if (!contextKey || !pageContext.value) {
      analysis.stopAnalysis();
      return;
    }

    void analysis.runAnalysis(currentResultMode.value).catch(() => undefined);
  },
  { immediate: true },
);

onMounted(() => {
  void loadModelPreferences();
});

onBeforeUnmount(() => {
  analysis.stopAnalysis();
});

async function handleModeChange(mode: AnalysisType) {
  if (!pageContext.value) {
    return;
  }

  await analysis.switchMode(mode).catch(() => undefined);
}

async function handleRerun() {
  if (!pageContext.value) {
    return;
  }

  await analysis.rerunAnalysis().catch(() => undefined);
}

function handleStop() {
  analysis.stopAnalysis();
}

async function handleCopy() {
  try {
    await analysis.copyResult();
    ElMessage.success('分析结果已复制');
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
          :analysis-type="analysisTypeLabel"
          :author="pageContext.author"
          :book-id="pageContext.bookId"
          :book-title="pageContext.bookTitle"
          :chapter-count="pageContext.chapterCount"
          :platform="pageContext.platform"
        />
      </header>

      <section class="analysis-page__panel">
        <div class="analysis-page__controls">
          <AnalysisModeTabs
            :model-value="analysis.state.activeMode"
            @update:model-value="handleModeChange"
          />
          <AnalysisToolbar
            :available-models="availableModels"
            :disabling="!pageContext"
            :model-name="selectedModel"
            :running="isRunning"
            @copy="handleCopy"
            @rerun="handleRerun"
            @stop="handleStop"
            @update:model-name="handleModelChange"
          />
        </div>

        <AnalysisResultCard
          :error-message="analysis.state.errorMessage"
          :phase="analysis.state.phase"
          :result-content="currentResult?.resultContent"
          :result-meta="{
            analysisModeLabel: currentAnalysisModeLabel,
            analysisDetailLabel: currentAnalysisDetailLabel,
            traceId: currentResult?.traceId ?? analysis.state.traceId,
            modelName: currentResult?.modelName,
            tokenUsed: currentResult?.tokenUsed,
          }"
          :streaming-text="analysis.state.streamingText"
        />
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

@media (max-width: 960px) {
  .analysis-page__hero {
    grid-template-columns: 1fr;
  }
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
}
</style>
