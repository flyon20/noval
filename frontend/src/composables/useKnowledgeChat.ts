import { computed, reactive } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import { getErrorPayload } from '@/lib/http-error';
import { createStreamingPlaybackController } from '@/lib/streaming-playback';
import {
  getStoredKnowledgeProjectId,
  setStoredKnowledgeProjectId,
} from '@/composables/useKnowledgeProjectSelection';
import type {
  ContextBudget,
  KnowledgeBookCandidate,
  KnowledgeChatMessage,
  KnowledgeChatResponse,
  KnowledgeChatRun,
  KnowledgeProject,
  KnowledgeReasoningMode,
  KnowledgeSource,
} from '@/types/knowledge';

const DEFAULT_LIMITS = {
  candidateLimit: 5,
  evidenceLimit: 5,
  rankLimit: 30,
  chapterCount: 3,
  timeoutMillis: 600000,
};

interface KnowledgeMessage extends KnowledgeChatMessage {
  content: string;
  status?: string;
  answerStatus?: string;
  intent?: string;
  answerBoundary?: string;
  sources?: KnowledgeSource[];
  fallbackUsed?: boolean;
  degraded?: boolean;
  degradationReasons?: string[];
  contextBudget?: ContextBudget;
  traceId?: string;
}

const MAX_CONTEXT_SUMMARY_LENGTH = 900000;
const MAX_HISTORY_MESSAGES = 12;
const MAX_HISTORY_CONTENT_LENGTH = 64000;
const STORAGE_KEY = 'noval:knowledge-chat:draft:v1';
const PROJECT_STORAGE_PREFIX = 'noval:knowledge-chat:project:v1:';
const MAX_PERSISTED_MESSAGES = 40;
const DURABLE_RUN_POLL_INTERVAL_MS = 1500;
const DURABLE_RUN_MAX_ACTIVE_MS = 660000;
const DEFAULT_REASONING_MODE: KnowledgeReasoningMode = 'fast';

interface PersistedKnowledgeChatState {
  conversationId?: string;
  messages?: KnowledgeMessage[];
  contextSummary?: string;
  chapterCount?: number;
  reasoningMode?: KnowledgeReasoningMode;
  bookName?: string;
  selectedCandidate?: KnowledgeBookCandidate | null;
  candidates?: KnowledgeBookCandidate[];
  sources?: KnowledgeSource[];
  status?: string;
  answer?: string;
  traceId?: string;
  contextBudget?: ContextBudget | null;
  pendingRunId?: string;
  activeProjectId?: number | null;
}

