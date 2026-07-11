<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Delete, Plus, Promotion } from '@element-plus/icons-vue';
import BookCandidatePicker from '@/components/knowledge/BookCandidatePicker.vue';
import KnowledgeMessageBubble from '@/components/knowledge/KnowledgeMessageBubble.vue';
import { useKnowledgeChat } from '@/composables/useKnowledgeChat';
import {
  getKnowledgeConversationSelectDetail,
  getKnowledgeProjectChangeDetail,
  KNOWLEDGE_CONVERSATION_SELECT_EVENT,
  KNOWLEDGE_PROJECT_CHANGE_EVENT,
} from '@/composables/useKnowledgeProjectSelection';
import { useVisualViewportKeyboard } from '@/composables/useVisualViewportKeyboard';

defineOptions({
  name: 'KnowledgeChatView',
});

const {
  state,
  canSend,
  sendQuestion,
  selectCandidate,
  loadProjects,
  selectProject,
  loadConversationRun,
  clearConversation,
  startNewConversation,
  deleteMessage,
} = useKnowledgeChat();
const { keyboardStyle } = useVisualViewportKeyboard();
const messagesRef = ref<HTMLElement | null>(null);
const numberFormatter = new Intl.NumberFormat('zh-CN');

const quickPrompts = [
  '凡人修仙传开篇卖点是什么？',
  '最近男频题材趋势是什么？',
  '修仙文开局怎么设计爽点？',
];

const contextBudget = computed(() => state.contextBudget);
const memoryLayerCount = computed(() => contextBudget.value?.memoryLayers?.length ?? 0);
const contextTraceId = computed(() => state.traceId || [...state.messages].reverse().find((message) => message.traceId)?.traceId || '');
const contextUsedRatio = computed(() => {
  const maxTokens = Number(contextBudget.value?.maxInputTokens);
  const usedTokens = Number(contextBudget.value?.estimatedUsedTokens);
  if (!Number.isFinite(maxTokens) || maxTokens <= 0 || !Number.isFinite(usedTokens)) {
    return undefined;
  }
  return Math.max(0, Math.min(1, usedTokens / maxTokens));
});
const compressionThresholdTokens = computed(() => {
  const raw = contextBudget.value?.compressionThresholdTokens ?? contextBudget.value?.compressionThreshold;
  const numeric = Number(raw);
  return Number.isFinite(numeric) && numeric > 0 ? numeric : undefined;
});
const memoryLayerSummary = computed(() => {
  const layers = contextBudget.value?.memoryLayers;
  if (!Array.isArray(layers) || layers.length === 0) {
    return '记忆层 0：本轮未加载可用记忆';
  }
  return layers
    .map((layer) => {
      const name = String(layer.name || 'memory');
      const status = String(layer.status || 'unknown');
      const count = Number(layer.itemCount);
      return Number.isFinite(count) ? `${name} ${status} ${count}` : `${name} ${status}`;
    })
    .join(' / ');
});

function formatTokenCount(value?: number) {
  return numberFormatter.format(Math.max(0, Math.round(Number(value) || 0)));
}

function formatRemainingRatio(value?: number) {
  const normalized = Number(value);
  if (!Number.isFinite(normalized)) {
    return '-';
  }
  return `${(Math.max(0, Math.min(1, normalized)) * 100).toFixed(2)}%`;
}

function formatRatio(value?: number) {
  const normalized = Number(value);
  if (!Number.isFinite(normalized)) {
    return '-';
  }
  return `${(Math.max(0, Math.min(1, normalized)) * 100).toFixed(2)}%`;
}

async function scrollMessagesToBottom() {
  await nextTick();
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
  }
}

function handleProjectChange(event: Event) {
  const detail = getKnowledgeProjectChangeDetail(event);
  selectProject(detail.projectId);
}

function handleConversationSelect(event: Event) {
  const detail = getKnowledgeConversationSelectDetail(event);
  selectProject(detail.projectId);
  void loadConversationRun(detail.conversationId);
}

onMounted(async () => {
  window.addEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, handleProjectChange);
  window.addEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, handleConversationSelect);
  await loadProjects();
  await scrollMessagesToBottom();
});

onBeforeUnmount(() => {
  window.removeEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, handleProjectChange);
  window.removeEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, handleConversationSelect);
});

