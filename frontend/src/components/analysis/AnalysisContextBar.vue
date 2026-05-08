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
    <div class="analysis-context__copy">
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 1rem;
  align-items: center;
  padding: 1.05rem 1.15rem;
  border-radius: 1.35rem;
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  background:
    radial-gradient(circle at top right, rgba(92, 124, 250, 0.18), transparent 26%),
    radial-gradient(circle at bottom left, rgba(255, 147, 186, 0.16), transparent 22%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-surface) 92%, transparent);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(1.1);
  -webkit-backdrop-filter: blur(18px) saturate(1.1);
}

.analysis-context__copy {
  display: grid;
  gap: 0.45rem;
  min-width: 0;
}

.analysis-context__eyebrow {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.analysis-context__title {
  margin: 0;
  color: var(--color-text);
  font-size: clamp(1.25rem, 2.5vw, 1.8rem);
  line-height: 1.1;
  overflow-wrap: anywhere;
}

.analysis-context__meta {
  display: flex;
  gap: 0.6rem 0.85rem;
  flex-wrap: wrap;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.analysis-context__chips {
  display: flex;
  gap: 0.55rem;
  flex-wrap: wrap;
  justify-content: flex-end;
  min-width: 0;
}

.analysis-context__chip {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  padding: 0.35rem 0.8rem;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  background: color-mix(in srgb, var(--color-glass) 80%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  color: var(--color-text);
  font-size: 0.9rem;
  white-space: nowrap;
}

@media (max-width: 760px) {
  .analysis-context {
    grid-template-columns: 1fr;
    align-items: flex-start;
    padding: 0.95rem;
  }

  .analysis-context__eyebrow {
    display: none;
  }

  .analysis-context__chips {
    justify-content: flex-start;
  }

  .analysis-context__chip {
    max-width: 100%;
    white-space: normal;
    overflow-wrap: anywhere;
  }
}
</style>
