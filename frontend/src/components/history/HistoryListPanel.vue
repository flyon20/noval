<script setup lang="ts">
import type { AnalysisHistorySummary } from '@/types/data';

const props = defineProps<{
  items: AnalysisHistorySummary[];
  loading?: boolean;
  loadingMore?: boolean;
  appendError?: string;
  isMobile?: boolean;
  isCompactDesktop?: boolean;
  page?: number;
  pageSize?: number;
  total?: number;
  hasNext?: boolean;
}>();

const emit = defineEmits<{
  select: [AnalysisHistorySummary];
  pageChange: [number];
  loadMore: [];
}>();

function handleSelect(item: AnalysisHistorySummary) {
  emit('select', item);
}

function analysisTypeLabel(value: string) {
  const labels: Record<string, string> = {
    deconstruct: '拆文',
    structure: '结构',
    plot: '情节',
    theme: '趋势',
  };
  return labels[value] ?? value;
}

function compactMeta(item: AnalysisHistorySummary) {
  return [
    item.channelCode && item.boardCode ? `${item.channelCode} / ${item.boardCode}` : undefined,
    item.chapterCount !== undefined && item.chapterCount !== null ? `${item.chapterCount}章` : undefined,
    item.modelName,
  ].filter(Boolean);
}
</script>

<template>
  <section class="history-list" :data-loading="props.loading ? 'true' : 'false'">
    <div v-if="props.loading" class="history-list__loading">历史记录加载中...</div>

    <template v-else-if="props.isMobile || props.isCompactDesktop">
      <article v-for="item in props.items" :key="item.id" class="history-list__item">
        <button
          class="history-list__trigger"
          type="button"
          :data-test="`history-item-${item.id}`"
          @click="handleSelect(item)"
        >
          <div class="history-list__header">
            <strong>{{ item.bookName ?? '未命名作品' }}</strong>
            <span>{{ analysisTypeLabel(item.analysisType) }}</span>
          </div>
          <p class="history-list__summary">
            模型：{{ item.modelName }} · 章节：{{ item.chapterCount }}
            <template v-if="item.channelCode || item.boardCode"> · {{ item.channelCode }}/{{ item.boardCode }}</template>
          </p>
          <p v-if="item.summaryPreview" class="history-list__preview">{{ item.summaryPreview }}</p>
          <div v-if="item.matchSnippets?.length" class="history-list__matches" data-test="history-match-snippets">
            <span v-for="snippet in item.matchSnippets.slice(0, 2)" :key="snippet">{{ snippet }}</span>
          </div>
          <p class="history-list__meta">{{ item.createdAt }}</p>
        </button>
      </article>

      <el-button
        v-if="props.isMobile && props.hasNext"
        class="history-list__load-more"
        plain
        :loading="props.loadingMore"
        data-test="history-load-more"
        @click="emit('loadMore')"
      >
        加载更多
      </el-button>
      <div v-if="props.appendError" class="history-list__append-error">{{ props.appendError }}</div>
      <div v-if="!props.items.length" class="history-list__empty">暂无历史记录</div>
      <div v-if="!props.isMobile && props.items.length" class="history-list__pagination">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :current-page="props.page ?? 1"
          :page-size="props.pageSize ?? 20"
          :total="props.total ?? 0"
          data-test="history-pagination"
          @current-change="emit('pageChange', $event)"
        />
      </div>
    </template>

    <template v-else>
      <div v-if="props.items.length" class="history-list__table-wrap">
        <el-table
          :data="props.items"
          stripe
          table-layout="auto"
          empty-text="暂无历史记录"
          data-test="history-table"
          @row-click="handleSelect"
        >
          <el-table-column label="作品 / 信息" min-width="260">
            <template #default="{ row }">
              <div class="history-list__book-cell">
                <button
                  class="history-list__table-link"
                  type="button"
                  :data-test="`history-item-${row.id}`"
                  @click.stop="handleSelect(row)"
                >
                  {{ row.bookName ?? '未命名作品' }}
                </button>
                <div class="history-list__chips">
                  <span>{{ analysisTypeLabel(row.analysisType) }}</span>
                  <span v-for="meta in compactMeta(row)" :key="meta">{{ meta }}</span>
                </div>
                <p class="history-list__time">{{ row.createdAt }}</p>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="摘要 / 命中" min-width="420">
            <template #default="{ row }">
              <div class="history-list__text-cell">
                <p v-if="row.summaryPreview" class="history-list__table-preview">{{ row.summaryPreview }}</p>
                <div v-if="row.matchSnippets?.length" class="history-list__table-matches" data-test="history-match-snippets">
                  <span v-for="snippet in row.matchSnippets.slice(0, 3)" :key="snippet">{{ snippet }}</span>
                </div>
                <span v-if="!row.summaryPreview && !row.matchSnippets?.length">-</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="108" align="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :data-test="`history-preview-${row.id}`"
                @click.stop="handleSelect(row)"
              >
                预览
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else class="history-list__empty">暂无历史记录</div>

      <div class="history-list__pagination">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :current-page="props.page ?? 1"
          :page-size="props.pageSize ?? 20"
          :total="props.total ?? 0"
          data-test="history-pagination"
          @current-change="emit('pageChange', $event)"
        />
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.history-list {
  display: grid;
  gap: 0.8rem;
}

