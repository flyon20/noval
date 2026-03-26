<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { RankSnapshot } from '@/types/data';

const props = withDefaults(
  defineProps<{
    snapshots: RankSnapshot[];
    sampleCount?: number;
  }>(),
  {
    sampleCount: 0,
  },
);

const availableCount = computed(() => {
  return props.sampleCount > 0 ? props.sampleCount : props.snapshots.length;
});
const isMobileViewport = ref(false);

const snapshotTitle = computed(() => {
  return availableCount.value > 0 ? `最近${availableCount.value}次快照` : '最近快照';
});

const snapshotDescription = computed(() => {
  if (availableCount.value > 0) {
    return `当前 ${availableCount.value} 次快照`;
  }

  return '暂无快照';
});

function syncViewportMode() {
  isMobileViewport.value = window.innerWidth <= 760;
}

onMounted(() => {
  syncViewportMode();
  window.addEventListener('resize', syncViewportMode);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewportMode);
});
</script>

<template>
  <article class="trend-snapshot-table" data-test="trend-snapshot-table">
    <header class="trend-snapshot-table__header">
      <h3 data-test="trend-snapshot-title">{{ snapshotTitle }}</h3>
      <p>{{ snapshotDescription }}</p>
    </header>

    <div v-if="!isMobileViewport" class="trend-snapshot-table__table-wrap">
      <el-table :data="snapshots" stripe empty-text="暂无快照数据" table-layout="fixed">
        <el-table-column label="快照时间" prop="snapshotTime" min-width="180" show-overflow-tooltip />
        <el-table-column label="书籍数" prop="bookCount" min-width="100" />
        <el-table-column label="榜首作品" prop="topBookName" min-width="180" show-overflow-tooltip />
        <el-table-column label="作者" prop="topBookAuthor" min-width="140" show-overflow-tooltip />
      </el-table>
    </div>
    <div
      v-else-if="snapshots.length"
      class="trend-snapshot-table__mobile-list"
      data-test="trend-snapshot-mobile-list"
    >
      <article
        v-for="(item, index) in snapshots"
        :key="item.snapshotTime"
        class="trend-snapshot-table__mobile-item"
        :data-test="`trend-snapshot-mobile-item-${index}`"
      >
        <div class="trend-snapshot-table__mobile-row">
          <span>快照时间</span>
          <strong>{{ item.snapshotTime }}</strong>
        </div>
        <div class="trend-snapshot-table__mobile-row">
          <span>书籍数</span>
          <strong>{{ item.bookCount }}</strong>
        </div>
        <div class="trend-snapshot-table__mobile-row">
          <span>榜首作品</span>
          <strong>{{ item.topBookName || '--' }}</strong>
        </div>
        <div class="trend-snapshot-table__mobile-row">
          <span>作者</span>
          <strong>{{ item.topBookAuthor || '--' }}</strong>
        </div>
      </article>
    </div>
    <p v-else class="trend-snapshot-table__empty">暂无快照数据</p>
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

.trend-snapshot-table__empty {
  margin: 0;
  color: var(--color-text-muted);
}

.trend-snapshot-table__table-wrap {
  overflow-x: auto;
}

.trend-snapshot-table__table-wrap :deep(.el-table) {
  min-width: 600px;
}

.trend-snapshot-table__mobile-list {
  display: grid;
  gap: 0.75rem;
}

.trend-snapshot-table__mobile-item {
  display: grid;
  gap: 0.55rem;
  padding: 0.95rem 1rem;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.05);
}

.trend-snapshot-table__mobile-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.trend-snapshot-table__mobile-row span {
  color: var(--color-text-muted);
  font-size: 0.84rem;
  flex-shrink: 0;
}

.trend-snapshot-table__mobile-row strong {
  text-align: right;
  overflow-wrap: anywhere;
  word-break: break-word;
}
</style>
