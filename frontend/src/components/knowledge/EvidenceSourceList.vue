<script setup lang="ts">
import type { KnowledgeSource } from '@/types/knowledge';

defineProps<{
  sources: KnowledgeSource[];
}>();

function sourceLabel(source: KnowledgeSource, index: number) {
  if (source.chapterNo) {
    return `[${index + 1}] 第 ${source.chapterNo} 章`;
  }
  if (source.analysisType) {
    return `[${index + 1}] ${source.analysisType}`;
  }
  return `[${index + 1}] 来源`;
}
</script>

<template>
  <section class="source-list" aria-label="引用来源">
    <header class="source-list__header">
      <h2>引用来源</h2>
      <span>{{ sources.length }} 条</span>
    </header>
    <el-empty v-if="!sources.length" description="暂无可展示来源" :image-size="80" />
    <ol v-else class="source-list__items">
      <li v-for="(source, index) in sources" :key="source.chunkId ?? `${source.title}-${index}`" class="source-list__item">
        <div class="source-list__topline">
          <strong>{{ sourceLabel(source, index) }}</strong>
          <el-tag v-if="source.score" size="small" type="info">{{ Math.round(source.score * 100) }}%</el-tag>
        </div>
        <p class="source-list__title">{{ source.title || source.bookName || '未命名来源' }}</p>
        <p v-if="source.preview" class="source-list__preview">{{ source.preview }}</p>
      </li>
    </ol>
  </section>
</template>

<style scoped lang="scss">
.source-list {
  display: grid;
  gap: 0.85rem;
}

.source-list__header,
.source-list__topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.source-list__header {
  color: var(--color-text-muted);
}

.source-list__header h2,
.source-list__title,
.source-list__preview {
  margin: 0;
}

.source-list__header h2 {
  color: var(--color-text);
  font-size: 1rem;
}

.source-list__items {
  display: grid;
  gap: 0.75rem;
  padding: 0;
  margin: 0;
  list-style: none;
}

.source-list__item {
  display: grid;
  gap: 0.35rem;
  padding: 0.85rem;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-strong);
}

.source-list__topline {
  color: var(--color-accent-strong);
}

.source-list__title {
  color: var(--color-text);
  font-weight: 600;
}

.source-list__preview {
  color: var(--color-text-muted);
  line-height: 1.6;
}
</style>