.history-list__item {
  border: 1px solid var(--color-border);
  border-radius: 1.1rem;
  background:
    linear-gradient(
      155deg,
      color-mix(in srgb, var(--color-surface-strong) 98%, transparent),
      color-mix(in srgb, var(--color-surface) 94%, transparent)
    );
  box-shadow: var(--shadow-soft);
  color: var(--color-text);
}

.history-list__trigger {
  width: 100%;
  min-height: 44px;
  display: grid;
  gap: 0.45rem;
  padding: 1rem;
  border: 0;
  border-radius: inherit;
  background: transparent;
  text-align: left;
  font: inherit;
  color: inherit;
  cursor: pointer;
  touch-action: manipulation;
}

.history-list__trigger:hover {
  background: color-mix(in srgb, var(--color-primary-soft) 76%, transparent);
}

.history-list__header {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.1rem;
  font-size: 1rem;
  min-width: 0;
  overflow-wrap: anywhere;
}

.history-list__summary,
.history-list__preview,
.history-list__meta {
  margin: 0;
  overflow-wrap: anywhere;
}

.history-list__summary,
.history-list__preview,
.history-list__meta {
  color: var(--color-text-muted);
}

.history-list__preview {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.55;
}

.history-list__matches {
  display: grid;
  gap: 0.35rem;
}

.history-list__matches span,
.history-list__table-matches span {
  display: inline-block;
  max-width: 100%;
  color: var(--color-text);
  background: color-mix(in srgb, var(--color-primary-soft) 70%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-primary) 20%, transparent);
  border-radius: 0.6rem;
  padding: 0.3rem 0.45rem;
  line-height: 1.45;
  white-space: normal;
  overflow-wrap: anywhere;
}

.history-list__table-wrap {
  border: 1px solid var(--color-border);
  border-radius: 1rem;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
}

.history-list__book-cell,
.history-list__text-cell {
  min-width: 0;
  display: grid;
  gap: 0.45rem;
  overflow-wrap: anywhere;
  white-space: normal;
}

.history-list__table-link {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--color-primary);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.45;
}

.history-list__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.history-list__chips span {
  border: 1px solid color-mix(in srgb, var(--color-border) 78%, transparent);
  border-radius: 999px;
  padding: 0.12rem 0.45rem;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  line-height: 1.45;
}

.history-list__time,
.history-list__table-preview {
  margin: 0;
}

.history-list__time {
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.history-list__table-preview {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  color: var(--color-text);
  line-height: 1.6;
}

.history-list__table-matches {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.history-list__pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 0.25rem;
}

.history-list__load-more {
  width: 100%;
}

.history-list__append-error {
  color: var(--color-danger);
  font-size: 0.9rem;
  text-align: center;
}

.history-list__loading,
.history-list__empty {
  padding: 2rem;
  text-align: center;
  color: var(--color-text-muted);
}
</style>