watch(
  () => [
    state.messages.map((message) => message.content).join('|'),
    state.candidates.length,
    state.loading,
  ],
  scrollMessagesToBottom,
);
</script>

<template>
  <main class="knowledge-chat" :style="keyboardStyle">
    <header v-if="state.messages.length" class="knowledge-chat__toolbar">
      <span>最近会话</span>
      <el-button
        data-test="knowledge-new-chat"
        size="small"
        type="primary"
        plain
        :icon="Plus"
        :disabled="state.loading"
        aria-label="新建会话"
        @click="startNewConversation"
      >
        新建会话
      </el-button>
      <el-button
        data-test="knowledge-clear-chat"
        size="small"
        :icon="Delete"
        :disabled="state.loading"
        @click="clearConversation"
      >
        清空会话
      </el-button>
    </header>

    <section ref="messagesRef" class="knowledge-chat__messages" aria-live="polite">
      <div v-if="!state.messages.length" class="knowledge-chat__empty">
        <h1>网文 AI 问答</h1>
        <div class="knowledge-chat__chips">
          <button
            v-for="prompt in quickPrompts"
            :key="prompt"
            type="button"
            @click="state.question = prompt"
          >
            {{ prompt }}
          </button>
        </div>
      </div>

      <KnowledgeMessageBubble
        v-for="(message, index) in state.messages"
        :key="`${message.role}-${index}`"
        :role="message.role"
        :content="message.content"
        :status="message.status"
        :answer-status="message.answerStatus"
        :intent="message.intent"
        :answer-boundary="message.answerBoundary"
        :sources="message.sources"
        :fallback-used="message.fallbackUsed"
        :degraded="message.degraded"
        :degradation-reasons="message.degradationReasons"
        :deletable="!state.loading"
        :delete-test-id="`knowledge-delete-message-${index}`"
        @delete="deleteMessage(index)"
      />

      <BookCandidatePicker
        v-if="state.candidates.length"
        :candidates="state.candidates"
        :loading="state.loading"
        @select="selectCandidate"
      />

      <div v-if="state.loading && !state.answer" class="knowledge-chat__typing">
        {{ state.status || '正在思考...' }}
      </div>
      <div
        v-else-if="state.loading && state.status"
        class="knowledge-chat__run-status"
        data-test="knowledge-run-status"
      >
        <span>{{ state.status }}</span>
        <small v-if="contextTraceId">Trace {{ contextTraceId }}</small>
      </div>
    </section>

    <el-alert
      v-if="state.errorMessage"
      class="knowledge-chat__error"
      type="error"
      :closable="false"
      :title="state.errorMessage"
      show-icon
    />

    <section
      v-if="contextBudget"
      class="knowledge-chat__context-budget"
      data-test="knowledge-context-budget"
      aria-label="上下文容量状态"
    >
      <strong>上下文容量</strong>
      <span>已用 {{ formatTokenCount(contextBudget.estimatedUsedTokens) }} tokens</span>
      <span>剩余 {{ formatRemainingRatio(contextBudget.remainingRatio) }}</span>
      <span>{{ contextBudget.compressed ? '已压缩' : '未压缩' }}</span>
      <span>记忆层 {{ memoryLayerCount }}</span>
      <span v-if="contextBudget.maxInputTokens">总容量 {{ formatTokenCount(contextBudget.maxInputTokens) }} tokens</span>
      <span v-if="contextUsedRatio !== undefined">已用比例 {{ formatRatio(contextUsedRatio) }}</span>
      <span v-if="contextBudget.remainingTokens">剩余 {{ formatTokenCount(contextBudget.remainingTokens) }} tokens</span>
      <span v-if="compressionThresholdTokens">压缩阈值 {{ formatTokenCount(compressionThresholdTokens) }} tokens</span>
      <span>{{ memoryLayerSummary }}</span>
      <span v-if="contextTraceId">Trace {{ contextTraceId }}</span>
    </section>

    <form class="knowledge-chat__composer" @submit.prevent="sendQuestion">
      <div class="knowledge-chat__input" data-test="knowledge-question-input">
        <el-input
          v-model="state.question"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          resize="none"
          placeholder="问网文相关问题"
          :disabled="state.loading"
          @keydown.enter.exact.prevent="sendQuestion"
        />
      </div>

      <div class="knowledge-chat__tools">
        <el-segmented
          v-model="state.reasoningMode"
          data-test="knowledge-reasoning-mode"
          size="small"
          :options="[
            { label: '快速', value: 'fast' },
            { label: '深度', value: 'deep' },
          ]"
          :disabled="state.loading"
          aria-label="AI 问答推理模式"
        />
        <el-segmented
          v-model="state.chapterCount"
          size="small"
          :options="[3, 5, 10]"
          :disabled="state.loading"
          aria-label="抓取章节数"
        />
        <el-button
          data-test="knowledge-send-button"
          class="knowledge-chat__send"
          type="primary"
          circle
          :icon="Promotion"
          :loading="state.loading"
          :disabled="!canSend"
          native-type="button"
          aria-label="发送"
          @click="sendQuestion"
        />
      </div>
    </form>

  </main>
