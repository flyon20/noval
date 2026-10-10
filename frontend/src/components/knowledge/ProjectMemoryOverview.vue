<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Clock,
  Collection,
  Connection,
  DocumentChecked,
  Files,
  MapLocation,
  Refresh,
  User,
} from '@element-plus/icons-vue';
import { knowledgeApi } from '@/api/knowledge';
import type { ProjectMemoryOverview } from '@/types/knowledge';

const props = defineProps<{
  projectId: number | null;
  workId: number | null;
  refreshKey?: number;
}>();

const overview = ref<ProjectMemoryOverview | null>(null);
const loading = ref(false);
const errorMessage = ref('');
let loadGeneration = 0;

const chapterCoverageLabel = computed(() => {
  const data = overview.value;
  if (!data?.activeChapterCount) {
    return '暂无有效章节';
  }
  if (data.chapterFrom === data.chapterTo) {
    return `第 ${data.chapterFrom} 章`;
  }
  return `第 ${data.chapterFrom}-${data.chapterTo} 章`;
});

const summaryStatus = computed(() => {
  const data = overview.value;
  if (!data) {
    return { label: '尚未读取', type: 'info' as const };
  }
  switch (data.summaryCoverageStatus) {
    case 'COMPLETE':
      return { label: '摘要覆盖完整', type: 'success' as const };
    case 'PARTIAL':
      return { label: `摘要覆盖 ${data.summaryCoveredChapterCount}/${data.activeChapterCount} 章`, type: 'warning' as const };
    case 'NO_CORPUS':
      return { label: '等待章节资料', type: 'info' as const };
    default:
      return { label: '摘要尚未建立', type: 'info' as const };
  }
});

const foreshadowingBreakdown = computed(() => Object.entries(overview.value?.foreshadowingStatusCounts ?? {})
  .filter(([, count]) => count > 0)
  .map(([status, count]) => ({ status, count, label: foreshadowingStatusLabel(status) })));

watch(
  () => [props.projectId, props.workId, props.refreshKey] as const,
  () => void loadOverview(),
  { immediate: true },
);

async function loadOverview() {
  const projectId = props.projectId;
  const workId = props.workId;
  const generation = ++loadGeneration;
  overview.value = null;
  errorMessage.value = '';
  if (!projectId || !workId) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const response = await knowledgeApi.getProjectMemoryOverview(projectId, workId);
    if (generation !== loadGeneration || projectId !== props.projectId || workId !== props.workId) {
      return;
    }
    overview.value = response.data.data;
  } catch {
    if (generation === loadGeneration && projectId === props.projectId && workId === props.workId) {
      errorMessage.value = '作品记忆加载失败';
    }
  } finally {
    if (generation === loadGeneration && projectId === props.projectId && workId === props.workId) {
      loading.value = false;
    }
  }
}

function foreshadowingStatusLabel(status: string) {
  switch (status.toUpperCase()) {
    case 'OPEN':
      return '未回收';
    case 'PAID_OFF':
      return '已回收';
    case 'DISPUTED':
      return '待确认';
    default:
      return status;
  }
}
</script>

