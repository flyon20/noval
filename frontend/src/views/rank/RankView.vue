<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { crawlerApi } from '@/api/crawler';
import BookDetailDrawer from '@/components/rank/BookDetailDrawer.vue';
import ChapterPreviewDrawer from '@/components/rank/ChapterPreviewDrawer.vue';
import {
  CHAPTER_COUNT_OPTIONS,
  DEFAULT_PLATFORM,
  DEFAULT_RANK_PAGE_SIZE,
  RANK_PAGE_SIZE_OPTIONS,
} from '@/constants/crawler';
import { getErrorPayload } from '@/lib/http-error';
import type {
  BookDetail,
  ChapterItem,
  ChapterRefreshResult,
  RankBoardCatalog,
  RankBoardOption,
  RankBookItem,
  RankPageResult,
  RankRefreshResult,
  UiChapterCount,
} from '@/types/crawler';

const INTRO_PREVIEW_LENGTH = 100;

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
  chapterRefreshLoading: false,
  errorMessage: '',
  traceId: '',
  detailTraceId: '',
  chapterTraceId: '',
  boardCatalog: [] as RankBoardCatalog[],
  rankList: [] as RankBookItem[],
  selectedBook: null as BookDetail | null,
  chapterPreview: [] as ChapterItem[],
  chapterRefreshSummary: null as ChapterRefreshResult | null,
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

const totalPages = computed(() => {
  if (!state.total || !state.pageSize) {
    return 0;
  }
  return Math.ceil(state.total / state.pageSize);
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
    return '命中刷新限制，已复用最近快照';
  }
  return state.refreshInfo.reused ? '当前展示缓存快照' : '当前展示最新整榜';
});

function truncateText(content: string, maxLength: number) {
  const normalized = content.replace(/\s+/g, ' ').trim();
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return `${normalized.slice(0, maxLength)}...`;
}

function getIntroPreview(content: string) {
  return truncateText(content || '', INTRO_PREVIEW_LENGTH);
}

async function initializePage() {
  state.listLoading = true;
  state.errorMessage = '';
  state.traceId = '';

  try {
    const [response, preference] = await Promise.all([
      crawlerApi.getBoards({
        platform: filters.platform,
      }),
      loadUserPreference(),
    ]);
    state.boardCatalog = response.data.data;
    state.traceId = response.data.traceId;

    const firstChannel = state.boardCatalog[0];
    const firstBoard = firstChannel?.boards[0];
    if (!firstChannel || !firstBoard) {
      state.rankList = [];
      state.total = 0;
      return;
    }

    const preferredChannel = preference
      ? state.boardCatalog.find((item) => item.channelCode === preference.channelCode)
      : null;
    const preferredBoard = preferredChannel
      ? preferredChannel.boards.find((item) => item.boardCode === preference.boardCode)
      : null;
    filters.channelCode = preferredChannel?.channelCode ?? firstChannel.channelCode;
    filters.boardCode = preferredBoard?.boardCode ?? preferredChannel?.boards[0]?.boardCode ?? firstBoard.boardCode;
    await loadCurrentBoard();
  } catch (error) {
    applyListError(error, '榜单目录加载失败');
  } finally {
    state.listLoading = false;
  }
}

