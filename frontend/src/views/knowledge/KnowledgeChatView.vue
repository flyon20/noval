<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import { Promotion } from '@element-plus/icons-vue';
import BookCandidatePicker from '@/components/knowledge/BookCandidatePicker.vue';
import KnowledgeMessageBubble from '@/components/knowledge/KnowledgeMessageBubble.vue';
import { useKnowledgeChat } from '@/composables/useKnowledgeChat';

const { state, canSend, sendQuestion, selectCandidate } = useKnowledgeChat();
const messagesRef = ref<HTMLElement | null>(null);

const quickPrompts = [
  '凡人修仙传开篇卖点是什么？',
  '最近男频题材趋势是什么？',
  '修仙文开局怎么设计爽点？',
];

watch(
  () => [
    state.messages.map((message) => message.content).join('|'),
    state.candidates.length,
    state.loading,
  ],
  async () => {
    await nextTick();
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
    }
  },
);
</script>

<template>
  <main class="knowledge-chat">
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
        :sources="message.sources"
      />

      <BookCandidatePicker
        v-if="state.candidates.length"
        :candidates="state.candidates"
        :loading="state.loading"
        @select="selectCandidate"
      />

      <div v-if="state.loading && !state.answer" class="knowledge-chat__typing">
        正在思考
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
  height: calc(100dvh - 4rem);
  min-height: 0;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto auto;
  padding-bottom: 1rem;
  overflow: hidden;
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

.knowledge-chat__error {
  width: min(100%, 880px);
  justify-self: center;
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
}

.knowledge-chat__send {
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
}

@media (max-width: 720px) {
  .knowledge-chat {
    height: calc(100dvh - var(--bottom-nav-height) - 1.75rem);
    padding-bottom: calc(env(safe-area-inset-bottom) + 1.15rem);
  }

  .knowledge-chat__messages {
    width: 100%;
    padding: 0.75rem 0.75rem 1.25rem;
  }

  .knowledge-chat__empty {
    gap: 0.9rem;
  }

  .knowledge-chat__empty h1 {
    font-size: 1.35rem;
  }

  .knowledge-chat__composer {
    width: calc(100% - 1rem);
    margin: 0 0.5rem 0.75rem;
    padding: 0.65rem;
  }

  .knowledge-chat__tools :deep(.el-segmented) {
    max-width: calc(100vw - 5.75rem);
  }
}
</style>
