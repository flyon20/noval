<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ArrowDown, CircleClose, Delete, MagicStick, Plus, Promotion } from '@element-plus/icons-vue';
import { knowledgeApi } from '@/api/knowledge';
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
import type { SkillShortcut } from '@/types/knowledge';
import { knowledgeUserStatusLabel, memoryLayerLabel } from '@/utils/knowledgeDisplay';

defineOptions({
  name: 'KnowledgeChatView',
});

const {
  state,
  canSend,
  reasoningTiers,
  reasoningIsToggle,
  modelGroups,
  loadModelOptions,
  selectModel,
  selectReasoningEffort,
  selectReasoningMode,
  sendQuestion,
  selectCandidate,
  loadProjects,
  selectProject,
  loadConversationRun,
  clearConversation,
  startNewConversation,
  cancelActiveRun,
  handleVisibilityChange,
  loadMessageProcess,
  dispose,
  deleteMessage,
} = useKnowledgeChat();
const { keyboardStyle } = useVisualViewportKeyboard();
const messagesRef = ref<HTMLElement | null>(null);
const contextBudgetRef = ref<HTMLElement | null>(null);
const contextTriggerRef = ref<HTMLButtonElement | null>(null);
const stickToBottom = ref(true);
const showScrollToBottom = ref(false);
const contextDetailsOpen = ref(false);
const skillShortcuts = ref<SkillShortcut[]>([]);
const numberFormatter = new Intl.NumberFormat('zh-CN');

/** 贴底判定的容差：流式增量每来一帧都置底，会把正在上翻的人反复拽回去。 */
const STICK_TO_BOTTOM_THRESHOLD_PX = 96;

const quickPrompts = [
  '凡人修仙传开篇卖点是什么？',
  '最近男频题材趋势是什么？',
  '修仙文开局怎么设计爽点？',
];

const REASONING_TIER_LABELS: Record<string, string> = {
  minimal: '最小',
  low: '低',
  medium: '中',
  high: '高',
  xhigh: '极高',
  max: '最高',
};

const PROVIDER_LABELS: Record<string, string> = {
  openai: 'OpenAI',
  'openai-compatible': '通用 OpenAI 兼容',
  deepseek: 'DeepSeek',
  moonshot: 'Kimi',
  zhipu: '智谱 GLM',
  qwen: '通义千问',
  anthropic: 'Claude',
  dify: 'Dify',
};

const reasoningTierOptions = computed(() => reasoningTiers.value.map((tier) => ({
  label: REASONING_TIER_LABELS[tier] ?? tier,
  value: tier,
})));
const reasoningToggleActive = computed(() => (
  reasoningIsToggle.value && state.reasoningEffort === reasoningTiers.value[reasoningTiers.value.length - 1]
));

function providerLabel(providerType: string) {
  return PROVIDER_LABELS[providerType] ?? providerType;
}

function toggleReasoning(active: boolean | string | number) {
  const tiers = reasoningTiers.value;
  selectReasoningEffort(active ? tiers[tiers.length - 1] : tiers[0]);
}

function toggleContextDetails() {
  contextDetailsOpen.value = !contextDetailsOpen.value;
}

function closeContextDetails(restoreFocus = false) {
  contextDetailsOpen.value = false;
  if (restoreFocus) {
    void nextTick(() => contextTriggerRef.value?.focus());
  }
}

function handleContextPointerDown(event: PointerEvent) {
  if (!contextDetailsOpen.value) {
    return;
  }
  const target = event.target;
  if (!(target instanceof Node) || !contextBudgetRef.value?.contains(target)) {
    closeContextDetails();
  }
}

function handleContextKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !contextDetailsOpen.value) {
    return;
  }
  event.preventDefault();
  closeContextDetails(true);
}

