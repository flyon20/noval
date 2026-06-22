<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { dataApi } from '@/api/data';
import HistoryDetailPanel from '@/components/history/HistoryDetailPanel.vue';
import HistoryFilterBar from '@/components/history/HistoryFilterBar.vue';
import HistoryListPanel from '@/components/history/HistoryListPanel.vue';
import { useMobileDrawerBack } from '@/composables/useMobileDrawerBack';
import { useMobileEdgeSwipeClose } from '@/composables/useMobileEdgeSwipeClose';
import type { AnalysisHistoryDetail, AnalysisHistoryQuery, AnalysisHistorySummary } from '@/types/data';

const historyItems = ref<AnalysisHistorySummary[]>([]);
const selectedSummary = ref<AnalysisHistorySummary | null>(null);
const selectedDetail = ref<AnalysisHistoryDetail | null>(null);
const loading = ref(false);
const loadingMore = ref(false);
const detailLoading = ref(false);
const errorMessage = ref('');
const appendErrorMessage = ref('');
const detailErrorMessage = ref('');
const detailDrawerVisible = ref(false);
const windowWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth);
const detailRequestSeq = ref(0);

const queryState = reactive({
  platform: 'fanqie' as const,
  analysisType: undefined as AnalysisHistorySummary['analysisType'] | undefined,
  bookId: undefined as number | undefined,
  channelCode: undefined as string | undefined,
  boardCode: undefined as string | undefined,
  chapterCount: undefined as number | undefined,
  modelName: undefined as string | undefined,
  keyword: undefined as string | undefined,
  startTime: undefined as string | undefined,
  endTime: undefined as string | undefined,
  page: 1,
  pageSize: 20,
  total: 0,
  hasNext: false,
});

const isMobile = computed(() => windowWidth.value <= 960);
const isCompactDesktop = computed(() => !isMobile.value && windowWidth.value < 1280);
const detailDrawerSwipe = useMobileEdgeSwipeClose(closeDetailDrawer);
useMobileDrawerBack({
  isOpen: () => detailDrawerVisible.value,
  close: closeDetailDrawer,
  mobileWidth: 960,
  isMobile: () => typeof window !== 'undefined' && window.innerWidth <= 960,
});

function normalizeHistoryPage(data: unknown) {
  if (Array.isArray(data)) {
    return {
      items: data as AnalysisHistorySummary[],
      page: queryState.page,
      pageSize: queryState.pageSize,
      total: data.length,
      hasNext: false,
    };
  }

  if (data && typeof data === 'object') {
    const pageData = data as {
      items?: AnalysisHistorySummary[];
      page?: number;
      pageSize?: number;
      total?: number;
      hasNext?: boolean;
    };

    return {
      items: pageData.items ?? [],
      page: pageData.page ?? queryState.page,
      pageSize: pageData.pageSize ?? queryState.pageSize,
      total: pageData.total ?? pageData.items?.length ?? 0,
      hasNext: pageData.hasNext ?? false,
    };
  }

  return {
    items: [],
    page: queryState.page,
    pageSize: queryState.pageSize,
    total: 0,
    hasNext: false,
  };
}

function detailFromSummary(item: AnalysisHistorySummary) {
  const maybeDetail = item as AnalysisHistorySummary & Partial<AnalysisHistoryDetail> & {
    detailContent?: string | null;
  };
  const resultContent = typeof maybeDetail.resultContent === 'string'
    ? maybeDetail.resultContent
    : maybeDetail.detailContent;

  if (!resultContent) {
    return null;
  }

  return {
    ...item,
    resultContent,
    resultJson: maybeDetail.resultJson ?? {},
  } satisfies AnalysisHistoryDetail;
}

