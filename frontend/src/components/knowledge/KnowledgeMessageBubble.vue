<script setup lang="ts">
import { computed, ref } from 'vue';
import { Delete } from '@element-plus/icons-vue';
import { renderAnalysisMarkdown } from '@/lib/markdown';
import type { KnowledgeSource } from '@/types/knowledge';

const props = defineProps<{
  role: 'user' | 'assistant';
  content: string;
  status?: string;
  answerStatus?: string;
  intent?: string;
  answerBoundary?: string;
  sources?: KnowledgeSource[];
  fallbackUsed?: boolean;
  degraded?: boolean;
  degradationReasons?: string[];
  deletable?: boolean;
  deleteTestId?: string;
}>();

defineEmits<{
  delete: [];
}>();

const renderedContent = computed(() => (
  props.role === 'assistant'
    ? renderAnalysisMarkdown(props.content)
    : props.content
));

const showSources = ref(false);

const answerStatusLabel = computed(() => {
  const labels: Record<string, string> = {
    answered_with_evidence: '有证据',
    partial_answer: '部分证据',
    creative_answer: '创作建议',
    needs_data: '需补数据',
    needs_chapter_evidence: '需章节证据',
    out_of_scope: '超出范围',
  };
  if (props.answerStatus && labels[props.answerStatus]) {
    return labels[props.answerStatus];
  }
  if (props.status && props.status !== 'answered' && props.status !== 'streaming') {
    return props.status;
  }
  return '';
});

const intentLabel = computed(() => {
  const labels: Record<string, string> = {
    market_scan: '扫榜研判',
    opening_strategy: '开书策略',
    book_breakdown: '拆书分析',
    outline_building: '大纲',
    chapter_outline: '细纲',
    inspiration_expand: '灵感发散',
    character_design: '人设',
    worldbuilding: '世界观',
    revision_advice: '改稿建议',
    followup_context: '追问',
    mixed_creation_research: '复合任务',
    single_book_research: '单书研究',
    trend_research: '趋势研究',
    creative_advice: '创作建议',
    rank_lookup: '榜单事实',
    out_of_scope: '超出范围',
  };
  return props.intent ? labels[props.intent] || props.intent : '';
});

const answerBoundaryLabel = computed(() => {
  const labels: Record<string, string> = {
    market_evidence: '市场证据',
    market_evidence_plus_author_inference: '市场证据+作者推演',
    book_evidence_plus_craft_extraction: '作品证据+技法提炼',
    creative_inference: '创作推演',
    outline_generation: '大纲生成',
    needs_more_data: '需要补数据',
    out_of_scope: '范围外',
    structured_fact: '结构化事实',
    evidence_grounded: '证据回答',
    evidence_plus_author_inference: '证据+作者推演',
  };
  return props.answerBoundary ? labels[props.answerBoundary] || props.answerBoundary : '';
});

const showDegradedNotice = computed(() => props.role === 'assistant' && (props.degraded || props.fallbackUsed));

const degradedReasonLabel = computed(() => (props.degradationReasons ?? []).filter(Boolean).join(', '));

function sourceLabel(source: KnowledgeSource, index: number) {
  if ((source.sourceType || '').toUpperCase() === 'RANK') {
    return `[${index + 1}] ${source.rankNo ? `#${source.rankNo}` : '榜单'}`;
  }
  if (source.chapterNo) {
    return `[${index + 1}] 第 ${source.chapterNo} 章`;
  }
  if (source.analysisType) {
    return `[${index + 1}] ${source.analysisType}`;
  }
  return `[${index + 1}] 来源`;
}

function isRankSource(source: KnowledgeSource) {
  return (source.sourceType || '').toUpperCase() === 'RANK';
}
</script>

<template>
  <article class="knowledge-message" :class="`is-${role}`">
    <button
      v-if="deletable"
      class="knowledge-message__delete"
      type="button"
      :data-test="deleteTestId"
      aria-label="删除消息"
      @click="$emit('delete')"
    >
      <el-icon :size="14"><Delete /></el-icon>
    </button>

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
      <span v-if="showDegradedNotice" class="knowledge-message__degraded">
        降级回答
        <small v-if="degradedReasonLabel">{{ degradedReasonLabel }}</small>
      </span>
      <span v-if="answerStatusLabel" class="knowledge-message__status">{{ answerStatusLabel }}</span>
      <span v-if="intentLabel" class="knowledge-message__badge">{{ intentLabel }}</span>
      <span v-if="answerBoundaryLabel" class="knowledge-message__badge">{{ answerBoundaryLabel }}</span>
    </footer>

    <ol v-if="role === 'assistant' && sources?.length && showSources" class="knowledge-message__sources">
      <li v-for="(source, index) in sources" :key="source.chunkId ?? `${source.title}-${index}`">
        <template v-if="isRankSource(source)">
          <div class="knowledge-message__rank-source">
            <strong>{{ sourceLabel(source, index) }}</strong>
            <span>{{ source.bookName || source.title || '未命名作品' }}</span>
            <small v-if="source.author">{{ source.author }}</small>
          </div>
          <p v-if="source.title || source.category">{{ [source.title, source.category].filter(Boolean).join(' · ') }}</p>
        </template>
        <template v-else>
        <strong>{{ sourceLabel(source, index) }}</strong>
        <span>{{ source.title || source.bookName || '未命名来源' }}</span>
        <p v-if="source.preview">{{ source.preview }}</p>
        </template>
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
  position: relative;
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

.knowledge-message__delete {
  position: absolute;
  top: -0.75rem;
  right: -0.75rem;
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-text-muted);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  opacity: 0;
  transition: opacity 160ms ease, color 160ms ease, border-color 160ms ease;
}

.knowledge-message:hover .knowledge-message__delete,
.knowledge-message__delete:focus-visible {
  opacity: 1;
}

.knowledge-message__delete:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--color-primary) 55%, transparent);
  outline-offset: 2px;
}

.knowledge-message__delete:hover {
  color: var(--color-danger, #b42318);
  border-color: color-mix(in srgb, var(--color-danger, #b42318) 32%, var(--color-border));
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

.knowledge-message__degraded {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0 0.5rem;
  border: 1px solid color-mix(in srgb, var(--el-color-warning) 42%, var(--color-border));
  border-radius: 999px;
  color: var(--el-color-warning-dark-2);
  background: color-mix(in srgb, var(--el-color-warning-light-9) 72%, var(--color-surface));
  font-size: 0.76rem;
  line-height: 1;
  white-space: nowrap;
}

.knowledge-message__degraded small {
  color: inherit;
  font-size: 0.72rem;
}

.knowledge-message__badge {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  padding: 0 0.5rem;
  border: 1px solid color-mix(in srgb, var(--color-primary) 22%, var(--color-border));
  border-radius: 999px;
  color: color-mix(in srgb, var(--color-primary) 78%, var(--color-text));
  background: color-mix(in srgb, var(--color-primary) 8%, var(--color-surface));
  font-size: 0.76rem;
  line-height: 1;
  white-space: nowrap;
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

.knowledge-message__rank-source {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 0.5rem;
}

.knowledge-message__rank-source span {
  overflow: hidden;
  color: var(--color-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-message__rank-source small {
  color: var(--color-text-muted);
  font-size: 0.76rem;
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

  .knowledge-message__delete {
    opacity: 1;
  }

  .knowledge-message.is-assistant {
    padding-left: 0;
    padding-right: 0;
  }
}
</style>
