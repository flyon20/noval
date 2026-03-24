<script setup lang="ts">
const props = defineProps<{
  bookTitle?: string;
  author?: string;
  bookId?: number;
  platform?: string;
  chapterCount?: number;
  analysisType?: string;
}>();
</script>

<template>
  <section class="analysis-context" data-role="analysis-context">
    <div>
      <p class="analysis-context__eyebrow">{{ props.analysisType ?? '当前分析模式' }}</p>
      <h2 class="analysis-context__title">{{ props.bookTitle ?? '待分析作品' }}</h2>
      <p class="analysis-context__meta">
        <span v-if="props.author">作者：{{ props.author }}</span>
        <span v-if="props.bookId">作品 ID：{{ props.bookId }}</span>
      </p>
    </div>
    <div class="analysis-context__chips">
      <span class="analysis-context__chip" data-chip="platform">平台：{{ props.platform ?? 'fanqie' }}</span>
      <span class="analysis-context__chip" data-chip="chapter">抓取章节：{{ props.chapterCount ?? '-' }} 章</span>
    </div>
  </section>
</template>

<style scoped lang="scss">
.analysis-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.2rem 1.5rem;
  border-radius: 1.25rem;
  background:
    linear-gradient(125deg, rgba(242, 236, 226, 0.98), rgba(238, 240, 230, 0.9)),
    rgba(255, 255, 255, 0.9);
  border: 1px solid var(--color-border);
}

.analysis-context__eyebrow {
  margin: 0;
  font-size: 0.85rem;
  color: var(--color-text-muted);
  letter-spacing: 0.2em;
}

.analysis-context__title {
  margin: 0.25rem 0;
  font-size: clamp(1.6rem, 2vw, 2.6rem);
}

.analysis-context__meta {
  display: flex;
  gap: 0.85rem;
  flex-wrap: wrap;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.analysis-context__chips {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.analysis-context__chip {
  padding: 0.4rem 0.9rem;
  border-radius: 999px;
  border: 1px solid rgba(35, 65, 58, 0.18);
  background: rgba(255, 255, 255, 0.8);
  font-size: 0.85rem;
}

@media (max-width: 760px) {
  .analysis-context {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