async function loadCurrentBoard() {
  if (!filters.channelCode || !filters.boardCode) {
    return;
  }

  state.listLoading = true;
  state.errorMessage = '';

  try {
    const response = await crawlerApi.getRankPage({
      platform: filters.platform,
      channelCode: filters.channelCode,
      boardCode: filters.boardCode,
      page: 1,
      pageSize: state.pageSize,
    });
    const pageResult = response.data.data;
    applyPageResult(pageResult);
    state.traceId = response.data.traceId;
    state.refreshInfo = {
      channelCode: filters.channelCode,
      boardCode: filters.boardCode,
      snapshotId: pageResult.snapshotId,
      snapshotTime: pageResult.snapshotTime,
      total: pageResult.total,
      reused: true,
      refreshLimited: false,
      analysisTriggered: false,
    };
    state.errorMessage = '';
  } catch (error) {
    if (isSnapshotMissingError(error)) {
      await refreshCurrentBoard('AUTO');
      return;
    }
    applyListError(error, '姒滃崟鍒嗛〉鍔犺浇澶辫触');
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
  await saveUserPreference();
  await loadCurrentBoard();
}

async function handleBoardChange(boardCode: string) {
  filters.boardCode = boardCode;
  await saveUserPreference();
  await loadCurrentBoard();
}

async function handlePageChange(page: number) {
  await fetchRankPage(page);
}

async function handlePageSizeChange(pageSize: number) {
  if (state.pageSize === pageSize) {
    return;
  }
  state.pageSize = pageSize;
  state.page = 1;
  await fetchRankPage(1);
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
  state.chapterRefreshSummary = null;

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

async function refreshChapters() {
  if (!state.activeBookId) {
    return;
  }

  state.chapterRefreshLoading = true;
  try {
    const response = await crawlerApi.refreshChapters({
      platform: filters.platform,
      bookId: state.activeBookId,
      chapterCount: filters.chapterCount,
    });
    state.chapterPreview = response.data.data.chapters;
    state.chapterRefreshSummary = response.data.data;
    state.chapterTraceId = response.data.traceId;
    ElMessage.success('章节已重新抓取并更新缓存');
  } catch (error) {
    const payload = getErrorPayload(error);
    state.chapterTraceId = payload.traceId ?? '';
    ElMessage.error(payload.message ?? '重新抓取章节失败');
  } finally {
    state.chapterRefreshLoading = false;
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

function isSnapshotMissingError(error: unknown) {
  return getErrorPayload(error).code === 404;
}

async function loadUserPreference() {
  try {
    const response = await crawlerApi.getPreference({
      platform: filters.platform,
    });
    return response.data.data;
  } catch {
    return null;
  }
}

async function saveUserPreference() {
  if (!filters.channelCode || !filters.boardCode) {
    return;
  }
  try {
    await crawlerApi.savePreference({
      platform: filters.platform,
      channelCode: filters.channelCode,
      boardCode: filters.boardCode,
    });
  } catch {
    // Ignore preference write failures to avoid blocking rank browsing.
  }
}

onMounted(() => {
  void initializePage();
});
</script>

<template>
  <section class="rank-page">
    <header class="rank-page__hero">
      <div class="rank-page__hero-copy">
        <p class="rank-page__eyebrow">Rank Workspace</p>
        <h1>扫榜页</h1>
        <p class="rank-page__summary-copy">
          查看当前榜单、分页浏览并进入详情。
        </p>
      </div>
      <div class="rank-page__hero-badge">
        <span>{{ selectedChannelName }}</span>
        <strong>{{ selectedBoardName }}</strong>
      </div>
    </header>

    <section class="rank-page__panel">
      <div class="rank-page__toolbar">
        <div class="rank-page__toolbar-group">
          <label class="rank-page__label">频道</label>
          <el-select
            v-model="filters.channelCode"
            class="rank-page__select"
            @change="handleChannelChange"
          >
            <el-option
              v-for="channel in channelOptions"
              :key="channel.channelCode"
              :label="channel.channelName"
              :value="channel.channelCode"
            />
          </el-select>
        </div>

        <div class="rank-page__toolbar-group">
          <label class="rank-page__label">榜单</label>
          <el-select
            v-model="filters.boardCode"
            class="rank-page__select rank-page__select--wide"
            @change="handleBoardChange"
          >
            <el-option
              v-for="board in boardOptions"
              :key="board.boardCode"
              :label="board.boardName"
              :value="board.boardCode"
            />
          </el-select>
        </div>

        <div class="rank-page__toolbar-group">
          <label class="rank-page__label">抓章数量</label>
          <el-segmented v-model="filters.chapterCount" :options="CHAPTER_COUNT_OPTIONS" />
        </div>

        <div class="rank-page__toolbar-group">
          <label class="rank-page__label">每页显示</label>
          <div class="rank-page__page-size" data-testid="rank-page-size">
            <button
              v-for="option in RANK_PAGE_SIZE_OPTIONS"
              :key="option"
              class="rank-page__page-size-button"
              :class="{ 'is-active': state.pageSize === option }"
              :data-testid="`rank-page-size-${option}`"
              type="button"
              @click="handlePageSizeChange(option)"
            >
              {{ option }}
            </button>
          </div>
        </div>

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

      <div class="rank-page__snapshot">
        <article class="rank-page__snapshot-card">
          <span>快照 ID</span>
          <strong>{{ state.snapshotId ?? '-' }}</strong>
        </article>
        <article class="rank-page__snapshot-card">
          <span>更新时间</span>
          <strong>{{ state.snapshotTime || '-' }}</strong>
        </article>
        <article class="rank-page__snapshot-card">
          <span>总书数</span>
          <strong>{{ state.total }}</strong>
        </article>
        <article class="rank-page__snapshot-card">
          <span>当前状态</span>
          <strong>{{ refreshStatusText }}</strong>
        </article>
      </div>

      <el-alert
        v-if="state.errorMessage"
        :closable="false"
        :description="state.traceId ? `traceId: ${state.traceId}` : undefined"
        :title="state.errorMessage"
        class="rank-page__alert"
        type="error"
      />

      <div v-if="state.listLoading" class="rank-page__skeletons">
        <el-skeleton v-for="item in state.pageSize" :key="item" animated :rows="3" />
      </div>

      <div v-else class="rank-page__list">
        <article
          v-for="row in state.rankList"
          :key="row.bookId"
          class="rank-page__item"
        >
          <div class="rank-page__item-rank" :class="{ 'is-top3': row.rankNo <= 3 }">
            <span class="rank-page__item-rank-number">#{{ row.rankNo }}</span>
          </div>

          <div class="rank-page__item-main">
            <div class="rank-page__item-title">
              <h3>{{ row.bookName }}</h3>
              <p>{{ row.author }}</p>
            </div>
            <p class="rank-page__item-intro">
              {{ getIntroPreview(row.intro) }}
            </p>
          </div>

          <div class="rank-page__item-actions">
            <el-button
              :data-testid="`rank-detail-${row.bookId}`"
              plain
              type="primary"
              @click="openDetail(row)"
            >
              详情
            </el-button>
            <el-button
              :data-testid="`rank-chapters-${row.bookId}`"
              type="primary"
              @click="openChapters(row)"
            >
              抓章
            </el-button>
          </div>
        </article>
      </div>

      <div v-if="state.total > 0" class="rank-page__pagination">
        <span class="rank-page__pagination-meta">第 {{ state.page }} / {{ totalPages || 1 }} 页</span>
        <el-pagination
          :current-page="state.page"
          :page-size="state.pageSize"
          :total="state.total"
          background
          layout="prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
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
      :refresh-loading="state.chapterRefreshLoading"
      :refresh-summary="state.chapterRefreshSummary"
      :platform="filters.platform"
      :trace-id="state.chapterTraceId"
      @go-analysis="goAnalysis"
      @refresh-chapters="refreshChapters"
    />
  </section>
</template>

<style scoped lang="scss">
.rank-page {
  display: grid;
  gap: 1.5rem;
}

.rank-page__hero,
.rank-page__panel {
  border: 1px solid var(--color-border);
  border-radius: 1.5rem;
  background: rgba(255, 252, 247, 0.9);
  box-shadow: var(--shadow-soft);
}

.rank-page__hero {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(220px, 0.8fr);
  gap: 1rem;
  padding: 1.65rem 1.8rem;
  background:
    radial-gradient(circle at top right, rgba(183, 135, 87, 0.18), transparent 24%),
    linear-gradient(135deg, rgba(255, 252, 247, 0.98), rgba(244, 236, 225, 0.92));
}

.rank-page__hero-copy,
.rank-page__hero-badge {
  display: grid;
  gap: 0.45rem;
}

.rank-page__eyebrow,
.rank-page__hero-copy h1,
.rank-page__summary-copy {
  margin: 0;
}

.rank-page__eyebrow {
  color: var(--color-accent-strong);
  font-size: 0.78rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.rank-page__hero-copy h1 {
  font-size: clamp(2rem, 3vw, 2.8rem);
  line-height: 1.15;
}

.rank-page__summary-copy {
  max-width: 46rem;
  color: var(--color-text-muted);
  line-height: 1.8;
}

.rank-page__hero-badge {
  align-content: start;
  justify-items: end;
  padding: 1rem 1.15rem;
  border: 1px solid rgba(35, 65, 58, 0.12);
  border-radius: 1.2rem;
  background: rgba(255, 255, 255, 0.56);
}

.rank-page__hero-badge span {
  color: var(--color-text-muted);
  font-size: 0.82rem;
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

.rank-page__hero-badge strong {
  font-size: 1.15rem;
  text-align: right;
}

.rank-page__panel {
  display: grid;
  gap: 1.25rem;
  padding: 1.4rem;
}

.rank-page__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  align-items: end;
}

.rank-page__toolbar-group {
  display: grid;
  gap: 0.45rem;
}

.rank-page__label {
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.rank-page__select {
  width: 180px;
}

.rank-page__select--wide {
  width: 220px;
}

.rank-page__page-size {
  display: inline-flex;
  gap: 0.4rem;
  padding: 0.25rem;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
}

.rank-page__page-size-button {
  min-width: 46px;
  padding: 0.55rem 0.9rem;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: 160ms ease;
}

.rank-page__page-size-button.is-active {
  background: rgba(35, 65, 58, 0.92);
  color: #fff;
}

.rank-page__toolbar-actions {
  margin-left: auto;
}

.rank-page__snapshot {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.9rem;
}

.rank-page__snapshot-card {
  display: grid;
  gap: 0.35rem;
  padding: 1rem 1.05rem;
  border: 1px solid rgba(35, 65, 58, 0.1);
  border-radius: 1.15rem;
  background: rgba(255, 255, 255, 0.72);
}

.rank-page__snapshot-card span {
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.rank-page__snapshot-card strong {
  font-size: 1rem;
}

.rank-page__list,
.rank-page__skeletons {
  display: grid;
  gap: 1rem;
}

.rank-page__item {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) auto;
  gap: 1rem;
  align-items: start;
  padding: 1.15rem 1.2rem;
  border: 1px solid rgba(35, 65, 58, 0.12);
  border-radius: 1.25rem;
  background: rgba(255, 255, 255, 0.72);
  transition: transform 180ms ease, border-color 180ms ease, box-shadow 180ms ease;
}

.rank-page__item:hover {
  transform: translateY(-2px);
  border-color: rgba(35, 65, 58, 0.2);
  box-shadow: var(--shadow-soft);
}

.rank-page__item-rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 56px;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.08);
  color: var(--color-accent-strong);
  font-weight: 700;
  transition: background 200ms ease;
}

.rank-page__item-rank.is-top3 {
  background: linear-gradient(135deg, rgba(199, 146, 92, 0.22), rgba(199, 146, 92, 0.08));
  border: 1px solid rgba(199, 146, 92, 0.3);
}

.rank-page__item-rank.is-top3 .rank-page__item-rank-number {
  color: var(--color-accent-strong);
  font-size: 1.3rem;
}

.rank-page__item-rank-number {
  font-family: var(--font-heading);
  font-size: 1.1rem;
  line-height: 1;
}

.rank-page__item-main {
  display: grid;
  gap: 0.55rem;
  min-width: 0;
}

.rank-page__item-title {
  min-width: 0;
}

.rank-page__item-title h3,
.rank-page__item-title p,
.rank-page__item-intro {
  margin: 0;
}

.rank-page__item-title h3 {
  color: var(--color-text);
  font-size: 1.08rem;
  line-height: 1.35;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.rank-page__item-title p {
  margin-top: 0.2rem;
  color: var(--color-text-muted);
  overflow-wrap: anywhere;
}

.rank-page__item-intro {
  color: var(--color-text-muted);
  line-height: 1.75;
}

.rank-page__item-actions {
  display: grid;
  gap: 0.65rem;
}

.rank-page__pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.rank-page__pagination-meta {
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.rank-page__alert {
  margin-bottom: 0.2rem;
}

@media (max-width: 1100px) {
  .rank-page__hero {
    grid-template-columns: 1fr;
  }

  .rank-page__snapshot {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .rank-page__item {
    grid-template-columns: auto 1fr;
    grid-template-rows: auto auto;
  }

  .rank-page__item-rank {
    grid-row: 1;
    grid-column: 1;
  }

  .rank-page__item-main {
    grid-row: 1;
    grid-column: 2;
  }

  .rank-page__item-actions {
    grid-row: 2;
    grid-column: 1 / -1;
    display: flex;
    gap: 0.5rem;
  }

  .rank-page__item-actions .el-button {
    flex: 1;
  }

  .rank-page__pagination {
    flex-direction: column;
    align-items: stretch;
    gap: 0.75rem;
  }

  .rank-page__pagination-meta {
    text-align: center;
  }

  .rank-page__toolbar {
    gap: 0.75rem;
  }

  .rank-page__toolbar-group {
    flex: 1 1 calc(50% - 0.375rem);
    min-width: 0;
  }

  .rank-page__select,
  .rank-page__select--wide {
    width: 100%;
  }

  .rank-page__toolbar-actions {
    margin-left: 0;
    width: 100%;
  }

  .rank-page__toolbar-actions .el-button {
    width: 100%;
  }

  .rank-page__snapshot {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