const contextBudget = computed(() => state.contextBudget);
const memoryLayerCount = computed(() => contextBudget.value?.memoryLayers?.length ?? 0);
const contextTraceId = computed(() => state.traceId || [...state.messages].reverse().find((message) => message.traceId)?.traceId || '');
const activeConversationTitle = computed(() => {
  if (!state.conversationId) {
    return '当前会话';
  }
  const conversation = state.conversations.find((item) => item.conversationId === state.conversationId);
  return conversationTitle(conversation?.title);
});
const contextUsedTokens = computed(() => firstNonNegativeNumber(
  contextBudget.value?.usedTokens,
  contextBudget.value?.observedInputTokens,
  contextBudget.value?.estimatedUsedTokens,
));
const contextUsedRatio = computed(() => {
  const maxTokens = firstNonNegativeNumber(contextBudget.value?.maxInputTokens);
  if (maxTokens !== undefined && maxTokens > 0 && contextUsedTokens.value !== undefined) {
    return Math.max(0, Math.min(1, contextUsedTokens.value / maxTokens));
  }
  const remainingRatio = firstNonNegativeNumber(contextBudget.value?.remainingRatio);
  return remainingRatio === undefined ? undefined : Math.max(0, Math.min(1, 1 - remainingRatio));
});
const contextMeterWidth = computed(() => `${Math.round((contextUsedRatio.value ?? 0) * 100)}%`);
const contextRingDasharray = computed(() => `${((contextUsedRatio.value ?? 0) * 100).toFixed(1)} 100`);
const contextUsedPercent = computed(() => (
  contextUsedRatio.value === undefined ? '--' : `${Math.round(contextUsedRatio.value * 100)}%`
));
const contextPressureClass = computed(() => {
  const ratio = contextUsedRatio.value ?? 0;
  if (ratio >= 0.9) {
    return 'is-danger';
  }
  if (ratio >= 0.75) {
    return 'is-warning';
  }
  return 'is-normal';
});
const contextCompressionStatus = computed(() => {
  if (contextBudget.value?.compacting) {
    return '正在自动压缩';
  }
  if (contextBudget.value?.compressed) {
    return '已压缩，容量已刷新';
  }
  return '未压缩';
});
const contextAriaLabel = computed(() => {
  const used = contextUsedTokens.value === undefined
    ? '未知'
    : `${formatTokenCount(contextUsedTokens.value)} tokens`;
  const ratio = contextUsedRatio.value === undefined ? '未知' : formatRatio(contextUsedRatio.value);
  return `上下文容量，已用 ${used}，占 ${ratio}，${contextCompressionStatus.value}`;
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
      const name = memoryLayerLabel(layer.name);
      const status = knowledgeUserStatusLabel(layer.status, '状态未知');
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

function firstNonNegativeNumber(...values: unknown[]) {
  for (const value of values) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    const numeric = Number(value);
    if (Number.isFinite(numeric) && numeric >= 0) {
      return numeric;
    }
  }
  return undefined;
}

function handleMessagesScroll() {
  const element = messagesRef.value;
  if (!element) {
    return;
  }
  const distance = Math.max(0, element.scrollHeight - element.scrollTop - element.clientHeight);
  stickToBottom.value = distance <= STICK_TO_BOTTOM_THRESHOLD_PX;
  showScrollToBottom.value = !stickToBottom.value && state.messages.length > 0;
}

async function scrollMessagesToBottom(options?: { force?: boolean }) {
  await nextTick();
  const element = messagesRef.value;
  if (!element) {
    return;
  }
  if (!options?.force && !stickToBottom.value) {
    return;
  }
  element.scrollTop = element.scrollHeight;
  stickToBottom.value = true;
  showScrollToBottom.value = false;
}

/** 自己按下发送就该看最新一条：不管刚才翻到哪里，都恢复跟随。 */
function submitQuestion() {
  stickToBottom.value = true;
  showScrollToBottom.value = false;
  return sendQuestion();
}

function handleProjectChange(event: Event) {
  const detail = getKnowledgeProjectChangeDetail(event);
  selectProject(detail.projectId, true, detail.workId, detail.referenceWorkIds);
}

function handleConversationSelect(event: Event) {
  const detail = getKnowledgeConversationSelectDetail(event);
  selectProject(detail.projectId, false, detail.workId, detail.referenceWorkIds);
  void loadConversationRun(detail.conversationId);
}

function conversationTitle(title?: string) {
  const normalized = String(title || '').trim();
  return !normalized || normalized.toLowerCase() === 'new conversation' ? '新会话' : normalized;
}

async function loadSkillShortcuts() {
  try {
    const response = await knowledgeApi.listSkillShortcuts();
    skillShortcuts.value = response.data.data ?? [];
    if (state.preferredSkillId
      && !skillShortcuts.value.some((shortcut) => shortcut.skillId === state.preferredSkillId)) {
      state.preferredSkillId = '';
    }
  } catch {
    skillShortcuts.value = [];
    state.preferredSkillId = '';
  }
}

function selectSkillShortcut(skillId: string) {
  state.preferredSkillId = skillId;
}

onMounted(async () => {
  window.addEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, handleProjectChange);
  window.addEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, handleConversationSelect);
  document.addEventListener('visibilitychange', handleVisibilityChange);
  document.addEventListener('pointerdown', handleContextPointerDown);
  document.addEventListener('keydown', handleContextKeydown);
  await Promise.all([loadProjects(), loadSkillShortcuts(), loadModelOptions()]);
  await scrollMessagesToBottom({ force: true });
});

