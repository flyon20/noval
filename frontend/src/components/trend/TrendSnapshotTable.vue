<script setup lang="ts">
import { computed } from 'vue';
import type { RankSnapshot } from '@/types/data';
import { formatRankCategoryLabel } from '@/lib/trend-display';

const props = defineProps<{
  snapshots: RankSnapshot[];
}>();

const rows = computed(() =>
  props.snapshots.map((item) => ({
    ...item,
    categoryLabel: formatRankCategoryLabel(item.category),
  })),
);
</script>

<template>
  <article class="trend-snapshot-table" data-test="trend-snapshot-table">
    <header class="trend-snapshot-table__header">
      <h3>最新快照</h3>
      <p>最近抓取到的榜单快照样本，会优先把原始分类码转换成可读中文。</p>
    </header>

    <el-table :data="rows" stripe empty-text="暂无快照数据">
      <el-table-column label="分类" min-width="180">
        <template #default="{ row }">
          <div class="trend-snapshot-table__category">
            <strong>{{ row.categoryLabel }}</strong>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="抓取时间" prop="crawlTime" min-width="180" />
      <el-table-column label="书籍数" prop="bookCount" min-width="120" />
    </el-table>
  </article>
</template>

<style scoped lang="scss">
.trend-snapshot-table {
  display: grid;
  gap: 0.9rem;
  padding: 1rem;
  border-radius: 1.25rem;
  border: 1px solid var(--color-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-soft);
}

.trend-snapshot-table__header,
.trend-snapshot-table__category {
  display: grid;
  gap: 0.25rem;
}

.trend-snapshot-table__header h3,
.trend-snapshot-table__header p {
  margin: 0;
}

.trend-snapshot-table__header p {
  color: var(--color-text-muted);
  line-height: 1.6;
}

.trend-snapshot-table__category strong {
  font-weight: 600;
  overflow-wrap: anywhere;
  word-break: break-word;
}
</style>
