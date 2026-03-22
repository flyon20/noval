<script setup lang="ts">
import { computed } from 'vue';
import type { ChapterItem, ChapterRefreshResult, Platform } from '@/types/crawler';

const props = defineProps<{
  modelValue: boolean;
  chapters: ChapterItem[];
  loading?: boolean;
  refreshLoading?: boolean;
  traceId?: string;
  bookId?: number;
  platform?: Platform;
  chapterCount: number;
  refreshSummary?: ChapterRefreshResult | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  goAnalysis: [];
  refreshChapters: [];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});
</script>

<template>
  <el-drawer
    v-model="visible"
    :append-to-body="false"
    :destroy-on-close="false"
    :with-header="false"
    direction="btt"
    size="72%"
  >
    <div class="chapter-drawer">
      <div class="chapter-drawer__header">
        <div>
          <p>章节预览</p>
          <h3>当前已加载 {{ chapters.length }} 章</h3>
        </div>
        <el-button
          data-testid="go-analysis"
          type="primary"
          :disabled="!bookId || !platform || chapters.length === 0"
          @click="emit('goAnalysis')"
        >
          进入分析页
        </el-button>
      </div>

      <div class="chapter-drawer__actions">
        <el-button
          data-testid="refresh-chapters"
          :disabled="!bookId || !platform"
          :loading="refreshLoading"
          @click="emit('refreshChapters')"
        >
          重新抓取章节
        </el-button>
        <p v-if="refreshSummary" class="chapter-drawer__quota">
          当前窗口 {{ refreshSummary.windowDays }} 天，已用 {{ refreshSummary.usedRefreshTimes }}/{{ refreshSummary.maxAllowedRefreshTimes }}，剩余 {{ refreshSummary.remainingRefreshTimes }}
        </p>
      </div>

      <el-skeleton v-if="loading" animated :rows="8" />

      <div v-else class="chapter-drawer__list">
        <article v-for="chapter in chapters" :key="chapter.chapterNo" class="chapter-card">
          <div class="chapter-card__heading">
            <strong>{{ chapter.chapterNo }}. {{ chapter.chapterTitle }}</strong>
            <span>{{ chapter.wordCount }} 字</span>
          </div>
          <p>{{ chapter.content }}</p>
        </article>
      </div>

      <p v-if="traceId" class="chapter-drawer__trace">traceId: {{ traceId }}</p>
      <p class="chapter-drawer__hint">分析参数：{{ platform ?? 'fanqie' }} / {{ bookId ?? '-' }} / {{ chapterCount }} 章</p>
    </div>
  </el-drawer>
</template>

<style scoped lang="scss">
.chapter-drawer {
  display: grid;
  gap: 1rem;
}

.chapter-drawer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.chapter-drawer__actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.chapter-drawer__header p,
.chapter-drawer__header h3,
.chapter-drawer__trace,
.chapter-drawer__hint,
.chapter-drawer__quota,
.chapter-card p {
  margin: 0;
}

.chapter-drawer__header p {
  color: var(--color-text-muted);
  font-size: 0.8rem;
}

.chapter-drawer__list {
  display: grid;
  gap: 1rem;
  max-height: 55vh;
  overflow: auto;
  padding-right: 0.5rem;
}

.chapter-card {
  display: grid;
  gap: 0.75rem;
  padding: 1rem;
  border: 1px solid var(--color-border);
  border-radius: 1rem;
  background: rgba(255, 255, 255, 0.65);
}

.chapter-card__heading {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  color: var(--color-text-muted);
}

.chapter-card p {
  line-height: 1.8;
  white-space: pre-wrap;
}

.chapter-drawer__trace,
.chapter-drawer__hint,
.chapter-drawer__quota {
  color: var(--color-text-muted);
  font-size: 0.85rem;
}
</style>