export function useKnowledgeChat() {
  let durableRunPollTimer: number | undefined;
  let durableRunPollStartedAt = 0;
  const state = reactive({
    question: '',
    bookName: '',
    chapterCount: 3,
    reasoningMode: DEFAULT_REASONING_MODE as KnowledgeReasoningMode,
    loading: false,
    errorMessage: '',
    status: '',
    answer: '',
    messages: [] as KnowledgeMessage[],
    candidates: [] as KnowledgeBookCandidate[],
    sources: [] as KnowledgeSource[],
    actions: [] as string[],
    selectedCandidate: null as KnowledgeBookCandidate | null,
    contextSummary: '',
    traceId: '',
    contextBudget: null as ContextBudget | null,
    conversationId: '',
    pendingRunId: '',
    projects: [] as KnowledgeProject[],
    activeProjectId: null as number | null,
    projectNameDraft: '',
  });
  restoreState();
  resumePendingRun();

  const canSend = computed(() => state.question.trim().length > 0 && !state.loading);

  async function sendQuestion() {
    if (!canSend.value) {
      return;
    }
    const question = state.question.trim();
    state.question = '';
    await submit({
      question,
    });
  }

  async function loadProjects() {
    const response = await knowledgeApi.listProjects();
    state.projects = response.data.data ?? [];
    const activeProjectStillExists = state.activeProjectId
      ? state.projects.some((project) => project.projectId === state.activeProjectId)
      : false;
    if (state.activeProjectId && !activeProjectStillExists) {
      selectProject(state.projects[0]?.projectId ?? null);
      return;
    }
    if (!state.activeProjectId && state.projects.length) {
      selectProject(state.projects[0].projectId);
    }
  }

  async function createProject() {
    const name = state.projectNameDraft.trim();
    if (!name) {
      return;
    }
    const response = await knowledgeApi.createProject({ name });
    const project = response.data.data;
    state.projects = [project, ...state.projects.filter((item) => item.projectId !== project.projectId)];
    state.projectNameDraft = '';
    selectProject(project.projectId);
  }

  function selectProject(projectId: number | null) {
    persistState();
    state.activeProjectId = projectId;
    persistActiveProjectId();
    clearVolatileConversation();
    restoreState();
    state.activeProjectId = projectId;
    persistState();
  }

  async function selectCandidate(candidate: KnowledgeBookCandidate) {
    const question = state.question.trim() || latestUserQuestion();
    if (!question) {
      return;
    }
    state.selectedCandidate = candidate;
    state.bookName = candidate.bookName;
    await submit({
      question,
      bookName: candidate.bookName,
      selectedCandidate: candidate,
    });
  }

  async function submit(payload: { question: string; bookName?: string; selectedCandidate?: KnowledgeBookCandidate }) {
    state.loading = true;
    state.errorMessage = '';
    state.answer = '';
    state.status = '';
    const conversationId = ensureConversationId();
    if (!payload.selectedCandidate) {
      state.messages.push({ role: 'user', content: payload.question });
      persistState();
    }

    let assistantMessage: KnowledgeMessage | null = null;
    let sawDelta = false;
    let streamedAnswer = '';
    let doneApplied = false;
    const ensureAssistantMessage = () => {
      if (!assistantMessage) {
        assistantMessage = { role: 'assistant', content: '', status: 'streaming', sources: [] };
        state.messages.push(assistantMessage);
      }
      return assistantMessage;
    };
    const playback = createStreamingPlaybackController((text) => {
      const message = ensureAssistantMessage();
      message.content = text;
      state.answer = text;
      persistState();
    }, {
      charsPerTick: 8,
      tickMillis: 18,
      targetDurationMs: 900,
    });
    const applyStreamDone = async (response: KnowledgeChatResponse) => {
      if (!sawDelta) {
        applyResponse(response, ensureAssistantMessage());
        playback.destroy();
        return;
      }

      const shouldReplaceStream = shouldReplaceStreamWithFinalAnswer(response);
      const finalText = shouldReplaceStream
        ? response.answer || assistantMessage?.content || ''
        : streamedAnswer || assistantMessage?.content || response.answer || '';
      await playback.flushTo(finalText);
      applyResponse(response, assistantMessage, {
        preserveStreamingContent: !shouldReplaceStream,
      });
      playback.destroy();
    };
    let donePromise: Promise<void> | null = null;
    try {
      const requestPayload = {
        question: payload.question,
        conversationId,
        projectId: state.activeProjectId || undefined,
        ...(payload.bookName ? { bookName: payload.bookName } : {}),
        ...(payload.selectedCandidate ? { selectedCandidate: payload.selectedCandidate } : {}),
        mode: 'research',
        reasoningMode: state.reasoningMode,
        contextSummary: state.contextSummary,
        history: buildRecentHistory(payload.selectedCandidate),
        limits: {
          ...DEFAULT_LIMITS,
          chapterCount: state.chapterCount,
        },
      };
      if (requestPayload.reasoningMode === 'deep') {
        await submitDurableRun(requestPayload, ensureAssistantMessage());
        return;
      }
      const task = knowledgeApi.streamChat(
        requestPayload,
        {
          onStart(event) {
            state.traceId = event.traceId ?? state.traceId;
            state.status = 'running';
            ensureAssistantMessage();
            persistState();
          },
          onProgress(event) {
            state.status = normalizeProgressStatus(event);
            ensureAssistantMessage();
            persistState();
          },
          onDelta(event) {
            sawDelta = true;
            streamedAnswer += event.delta;
            playback.append(event.delta);
          },
          onDone(event) {
            doneApplied = true;
            donePromise = applyStreamDone(event.data);
          },
          onError(event) {
            state.errorMessage = event.message || '请求失败，请稍后重试。';
          },
        },
      );
      const response = await task.result;
      if (donePromise) {
        await donePromise;
        return;
      }
      if (!doneApplied) {
        await applyStreamDone(response);
      }
    } catch (error) {
      const payload = getErrorPayload(error);
      state.errorMessage = payload.message || '请求失败，请稍后重试。';
    } finally {
      playback.destroy();
      if (!state.pendingRunId) {
        state.loading = false;
      }
      persistState();
    }
  }

  async function submitDurableRun(
    requestPayload: Parameters<typeof knowledgeApi.startChatRun>[0],
    assistantMessage: KnowledgeMessage,
  ) {
    assistantMessage.status = normalizeRunStatus('RUNNING');
    state.status = normalizeRunStatus('RUNNING');
    persistState();
    const response = await knowledgeApi.startChatRun(requestPayload);
    const run = response.data.data;
    state.pendingRunId = run.runId;
    state.conversationId = run.conversationId || state.conversationId;
    applyRunProgress(run, assistantMessage);
    persistState();
    if (isTerminalRun(run)) {
      applyCompletedRun(run, assistantMessage);
      return;
    }
    durableRunPollStartedAt = Date.now();
    scheduleChatRunPoll(run.runId, assistantMessage);
  }

  async function resumePendingRun() {
    if (!state.pendingRunId) {
      return;
    }
    const assistantMessage = ensurePendingRunAssistantMessage();
    state.loading = true;
    try {
      const response = await knowledgeApi.getChatRun(state.pendingRunId);
      const run = response.data.data;
      applyRunProgress(run, assistantMessage);
      if (isTerminalRun(run)) {
        applyCompletedRun(run, assistantMessage);
      } else {
        durableRunPollStartedAt = Date.now();
        scheduleChatRunPoll(run.runId, assistantMessage);
      }
    } catch {
      state.loading = false;
      persistState();
    }
  }

  function ensurePendingRunAssistantMessage(): KnowledgeMessage {
    const existing = [...state.messages].reverse().find((message) => message.role === 'assistant');
    if (existing) {
      return existing;
    }
    const placeholder: KnowledgeMessage = {
      role: 'assistant',
      content: '',
      status: '正在恢复后台回答',
    };
    state.messages.push(placeholder);
    persistState();
    return placeholder;
  }

  function scheduleChatRunPoll(runId: string, assistantMessage: KnowledgeMessage) {
    if (typeof window === 'undefined') {
      return;
    }
    if (durableRunPollTimer !== undefined) {
      window.clearTimeout(durableRunPollTimer);
    }
    durableRunPollTimer = window.setTimeout(() => {
      pollChatRun(runId, assistantMessage).catch(() => {
        state.loading = false;
        persistState();
      });
    }, DURABLE_RUN_POLL_INTERVAL_MS);
  }

  async function pollChatRun(runId: string, assistantMessage: KnowledgeMessage) {
    if (durableRunPollStartedAt && Date.now() - durableRunPollStartedAt > DURABLE_RUN_MAX_ACTIVE_MS) {
      state.loading = false;
      state.errorMessage = '后台回答仍在执行，稍后可回到本会话继续查看结果。';
      persistState();
      return;
    }
    const response = await knowledgeApi.getChatRun(runId);
    const run = response.data.data;
    applyRunProgress(run, assistantMessage);
    if (isTerminalRun(run)) {
      applyCompletedRun(run, assistantMessage);
      return;
    }
    scheduleChatRunPoll(runId, assistantMessage);
  }

  function applyRunProgress(run: KnowledgeChatRun, assistantMessage: KnowledgeMessage) {
    const status = normalizeRunStatus(run.progressMessage || run.status || state.status);
    state.status = status;
    assistantMessage.status = status;
    if (run.answer) {
      assistantMessage.content = run.answer;
      state.answer = run.answer;
    }
    if (run.traceId) {
      state.traceId = run.traceId;
      assistantMessage.traceId = run.traceId;
    }
  }

  function applyCompletedRun(run: KnowledgeChatRun, assistantMessage: KnowledgeMessage) {
    const resultJson = parseRunResultJson(run.resultJson);
    const response: KnowledgeChatResponse = {
      status: run.status === 'ANSWERED' ? 'answered' : String(run.status || '').toLowerCase(),
      answer: run.answer || '',
      candidates: [],
      sources: [],
      actions: [],
      resultJson,
    };
    if (run.status === 'FAILED') {
      state.errorMessage = run.errorMessage || '后台回答失败，请稍后重试。';
    }
    if (run.status === 'CANCELLED') {
      state.errorMessage = '后台回答已取消。';
    }
    state.pendingRunId = '';
    if (durableRunPollTimer !== undefined && typeof window !== 'undefined') {
      window.clearTimeout(durableRunPollTimer);
      durableRunPollTimer = undefined;
    }
    state.loading = false;
    applyResponse(response, assistantMessage);
  }

  function parseRunResultJson(raw: string | undefined): KnowledgeChatResponse['resultJson'] {
    if (!raw) {
      return {};
    }
    try {
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch {
      return {};
    }
  }

  function isTerminalRun(run: KnowledgeChatRun) {
    return ['ANSWERED', 'FAILED', 'CANCELLED'].includes(String(run.status || '').toUpperCase());
  }

  async function loadConversationRun(conversationId: string) {
    const normalizedConversationId = conversationId.trim();
    if (!normalizedConversationId) {
      return;
    }
    if (durableRunPollTimer !== undefined && typeof window !== 'undefined') {
      window.clearTimeout(durableRunPollTimer);
      durableRunPollTimer = undefined;
    }
    state.loading = true;
    state.errorMessage = '';
    try {
      const response = await knowledgeApi.listConversationRuns(normalizedConversationId, 1);
      const run = response.data.data?.[0];
      if (!run) {
        state.loading = false;
        state.errorMessage = '这个会话暂时没有可恢复的回答';
        persistState();
        return;
      }
      clearVolatileConversation();
      state.conversationId = run.conversationId || normalizedConversationId;
      state.messages = [
        {
          role: 'user',
          content: run.question || '历史会话',
        },
        {
          role: 'assistant',
          content: run.answer || '',
          status: normalizeRunStatus(run.progressMessage || run.status),
          traceId: run.traceId,
          sources: [],
        },
      ];
      const assistantMessage = state.messages[1];
      applyRunProgress(run, assistantMessage);
      if (isTerminalRun(run)) {
        applyCompletedRun(run, assistantMessage);
      } else {
        state.pendingRunId = run.runId;
        durableRunPollStartedAt = Date.now();
        scheduleChatRunPoll(run.runId, assistantMessage);
      }
      persistState();
    } catch {
      state.loading = false;
      state.errorMessage = '会话恢复失败，请稍后重试';
      persistState();
    }
  }

  function applyResponse(
    response: KnowledgeChatResponse,
    existingMessage?: KnowledgeMessage | null,
    options: { preserveStreamingContent?: boolean } = {},
  ) {
    state.status = response.status;
    state.candidates = response.candidates ?? [];
    state.sources = response.sources ?? [];
    state.actions = response.actions ?? [];
    const nextConversationId = response.resultJson?.conversationId;
    if (typeof nextConversationId === 'string' && nextConversationId.trim()) {
      state.conversationId = nextConversationId;
    }

    if (response.answer) {
      const answerStatus = typeof response.resultJson?.answerStatus === 'string'
        ? response.resultJson.answerStatus
        : undefined;
      const intent = typeof response.resultJson?.domainIntent === 'string'
        ? response.resultJson.domainIntent
        : typeof response.resultJson?.intent === 'string'
          ? response.resultJson.intent
          : undefined;
      const answerBoundary = typeof response.resultJson?.answerBoundary === 'string'
          ? response.resultJson.answerBoundary
          : typeof response.resultJson?.domainAnswerBoundary === 'string'
            ? response.resultJson.domainAnswerBoundary
            : undefined;
      const fallbackUsed = response.resultJson?.fallbackUsed === true;
      const degraded = response.resultJson?.degraded === true;
      const degradationReasons = Array.isArray(response.resultJson?.degradationReasons)
        ? response.resultJson.degradationReasons.map((item) => String(item))
        : [];
      const contextBudget = normalizeContextBudget(response.resultJson?.contextBudget);
      const traceId = extractTraceId(response);
      if (traceId) {
        state.traceId = traceId;
      }
      state.contextBudget = contextBudget;
      if (existingMessage) {
        if (!options.preserveStreamingContent) {
          existingMessage.content = response.answer;
        }
        existingMessage.status = response.status;
        existingMessage.answerStatus = answerStatus;
        existingMessage.intent = intent;
        existingMessage.answerBoundary = answerBoundary;
        existingMessage.sources = response.sources ?? [];
        existingMessage.fallbackUsed = fallbackUsed;
        existingMessage.degraded = degraded;
        existingMessage.degradationReasons = degradationReasons;
        existingMessage.contextBudget = contextBudget ?? undefined;
        existingMessage.traceId = traceId || existingMessage.traceId;
        state.answer = existingMessage.content;
      } else {
        state.messages.push({
          role: 'assistant',
          content: response.answer,
          status: response.status,
          answerStatus,
          intent,
          answerBoundary,
          sources: response.sources ?? [],
          fallbackUsed,
          degraded,
          degradationReasons,
          contextBudget: contextBudget ?? undefined,
          traceId: traceId || undefined,
        });
        state.answer = response.answer;
      }
    }

    updateContextSummary(response, {
      assistantContent: options.preserveStreamingContent ? existingMessage?.content : undefined,
    });
    trimMessages();
    persistState();
  }

  function buildRecentHistory(selectedCandidate?: KnowledgeBookCandidate): KnowledgeChatMessage[] {
    const history = state.messages
      .slice(-MAX_HISTORY_MESSAGES)
      .map((message) => ({
        role: message.role,
        content: truncateForContext(message.content, MAX_HISTORY_CONTENT_LENGTH),
      }));
    if (selectedCandidate) {
      history.push({
        role: 'user',
        content: truncateForContext(`选择书籍：${formatCandidate(selectedCandidate)}`, MAX_HISTORY_CONTENT_LENGTH),
      });
    }
    return history;
  }

  function updateContextSummary(
    response: KnowledgeChatResponse,
    options: { assistantContent?: string } = {},
  ) {
    const latestUser = [...state.messages].reverse().find((message) => message.role === 'user')?.content ?? '';
    const latestAssistant = options.assistantContent
      || response.answer
      || [...state.messages].reverse().find((message) => message.role === 'assistant')?.content
      || '';
    const latestIntent = typeof response.resultJson?.domainIntent === 'string' && response.resultJson.domainIntent.trim()
      ? String(response.resultJson.domainIntent)
      : typeof response.resultJson?.intent === 'string' && response.resultJson.intent.trim()
        ? String(response.resultJson.intent)
        : '';
    const assistantBudget = latestAssistant.length > 80_000
      ? 120_000
      : latestAssistant.length > 20_000
        ? 64_000
      : latestAssistant.length > 4_000
        ? 16_000
        : 2_400;
    const selectedBook = state.selectedCandidate
      ? formatCandidate(state.selectedCandidate)
      : response.resultJson?.bookName
        ? String(response.resultJson.bookName)
        : state.bookName;
    const sourceBooks = Array.from(new Set((response.sources ?? []).map((source) => source.bookName).filter(Boolean)));
    const parts = [
      selectedBook ? `当前作品：${selectedBook}` : '',
      latestIntent ? `最近意图：${latestIntent}` : '',
      latestUser ? `最近用户目标：${truncateForContext(latestUser, 240)}` : '',
      latestAssistant ? `上一轮结论：${truncateForContext(latestAssistant, assistantBudget)}` : '',
      state.chapterCount ? `抓章偏好：${state.chapterCount}章` : '',
      sourceBooks.length ? `已引用作品：${sourceBooks.slice(0, 4).join('、')}` : '',
      response.status ? `最近状态：${response.status}` : '',
    ].filter(Boolean);
    state.contextSummary = truncateForContext(parts.join('\n'), MAX_CONTEXT_SUMMARY_LENGTH);
  }

  function shouldReplaceStreamWithFinalAnswer(response: KnowledgeChatResponse) {
    return false;
  }

  function normalizeContextBudget(value: unknown): ContextBudget | null {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return null;
    }
    const budget = value as ContextBudget;
    return {
      ...budget,
      memoryLayers: Array.isArray(budget.memoryLayers)
        ? budget.memoryLayers.map((layer) => ({
          ...layer,
          name: String(layer.name ?? ''),
          status: layer.status === undefined ? undefined : String(layer.status),
          itemCount: typeof layer.itemCount === 'number' ? layer.itemCount : undefined,
        }))
        : [],
      warnings: Array.isArray(budget.warnings) ? budget.warnings.map(String) : [],
    };
  }

  function extractTraceId(response: KnowledgeChatResponse) {
    const resultTraceId = response.resultJson?.traceId;
    if (typeof resultTraceId === 'string' && resultTraceId.trim()) {
      return resultTraceId.trim();
    }
    const topLevelTraceId = (response as KnowledgeChatResponse & { traceId?: unknown }).traceId;
    if (typeof topLevelTraceId === 'string' && topLevelTraceId.trim()) {
      return topLevelTraceId.trim();
    }
    return '';
  }

  function trimMessages() {
    if (state.messages.length <= MAX_PERSISTED_MESSAGES) {
      return;
    }
    state.messages.splice(0, state.messages.length - MAX_PERSISTED_MESSAGES);
  }

  function formatCandidate(candidate: KnowledgeBookCandidate) {
    return [candidate.bookName, candidate.author ? `作者：${candidate.author}` : ''].filter(Boolean).join('，');
  }

  function truncateForContext(value: string, maxLength: number) {
    const compact = (value ?? '').replace(/\s+/g, ' ').trim();
    if (compact.length <= maxLength) {
      return compact;
    }
    return `${compact.slice(0, maxLength)}...`;
  }

  function restoreState() {
    if (typeof window === 'undefined') {
      return;
    }
    try {
      const activeProjectId = restoreActiveProjectId();
      if (activeProjectId) {
        state.activeProjectId = activeProjectId;
      }
      const raw = window.localStorage.getItem(storageKey());
      if (!raw) {
        return;
      }
      const saved = JSON.parse(raw) as PersistedKnowledgeChatState;
      if (!activeProjectId) {
        state.activeProjectId = normalizeProjectId(saved.activeProjectId);
      }
      state.messages = Array.isArray(saved.messages) ? saved.messages.slice(-MAX_PERSISTED_MESSAGES) : [];
      state.contextSummary = typeof saved.contextSummary === 'string' ? saved.contextSummary : '';
      state.chapterCount = normalizeChapterCount(saved.chapterCount);
      state.reasoningMode = normalizeReasoningMode(saved.reasoningMode);
      state.bookName = typeof saved.bookName === 'string' ? saved.bookName : '';
      state.selectedCandidate = saved.selectedCandidate ?? null;
      state.candidates = Array.isArray(saved.candidates) ? saved.candidates : [];
      state.sources = Array.isArray(saved.sources) ? saved.sources : [];
      state.status = typeof saved.status === 'string' ? saved.status : '';
      state.answer = typeof saved.answer === 'string' ? saved.answer : '';
      state.conversationId = typeof saved.conversationId === 'string' ? saved.conversationId : '';
      state.pendingRunId = typeof saved.pendingRunId === 'string' ? saved.pendingRunId : '';
      state.traceId = typeof saved.traceId === 'string' ? saved.traceId : '';
      state.contextBudget = normalizeContextBudget(saved.contextBudget)
        ?? [...state.messages].reverse().find((message) => message.contextBudget)?.contextBudget
        ?? null;
    } catch {
      window.localStorage.removeItem(storageKey());
    }
  }

  function persistState() {
    if (typeof window === 'undefined') {
      return;
    }
    const payload: PersistedKnowledgeChatState = {
      messages: state.messages.slice(-MAX_PERSISTED_MESSAGES).map((message) => ({
        role: message.role,
        content: message.content,
        status: message.status,
        answerStatus: message.answerStatus,
        intent: message.intent,
        answerBoundary: message.answerBoundary,
        sources: message.sources ?? [],
        fallbackUsed: message.fallbackUsed,
        degraded: message.degraded,
        degradationReasons: message.degradationReasons,
        contextBudget: message.contextBudget,
        traceId: message.traceId,
      })),
      contextSummary: state.contextSummary,
      chapterCount: state.chapterCount,
      reasoningMode: state.reasoningMode,
      bookName: state.bookName,
      selectedCandidate: state.selectedCandidate,
      candidates: state.candidates,
      sources: state.sources,
      status: state.status,
      answer: state.answer,
      traceId: state.traceId,
      contextBudget: state.contextBudget,
      conversationId: state.conversationId,
      pendingRunId: state.pendingRunId,
      activeProjectId: state.activeProjectId,
    };
    persistActiveProjectId();
    const serialized = JSON.stringify(payload);
    try {
      window.localStorage.setItem(storageKey(), serialized);
    } catch (error) {
      const trimmedPayload = {
        ...payload,
        messages: payload.messages.slice(-12).map((message) => ({
          ...message,
          content: truncateForContext(message.content, 8000),
        })),
        contextSummary: truncateForContext(payload.contextSummary, 120_000),
        answer: truncateForContext(payload.answer, 16_000),
      };
      try {
        window.localStorage.setItem(storageKey(), JSON.stringify(trimmedPayload));
      } catch {
        if (error instanceof Error) {
          console.warn('Failed to persist knowledge chat state', error.message);
        }
      }
    }
  }

  function normalizeChapterCount(value: unknown) {
    const parsed = Number(value);
    return [3, 5, 10].includes(parsed) ? parsed : 3;
  }

  function normalizeReasoningMode(value: unknown): KnowledgeReasoningMode {
    return value === 'deep' ? 'deep' : DEFAULT_REASONING_MODE;
  }

  function normalizeProgressStatus(event: { phase?: string; message?: string }) {
    const phase = typeof event.phase === 'string' ? event.phase.trim() : '';
    const message = typeof event.message === 'string' ? event.message.trim() : '';
    const translatedByMessage: Record<string, string> = {
      'Preparing agent context': '正在整理会话上下文',
      'Classifying task intent': '正在识别你的写作意图',
      'Planning agent tasks': '正在规划任务步骤',
      'Validating task preconditions': '正在检查上下文和前置条件',
      'Executing governed tools': '正在调用资料和工具',
      'Checking evidence sufficiency': '正在校验证据是否足够',
      'Generating answer': '正在生成回答',
      'Extracting memory candidates': '正在提取可复用上下文',
      'Finalizing agent trace': '正在整理运行记录',
      'searching knowledge': '正在检索资料',
    };
    const translatedByPhase: Record<string, string> = {
      prepare: '正在整理会话上下文',
      intent: '正在识别你的写作意图',
      plan: '正在规划任务步骤',
      preconditions: '正在检查上下文和前置条件',
      retrieve: '正在检索资料',
      evidence: '正在调用资料和工具',
      evidence_review: '正在校验证据是否足够',
      generate: '正在生成回答',
      answer: '正在生成回答',
      memory: '正在提取可复用上下文',
      trace: '正在整理运行记录',
    };
    if (message && translatedByMessage[message]) {
      return translatedByMessage[message];
    }
    if (message && /[^\u0000-\u007f]/.test(message)) {
      return message;
    }
    if (phase && translatedByPhase[phase]) {
      return translatedByPhase[phase];
    }
    return message || phase || state.status;
  }

  function normalizeRunStatus(value?: string) {
    const normalized = String(value || '').trim();
    if (!normalized) {
      return state.status || '正在后台执行';
    }
    if (/[\u4e00-\u9fff]/.test(normalized)) {
      return normalized;
    }
    switch (normalized.toUpperCase()) {
      case 'PENDING':
        return '后台任务已排队';
      case 'RUNNING':
        return '正在后台执行';
      case 'ANSWERED':
        return '后台回答已完成';
      case 'FAILED':
        return '后台回答失败';
      case 'CANCELLED':
        return '后台回答已取消';
      default:
        return normalized;
    }
  }

  function normalizeProjectId(value: unknown) {
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
  }

  function restoreActiveProjectId() {
    return getStoredKnowledgeProjectId();
  }

  function persistActiveProjectId() {
    setStoredKnowledgeProjectId(state.activeProjectId);
  }

  function clearConversation() {
    clearVolatileConversation();
    persistState();
  }

  function startNewConversation() {
    clearVolatileConversation();
    ensureConversationId();
    persistState();
  }

  function storageKey() {
    return state.activeProjectId ? `${PROJECT_STORAGE_PREFIX}${state.activeProjectId}` : STORAGE_KEY;
  }

  function latestUserQuestion() {
    return [...state.messages].reverse().find((message) => message.role === 'user')?.content.trim() ?? '';
  }

  function clearVolatileConversation() {
    if (durableRunPollTimer !== undefined && typeof window !== 'undefined') {
      window.clearTimeout(durableRunPollTimer);
      durableRunPollTimer = undefined;
    }
    state.errorMessage = '';
    state.status = '';
    state.answer = '';
    state.messages = [];
    state.candidates = [];
    state.sources = [];
    state.actions = [];
    state.selectedCandidate = null;
    state.contextSummary = '';
    state.traceId = '';
    state.contextBudget = null;
    state.conversationId = '';
    state.pendingRunId = '';
  }

  function ensureConversationId() {
    if (!state.conversationId) {
      state.conversationId = generateConversationId();
    }
    return state.conversationId;
  }

  function generateConversationId() {
    const randomUuid = globalThis.crypto?.randomUUID?.();
    if (randomUuid) {
      return randomUuid;
    }
    return `conv-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }

  function deleteMessage(index: number) {
    if (index < 0 || index >= state.messages.length) {
      return;
    }
    state.messages.splice(index, 1);
    const latestAssistant = [...state.messages].reverse().find((message) => message.role === 'assistant');
    state.answer = latestAssistant?.content ?? '';
    state.sources = latestAssistant?.sources ?? [];
    if (!state.messages.length) {
      state.status = '';
      state.candidates = [];
      state.actions = [];
      state.contextSummary = '';
      state.conversationId = '';
      state.pendingRunId = '';
    }
    persistState();
  }

  return {
    state,
    canSend,
    sendQuestion,
    selectCandidate,
    loadProjects,
    createProject,
    selectProject,
    loadConversationRun,
    clearConversation,
    startNewConversation,
    deleteMessage,
  };
}
