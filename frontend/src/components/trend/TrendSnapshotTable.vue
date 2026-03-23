<script setup lang="ts">
import type { RankSnapshot } from '@/types/data';

defineProps<{
  snapshots: RankSnapshot[];
}>();
</script>

<template>
  <article class="trend-snapshot-table" data-test="trend-snapshot-table">
    <header class="trend-snapshot-table__header">
      <h3>最近三次快照</h3>
      <p>按时间倒序展示榜单样本，方便你直接核对最新抓到的数量和代表作品。</p>
    </header>

    <div class="trend-snapshot-table__table-wrap">
      <el-table :data="snapshots" stripe empty-text="暂无快照数据">
        <el-table-column label="快照时间" prop="snapshotTime" min-width="180" />
        <el-table-column label="书籍数" prop="bookCount" min-width="100" />
        <el-table-column label="榜首作品" prop="topBookName" min-width="180" />
        <el-table-column label="作者" prop="topBookAuthor" min-width="140" />
      </el-table>
    </div>
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

.trend-snapshot-table__header {
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

.trend-snapshot-table__table-wrap {
  overflow-x: auto;
}

.trend-snapshot-table__table-wrap :deep(.el-table) {
  min-width: 600px;
}
</style>
