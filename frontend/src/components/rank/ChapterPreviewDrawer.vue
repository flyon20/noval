<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ChapterItem, ChapterRefreshResult, Platform } from '@/types/crawler';

const SUMMARY_LENGTH = 160;

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

const selectedChapterNo = ref<number | null>(null);

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

const selectedChapter = computed(() =>
  props.chapters.find((chapter) => chapter.chapterNo === selectedChapterNo.value) ?? null,
);

watch(
  () => props.modelValue,
  (next) => {
    if (!next) {
      selectedChapterNo.value = null;
    }
  },
);

function getExcerpt(content: string) {
  const normalized = content.replace(/\s+/g, ' ').trim();
  if (normalized.length <= SUMMARY_LENGTH) {
    return normalized;
  }
  return `${normalized.slice(0, SUMMARY_LENGTH)}...`;
}

function openChapter(chapterNo: number) {
  selectedChapterNo.value = chapterNo;
}

function closeChapterDetail() {
  selectedChapterNo.value = null;
}

function closeDrawer() {
  selectedChapterNo.value = null;
  visible.value = false;
}
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
        <div class="chapter-drawer__header-main">
          <p>章节预览</p>
          <h3>{{ selectedChapter ? selectedChapter.chapterTitle : `当前已加载 ${chapters.length} 章` }}</h3>
        </div>
        <div class="chapter-drawer__header-actions">
          <el-button
            v-if="selectedChapter"
            data-testid="chapter-back"
            plain
            @click="closeChapterDetail"
          >
            返回列表
          </el-button>
          <el-button
            class="chapter-drawer__close"
            data-testid="chapter-close"
            plain
            type="default"
            @click="closeDrawer"
          >
            关闭
          </el-button>
          <el-button
            data-testid="go-analysis"
            type="primary"
            :disabled="!bookId || !platform || chapters.length === 0"
            @click="emit('goAnalysis')"
          >
            进入分析页
          </el-button>
        </div>
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
          当前窗口 {{ refreshSummary.windowDays }} 天，已用 {{ refreshSummary.usedRefreshTimes }}/{{
            refreshSummary.maxAllowedRefreshTimes
          }}，剩余 {{ refreshSummary.remainingRefreshTimes }}
        </p>
      </div>

      <el-skeleton v-if="loading" animated :rows="8" />

      <template v-else-if="selectedChapter">
        <section class="chapter-detail">
          <div class="chapter-detail__meta">
            <span>第 {{ selectedChapter.chapterNo }} 章</span>
            <span>{{ selectedChapter.wordCount }} 字</span>
          </div>
          <p class="chapter-detail__content">{{ selectedChapter.content }}</p>
        </section>
      </template>

      <div v-else class="chapter-drawer__list">
        <article
          v-for="chapter in chapters"
          :key="chapter.chapterNo"
          class="chapter-card"
          :data-testid="`chapter-item-${chapter.chapterNo}`"
          @click="openChapter(chapter.chapterNo)"
        >
          <div class="chapter-card__heading">
            <strong>{{ chapter.chapterNo }}. {{ chapter.chapterTitle }}</strong>
            <span>{{ chapter.wordCount }} 字</span>
          </div>
          <p>{{ getExcerpt(chapter.content) }}</p>
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

.chapter-drawer__header,
.chapter-card__heading,
.chapter-detail__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.chapter-drawer__header-main {
  min-width: 0;
  flex: 1;
}

.chapter-drawer__header-actions,
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
.chapter-card p,
.chapter-detail__content {
  margin: 0;
}

.chapter-drawer__header p {
  color: var(--color-text-muted);
  font-size: 0.8rem;
}

.chapter-drawer__header h3 {
  font-size: 1.3rem;
  line-height: 1.3;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.chapter-drawer__close {
  min-height: 2.5rem;
}

.chapter-drawer__list {
  display: grid;
  gap: 1rem;
  max-height: 55vh;
  overflow: auto;
  padding-right: 0.5rem;
}

.chapter-card,
.chapter-detail {
  display: grid;
  gap: 0.75rem;
  padding: 1rem 1.1rem;
  border: 1px solid var(--color-border);
  border-radius: 1.1rem;
  background: rgba(255, 255, 255, 0.72);
}

.chapter-card {
  cursor: pointer;
  transition: transform 180ms ease, border-color 180ms ease, box-shadow 180ms ease;
}

.chapter-card:hover {
  transform: translateY(-2px);
  border-color: rgba(35, 65, 58, 0.22);
  box-shadow: var(--shadow-soft);
}

.chapter-card__heading,
.chapter-detail__meta {
  color: var(--color-text-muted);
}

.chapter-card p,
.chapter-detail__content {
  line-height: 1.8;
  white-space: pre-wrap;
}

.chapter-drawer__trace,
.chapter-drawer__hint,
.chapter-drawer__quota {
  color: var(--color-text-muted);
  font-size: 0.85rem;
}

@media (max-width: 920px) {
  .chapter-drawer__header,
  .chapter-card__heading,
  .chapter-detail__meta {
    align-items: flex-start;
  }

  .chapter-drawer__header-actions {
    width: 100%;
  }
}
</style>
