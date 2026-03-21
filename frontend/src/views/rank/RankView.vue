<script setup lang="ts">
import { onMounted, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { crawlerApi } from '@/api/crawler';
import BookDetailDrawer from '@/components/rank/BookDetailDrawer.vue';
import ChapterPreviewDrawer from '@/components/rank/ChapterPreviewDrawer.vue';
import { DEFAULT_PLATFORM, DEFAULT_RANK_CATEGORY } from '@/constants/crawler';
import { getErrorPayload } from '@/lib/http-error';
import type { BookDetail, ChapterItem, KnownRankCategory, RankBookItem, UiChapterCount } from '@/types/crawler';

const router = useRouter();

const filters = reactive({
  platform: DEFAULT_PLATFORM,
  category: DEFAULT_RANK_CATEGORY as KnownRankCategory,
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
  rankList: [] as RankBookItem[],
  selectedBook: null as BookDetail | null,
  chapterPreview: [] as ChapterItem[],
  detailOpen: false,
  chapterOpen: false,
  activeBookId: undefined as number | undefined,
});

async function loadRank() {
  state.listLoading = true;
  state.errorMessage = '';
  state.traceId = '';

  try {
    const response = await crawlerApi.getRank({
      platform: filters.platform,
      category: filters.category,
    });

    state.rankList = response.data.data;
    state.traceId = response.data.traceId;
  } catch (error) {
    const payload = getErrorPayload(error);
    state.errorMessage = payload.message ?? '榜单加载失败';
    state.traceId = payload.traceId ?? '';
  } finally {
    state.listLoading = false;
  }
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

onMounted(() => {
  void loadRank();
});
</script>

<template>
  <section class="rank-page">
    <header class="rank-page__hero">
      <div>
        <p>扫描榜单</p>
        <h1>把榜单、书籍详情和抓章入口放到同一页里</h1>
      </div>
      <span class="rank-page__badge">平台固定：{{ filters.platform }}</span>
    </header>

    <section class="rank-page__card">
      <form class="rank-page__toolbar" @submit.prevent="loadRank">
        <el-form-item label="榜单分类">
          <el-select v-model="filters.category" style="width: 180px">
            <el-option label="男频热门 A" value="male-hot-a" />
            <el-option label="男频热门 B" value="male-hot-b" />
            <el-option label="男频新书 A" value="male-new-a" />
          </el-select>
        </el-form-item>

        <el-form-item label="抓章数量">
          <el-segmented v-model="filters.chapterCount" :options="[1, 3, 5, 10]" />
        </el-form-item>

        <div class="rank-page__toolbar-actions">
          <el-button :loading="state.listLoading" native-type="submit" type="primary">重新扫榜</el-button>
        </div>
      </form>

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
  max-width: 18ch;
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
