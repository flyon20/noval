import { computed, reactive } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import { getErrorPayload } from '@/lib/http-error';
import { createStreamingPlaybackController } from '@/lib/streaming-playback';
import type {
  KnowledgeBookCandidate,
  KnowledgeChatMessage,
  KnowledgeChatResponse,
  KnowledgeProject,
  KnowledgeSource,
} from '@/types/knowledge';

const DEFAULT_LIMITS = {
  candidateLimit: 5,
  evidenceLimit: 5,
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
}

const MAX_CONTEXT_SUMMARY_LENGTH = 900000;
const MAX_HISTORY_MESSAGES = 12;
const MAX_HISTORY_CONTENT_LENGTH = 64000;
const STORAGE_KEY = 'noval:knowledge-chat:draft:v1';
const PROJECT_STORAGE_PREFIX = 'noval:knowledge-chat:project:v1:';
const MAX_PERSISTED_MESSAGES = 40;

interface PersistedKnowledgeChatState {
  conversationId?: string;
  messages?: KnowledgeMessage[];
  contextSummary?: string;
  chapterCount?: number;
  bookName?: string;
  selectedCandidate?: KnowledgeBookCandidate | null;
  candidates?: KnowledgeBookCandidate[];
  sources?: KnowledgeSource[];
  status?: string;
  answer?: string;
  activeProjectId?: number | null;
}

export function useKnowledgeChat() {
  const state = reactive({
    question: '',
    bookName: '',
    chapterCount: 3,
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
    conversationId: '',
    projects: [] as KnowledgeProject[],
    activeProjectId: null as number | null,
    projectNameDraft: '',
  });
  restoreState();

  const canSend = computed(() => state.question.trim().length > 0 && !state.loading);

  async function sendQuestion() {
    if (!canSend.value) {
      return;
    }
    await submit({
      question: state.question.trim(),
    });
  }

  async function loadProjects() {
    const response = await knowledgeApi.listProjects();
    state.projects = response.data.data ?? [];
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
    clearVolatileConversation();
    restoreState();
    state.activeProjectId = projectId;
    persistState();
  }

  async function selectCandidate(candidate: KnowledgeBookCandidate) {
    state.selectedCandidate = candidate;
    state.bookName = candidate.bookName;
    await submit({
      question: state.question.trim(),
      bookName: candidate.bookName,
      selectedCandidate: candidate,
    });
  }

  async function submit(payload: { question: string; bookName?: string; selectedCandidate?: KnowledgeBookCandidate }) {
    state.loading = true;
    state.errorMessage = '';
    state.answer = '';
    state.status = '';
    if (!payload.selectedCandidate) {
      state.messages.push({ role: 'user', content: payload.question });
      persistState();
    }

    let assistantMessage: KnowledgeMessage | null = null;
    let sawDelta = false;
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

      await playback.flushTo(response.answer || assistantMessage?.content || '');
      applyResponse(response, assistantMessage);
      playback.destroy();
    };
    let donePromise: Promise<void> | null = null;
    try {
      const requestPayload = {
        question: payload.question,
        conversationId: state.conversationId || undefined,
        projectId: state.activeProjectId || undefined,
        ...(payload.bookName ? { bookName: payload.bookName } : {}),
        ...(payload.selectedCandidate ? { selectedCandidate: payload.selectedCandidate } : {}),
        mode: 'research',
        contextSummary: state.contextSummary,
        history: buildRecentHistory(payload.selectedCandidate),
        limits: {
          ...DEFAULT_LIMITS,
          chapterCount: state.chapterCount,
        },
      };
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
            state.status = event.message || event.phase || state.status;
            ensureAssistantMessage();
            persistState();
          },
          onDelta(event) {
            sawDelta = true;
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
      state.loading = false;
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
      if (existingMessage) {
        if (!options.preserveStreamingContent) {
          existingMessage.content = response.answer;
        }
        existingMessage.status = response.status;
        existingMessage.answerStatus = answerStatus;
        existingMessage.intent = intent;
        existingMessage.answerBoundary = answerBoundary;
        existingMessage.sources = response.sources ?? [];
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
        });
        state.answer = response.answer;
      }
    }

    updateContextSummary(response);
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

  function updateContextSummary(response: KnowledgeChatResponse) {
    const latestUser = [...state.messages].reverse().find((message) => message.role === 'user')?.content ?? '';
    const latestAssistant = response.answer || [...state.messages].reverse().find((message) => message.role === 'assistant')?.content || '';
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
      const raw = window.localStorage.getItem(storageKey());
      if (!raw) {
        return;
      }
      const saved = JSON.parse(raw) as PersistedKnowledgeChatState;
      state.messages = Array.isArray(saved.messages) ? saved.messages.slice(-MAX_PERSISTED_MESSAGES) : [];
      state.contextSummary = typeof saved.contextSummary === 'string' ? saved.contextSummary : '';
      state.chapterCount = normalizeChapterCount(saved.chapterCount);
      state.bookName = typeof saved.bookName === 'string' ? saved.bookName : '';
      state.selectedCandidate = saved.selectedCandidate ?? null;
      state.candidates = Array.isArray(saved.candidates) ? saved.candidates : [];
      state.sources = Array.isArray(saved.sources) ? saved.sources : [];
      state.status = typeof saved.status === 'string' ? saved.status : '';
      state.answer = typeof saved.answer === 'string' ? saved.answer : '';
      state.conversationId = typeof saved.conversationId === 'string' ? saved.conversationId : '';
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
      })),
      contextSummary: state.contextSummary,
      chapterCount: state.chapterCount,
      bookName: state.bookName,
      selectedCandidate: state.selectedCandidate,
      candidates: state.candidates,
      sources: state.sources,
      status: state.status,
      answer: state.answer,
      conversationId: state.conversationId,
      activeProjectId: state.activeProjectId,
    };
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

  function clearConversation() {
    clearVolatileConversation();
    persistState();
  }

  function storageKey() {
    return state.activeProjectId ? `${PROJECT_STORAGE_PREFIX}${state.activeProjectId}` : STORAGE_KEY;
  }

  function clearVolatileConversation() {
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
    state.conversationId = '';
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
    clearConversation,
    deleteMessage,
  };
}
