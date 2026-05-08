import { computed, reactive } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import { getErrorPayload } from '@/lib/http-error';
import type {
  KnowledgeBookCandidate,
  KnowledgeChatMessage,
  KnowledgeChatResponse,
  KnowledgeSource,
} from '@/types/knowledge';

const DEFAULT_LIMITS = {
  candidateLimit: 5,
  evidenceLimit: 5,
  chapterCount: 3,
};

interface KnowledgeMessage extends KnowledgeChatMessage {
  content: string;
  status?: string;
  sources?: KnowledgeSource[];
}

const MAX_CONTEXT_SUMMARY_LENGTH = 1200;
const MAX_HISTORY_MESSAGES = 6;
const MAX_HISTORY_CONTENT_LENGTH = 700;
const STORAGE_KEY = 'noval:knowledge-chat:draft:v1';
const MAX_PERSISTED_MESSAGES = 40;

interface PersistedKnowledgeChatState {
  messages?: KnowledgeMessage[];
  contextSummary?: string;
  chapterCount?: number;
  bookName?: string;
  selectedCandidate?: KnowledgeBookCandidate | null;
  candidates?: KnowledgeBookCandidate[];
  sources?: KnowledgeSource[];
  status?: string;
  answer?: string;
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
  });
  restoreState();

  const canSend = computed(() => state.question.trim().length > 0 && !state.loading);

  async function sendQuestion() {
    if (!canSend.value) {
      return;
    }
    await submit({
      question: state.question.trim(),
      bookName: state.bookName.trim(),
    });
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
    if (!payload.selectedCandidate) {
      state.messages.push({ role: 'user', content: payload.question });
      persistState();
    }

    let assistantMessage: KnowledgeMessage | null = null;
    try {
      const task = knowledgeApi.streamChat(
        {
          question: payload.question,
          bookName: payload.bookName ?? '',
          selectedCandidate: payload.selectedCandidate,
          mode: 'research',
          contextSummary: state.contextSummary,
          history: buildRecentHistory(payload.selectedCandidate),
          limits: {
            ...DEFAULT_LIMITS,
            chapterCount: state.chapterCount,
          },
        },
        {
          onStart(event) {
            state.traceId = event.traceId ?? state.traceId;
            state.status = 'running';
          },
          onDelta(event) {
            if (!assistantMessage) {
              assistantMessage = { role: 'assistant', content: '', status: 'streaming', sources: [] };
              state.messages.push(assistantMessage);
            }
            assistantMessage.content += event.delta;
            state.answer = assistantMessage.content;
            persistState();
          },
          onDone(event) {
            applyResponse(event.data, assistantMessage);
          },
          onError(event) {
            state.errorMessage = event.message || '请求失败，请稍后重试。';
          },
        },
      );
      const response = await task.result;
      applyResponse(response, assistantMessage);
    } catch (error) {
      const payload = getErrorPayload(error);
      state.errorMessage = payload.message || '请求失败，请稍后重试。';
    } finally {
      state.loading = false;
      persistState();
    }
  }

  function applyResponse(response: KnowledgeChatResponse, existingMessage?: KnowledgeMessage | null) {
    state.status = response.status;
    state.candidates = response.candidates ?? [];
    state.sources = response.sources ?? [];
    state.actions = response.actions ?? [];

    if (response.answer) {
      if (existingMessage) {
        if (response.status !== 'answered' || !existingMessage.content || existingMessage.content.length < response.answer.length) {
          existingMessage.content = response.answer;
        }
        existingMessage.status = response.status;
        existingMessage.sources = response.sources ?? [];
        state.answer = existingMessage.content;
      } else {
        state.messages.push({
          role: 'assistant',
          content: response.answer,
          status: response.status,
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
    const selectedBook = state.selectedCandidate
      ? formatCandidate(state.selectedCandidate)
      : response.resultJson?.bookName
        ? String(response.resultJson.bookName)
        : state.bookName;
    const sourceBooks = Array.from(new Set((response.sources ?? []).map((source) => source.bookName).filter(Boolean)));
    const parts = [
      selectedBook ? `当前作品：${selectedBook}` : '',
      latestUser ? `最近用户目标：${truncateForContext(latestUser, 240)}` : '',
      latestAssistant ? `上一轮结论：${truncateForContext(latestAssistant, 520)}` : '',
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
      const raw = window.localStorage.getItem(STORAGE_KEY);
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
    } catch {
      window.localStorage.removeItem(STORAGE_KEY);
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
    };
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
  }

  function normalizeChapterCount(value: unknown) {
    const parsed = Number(value);
    return [3, 5, 10].includes(parsed) ? parsed : 3;
  }

  return {
    state,
    canSend,
    sendQuestion,
    selectCandidate,
  };
}
