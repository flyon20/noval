<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { crawlerApi } from '@/api/crawler';
import BookDetailDrawer from '@/components/rank/BookDetailDrawer.vue';
import ChapterPreviewDrawer from '@/components/rank/ChapterPreviewDrawer.vue';
import { CHAPTER_COUNT_OPTIONS, DEFAULT_PLATFORM, DEFAULT_RANK_PAGE_SIZE } from '@/constants/crawler';
import { getErrorPayload } from '@/lib/http-error';
import type {
  BookDetail,
  ChapterItem,
  RankBoardCatalog,
  RankBoardOption,
  RankBookItem,
  RankPageResult,
  RankRefreshResult,
  UiChapterCount,
} from '@/types/crawler';

const router = useRouter();

const filters = reactive({
  platform: DEFAULT_PLATFORM,
  channelCode: '',
  boardCode: '',
  chapterCount: 3 as UiChapterCount,
});

const state = reactive({
  listLoading: false,
  detailLoading: false,
  chapterLoading: false,
  errorMessage: '',
  traceId: '',
  detailTraceId: '',
  chapterTraceId: '',
  boardCatalog: [] as RankBoardCatalog[],
  rankList: [] as RankBookItem[],
  selectedBook: null as BookDetail | null,
  chapterPreview: [] as ChapterItem[],
  detailOpen: false,
  chapterOpen: false,
  activeBookId: undefined as number | undefined,
  page: 1,
  pageSize: DEFAULT_RANK_PAGE_SIZE,
  total: 0,
  snapshotId: undefined as number | undefined,
  snapshotTime: '',
  refreshInfo: null as RankRefreshResult | null,
});

const channelOptions = computed(() => state.boardCatalog);

const boardOptions = computed<RankBoardOption[]>(() => {
  return state.boardCatalog.find((item) => item.channelCode === filters.channelCode)?.boards ?? [];
});

const selectedChannelName = computed(() => {
  return state.boardCatalog.find((item) => item.channelCode === filters.channelCode)?.channelName ?? '未选择频道';
});

const selectedBoardName = computed(() => {
  return boardOptions.value.find((item) => item.boardCode === filters.boardCode)?.boardName ?? '未选择榜单';
});

const refreshStatusText = computed(() => {
  if (!state.refreshInfo) {
    return '等待加载';
  }
  if (state.refreshInfo.refreshLimited) {
    return '已命中抓取限制，复用最近快照';
  }
  return state.refreshInfo.reused ? '已复用缓存快照' : '已刷新最新整榜';
});

async function initializePage() {
  state.listLoading = true;
  state.errorMessage = '';
  state.traceId = '';

  try {
    const response = await crawlerApi.getBoards({
      platform: filters.platform,
    });
    state.boardCatalog = response.data.data;
    state.traceId = response.data.traceId;

    const firstChannel = state.boardCatalog[0];
    const firstBoard = firstChannel?.boards[0];
    if (!firstChannel || !firstBoard) {
      state.rankList = [];
      state.total = 0;
      return;
    }

    filters.channelCode = firstChannel.channelCode;
    filters.boardCode = firstBoard.boardCode;
    await refreshCurrentBoard('AUTO');
  } catch (error) {
    applyListError(error, '榜单目录加载失败');
  } finally {
    state.listLoading = false;
  }
}

async function refreshCurrentBoard(refreshMode: 'AUTO' | 'FORCE') {
  if (!filters.channelCode || !filters.boardCode) {
    return;
  }

  state.listLoading = true;
  state.errorMessage = '';

  try {
    const refreshResponse = await crawlerApi.refreshRankBoard({
      platform: filters.platform,
      channelCode: filters.channelCode,
      boardCode: filters.boardCode,
      refreshMode,
    });
    state.refreshInfo = refreshResponse.data.data;
    state.traceId = refreshResponse.data.traceId;
    state.page = 1;
    await fetchRankPage(1, true);
  } catch (error) {
    applyListError(error, '榜单抓取失败');
  } finally {
    state.listLoading = false;
  }
}