onBeforeUnmount(() => {
  window.removeEventListener(KNOWLEDGE_PROJECT_CHANGE_EVENT, handleProjectChange);
  window.removeEventListener(KNOWLEDGE_CONVERSATION_SELECT_EVENT, handleConversationSelect);
  document.removeEventListener('visibilitychange', handleVisibilityChange);
  document.removeEventListener('pointerdown', handleContextPointerDown);
  document.removeEventListener('keydown', handleContextKeydown);
  dispose();
});

watch(
  () => [
    state.messages.map((message) => message.content).join('|'),
    state.candidates.length,
    state.loading,
  ],
  () => {
    void scrollMessagesToBottom();
  },
);
</script>

<template>
  <main class="knowledge-chat" :style="keyboardStyle">
    <header v-if="state.messages.length || state.conversations.length" class="knowledge-chat__toolbar">
      <strong data-test="knowledge-current-conversation">{{ activeConversationTitle }}</strong>
      <div class="knowledge-chat__toolbar-actions">
        <div
          v-if="contextBudget"
          ref="contextBudgetRef"
          class="knowledge-chat__context-budget"
          :class="{ 'is-open': contextDetailsOpen }"
          data-test="knowledge-context-budget"
        >
          <button
            type="button"
            ref="contextTriggerRef"
            class="knowledge-chat__context-trigger"
            :class="[
              contextPressureClass,
              { 'is-compacting': contextBudget.compacting },
            ]"
            :aria-label="contextAriaLabel"
            :title="contextAriaLabel"
            :aria-expanded="contextDetailsOpen"
            aria-controls="knowledge-context-popover"
            data-test="knowledge-context-trigger"
            @click="toggleContextDetails"
          >
            <svg class="knowledge-chat__context-ring" viewBox="0 0 36 36" aria-hidden="true">
              <circle class="knowledge-chat__context-ring-track" cx="18" cy="18" r="15.5" pathLength="100" />
              <circle
                class="knowledge-chat__context-ring-value"
                data-test="knowledge-context-ring-value"
                cx="18"
                cy="18"
                r="15.5"
                pathLength="100"
                :stroke-dasharray="contextRingDasharray"
              />
            </svg>
            <span class="knowledge-chat__context-percent" data-test="knowledge-context-percent">
              {{ contextUsedPercent }}
            </span>
          </button>
          <div
            id="knowledge-context-popover"
            class="knowledge-chat__context-popover"
            role="region"
            aria-label="上下文容量"
            :aria-hidden="!contextDetailsOpen"
            :tabindex="contextDetailsOpen ? 0 : -1"
            data-test="knowledge-context-popover"
          >
            <header>
              <strong>上下文容量</strong>
              <span v-if="contextUsedRatio !== undefined">已用 {{ formatRatio(contextUsedRatio) }}</span>
            </header>
            <div class="knowledge-chat__context-meter" aria-hidden="true">
              <span :class="contextPressureClass" :style="{ width: contextMeterWidth }" />
            </div>
            <dl>
              <div>
                <dt>已用</dt>
                <dd>{{ formatTokenCount(contextUsedTokens) }} tokens</dd>
              </div>
              <div>
                <dt>剩余</dt>
                <dd>{{ formatRemainingRatio(contextBudget.remainingRatio) }}</dd>
              </div>
              <div v-if="contextBudget.maxInputTokens != null" class="knowledge-chat__context-secondary">
                <dt>总容量</dt>
                <dd>{{ formatTokenCount(contextBudget.maxInputTokens) }} tokens</dd>
              </div>
              <div v-if="contextBudget.remainingTokens != null" class="knowledge-chat__context-secondary">
                <dt>剩余容量</dt>
                <dd>{{ formatTokenCount(contextBudget.remainingTokens) }} tokens</dd>
              </div>
              <div v-if="compressionThresholdTokens" class="knowledge-chat__context-secondary">
                <dt>压缩阈值</dt>
                <dd>{{ formatTokenCount(compressionThresholdTokens) }} tokens</dd>
              </div>
              <div>
                <dt>状态</dt>
                <dd data-test="knowledge-context-status">
                  {{ contextCompressionStatus }}
                  <span class="knowledge-chat__context-secondary"> · 记忆层 {{ memoryLayerCount }}</span>
                </dd>
              </div>
            </dl>
            <p class="knowledge-chat__context-secondary">{{ memoryLayerSummary }}</p>
            <small v-if="contextTraceId" class="knowledge-chat__context-secondary">Trace {{ contextTraceId }}</small>
          </div>
        </div>
        <el-tooltip content="新建会话" placement="bottom">
          <el-button
            data-test="knowledge-new-chat"
            size="small"
            circle
            :icon="Plus"
            :disabled="state.creatingConversation || (state.loading && !state.pendingRunId)"
            aria-label="新建会话"
            @click="startNewConversation"
          />
        </el-tooltip>
        <el-tooltip content="清空当前会话" placement="bottom">
          <el-button
            data-test="knowledge-clear-chat"
            size="small"
            circle
            :icon="Delete"
            :disabled="state.loading"
            aria-label="清空当前会话"
            @click="clearConversation"
          />
        </el-tooltip>
      </div>
    </header>

    <section
      ref="messagesRef"
      class="knowledge-chat__messages"
      aria-live="polite"
      @scroll.passive="handleMessagesScroll"
    >
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
        :key="`${message.role}-${message.runId || index}-${index}`"
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
        :process="message.process"
        :deletable="!state.loading"
        :delete-test-id="`knowledge-delete-message-${index}`"
        :copy-test-id="`knowledge-copy-message-${index}`"
        @delete="deleteMessage(index)"
        @load-process="loadMessageProcess(message)"
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

    <div v-if="showScrollToBottom" class="knowledge-chat__scroll-dock">
      <button
        type="button"
        class="knowledge-chat__scroll-bottom"
        data-test="knowledge-scroll-bottom"
        aria-label="回到底部"
        title="回到底部"
        @click="scrollMessagesToBottom({ force: true })"
      >
        <el-icon :size="14"><ArrowDown /></el-icon>
      </button>
    </div>

    <el-alert
      v-if="state.errorMessage"
      class="knowledge-chat__error"
      type="error"
      :closable="false"
      :title="state.errorMessage"
      show-icon
    />

    <form class="knowledge-chat__composer" @submit.prevent="submitQuestion">
      <div class="knowledge-chat__skill-shortcuts" data-test="knowledge-skill-shortcuts">
        <span class="knowledge-chat__skill-label">
          <el-icon :size="16"><MagicStick /></el-icon>
          <span>Skill</span>
        </span>
        <div class="knowledge-chat__skill-options" role="radiogroup" aria-label="专业 Skill 快捷选择">
          <el-tooltip content="由 Harness 根据问题意图自动选择" placement="top">
            <button
              type="button"
              class="knowledge-chat__skill-option"
              :class="{ 'is-active': !state.preferredSkillId }"
              :aria-checked="!state.preferredSkillId"
              :disabled="state.loading"
              role="radio"
              data-test="knowledge-skill-auto"
              @click="state.preferredSkillId = ''"
            >
              自动路由
            </button>
          </el-tooltip>
          <el-tooltip
            v-for="shortcut in skillShortcuts"
            :key="shortcut.skillId"
            :content="shortcut.description || shortcut.title"
            placement="top"
          >
            <button
              type="button"
              class="knowledge-chat__skill-option"
              :class="{ 'is-active': state.preferredSkillId === shortcut.skillId }"
              :aria-checked="state.preferredSkillId === shortcut.skillId"
              :aria-label="shortcut.title"
              :disabled="state.loading"
              role="radio"
              :data-test="`knowledge-skill-${shortcut.skillId}`"
              @click="selectSkillShortcut(shortcut.skillId)"
            >
              {{ shortcut.title }}
            </button>
          </el-tooltip>
        </div>
      </div>

      <div class="knowledge-chat__composer-main">
        <div class="knowledge-chat__input" data-test="knowledge-question-input">
          <el-input
            v-model="state.question"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 5 }"
            resize="none"
            placeholder="问网文相关问题"
            aria-label="网文 AI 问答输入"
            :disabled="state.loading"
            @keydown.enter.exact.prevent="submitQuestion"
          />
        </div>
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
          @click="submitQuestion"
        />
      </div>

      <div class="knowledge-chat__tools">
        <div class="knowledge-chat__tool-options">
          <el-button
            v-if="state.pendingRunId"
            data-test="knowledge-cancel-run"
            size="small"
            type="danger"
            plain
            :icon="CircleClose"
            @click="cancelActiveRun"
          >
            取消
          </el-button>
          <el-select
            v-if="state.modelOptions.length"
            :model-value="state.modelKey"
            data-test="knowledge-model-picker"
            class="knowledge-chat__model-picker"
            size="small"
            :disabled="state.loading"
            aria-label="AI 问答模型"
            @update:model-value="selectModel"
          >
            <el-option-group
              v-for="group in modelGroups"
              :key="group.providerType"
              :label="providerLabel(group.providerType)"
            >
              <el-option
                v-for="option in group.options"
                :key="option.modelKey"
                :label="option.displayName || option.modelKey"
                :value="option.modelKey"
              />
            </el-option-group>
          </el-select>
          <el-segmented
            v-if="reasoningTierOptions.length > 2"
            :model-value="state.reasoningEffort"
            data-test="knowledge-reasoning-effort"
            size="small"
            :options="reasoningTierOptions"
            :disabled="state.loading"
            aria-label="AI 问答思考强度"
            @update:model-value="selectReasoningEffort"
          />
          <el-switch
            v-else-if="reasoningIsToggle"
            :model-value="reasoningToggleActive"
            data-test="knowledge-reasoning-toggle"
            size="small"
            inline-prompt
            active-text="思考"
            inactive-text="直答"
            :disabled="state.loading"
            aria-label="AI 问答是否开启思考"
            @update:model-value="toggleReasoning"
          />
          <el-segmented
            v-else
            :model-value="state.reasoningMode"
            data-test="knowledge-reasoning-mode"
            size="small"
            :options="[
              { label: '快速', value: 'fast' },
              { label: '深度', value: 'deep' },
            ]"
            :disabled="state.loading"
            aria-label="AI 问答推理模式"
            @update:model-value="selectReasoningMode"
          />
          <el-segmented
            v-model="state.chapterCount"
            size="small"
            :options="[3, 5, 10]"
            :disabled="state.loading"
            aria-label="抓取章节数"
          />
        </div>
      </div>
    </form>

  </main>