function buildHistoryQuery(): AnalysisHistoryQuery {
  const payload: AnalysisHistoryQuery = {
    platform: queryState.platform,
    page: queryState.page,
    pageSize: queryState.pageSize,
  };

  if (queryState.analysisType) {
    payload.analysisType = queryState.analysisType;
  }

  if (typeof queryState.bookId === 'number' && !Number.isNaN(queryState.bookId)) {
    payload.bookId = queryState.bookId;
  }

  if (queryState.channelCode) {
    payload.channelCode = queryState.channelCode;
  }

  if (queryState.boardCode) {
    payload.boardCode = queryState.boardCode;
  }

  if (typeof queryState.chapterCount === 'number' && !Number.isNaN(queryState.chapterCount)) {
    payload.chapterCount = queryState.chapterCount;
  }

  if (queryState.modelName) {
    payload.modelName = queryState.modelName;
  }

  if (queryState.keyword) {
    payload.keyword = queryState.keyword;
  }

  if (queryState.startTime) {
    payload.startTime = queryState.startTime;
  }

  if (queryState.endTime) {
    payload.endTime = queryState.endTime;
  }

  return payload;
}

function updateWindowWidth() {
  windowWidth.value = window.innerWidth;
}

async function loadHistory(options: { append?: boolean } = {}) {
  const append = options.append === true;
  if (append) {
    loadingMore.value = true;
    appendErrorMessage.value = '';
  } else {
    loading.value = true;
    errorMessage.value = '';
    appendErrorMessage.value = '';
  }

  try {
    const response = await dataApi.getHistory(buildHistoryQuery());
    const pageData = normalizeHistoryPage(response.data.data);
    const list = pageData?.items ?? [];

    historyItems.value = append ? [...historyItems.value, ...list] : list;
    queryState.total = pageData?.total ?? 0;
    queryState.hasNext = pageData?.hasNext ?? false;

    if (!historyItems.value.length) {
      selectedSummary.value = null;
      selectedDetail.value = null;
      detailDrawerVisible.value = false;
      return;
    }

    const currentSelected = historyItems.value.find((item) => item.id === selectedSummary.value?.id) ?? null;
    if (!currentSelected && !append) {
      selectedSummary.value = null;
      selectedDetail.value = null;
      detailErrorMessage.value = '';
    }
  } catch {
    if (append) {
      queryState.page = Math.max(1, queryState.page - 1);
      appendErrorMessage.value = '加载更多失败，请稍后重试。';
    } else {
      errorMessage.value = '历史记录加载失败，请稍后重试。';
    }
  } finally {
    if (append) {
      loadingMore.value = false;
    } else {
      loading.value = false;
    }
  }
}

function handleFilter(payload: {
  analysisType?: AnalysisHistorySummary['analysisType'];
  bookId?: number;
  channelCode?: string;
  boardCode?: string;
  chapterCount?: number;
  modelName?: string;
  keyword?: string;
  startTime?: string;
  endTime?: string;
  pageSize?: number;
}) {
  queryState.analysisType = payload.analysisType;
  queryState.bookId = payload.bookId;
  queryState.channelCode = payload.channelCode;
  queryState.boardCode = payload.boardCode;
  queryState.chapterCount = payload.chapterCount;
  queryState.modelName = payload.modelName;
  queryState.keyword = payload.keyword;
  queryState.startTime = payload.startTime;
  queryState.endTime = payload.endTime;
  queryState.pageSize = payload.pageSize ?? 20;
  queryState.page = 1;
  selectedSummary.value = null;
  selectedDetail.value = null;
  void loadHistory();
}

async function loadDetail(item: AnalysisHistorySummary, openDrawer: boolean) {
  const requestSeq = detailRequestSeq.value + 1;
  detailRequestSeq.value = requestSeq;
  selectedSummary.value = item;
  detailLoading.value = true;
  detailErrorMessage.value = '';
  if (openDrawer) {
    detailDrawerVisible.value = true;
  }
  try {
    const response = await dataApi.getHistoryDetail(item.id);
    if (detailRequestSeq.value === requestSeq && selectedSummary.value?.id === item.id) {
      selectedDetail.value = response.data.data ?? null;
    }
  } catch {
    if (detailRequestSeq.value === requestSeq && selectedSummary.value?.id === item.id) {
      const fallbackDetail = detailFromSummary(item);
      selectedDetail.value = fallbackDetail;
      detailErrorMessage.value = fallbackDetail ? '' : '详情加载失败，请稍后重试。';
    }
  } finally {
    if (detailRequestSeq.value === requestSeq) {
      detailLoading.value = false;
    }
  }
}

function handleSelect(item: AnalysisHistorySummary) {
  void loadDetail(item, true);
}

