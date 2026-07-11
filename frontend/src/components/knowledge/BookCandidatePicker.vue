<script setup lang="ts">
import type { KnowledgeBookCandidate } from '@/types/knowledge';

defineProps<{
  candidates: KnowledgeBookCandidate[];
  loading?: boolean;
}>();

const emit = defineEmits<{
  select: [candidate: KnowledgeBookCandidate];
}>();

function isUnreadable(candidate: KnowledgeBookCandidate) {
  return candidate.readableNovel === false;
}

function unavailableText(candidate: KnowledgeBookCandidate) {
  if (candidate.unavailableReason === 'search_result_is_audiobook') {
    return '听书结果，暂不支持章节采集';
  }
  if (candidate.unavailableReason === 'search_result_is_video') {
    return '视频结果，暂不支持章节采集';
  }
  return '该结果暂不支持章节采集';
}
</script>

<template>
  <section v-if="candidates.length" class="candidate-picker" aria-label="候选作品">
    <p class="candidate-picker__hint">选择要分析的作品</p>
    <ul class="candidate-picker__list">
      <li v-for="candidate in candidates" :key="`${candidate.platformBookId ?? candidate.bookId}-${candidate.bookName}`">
        <button
          data-test="candidate-select-button"
          type="button"
          :class="{ 'is-unreadable': isUnreadable(candidate) }"
          :disabled="loading || isUnreadable(candidate)"
          @click="emit('select', candidate)"
        >
          <strong>{{ candidate.bookName }}</strong>
          <span>{{ candidate.author || '未知作者' }}</span>
          <em v-if="isUnreadable(candidate)">{{ unavailableText(candidate) }}</em>
          <small v-if="candidate.intro">{{ candidate.intro }}</small>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped lang="scss">
.candidate-picker {
  align-self: flex-start;
  width: min(100%, 680px);
  display: grid;
  gap: 0.5rem;
}

.candidate-picker__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.85rem;
}

.candidate-picker__list {
  display: grid;
  gap: 0.45rem;
  padding: 0;
  margin: 0;
  list-style: none;
}

.candidate-picker button {
  width: 100%;
  min-height: 48px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.2rem 0.75rem;
  padding: 0.65rem 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  color: var(--color-text);
  background: var(--color-surface);
  cursor: pointer;
  text-align: left;
}

.candidate-picker button:disabled {
  cursor: wait;
  opacity: 0.7;
}

.candidate-picker button.is-unreadable {
  cursor: not-allowed;
}

.candidate-picker strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.candidate-picker span {
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.candidate-picker em {
  grid-column: 1 / -1;
  color: var(--color-danger, #b42318);
  font-size: 0.78rem;
  font-style: normal;
}

.candidate-picker small {
  grid-column: 1 / -1;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 0.8rem;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
}
</style>