</template>

<style scoped lang="scss">
.knowledge-chat {
  --keyboard-offset: 0px;
  height: auto;
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  grid-template-areas:
    'toolbar'
    'messages'
    'error'
    'composer';
  padding-bottom: 1rem;
  overflow: hidden;
}

.knowledge-chat__toolbar,
.knowledge-chat__messages,
.knowledge-chat__error,
.knowledge-chat__composer {
  min-width: 0;
  max-width: 100%;
}

.knowledge-chat__toolbar {
  grid-area: toolbar;
  position: relative;
  z-index: 5;
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

.knowledge-chat__toolbar > strong {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text);
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-chat__toolbar-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.knowledge-chat__toolbar-actions :deep(.el-button) {
  flex: 0 0 36px;
  width: 36px;
  min-width: 36px;
  height: 36px;
  min-height: 36px;
  aspect-ratio: 1;
  padding: 0;
}

.knowledge-chat__messages {
  grid-area: messages;
  min-height: 0;
  width: min(100%, 880px);
  justify-self: center;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem 1rem 1.5rem;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  touch-action: pan-y;
}

/* 回到底部键叠在 messages 区里：新增 grid 行会改 grid-template-areas，那是有测试锁的。
   容器铺满整栏但不吃事件，只有按钮本身可点。 */
.knowledge-chat__scroll-dock {
  grid-area: messages;
  z-index: 4;
  width: min(100%, 880px);
  justify-self: center;
  align-self: end;
  display: flex;
  justify-content: flex-end;
  padding: 0 1rem 0.75rem;
  pointer-events: none;
}

.knowledge-chat__scroll-bottom {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-text-muted);
  background: var(--color-surface-strong);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  pointer-events: auto;
  transition: color 140ms ease, border-color 140ms ease;
}