function handlePageChange(page: number) {
  queryState.page = page;
  void loadHistory();
}

function handleLoadMore() {
  if (!queryState.hasNext || loading.value || loadingMore.value) {
    return;
  }
  queryState.page += 1;
  void loadHistory({ append: true });
}

function closeDetailDrawer() {
  detailDrawerVisible.value = false;
}

onMounted(() => {
  if (typeof window !== 'undefined') {
    window.addEventListener('resize', updateWindowWidth);
  }

  void loadHistory();
});

onUnmounted(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', updateWindowWidth);
  }
});
</script>

<template>
  <section class="history-page">
    <HistoryFilterBar :loading="loading || loadingMore" :default-limit="queryState.pageSize" @filter="handleFilter" />

    <HistoryListPanel
      v-if="!errorMessage"
      :loading="loading"
      :loading-more="loadingMore"
      :append-error="appendErrorMessage"
      :items="historyItems"
      :is-mobile="isMobile"
      :is-compact-desktop="isCompactDesktop"
      :page="queryState.page"
      :page-size="queryState.pageSize"
      :total="queryState.total"
      :has-next="queryState.hasNext"
      @select="handleSelect"
      @page-change="handlePageChange"
      @load-more="handleLoadMore"
    />
    <div v-else class="history-error">{{ errorMessage }}</div>

    <el-drawer
      v-model="detailDrawerVisible"
      custom-class="history-detail-drawer"
      :size="isMobile ? '100%' : '58%'"
      direction="rtl"
      :with-header="false"
      :close-on-click-modal="true"
    >
      <div
        class="history-drawer-shell"
        @touchstart.passive="detailDrawerSwipe.onTouchStart"
        @touchend.passive="detailDrawerSwipe.onTouchEnd"
        @pointerdown.passive="detailDrawerSwipe.onPointerStart"
        @pointerup.passive="detailDrawerSwipe.onPointerEnd"
      >
        <div class="history-drawer-shell__bar">
          <div>
            <p class="history-drawer-shell__eyebrow">History Detail</p>
            <h3>{{ selectedSummary?.bookName ?? '历史详情' }}</h3>
          </div>
          <el-button
            class="history-drawer-shell__close"
            circle
            aria-label="关闭历史详情"
            data-test="history-detail-close"
            @click="closeDetailDrawer"
          >
            ×
          </el-button>
        </div>
        <HistoryDetailPanel :item="selectedDetail" :loading="detailLoading" :error="detailErrorMessage" />
      </div>
    </el-drawer>
  </section>
</template>

<style scoped lang="scss">
.history-page {
  display: grid;
  gap: 1rem;
}

.history-error {
  padding: 1.5rem;
  border-radius: 1.25rem;
  border: 1px solid color-mix(in srgb, var(--color-danger) 32%, transparent);
  background:
    linear-gradient(
      160deg,
      color-mix(in srgb, var(--color-surface-strong) 96%, transparent),
      color-mix(in srgb, var(--color-surface) 92%, transparent)
    );
  color: var(--color-danger);
}

.history-drawer-shell {
  height: 100%;
  min-width: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 0.75rem;
  padding: 1rem;
  overflow: hidden;
  background: var(--color-surface);
}

.history-drawer-shell__bar {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--color-border);
}

.history-drawer-shell__bar h3,
.history-drawer-shell__eyebrow {
  margin: 0;
  overflow-wrap: anywhere;
}

.history-drawer-shell__bar h3 {
  font-size: 1rem;
  line-height: 1.35;
}

.history-drawer-shell__eyebrow {
  margin-bottom: 0.2rem;
  color: var(--color-text-muted);
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.history-drawer-shell__close {
  flex: 0 0 auto;
  min-width: 44px;
  min-height: 44px;
  font-size: 1.35rem;
}

:global(.history-detail-drawer) {
  max-width: 920px;
}

:global(.history-detail-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
}

@media (max-width: 960px) {
  :global(.history-detail-drawer) {
    max-width: none;
  }

  .history-drawer-shell {
    padding: max(0.75rem, env(safe-area-inset-top)) max(0.75rem, env(safe-area-inset-right)) max(0.9rem, env(safe-area-inset-bottom)) max(0.75rem, env(safe-area-inset-left));
  }
}
</style>