<template>
  <section class="project-memory" data-test="project-memory-overview" aria-label="作品长期记忆">
    <header class="project-memory__header">
      <div>
        <p>作品长期记忆</p>
        <h3>{{ chapterCoverageLabel }}</h3>
      </div>
      <el-button
        circle
        text
        :icon="Refresh"
        :loading="loading"
        aria-label="刷新作品记忆"
        data-test="project-memory-refresh"
        @click="loadOverview"
      />
    </header>

    <el-skeleton v-if="loading && !overview" :rows="4" animated />

    <div v-else-if="errorMessage" class="project-memory__state" role="alert">
      <span>{{ errorMessage }}</span>
      <el-button size="small" :icon="Refresh" @click="loadOverview">重试</el-button>
    </div>

    <div v-else-if="!projectId || !workId" class="project-memory__state">
      请选择作品
    </div>

    <template v-else-if="overview">
      <div class="project-memory__coverage">
        <div>
          <strong>{{ overview.activeChapterCount }}</strong>
          <span>有效章节</span>
        </div>
        <div>
          <strong>{{ overview.indexedDocumentCount }}</strong>
          <span>已索引资料</span>
        </div>
        <el-tag :type="summaryStatus.type" effect="light" size="small">
          {{ summaryStatus.label }}
        </el-tag>
      </div>

      <dl class="project-memory__metrics">
        <div>
          <dt><el-icon><User /></el-icon>人物状态</dt>
          <dd>{{ overview.characterStateCount }}</dd>
        </div>
        <div>
          <dt><el-icon><MapLocation /></el-icon>世界设定</dt>
          <dd>{{ overview.worldRuleCount }}</dd>
        </div>
        <div>
          <dt><el-icon><Collection /></el-icon>伏笔</dt>
          <dd>{{ overview.foreshadowingCount }}</dd>
        </div>
        <div>
          <dt><el-icon><Clock /></el-icon>时间线事件</dt>
          <dd>{{ overview.timelineEventCount }}</dd>
        </div>
        <div>
          <dt><el-icon><Connection /></el-icon>关系节点</dt>
          <dd>{{ overview.storyNodeCount }}</dd>
        </div>
        <div>
          <dt><el-icon><Files /></el-icon>关系连线</dt>
          <dd>{{ overview.storyEdgeCount }}</dd>
        </div>
        <div>
          <dt><el-icon><DocumentChecked /></el-icon>长期事实</dt>
          <dd>{{ overview.longFormFactCount }}</dd>
        </div>
        <div>
          <dt><el-icon><Files /></el-icon>摘要节点</dt>
          <dd>{{ overview.summaryNodeCount }}</dd>
        </div>
      </dl>

      <div v-if="foreshadowingBreakdown.length" class="project-memory__breakdown" aria-label="伏笔状态">
        <span v-for="item in foreshadowingBreakdown" :key="item.status">
          {{ item.label }} {{ item.count }}
        </span>
      </div>

      <div
        v-if="overview.pendingExtractionCount || overview.pendingLongFormFactCount"
        class="project-memory__pending"
        data-test="project-memory-pending"
      >
        <span>待确认</span>
        <strong>{{ overview.pendingExtractionCount + overview.pendingLongFormFactCount }}</strong>
      </div>
    </template>
  </section>
</template>

<style scoped>
.project-memory {
  display: grid;
  gap: 0.65rem;
  min-width: 0;
}

.project-memory__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.project-memory__header p,
.project-memory__header h3 {
  margin: 0;
}

.project-memory__header p {
  color: var(--color-text-muted);
  font-size: 0.72rem;
}

.project-memory__header h3 {
  margin-top: 0.12rem;
  color: var(--color-text);
  font-size: 0.9rem;
}

.project-memory__coverage {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
  padding: 0.6rem 0;
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
}

.project-memory__coverage > div {
  min-width: 0;
  display: grid;
  gap: 0.08rem;
}

.project-memory__coverage strong,
.project-memory__metrics dd,
.project-memory__pending strong {
  font-variant-numeric: tabular-nums;
}

.project-memory__coverage strong {
  color: var(--color-text);
  font-size: 1.05rem;
}

.project-memory__coverage span,
.project-memory__metrics dt {
  color: var(--color-text-muted);
  font-size: 0.72rem;
}

.project-memory__coverage :deep(.el-tag) {
  grid-column: 1 / -1;
  justify-self: start;
  max-width: 100%;
}

.project-memory__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: 0;
}

.project-memory__metrics > div {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.4rem;
  min-height: 38px;
  padding: 0.35rem 0.45rem;
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 76%, transparent);
}

.project-memory__metrics > div:nth-child(odd) {
  border-right: 1px solid color-mix(in srgb, var(--color-border) 76%, transparent);
}

.project-memory__metrics dt {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.28rem;
}

.project-memory__metrics dt :deep(.el-icon) {
  flex: 0 0 auto;
  color: var(--color-accent-strong);
}

.project-memory__metrics dd {
  flex: 0 0 auto;
  margin: 0;
  color: var(--color-text);
  font-size: 0.82rem;
  font-weight: 700;
}

.project-memory__breakdown {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.project-memory__breakdown span {
  padding: 0.2rem 0.4rem;
  border-radius: 6px;
  color: var(--color-text-muted);
  background: color-mix(in srgb, var(--color-primary-soft) 48%, var(--color-surface));
  font-size: 0.7rem;
}

.project-memory__pending {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.48rem 0.55rem;
  border: 1px solid color-mix(in srgb, var(--el-color-warning) 32%, var(--color-border));
  border-radius: 7px;
  color: var(--color-text);
  background: color-mix(in srgb, var(--el-color-warning-light-9) 62%, var(--color-surface));
  font-size: 0.75rem;
}

.project-memory__state {
  min-height: 96px;
  display: grid;
  place-items: center;
  gap: 0.5rem;
  padding: 0.75rem;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  text-align: center;
}
</style>
