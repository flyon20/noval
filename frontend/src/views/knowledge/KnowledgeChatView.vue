<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue';
import { Delete, Plus, Promotion } from '@element-plus/icons-vue';
import BookCandidatePicker from '@/components/knowledge/BookCandidatePicker.vue';
import KnowledgeMessageBubble from '@/components/knowledge/KnowledgeMessageBubble.vue';
import { useKnowledgeChat } from '@/composables/useKnowledgeChat';
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
  createProject,
  selectProject,
  clearConversation,
  deleteMessage,
} = useKnowledgeChat();
const { keyboardStyle } = useVisualViewportKeyboard();
const messagesRef = ref<HTMLElement | null>(null);

const quickPrompts = [
  '凡人修仙传开篇卖点是什么？',
  '最近男频题材趋势是什么？',
  '修仙文开局怎么设计爽点？',
];

async function scrollMessagesToBottom() {
  await nextTick();
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
  }
}

onMounted(async () => {
  await loadProjects();
  await scrollMessagesToBottom();
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
    <section class="knowledge-chat__projects" aria-label="writing projects">
      <el-select
        :model-value="state.activeProjectId"
        clearable
        size="small"
        placeholder="项目"
        data-test="knowledge-project-select"
        @change="(value: number | '') => selectProject(value || null)"
      >
        <el-option
          v-for="project in state.projects"
          :key="project.projectId"
          :label="project.name"
          :value="project.projectId"
        />
      </el-select>
      <div data-test="knowledge-project-name">
        <el-input
          v-model="state.projectNameDraft"
          size="small"
          placeholder="新项目"
          @keydown.enter.prevent="createProject"
        />
      </div>
      <el-button
        size="small"
        :icon="Plus"
        data-test="knowledge-create-project"
        @click="createProject"
      />
    </section>

    <header v-if="state.messages.length" class="knowledge-chat__toolbar">
      <span>最近会话</span>
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
  --keyboard-offset: 0px;
  height: calc(100dvh - 4rem);
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  padding-bottom: 1rem;
  overflow: hidden;
}

.knowledge-chat__projects {
  width: min(100%, 880px);
  justify-self: center;
  display: grid;
  grid-template-columns: minmax(10rem, 16rem) minmax(8rem, 1fr) auto;
  gap: 0.5rem;
  padding: 0 1rem 0.5rem;
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
    min-height: calc(100dvh - 56px);
    height: calc(100dvh - 56px);
    grid-template-rows: auto minmax(0, 1fr) auto;
    padding-bottom: 0;
  }

  .knowledge-chat__messages {
    width: 100%;
    padding:
      0.75rem
      0.75rem
      calc(9.5rem + var(--bottom-nav-height) + env(safe-area-inset-bottom, 0px) + var(--keyboard-offset));
    scroll-padding-bottom: calc(9.5rem + var(--bottom-nav-height) + env(safe-area-inset-bottom, 0px) + var(--keyboard-offset));
  }

  .knowledge-chat__projects {
    width: 100%;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
    padding: 0 0.75rem 0.5rem;
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
    position: fixed;
    left: 0.75rem;
    right: 0.75rem;
    bottom: calc(
      var(--bottom-nav-height)
      + env(safe-area-inset-bottom, 0px)
      + 0.65rem
      + var(--keyboard-offset)
    );
    z-index: 48;
    width: auto;
    margin: 0;
    padding: 0.65rem;
    transition: bottom 180ms ease;
  }

  .knowledge-chat__tools :deep(.el-segmented) {
    max-width: calc(100vw - 5.75rem);
  }
}
</style>
