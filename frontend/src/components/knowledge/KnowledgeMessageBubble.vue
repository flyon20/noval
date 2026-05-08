<script setup lang="ts">
import { computed, ref } from 'vue';
import { renderAnalysisMarkdown } from '@/lib/markdown';
import type { KnowledgeSource } from '@/types/knowledge';

const props = defineProps<{
  role: 'user' | 'assistant';
  content: string;
  status?: string;
  sources?: KnowledgeSource[];
}>();

const renderedContent = computed(() => (
  props.role === 'assistant'
    ? renderAnalysisMarkdown(props.content)
    : props.content
));

const showSources = ref(false);

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
  <article class="knowledge-message" :class="`is-${role}`">
    <div v-if="role === 'assistant'" class="knowledge-message__markdown" v-html="renderedContent" />
    <p v-else>{{ renderedContent }}</p>

    <footer v-if="role === 'assistant'" class="knowledge-message__meta">
      <button
        v-if="sources?.length"
        class="knowledge-message__sources-toggle"
        type="button"
        @click="showSources = !showSources"
      >
        引用来源 {{ sources.length }}
      </button>
      <span v-if="status && status !== 'answered'" class="knowledge-message__status">{{ status }}</span>
    </footer>

    <ol v-if="role === 'assistant' && sources?.length && showSources" class="knowledge-message__sources">
      <li v-for="(source, index) in sources" :key="source.chunkId ?? `${source.title}-${index}`">
        <strong>{{ sourceLabel(source, index) }}</strong>
        <span>{{ source.title || source.bookName || '未命名来源' }}</span>
        <p v-if="source.preview">{{ source.preview }}</p>
      </li>
    </ol>
  </article>
</template>

<style scoped lang="scss">
.knowledge-message {
  width: fit-content;
  max-width: min(78%, 720px);
  display: grid;
  gap: 0.45rem;
  border-radius: 8px;
  line-height: 1.75;
}

.knowledge-message p {
  margin: 0;
  white-space: pre-wrap;
}

.knowledge-message.is-user {
  align-self: flex-end;
  padding: 0.75rem 0.9rem;
  color: white;
  background: var(--color-primary);
}

.knowledge-message.is-assistant {
  align-self: flex-start;
  color: var(--color-text);
  background: transparent;
}

.knowledge-message__markdown :deep(.analysis-result__markdown) {
  display: grid;
  gap: 0.65rem;
}

.knowledge-message__markdown :deep(p),
.knowledge-message__markdown :deep(ul),
.knowledge-message__markdown :deep(ol),
.knowledge-message__markdown :deep(blockquote) {
  margin: 0;
}

.knowledge-message__markdown :deep(ul),
.knowledge-message__markdown :deep(ol) {
  padding-left: 1.2rem;
}

.knowledge-message__markdown :deep(code) {
  padding: 0.1rem 0.25rem;
  border-radius: 4px;
  background: color-mix(in srgb, var(--color-primary) 10%, var(--color-surface));
}

.knowledge-message__meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 28px;
}

.knowledge-message__sources-toggle {
  min-height: 28px;
  padding: 0 0.55rem;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-text-muted);
  background: var(--color-surface);
  cursor: pointer;
  font-size: 0.78rem;
}

.knowledge-message__status {
  color: var(--color-text-muted);
  font-size: 0.78rem;
}

.knowledge-message__sources {
  max-width: 640px;
  max-height: 180px;
  overflow: auto;
  display: grid;
  gap: 0.45rem;
  padding: 0.65rem 0.75rem;
  margin: 0;
  list-style: none;
  border-left: 2px solid var(--color-border);
  color: var(--color-text-muted);
  background: color-mix(in srgb, var(--color-surface) 76%, transparent);
}

.knowledge-message__sources li {
  display: grid;
  gap: 0.15rem;
}

.knowledge-message__sources strong {
  color: var(--color-text);
  font-size: 0.82rem;
}

.knowledge-message__sources span,
.knowledge-message__sources p {
  margin: 0;
  font-size: 0.8rem;
  line-height: 1.5;
}

@media (max-width: 720px) {
  .knowledge-message {
    max-width: 94%;
    padding: 0.75rem 0.85rem;
  }

  .knowledge-message.is-assistant {
    padding-left: 0;
    padding-right: 0;
  }
}
</style>