async function fetchRankPage(page: number, keepLoading = false) {
  if (!filters.channelCode || !filters.boardCode) {
    return;
  }

  if (!keepLoading) {
    state.listLoading = true;
    state.errorMessage = '';
  }

  try {
    const response = await crawlerApi.getRankPage({
      platform: filters.platform,
      channelCode: filters.channelCode,
      boardCode: filters.boardCode,
      page,
      pageSize: state.pageSize,
    });
    applyPageResult(response.data.data);
    state.traceId = response.data.traceId;
  } catch (error) {
    applyListError(error, '榜单分页加载失败');
  } finally {
    if (!keepLoading) {
      state.listLoading = false;
    }
  }
}

async function handleChannelChange(channelCode: string) {
  const nextChannel = state.boardCatalog.find((item) => item.channelCode === channelCode);
  const nextBoard = nextChannel?.boards[0];
  filters.channelCode = channelCode;
  filters.boardCode = nextBoard?.boardCode ?? '';
  await refreshCurrentBoard('AUTO');
}

async function handleBoardChange(boardCode: string) {
  filters.boardCode = boardCode;
  await refreshCurrentBoard('AUTO');
}

async function handlePageChange(page: number) {
  await fetchRankPage(page);
}

async function openDetail(row: RankBookItem) {
  state.detailOpen = true;
  state.detailLoading = true;
  state.detailTraceId = '';

  try {
    const response = await crawlerApi.getBookDetail(row.bookId, row.platform);
    state.selectedBook = response.data.data;
    state.detailTraceId = response.data.traceId;
  } catch (error) {
    const payload = getErrorPayload(error);
    state.selectedBook = null;
    state.detailTraceId = payload.traceId ?? '';
  } finally {
    state.detailLoading = false;
  }
}

async function openChapters(row: RankBookItem) {
  state.chapterOpen = true;
  state.chapterLoading = true;
  state.chapterTraceId = '';
  state.activeBookId = row.bookId;

  try {
    const response = await crawlerApi.getChapters({
      platform: row.platform,
      bookId: row.bookId,
      chapterCount: filters.chapterCount,
    });

    state.chapterPreview = response.data.data;
    state.chapterTraceId = response.data.traceId;
  } catch (error) {
    const payload = getErrorPayload(error);
    state.chapterPreview = [];
    state.chapterTraceId = payload.traceId ?? '';
  } finally {
    state.chapterLoading = false;
  }
}

async function goAnalysis() {
  if (!state.activeBookId) {
    return;
  }

  await router.push({
    path: '/analysis',
    query: {
      bookId: String(state.activeBookId),
      platform: filters.platform,
      chapterCount: String(filters.chapterCount),
    },
  });
}

function applyPageResult(pageResult: RankPageResult) {
  state.rankList = pageResult.items;
  state.page = pageResult.page;
  state.pageSize = pageResult.pageSize;
  state.total = pageResult.total;
  state.snapshotId = pageResult.snapshotId;
  state.snapshotTime = pageResult.snapshotTime ?? '';
}

function applyListError(error: unknown, fallbackMessage: string) {
  const payload = getErrorPayload(error);
  state.errorMessage = payload.message ?? fallbackMessage;
  state.traceId = payload.traceId ?? '';
  state.rankList = [];
}

onMounted(() => {
  void initializePage();
});
</script>

