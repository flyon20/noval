<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { dataApi } from '@/api/data';
import HistoryDetailPanel from '@/components/history/HistoryDetailPanel.vue';
import HistoryFilterBar from '@/components/history/HistoryFilterBar.vue';
import HistoryListPanel from '@/components/history/HistoryListPanel.vue';
import type { AnalysisHistoryItem } from '@/types/data';

const historyItems = ref<AnalysisHistoryItem[]>([]);
const selectedItem = ref<AnalysisHistoryItem | null>(null);
const loading = ref(false);
const errorMessage = ref('');
const detailDrawerVisible = ref(false);
const windowWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth);

const queryState = reactive({
  platform: 'fanqie' as const,
  analysisType: undefined as AnalysisHistoryItem['analysisType'] | undefined,
  bookId: undefined as number | undefined,
  limit: 20,
});

const isMobile = computed(() => windowWidth.value < 960);

type HistoryQuery = {
  platform: 'fanqie';
  analysisType?: AnalysisHistoryItem['analysisType'];
  bookId?: number;
  limit: number;
};

function buildHistoryQuery(): HistoryQuery {
  const payload: HistoryQuery = {
    platform: queryState.platform,
    limit: queryState.limit,
  };

  if (queryState.analysisType) {
    payload.analysisType = queryState.analysisType;
  }

  if (typeof queryState.bookId === 'number' && !Number.isNaN(queryState.bookId)) {
    payload.bookId = queryState.bookId;
  }

  return payload;
}

function updateWindowWidth() {
  windowWidth.value = window.innerWidth;

  if (!isMobile.value) {
    detailDrawerVisible.value = false;
  }
}

async function loadHistory() {
  loading.value = true;
  errorMessage.value = '';

  try {
    const response = await dataApi.getHistory(buildHistoryQuery());
    const list = response.data.data ?? [];

    historyItems.value = list;

    if (!list.length) {
      selectedItem.value = null;
      detailDrawerVisible.value = false;
      return;
    }

    const nextSelected =
      list.find((item) => item.id === selectedItem.value?.id) ??
      list[0] ??
      null;

    selectedItem.value = nextSelected;
  } catch {
    errorMessage.value = '历史记录加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

function handleFilter(payload: {
  analysisType?: AnalysisHistoryItem['analysisType'];
  bookId?: number;
  limit?: number;
}) {
  queryState.analysisType = payload.analysisType;
  queryState.bookId = payload.bookId;
  queryState.limit = payload.limit ?? 20;
  void loadHistory();
}

function handleSelect(item: AnalysisHistoryItem) {
  selectedItem.value = item;

  if (isMobile.value) {
    detailDrawerVisible.value = true;
  }
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
    <HistoryFilterBar :loading="loading" :default-limit="queryState.limit" @filter="handleFilter" />

    <div class="history-grid">
      <div class="history-grid__list">
        <HistoryListPanel
          v-if="!errorMessage"
          :loading="loading"
          :items="historyItems"
          @select="handleSelect"
        />
        <div v-else class="history-error">{{ errorMessage }}</div>
      </div>

      <div v-if="!isMobile" class="history-grid__detail">
        <HistoryDetailPanel :item="selectedItem" />
      </div>
    </div>

    <el-drawer
      v-model="detailDrawerVisible"
      size="70%"
      direction="btt"
      :with-header="false"
    >
      <HistoryDetailPanel :item="selectedItem" />
    </el-drawer>
  </section>
</template>

<style scoped lang="scss">
.history-page {
  display: grid;
  gap: 1rem;
}

.history-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 0.95fr);
  gap: 1rem;
  align-items: start;
}

.history-grid__detail {
  max-height: calc(100vh - 220px);
}

.history-error {
  padding: 1.5rem;
  border-radius: 1.25rem;
  border: 1px solid rgba(191, 83, 54, 0.2);
  background: rgba(255, 246, 243, 0.88);
  color: var(--color-danger);
}

@media (max-width: 960px) {
  .history-grid {
    grid-template-columns: 1fr;
  }

  .history-grid__detail {
    display: none;
  }
}
</style>