.knowledge-chat__scroll-bottom:hover,
.knowledge-chat__scroll-bottom:focus-visible {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.knowledge-chat__scroll-bottom:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--color-primary) 55%, transparent);
  outline-offset: 2px;
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
  grid-area: error;
  width: min(100%, 880px);
  justify-self: center;
}

.knowledge-chat__context-budget {
  position: static;
  flex: 0 0 auto;
}

.knowledge-chat__context-trigger {
  position: relative;
  flex: 0 0 36px;
  width: 36px;
  min-width: 36px;
  height: 36px;
  min-height: 36px;
  aspect-ratio: 1;
  display: inline-grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
}

.knowledge-chat__context-trigger:hover,
.knowledge-chat__context-trigger:focus-visible {
  color: var(--el-color-primary);
  outline: 2px solid color-mix(in srgb, var(--el-color-primary) 55%, transparent);
  outline-offset: 2px;
}

.knowledge-chat__context-trigger.is-warning {
  color: var(--el-color-warning);
}

.knowledge-chat__context-trigger.is-danger {
  color: var(--el-color-danger);
}

.knowledge-chat__context-ring {
  width: 28px;
  height: 28px;
  overflow: visible;
  transform: rotate(-90deg);
}

.knowledge-chat__context-ring-track,
.knowledge-chat__context-ring-value {
  fill: none;
  stroke-width: 3;
}

