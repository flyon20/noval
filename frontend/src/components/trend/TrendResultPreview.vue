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
  comparisonSummary?: string;
  keyPoints?: string[];
  resultMeta?: {
    traceId?: string;
    modelName?: string;
  };
  errorMessage?: string;
}>();

const detailVisible = ref(false);
const isMobile = ref(false);

const previewText = computed(() =>
  buildPreviewText(props.resultSummary || props.comparisonSummary || props.resultContent, 300) || '暂无趋势结果',
);
const normalizedKeyPoints = computed(() => (props.keyPoints ?? []).filter(Boolean));
const drawerSummary = computed(() => props.comparisonSummary || props.resultSummary || '');
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
          <p class="trend-result-preview__eyebrow">结构化结论</p>
          <h3 class="trend-result-preview__title">趋势判断</h3>
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

      <section
        v-if="normalizedKeyPoints.length"
        class="trend-result-preview__section"
        data-test="trend-result-key-points"
      >
        <div class="trend-result-preview__section-head">
          <h4>重点判断</h4>
          <span>{{ normalizedKeyPoints.length }} 条</span>
        </div>
        <ul class="trend-result-preview__list">
          <li v-for="point in normalizedKeyPoints" :key="point">
            {{ point }}
          </li>
        </ul>
      </section>

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
            <p>趋势详情</p>
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

        <section
          v-if="drawerSummary || normalizedKeyPoints.length"
          class="trend-result-drawer__summary"
        >
          <p v-if="drawerSummary" class="trend-result-drawer__summary-copy">
            {{ drawerSummary }}
          </p>
          <ul v-if="normalizedKeyPoints.length" class="trend-result-drawer__list">
            <li v-for="point in normalizedKeyPoints" :key="point">
              {{ point }}
            </li>
          </ul>
        </section>

        <div v-if="fullHtml" class="trend-result-drawer__content" v-html="fullHtml" />

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
.trend-result-drawer__topbar,
.trend-result-preview__section-head {
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
.trend-result-preview__section-head h4,
.trend-result-drawer__heading p,
.trend-result-drawer__heading h3,
.trend-result-drawer__summary-copy {
  margin: 0;
}

.trend-result-preview__eyebrow,
.trend-result-drawer__heading p,
.trend-result-preview__section-head span {
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

.trend-result-preview__section,
.trend-result-drawer__summary {
  display: grid;
  gap: 0.75rem;
  padding: 0.95rem 1rem;
  border-radius: 1rem;
  background:
    linear-gradient(135deg, rgba(247, 248, 242, 0.95), rgba(255, 250, 243, 0.92)),
    rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(35, 65, 58, 0.08);
}

.trend-result-preview__list,
.trend-result-drawer__list {
  display: grid;
  gap: 0.65rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.trend-result-preview__list li,
.trend-result-drawer__list li {
  position: relative;
  padding-left: 1rem;
  color: var(--color-text);
  line-height: 1.75;
}

.trend-result-preview__list li::before,
.trend-result-drawer__list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0.78rem;
  width: 0.38rem;
  height: 0.38rem;
  border-radius: 999px;
  background: rgba(190, 108, 28, 0.78);
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

.trend-result-drawer__summary-copy,
.trend-result-drawer__content {
  line-height: 1.8;
}

.trend-result-drawer__content {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.trend-result-drawer__close {
  min-height: 2.5rem;
}

@media (max-width: 768px) {
  .trend-result-preview__head,
  .trend-result-drawer__topbar,
  .trend-result-preview__section-head {
    flex-wrap: wrap;
  }

  .trend-result-preview__meta,
  .trend-result-drawer__meta {
    font-size: 0.78rem;
  }
}
</style>