<template>
  <section class="rank-page">
    <header class="rank-page__hero">
      <div>
        <p>扫描榜单</p>
        <h1>按频道和榜单整榜抓取，翻页只读取数据库快照</h1>
      </div>
      <span class="rank-page__badge">{{ selectedChannelName }} / {{ selectedBoardName }}</span>
    </header>

    <section class="rank-page__card">
      <div class="rank-page__toolbar">
        <el-form-item label="频道">
          <el-select
            v-model="filters.channelCode"
            style="width: 180px"
            @change="handleChannelChange"
          >
            <el-option
              v-for="channel in channelOptions"
              :key="channel.channelCode"
              :label="channel.channelName"
              :value="channel.channelCode"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="榜单">
          <el-select
            v-model="filters.boardCode"
            style="width: 200px"
            @change="handleBoardChange"
          >
            <el-option
              v-for="board in boardOptions"
              :key="board.boardCode"
              :label="board.boardName"
              :value="board.boardCode"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="抓章数量">
          <el-segmented v-model="filters.chapterCount" :options="CHAPTER_COUNT_OPTIONS" />
        </el-form-item>

        <div class="rank-page__toolbar-actions">
          <el-button
            data-testid="rank-force-refresh"
            :loading="state.listLoading"
            type="primary"
            @click="refreshCurrentBoard('FORCE')"
          >
            重新抓取
          </el-button>
        </div>
      </div>

      <div class="rank-page__summary">
        <span>快照ID：{{ state.snapshotId ?? '-' }}</span>
        <span>更新时间：{{ state.snapshotTime || '-' }}</span>
        <span>总书数：{{ state.total }}</span>
        <span>{{ refreshStatusText }}</span>
      </div>

      <el-alert
        v-if="state.errorMessage"
        :closable="false"
        :description="state.traceId ? `traceId: ${state.traceId}` : undefined"
        :title="state.errorMessage"
        class="rank-page__alert"
        type="error"
      />

      <el-table
        :data="state.rankList"
        :empty-text="state.listLoading ? '加载中...' : '暂无榜单数据'"
        :loading="state.listLoading"
        stripe
      >
        <el-table-column label="#" prop="rankNo" width="72" />
        <el-table-column label="书名" min-width="220" prop="bookName" />
        <el-table-column label="作者" min-width="140" prop="author" />
        <el-table-column label="简介" min-width="320">
          <template #default="{ row }">
            <div class="rank-page__intro">{{ row.intro }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" label="操作" width="220">
          <template #default="{ row }">
            <div class="rank-page__actions">
              <el-button
                :data-testid="`rank-detail-${row.bookId}`"
                link
                type="primary"
                @click="openDetail(row)"
              >
                详情
              </el-button>
              <el-button
                :data-testid="`rank-chapters-${row.bookId}`"
                link
                type="primary"
                @click="openChapters(row)"
              >
                抓章
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="state.total > 0"
        :current-page="state.page"
        :page-size="state.pageSize"
        :total="state.total"
        background
        layout="prev, pager, next"
        @current-change="handlePageChange"
      />
    </section>

    <BookDetailDrawer
      v-model="state.detailOpen"
      :detail="state.selectedBook"
      :loading="state.detailLoading"
      :trace-id="state.detailTraceId"
    />

    <ChapterPreviewDrawer
      v-model="state.chapterOpen"
      :book-id="state.activeBookId"
      :chapter-count="filters.chapterCount"
      :chapters="state.chapterPreview"
      :loading="state.chapterLoading"
      :platform="filters.platform"
      :trace-id="state.chapterTraceId"
      @go-analysis="goAnalysis"
    />
  </section>
</template>

<style scoped lang="scss">
.rank-page {
  display: grid;
  gap: 1.5rem;
}

.rank-page__hero,
.rank-page__card {
  border: 1px solid var(--color-border);
  border-radius: 1.35rem;
  background: rgba(255, 255, 255, 0.74);
  box-shadow: var(--shadow-soft);
}

.rank-page__hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.5rem 1.75rem;
  background:
    radial-gradient(circle at top right, rgba(210, 136, 61, 0.15), transparent 25%),
    linear-gradient(180deg, rgba(255, 251, 245, 0.96), rgba(248, 244, 236, 0.92));
}

.rank-page__hero p,
.rank-page__hero h1 {
  margin: 0;
}

.rank-page__hero p {
  color: var(--color-text-muted);
}

.rank-page__hero h1 {
  max-width: 16ch;
  margin-top: 0.35rem;
  font-size: clamp(1.8rem, 3vw, 2.7rem);
  line-height: 1.15;
}

.rank-page__badge {
  padding: 0.55rem 0.85rem;
  border: 1px solid rgba(35, 65, 58, 0.16);
  border-radius: 999px;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.rank-page__card {
  display: grid;
  gap: 1rem;
  padding: 1.25rem;
}

.rank-page__toolbar {
  display: flex;
  align-items: flex-end;
  gap: 1rem;
  flex-wrap: wrap;
}

.rank-page__toolbar :deep(.el-form-item) {
  margin-bottom: 0;
}

.rank-page__toolbar-actions {
  margin-left: auto;
}

.rank-page__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1rem;
  color: var(--color-text-muted);
  font-size: 0.92rem;
}

.rank-page__alert {
  margin-bottom: 0.25rem;
}

.rank-page__intro {
  color: var(--color-text-muted);
  line-height: 1.7;
}

.rank-page__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.25rem;
}

@media (max-width: 920px) {
  .rank-page__hero {
    flex-direction: column;
  }

  .rank-page__toolbar-actions {
    margin-left: 0;
  }
}
</style>