.knowledge-chat__context-ring-track {
  stroke: color-mix(in srgb, var(--color-border-strong) 72%, transparent);
}

.knowledge-chat__context-ring-value {
  stroke: currentColor;
  stroke-linecap: round;
  transition: stroke-dasharray 180ms ease, opacity 180ms ease;
}

.knowledge-chat__context-trigger.is-compacting .knowledge-chat__context-ring-value {
  animation: knowledge-context-pulse 900ms ease-in-out infinite alternate;
}

.knowledge-chat__context-percent {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: currentColor;
  font-size: 0.55rem;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  line-height: 1;
}

.knowledge-chat__context-popover {
  position: absolute;
  z-index: 30;
  top: calc(100% + 0.55rem);
  right: 1rem;
  width: min(340px, calc(100% - 2rem));
  max-height: min(300px, calc(100dvh - 14rem - var(--keyboard-offset)));
  overflow: auto;
  overscroll-behavior: contain;
  display: grid;
  gap: 0.65rem;
  padding: 0.8rem;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  box-shadow: var(--shadow-card);
  background: var(--color-surface-strong);
  color: var(--color-text-muted);
  font-size: 0.78rem;
  line-height: 1.45;
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  transform: translateY(4px);
  transition: opacity 120ms ease, transform 120ms ease, visibility 120ms ease;
}

@keyframes knowledge-context-pulse {
  from {
    opacity: 0.42;
  }
  to {
    opacity: 1;
  }
}

.knowledge-chat__context-budget.is-open .knowledge-chat__context-popover {
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
  transform: translateY(0);
}

.knowledge-chat__context-popover header,
.knowledge-chat__context-popover dl > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.knowledge-chat__context-popover strong {
  color: var(--color-text);
  font-weight: 650;
}

.knowledge-chat__context-popover dl,
.knowledge-chat__context-popover dd,
.knowledge-chat__context-popover p {
  margin: 0;
}