</template>

<style scoped lang="scss">
.knowledge-chat {
  --keyboard-offset: 0px;
  height: calc(100dvh - 4rem);
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  padding-bottom: 1rem;
  overflow: hidden;
}

.knowledge-chat__toolbar {
  width: min(100%, 880px);
  justify-self: center;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0 1rem 0.35rem;
  color: var(--color-text-muted);
  font-size: 0.86rem;
}

.knowledge-chat__messages {
  min-height: 0;
  width: min(100%, 880px);
  justify-self: center;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem 1rem 1.5rem;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.knowledge-chat__empty {
  margin: auto;
  width: min(100%, 680px);
  display: grid;
  gap: 1rem;
  text-align: center;
}

.knowledge-chat__empty h1 {
  margin: 0;
  color: var(--color-text);
  font-size: 1.65rem;
  font-family: var(--font-heading);
}

.knowledge-chat__chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.55rem;
}

.knowledge-chat__chips button {
  min-height: 36px;
  padding: 0 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-text);
  background: var(--color-surface);
  cursor: pointer;
}

.knowledge-chat__typing {
  align-self: flex-start;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.knowledge-chat__run-status {
  align-self: flex-start;
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.2rem 0;
  color: var(--color-text-muted);
  font-size: 0.86rem;
}

.knowledge-chat__run-status small {
  font-size: 0.78rem;
  color: var(--color-text-muted);
}

.knowledge-chat__error {
  width: min(100%, 880px);
  justify-self: center;
}

.knowledge-chat__context-budget {
  width: min(100%, 880px);
  justify-self: center;
  min-height: 36px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0.4rem 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: 0.8125rem;
}

.knowledge-chat__context-budget strong {
  color: var(--color-text);
  font-weight: 650;
}

.knowledge-chat__composer {
  width: min(100%, 880px);
  justify-self: center;
  display: grid;
  gap: 0.6rem;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-surface) 96%, transparent);
  box-shadow: var(--shadow-card);
}

.knowledge-chat__input :deep(.el-textarea__inner) {
  min-height: 46px !important;
  border-radius: 8px;
  line-height: 1.6;
}

.knowledge-chat__tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.knowledge-chat__send {
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
}

@media (max-width: 720px) {
  .knowledge-chat {
    height: calc(
      100dvh
      - 56px
      - var(--bottom-nav-height)
      - env(safe-area-inset-bottom, 0px)
      - 1.25rem
      - var(--keyboard-offset)
    );
    min-height: 0;
    grid-template-rows: auto minmax(0, 1fr) auto auto;
    padding-bottom: 0;
  }

  .knowledge-chat__messages {
    width: 100%;
    padding: 0.75rem 0.75rem 1rem;
    scroll-padding-bottom: 1rem;
  }

  .knowledge-chat__toolbar {
    width: 100%;
    padding: 0 0.75rem 0.35rem;
  }

  .knowledge-chat__empty {
    gap: 0.9rem;
  }

  .knowledge-chat__empty h1 {
    font-size: 1.35rem;
  }

  .knowledge-chat__composer {
    position: relative;
    z-index: 2;
    width: calc(100% - 1.5rem);
    margin: 0 auto 0.75rem;
    padding: 0.65rem;
  }

  .knowledge-chat__tools :deep(.el-segmented) {
    max-width: calc(100vw - 5.75rem);
  }
}
</style>
