<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import AnalysisResultCard from '@/components/analysis/AnalysisResultCard.vue';
import { buildPreviewText, stripMarkdownToText } from '@/lib/trend-display';
import { renderAnalysisMarkdown } from '@/lib/markdown';

const props = defineProps<{
  phase: 'idle' | 'preparing' | 'streaming' | 'fallback-blocking' | 'done' | 'error' | 'aborted';
  streamingText?: string;
  resultContent?: string;
  resultSummary?: string;
  resultMeta?: {
    traceId?: string;
    modelName?: string;
  };
  errorMessage?: string;
}>();

const detailVisible = ref(false);
const isMobile = ref(false);

const previewText = computed(() =>
  buildPreviewText(props.resultSummary || props.resultContent, 300) || '暂无趋势结果',
);
const fullText = computed(() => stripMarkdownToText(props.resultContent));
const fullHtml = computed(() => (props.resultContent ? renderAnalysisMarkdown(props.resultContent) : ''));
const canOpenDetail = computed(() => Boolean(props.resultContent));
const drawerDirection = computed(() => (isMobile.value ? 'btt' : 'rtl'));
const drawerSize = computed(() => (isMobile.value ? '82%' : '560px'));

function syncViewport() {
  if (typeof window === 'undefined') {
    return;
  }

  isMobile.value = window.innerWidth <= 768;
}

function closeDrawer() {
  detailVisible.value = false;
}

onMounted(() => {
  syncViewport();
  window.addEventListener('resize', syncViewport);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewport);
});
</script>

<template>
  <div class="trend-result-preview">
    <AnalysisResultCard
      v-if="phase !== 'done'"
      :error-message="errorMessage"
      :phase="phase"
      :result-content="resultContent"
      :result-meta="resultMeta"
      :streaming-text="streamingText"
    />

    <article v-else class="trend-result-preview__card">
      <div class="trend-result-preview__head">
        <div class="trend-result-preview__copy">
          <p class="trend-result-preview__eyebrow">300 字预览</p>
          <h3 class="trend-result-preview__title">趋势结论</h3>
        </div>
        <el-button
          v-if="canOpenDetail"
          data-test="trend-result-detail-open"
          plain
          type="primary"
          @click="detailVisible = true"
        >
          查看详情
        </el-button>
      </div>

      <p class="trend-result-preview__body" data-test="trend-result-preview">
        {{ previewText }}
      </p>

      <div class="trend-result-preview__meta">
        <span v-if="resultMeta?.modelName">模型：{{ resultMeta.modelName }}</span>
        <span v-if="resultMeta?.traceId" class="trend-result-preview__trace">traceId: {{ resultMeta.traceId }}</span>
        <span>全文长度：{{ fullText.length || 0 }} 字</span>
      </div>
    </article>

    <el-drawer
      v-if="detailVisible"
      v-model="detailVisible"
      :append-to-body="false"
      :destroy-on-close="false"
      :with-header="false"
      :direction="drawerDirection"
      :size="drawerSize"
    >
      <div class="trend-result-drawer" data-test="trend-result-detail">
        <div class="trend-result-drawer__topbar">
          <div class="trend-result-drawer__heading">
            <p>趋势全文</p>
            <h3>完整分析结果</h3>
          </div>
          <el-button
            class="trend-result-drawer__close"
            data-test="trend-result-detail-close"
            plain
            type="default"
            @click="closeDrawer"
          >
            关闭
          </el-button>
        </div>

        <div class="trend-result-drawer__content" v-html="fullHtml" />

        <div class="trend-result-drawer__meta">
          <span v-if="resultMeta?.modelName">模型：{{ resultMeta.modelName }}</span>
          <span v-if="resultMeta?.traceId" class="trend-result-preview__trace">traceId: {{ resultMeta.traceId }}</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.trend-result-preview {
  display: grid;
}

.trend-result-preview__card {
  display: grid;
  gap: 1rem;
  min-height: 240px;
  padding: 1.25rem;
  border: 1px solid var(--color-border);
  border-radius: 1.25rem;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: var(--shadow-soft);
}

.trend-result-preview__head,
.trend-result-drawer__topbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.trend-result-preview__copy,
.trend-result-drawer__heading {
  display: grid;
  gap: 0.35rem;
}

.trend-result-preview__eyebrow,
.trend-result-preview__title,
.trend-result-preview__body,
.trend-result-drawer__heading p,
.trend-result-drawer__heading h3 {
  margin: 0;
}

.trend-result-preview__eyebrow,
.trend-result-drawer__heading p {
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.trend-result-preview__title,
.trend-result-drawer__heading h3 {
  font-size: 1.2rem;
}

.trend-result-preview__body {
  color: var(--color-text);
  line-height: 1.85;
  white-space: pre-wrap;
}

.trend-result-preview__meta,
.trend-result-drawer__meta {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.trend-result-preview__trace {
  color: var(--color-danger);
}

.trend-result-drawer {
  display: grid;
  gap: 1rem;
  min-width: 0;
}

.trend-result-drawer__content {
  line-height: 1.8;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.trend-result-drawer__close {
  min-height: 2.5rem;
}

@media (max-width: 768px) {
  .trend-result-preview__head,
  .trend-result-drawer__topbar {
    flex-wrap: wrap;
  }

  .trend-result-preview__meta,
  .trend-result-drawer__meta {
    font-size: 0.78rem;
  }
}
</style>