.knowledge-chat__context-popover dl {
  display: grid;
  gap: 0.3rem;
}

.knowledge-chat__context-popover dd {
  color: var(--color-text);
  text-align: right;
}

.knowledge-chat__context-popover p,
.knowledge-chat__context-popover small {
  overflow-wrap: anywhere;
}

.knowledge-chat__context-meter {
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--color-border);
}

.knowledge-chat__context-meter span {
  height: 100%;
  display: block;
  border-radius: inherit;
  background: var(--el-color-primary);
}

.knowledge-chat__context-meter span.is-warning {
  background: var(--el-color-warning);
}

.knowledge-chat__context-meter span.is-danger {
  background: var(--el-color-danger);
}

.knowledge-chat__composer {
  grid-area: composer;
  width: min(100%, 880px);
  justify-self: center;
  display: grid;
  gap: 0.6rem;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--color-primary-soft) 38%, transparent), transparent 48%),
    color-mix(in srgb, var(--color-surface) 96%, transparent);
  box-shadow: var(--shadow-card);
}

.knowledge-chat__composer-main {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 0.6rem;
}

.knowledge-chat__skill-shortcuts {
  min-width: 0;
  max-width: 100%;
  display: flex;
  align-items: center;
  gap: 0.55rem;
}

.knowledge-chat__skill-label {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  color: var(--color-text-muted);
  font-size: 0.78rem;
  font-weight: 650;
}

.knowledge-chat__skill-options {
  min-width: 0;
  max-width: 100%;
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding-bottom: 0.1rem;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
  scrollbar-width: thin;
}

.knowledge-chat__skill-option {
  min-height: 44px;
  flex: 0 0 auto;
  padding: 0 0.65rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  color: var(--color-text-muted);
  background: var(--color-surface);
  font-size: 0.78rem;
  white-space: nowrap;
  cursor: pointer;
}

.knowledge-chat__skill-option:hover,
.knowledge-chat__skill-option:focus-visible {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  outline: none;
}

.knowledge-chat__skill-option.is-active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--gradient-soft);
}

.knowledge-chat__skill-option:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.knowledge-chat__input :deep(.el-textarea__inner) {
  min-height: 46px !important;
  border-radius: 8px;
  line-height: 1.6;
}

.knowledge-chat__tools {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.knowledge-chat__tool-options {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.55rem;
  flex-wrap: wrap;
}

.knowledge-chat__model-picker {
  width: 11rem;
  max-width: 45vw;
}

.knowledge-chat__send {
  min-width: 44px;
  width: 44px;
  min-height: 44px;
  height: 44px;
  flex: 0 0 auto;
  aspect-ratio: 1;
  padding: 0;
}

@media (max-width: 768px) {
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
    background: var(--color-bg);
    overscroll-behavior: none;
  }

  .knowledge-chat__messages {
    width: 100%;
    gap: 0.85rem;
    padding: 0.55rem 0.75rem 1rem;
    scroll-padding-bottom: 1rem;
  }

  .knowledge-chat__toolbar {
    position: relative;
    width: 100%;
    min-height: 36px;
    padding: 0 0.75rem;
    border-bottom: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
    background: var(--color-surface-strong);
    font-size: 0.8rem;
  }

  .knowledge-chat__toolbar-actions {
    gap: 0.5rem;
  }

  .knowledge-chat__toolbar-actions :deep(.el-button) {
    position: relative;
    flex-basis: 36px;
    width: 36px;
    min-width: 36px;
    height: 36px;
    min-height: 36px;
  }

  .knowledge-chat__toolbar-actions :deep(.el-button)::after {
    content: '';
    position: absolute;
    inset: -4px;
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
    width: 100%;
    gap: 0.35rem;
    margin: 0;
    padding: 0.35rem 0.75rem calc(0.3rem + env(safe-area-inset-bottom, 0px));
    border: 0;
    border-radius: 0;
    background: transparent;
    box-shadow: none;
  }

  .knowledge-chat__skill-shortcuts {
    width: 100%;
    gap: 0.25rem;
    overflow: hidden;
  }

  .knowledge-chat__skill-label {
    width: 32px;
    height: 36px;
    justify-content: center;
  }

  .knowledge-chat__skill-label > span {
    display: none;
  }

  .knowledge-chat__skill-options {
    flex: 1 1 auto;
    gap: 0.3rem;
    padding: 0;
    scrollbar-width: none;
    touch-action: pan-x;
  }

  .knowledge-chat__skill-options::-webkit-scrollbar,
  .knowledge-chat__tools::-webkit-scrollbar {
    display: none;
  }

  .knowledge-chat__skill-option {
    position: relative;
    min-height: 36px;
    height: 36px;
    padding: 0 0.5rem;
    border-radius: 6px;
    font-size: 0.72rem;
  }

  .knowledge-chat__skill-option::after {
    content: '';
    position: absolute;
    inset: -4px 0;
  }

  .knowledge-chat__composer-main {
    grid-template-columns: minmax(0, 1fr) 44px;
    align-items: end;
    gap: 0.4rem;
    padding: 0.25rem 0.35rem 0.25rem 0.8rem;
    border: 1px solid color-mix(in srgb, var(--color-border-strong) 72%, transparent);
    border-radius: 18px;
    background: var(--color-surface-strong);
    box-shadow: none;
  }

  .knowledge-chat__input :deep(.el-textarea__inner) {
    min-height: 44px !important;
    padding: 0.65rem 0;
    border-radius: 0;
    background: transparent;
    box-shadow: none;
  }

  .knowledge-chat__tools {
    min-width: 0;
    flex-wrap: nowrap;
    gap: 0.4rem;
    overflow-x: auto;
    overflow-y: hidden;
    overscroll-behavior-inline: contain;
    scrollbar-width: none;
    touch-action: pan-x;
  }

  .knowledge-chat__tool-options {
    width: max-content;
    flex: 0 0 auto;
    flex-wrap: nowrap;
    gap: 0.4rem;
  }

  .knowledge-chat__tools :deep(.el-segmented) {
    min-height: 44px;
    padding: 3px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--color-surface-strong) 86%, var(--color-border));
  }

  .knowledge-chat__tools :deep(.el-segmented__item) {
    min-height: 38px;
    padding: 0 0.7rem;
    border-radius: 999px;
  }

  .knowledge-chat__tools :deep(.el-segmented),
  .knowledge-chat__tools :deep(.el-button) {
    min-height: 44px;
    flex: 0 0 auto;
  }

  .knowledge-chat__send {
    flex: 0 0 44px;
    min-width: 44px;
    width: 44px;
    min-height: 44px;
    height: 44px;
    aspect-ratio: 1;
  }

  .knowledge-chat__context-trigger {
    flex: 0 0 36px;
    width: 36px;
    min-width: 36px;
    height: 36px;
    min-height: 36px;
    aspect-ratio: 1;
    background: transparent;
  }

  .knowledge-chat__context-trigger::after {
    content: '';
    position: absolute;
    inset: -4px;
  }

  .knowledge-chat__context-budget {
    position: static;
  }

  .knowledge-chat__context-popover {
    top: calc(100% + 0.35rem);
    left: auto;
    right: 0.75rem;
    width: min(300px, calc(100% - 1.5rem));
    max-height: min(220px, calc(100dvh - var(--bottom-nav-height) - 4rem - var(--keyboard-offset)));
    gap: 0.5rem;
    padding: 0.65rem;
    overflow-x: hidden;
    overflow-y: auto;
    overscroll-behavior: contain;
  }

  .knowledge-chat__context-popover dl {
    gap: 0.2rem;
  }

  .knowledge-chat__context-secondary {
    display: none;
  }

  .knowledge-chat__scroll-dock {
    width: 100%;
    padding: 0 0.75rem 0.6rem;
  }

  .knowledge-chat__scroll-bottom {
    position: relative;
    width: 36px;
    height: 36px;
  }

  .knowledge-chat__scroll-bottom::after {
    content: '';
    position: absolute;
    inset: -4px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .knowledge-chat__context-ring-value,
  .knowledge-chat__context-popover {
    transition: none;
  }

  .knowledge-chat__context-trigger.is-compacting .knowledge-chat__context-ring-value {
    animation: none;
  }
}
</style>
