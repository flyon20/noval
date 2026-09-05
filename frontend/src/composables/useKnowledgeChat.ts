import { computed, reactive } from 'vue';
import { knowledgeApi } from '@/api/knowledge';
import { systemConfigApi } from '@/api/config';
import { getErrorPayload } from '@/lib/http-error';
import { createStreamingPlaybackController } from '@/lib/streaming-playback';
import { knowledgeIntentLabel, knowledgeUserStatusLabel } from '@/utils/knowledgeDisplay';
import {
  emitKnowledgeConversationsChanged,
  getStoredKnowledgeProjectId,
  getStoredKnowledgeReferenceWorkIds,
  getStoredKnowledgeWorkId,
  normalizeKnowledgeReferenceWorkIds,
  normalizeKnowledgeWorkId,
  setStoredKnowledgeProjectId,
  setStoredKnowledgeReferenceWorkIds,
  setStoredKnowledgeWorkId,
} from '@/composables/useKnowledgeProjectSelection';
import type {
  ContextBudget,
  KnowledgeBookCandidate,
  KnowledgeChatMessage,
  KnowledgeChatRequest,
  KnowledgeChatResponse,
  KnowledgeChatRun,
  KnowledgeChatRunEvent,
  KnowledgeChatRunEventStreamTask,
  KnowledgeChatRunSnapshot,
  KnowledgeConversation,
  KnowledgeConversationMessage,
  KnowledgeProject,
  KnowledgeReasoningEffort,
  KnowledgeReasoningMode,
  KnowledgeRunContextCompaction,
  KnowledgeRunProcess,
  KnowledgeRunModelCall,
  KnowledgeRunProcessSummary,
  KnowledgeRunPromptCacheSummary,
  KnowledgeProviderRequestSummary,
  KnowledgeProviderResponseSummary,
  KnowledgeProviderTransportFallback,
  KnowledgeProviderUsage,
  KnowledgeRunProcessStep,
  KnowledgeSource,
  ProjectWork,
} from '@/types/knowledge';
import type { AiModelOption } from '@/types/config';

const DEFAULT_LIMITS = {
  candidateLimit: 5,
  evidenceLimit: 5,
  rankLimit: 30,
  chapterCount: 10,
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
  runId?: string;
  process?: KnowledgeRunProcess;
}

const MAX_CONTEXT_SUMMARY_LENGTH = 900000;
const MAX_HISTORY_MESSAGES = 40;
const MAX_HISTORY_CONTENT_LENGTH = 64000;
const STORAGE_KEY = 'noval:knowledge-chat:draft:v1';
const PROJECT_STORAGE_PREFIX = 'noval:knowledge-chat:project:v1:';
const MAX_PERSISTED_MESSAGES = 40;
const DURABLE_RUN_POLL_INTERVAL_MS = 3000;
const DURABLE_RUN_MAX_ACTIVE_MS = 660000;
const DEFAULT_REASONING_MODE: KnowledgeReasoningMode = 'fast';
const CANONICAL_REASONING_TIERS: KnowledgeReasoningEffort[] = ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
/** 这几档走可持久化运行（有进度事件、可取消、预算更高）；低档继续走直连流式。 */
const DEEP_REASONING_TIERS = new Set<KnowledgeReasoningEffort>(['medium', 'high', 'xhigh', 'max']);
const RUN_PROGRESS_EVENT_TYPES = new Set([
  'PROGRESS',
  'CONTEXT_COMPACTING',
  'CONTEXT_COMPACTED',
]);

function normalizeReasoningTiers(value: unknown): KnowledgeReasoningEffort[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const seen = new Set<string>();
  const tiers: KnowledgeReasoningEffort[] = [];
  for (const item of value) {
    const tier = String(item ?? '').trim().toLowerCase();
    if (!CANONICAL_REASONING_TIERS.includes(tier as KnowledgeReasoningEffort) || seen.has(tier)) {
      continue;
    }
    seen.add(tier);
    tiers.push(tier as KnowledgeReasoningEffort);
  }
  return tiers;
}

/**
 * 把上次选的档位收敛到当前模型真正提供的档位里。
 *
 * <p>切换模型时档位集合会变（OpenAI 四档、Kimi/GLM 三档、Qwen 只有开关），
 * 落在集合外的旧值不能原样送出去——供应商对未知枚举返回 400。取最接近的一档，
 * 让用户的「想多想一点」意图尽量保留。
 */
function clampReasoningEffort(
  effort: KnowledgeReasoningEffort | '',
  tiers: KnowledgeReasoningEffort[],
  fallbackMode: KnowledgeReasoningMode,
): KnowledgeReasoningEffort | '' {
  if (!tiers.length) {
    return '';
  }
  if (effort && tiers.includes(effort)) {
    return effort;
  }
  if (!effort) {
    // 没有历史选择时跟随原来的快速/深度默认，避免所有人一上来就跑最高档。
    return fallbackMode === 'deep' ? tiers[tiers.length - 1] : tiers[0];
  }
  const target = CANONICAL_REASONING_TIERS.indexOf(effort);
  let closest = tiers[0];
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const tier of tiers) {
    const distance = Math.abs(CANONICAL_REASONING_TIERS.indexOf(tier) - target);
    if (distance < bestDistance) {
      bestDistance = distance;
      closest = tier;
    }
  }
  return closest;
}

interface PersistedKnowledgeChatState {
  conversationId?: string;
  messages?: KnowledgeMessage[];
  contextSummary?: string;
  chapterCount?: number;
  reasoningMode?: KnowledgeReasoningMode;
  reasoningEffort?: KnowledgeReasoningEffort | '';
  modelKey?: string;
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
  activeWorkId?: number | null;
  lastEventSequence?: number;
}

export function useKnowledgeChat() {
  let durableRunPollTimer: number | undefined;
  let durableRunStreamTask: KnowledgeChatRunEventStreamTask | undefined;
  let fastStreamTask: ReturnType<typeof knowledgeApi.streamChat> | undefined;
  let fastStreamGeneration = 0;
  let durableRunPollStartedAt = 0;
  let trackingGeneration = 0;
  let conversationLoadGeneration = 0;
  let conversationListGeneration = 0;
  let workLoadGeneration = 0;
  let newConversationGeneration = 0;
  let persistTimer: number | undefined;
  let trackedAssistantMessage: KnowledgeMessage | null = null;
  const state = reactive({
    question: '',
    bookName: '',
    chapterCount: 10,
    reasoningMode: DEFAULT_REASONING_MODE as KnowledgeReasoningMode,
    reasoningEffort: '' as KnowledgeReasoningEffort | '',
    modelKey: '',
    modelOptions: [] as AiModelOption[],
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
    preferredSkillId: '',
    pendingRunId: '',
    lastEventSequence: 0,
    projects: [] as KnowledgeProject[],
    works: [] as ProjectWork[],
    conversations: [] as KnowledgeConversation[],
    activeProjectId: null as number | null,
    activeWorkId: null as number | null,
    referenceWorkIds: [] as number[],
    projectNameDraft: '',
    creatingConversation: false,
  });
  restoreState();
  resumePendingRun();

  const canSend = computed(() => (
    state.question.trim().length > 0 && !state.loading && !state.pendingRunId
  ));

  const selectedModel = computed(() => (
    state.modelOptions.find((option) => option.modelKey === state.modelKey) ?? null
  ));

  /** 当前模型真正提供的档位；为空表示该模型不接受推理参数，控件整块隐藏。 */
  const reasoningTiers = computed(() => {
    const option = selectedModel.value;
    if (!option || option.supportsReasoning === false) {
      return [] as KnowledgeReasoningEffort[];
    }
    return normalizeReasoningTiers(option.reasoningTiers);
  });

  /** Qwen 这类只有开关的供应商：两档等价于「思考 / 不思考」，用开关而不是分段控件。 */
  const reasoningIsToggle = computed(() => reasoningTiers.value.length === 2);

  /** 供应商分栏：模型多的家族（如 GPT）在选择器里按供应商归组。 */
  const modelGroups = computed(() => {
    const groups = new Map<string, AiModelOption[]>();
    for (const option of state.modelOptions) {
      const family = String(option.providerFamily || option.providerType || 'other').trim() || 'other';
      const bucket = groups.get(family);
      if (bucket) {
        bucket.push(option);
      } else {
        groups.set(family, [option]);
      }
    }
    return [...groups.entries()].map(([providerType, options]) => ({ providerType, options }));
  });

  async function loadModelOptions() {
    try {
      const response = await systemConfigApi.getModelOptions();
      state.modelOptions = response.data.data ?? [];
    } catch {
      // 模型列表拿不到时保持空列表：选择器隐藏，后端仍按注册表默认模型执行。
      state.modelOptions = [];
    }
    const stillAvailable = state.modelKey
      && state.modelOptions.some((option) => option.modelKey === state.modelKey);
    if (!stillAvailable) {
      const preferred = state.modelOptions.find((option) => option.isDefault === true)
        ?? state.modelOptions[0];
      state.modelKey = preferred?.modelKey ?? '';
    }
    applyReasoningEffort(state.reasoningEffort);
  }

  function selectModel(modelKey: string) {
    if (state.modelKey === modelKey) {
      return;
    }
    state.modelKey = modelKey;
    applyReasoningEffort(state.reasoningEffort);
  }

  function selectReasoningEffort(effort: KnowledgeReasoningEffort | '') {
    applyReasoningEffort(effort);
  }

  function selectReasoningMode(mode: KnowledgeReasoningMode) {
    state.reasoningMode = mode === 'deep' ? 'deep' : 'fast';
    applyReasoningEffort(state.reasoningEffort);
  }

  /**
   * 档位是唯一的输入，快速/深度由它推导。
   *
   * <p>档位决定的不只是供应商参数：中档以上走可持久化运行，低档走直连流式，
   * 所以两者必须一起改，否则会出现「选了最高档但按快速路径跑」的错配。
   * 没有档位的模型（Claude、gpt-4o）保留原来的快速/深度开关，不然它们会丢掉
   * 可持久化运行的进度与取消能力。
   */
  function applyReasoningEffort(effort: KnowledgeReasoningEffort | '') {
    const tiers = reasoningTiers.value;
    state.reasoningEffort = clampReasoningEffort(effort, tiers, state.reasoningMode);
    if (tiers.length && state.reasoningEffort) {
      state.reasoningMode = DEEP_REASONING_TIERS.has(state.reasoningEffort) ? 'deep' : 'fast';
    }
    persistState();
  }

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
    const previousProjectId = state.activeProjectId;
    const activeProjectStillExists = state.activeProjectId
      ? state.projects.some((project) => project.projectId === state.activeProjectId)
      : false;
    if (state.activeProjectId && !activeProjectStillExists) {
      state.activeProjectId = state.projects[0]?.projectId ?? null;
      state.activeWorkId = getStoredKnowledgeWorkId(state.activeProjectId);
      persistActiveProjectId();
    }
    if (!state.activeProjectId && state.projects.length) {
      state.activeProjectId = state.projects[0].projectId;
      state.activeWorkId = getStoredKnowledgeWorkId(state.activeProjectId);
      persistActiveProjectId();
    }
    if (state.activeProjectId !== previousProjectId || !state.activeWorkId) {
      state.activeWorkId = getStoredKnowledgeWorkId(state.activeProjectId);
    }
    state.referenceWorkIds = getStoredKnowledgeReferenceWorkIds(state.activeProjectId)
      .filter((workId) => workId !== state.activeWorkId);
    await Promise.all([
      loadProjectConversations(true),
      loadProjectWorks(state.activeProjectId, state.activeWorkId),
    ]);
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

  function selectProject(
    projectId: number | null,
    restoreConversation = true,
    workId?: number | null,
    referenceWorkIds?: number[],
  ) {
    const normalizedProjectId = normalizeProjectId(projectId);
    const normalizedWorkId = workId === undefined ? undefined : normalizeKnowledgeWorkId(workId);
    const normalizedReferenceWorkIds = referenceWorkIds === undefined
      ? undefined
      : normalizeKnowledgeReferenceWorkIds(referenceWorkIds);
    if (state.activeProjectId === normalizedProjectId) {
      if (normalizedWorkId !== undefined) {
        setActiveWork(normalizedWorkId);
      }
      if (normalizedReferenceWorkIds !== undefined) {
        setReferenceWorks(normalizedReferenceWorkIds);
      }
      return;
    }
    persistState();
    detachRunTracking();
    conversationLoadGeneration++;
    workLoadGeneration++;
    newConversationGeneration++;
    state.creatingConversation = false;
    state.activeProjectId = normalizedProjectId;
    state.works = [];
    state.activeWorkId = normalizedWorkId === undefined
      ? getStoredKnowledgeWorkId(normalizedProjectId)
      : normalizedWorkId;
    const nextReferenceWorkIds = (normalizedReferenceWorkIds
      ?? getStoredKnowledgeReferenceWorkIds(normalizedProjectId))
      .filter((referenceWorkId) => referenceWorkId !== state.activeWorkId);
    persistActiveProjectId();
    clearVolatileConversation();
    restoreState();
    state.activeProjectId = normalizedProjectId;
    state.referenceWorkIds = nextReferenceWorkIds;
    if (normalizedWorkId !== undefined) {
      state.activeWorkId = normalizedWorkId;
    }
    persistState();
    void loadProjectConversations(restoreConversation);
    void loadProjectWorks(normalizedProjectId, state.activeWorkId);
  }

  async function loadProjectWorks(
    projectId: number | null = state.activeProjectId,
    preferredWorkId: number | null = state.activeWorkId,
  ) {
    const generation = ++workLoadGeneration;
    if (!projectId) {
      state.works = [];
      setActiveWork(null);
      return;
    }
    try {
      const response = await knowledgeApi.listProjectWorks(projectId);
      if (generation !== workLoadGeneration || projectId !== state.activeProjectId) {
        return;
      }
      state.works = response.data.data ?? [];
      const storedWorkId = getStoredKnowledgeWorkId(projectId);
      const nextWork = state.works.find((work) => work.workId === state.activeWorkId)
        ?? state.works.find((work) => work.workId === preferredWorkId)
        ?? state.works.find((work) => work.workId === storedWorkId)
        ?? state.works[0]
        ?? null;
      setActiveWork(nextWork?.workId ?? null);
    } catch {
      if (generation === workLoadGeneration && projectId === state.activeProjectId && !state.activeWorkId) {
        state.works = [];
      }
    }
  }

  function setActiveWork(workId: number | null) {
    state.activeWorkId = normalizeKnowledgeWorkId(workId);
    setStoredKnowledgeWorkId(state.activeProjectId, state.activeWorkId);
    setReferenceWorks(state.referenceWorkIds.filter((referenceWorkId) => referenceWorkId !== state.activeWorkId));
    schedulePersistState();
  }

  function setReferenceWorks(workIds: unknown) {
    state.referenceWorkIds = normalizeKnowledgeReferenceWorkIds(workIds)
      .filter((workId) => workId !== state.activeWorkId);
    setStoredKnowledgeReferenceWorkIds(state.activeProjectId, state.referenceWorkIds);
    schedulePersistState();
  }

  async function loadProjectConversations(restoreConversation: boolean) {
    const projectId = state.activeProjectId;
    const generation = ++conversationListGeneration;
    try {
      const response = await knowledgeApi.listConversations(projectId);
      if (generation !== conversationListGeneration || projectId !== state.activeProjectId) {
        return;
      }
      const conversations = response.data.data ?? [];
      state.conversations = projectId
        ? conversations
        : conversations.filter((item) => item.projectId == null);
      if (!restoreConversation || !state.conversations.length) {
        return;
      }
      const target = state.conversations.find((item) => item.conversationId === state.conversationId)
        ?? state.conversations[0];
      if (target) {
        await loadConversationRun(target.conversationId);
      }
    } catch {
      if (generation === conversationListGeneration && projectId === state.activeProjectId) {
        state.conversations = [];
      }
    }
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
    detachFastStream();
    state.loading = true;
    state.errorMessage = '';
    state.answer = '';
    state.status = '';
    const conversationId = ensureConversationId();
    const streamGeneration = ++fastStreamGeneration;
    const isCurrentFastStream = () => streamGeneration === fastStreamGeneration;
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
        assistantMessage = {
          role: 'assistant',
          content: '',
          status: 'streaming',
          sources: [],
          process: emptyRunProcess('processing'),
        };
        state.messages.push(assistantMessage);
      }
      return assistantMessage;
    };
    const playback = createStreamingPlaybackController((text) => {
      if (!isCurrentFastStream()) {
        return;
      }
      const message = ensureAssistantMessage();
      message.content = text;
      state.answer = text;
      schedulePersistState();
    }, {
      charsPerTick: 8,
      tickMillis: 18,
      targetDurationMs: 900,
    });
    const applyStreamDone = async (response: KnowledgeChatResponse) => {
      if (!isCurrentFastStream()) {
        return;
      }
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
      if (!isCurrentFastStream()) {
        return;
      }
      applyResponse(response, assistantMessage, {
        preserveStreamingContent: !shouldReplaceStream,
      });
      playback.destroy();
    };
    let donePromise: Promise<void> | null = null;
    try {
      const preferredSkillId = state.preferredSkillId.trim();
      const requestPayload: KnowledgeChatRequest = {
        question: payload.question,
        conversationId,
        projectId: state.activeProjectId || undefined,
        workId: state.activeWorkId || undefined,
        ...(state.referenceWorkIds.length ? { referenceWorkIds: [...state.referenceWorkIds] } : {}),
        ...(preferredSkillId ? { preferredSkillId } : {}),
        ...(payload.bookName ? { bookName: payload.bookName } : {}),
        ...(payload.selectedCandidate ? { selectedCandidate: payload.selectedCandidate } : {}),
        mode: 'research',
        reasoningMode: state.reasoningMode,
        ...(state.reasoningEffort ? { reasoningEffort: state.reasoningEffort } : {}),
        ...(state.modelKey ? { modelKey: state.modelKey } : {}),
        contextSummary: state.contextSummary,
        history: buildRecentHistory(payload.selectedCandidate),
        limits: {
          ...DEFAULT_LIMITS,
          chapterCount: state.chapterCount,
        },
      };
      if (requestPayload.reasoningMode === 'deep') {
        await submitDurableRun(requestPayload, ensureAssistantMessage(), streamGeneration);
        return;
      }
      const task = knowledgeApi.streamChat(
        requestPayload,
        {
          onStart(event) {
            if (!isCurrentFastStream()) {
              return;
            }
            state.traceId = event.traceId ?? state.traceId;
            state.status = normalizeRunStatus('RUNNING');
            ensureAssistantMessage();
            persistState();
          },
          onProgress(event) {
            if (!isCurrentFastStream()) {
              return;
            }
            const assistantMessage = ensureAssistantMessage();
            state.status = normalizeProgressStatus(event);
            recordProcessProgress(assistantMessage, event.phase, event.message);
            applyContextBudgetProgress(event, assistantMessage);
            schedulePersistState();
          },
          onDelta(event) {
            if (!isCurrentFastStream()) {
              return;
            }
            sawDelta = true;
            streamedAnswer += event.delta;
            playback.append(event.delta);
          },
          onDone(event) {
            if (!isCurrentFastStream()) {
              return;
            }
            doneApplied = true;
            donePromise = applyStreamDone(event.data);
          },
          onError(event) {
            if (!isCurrentFastStream()) {
              return;
            }
            state.errorMessage = event.message || '请求失败，请稍后重试。';
          },
        },
      );
      fastStreamTask = task;
      const response = await task.result;
      if (!isCurrentFastStream()) {
        return;
      }
      if (donePromise) {
        await donePromise;
        return;
      }
      if (!doneApplied) {
        await applyStreamDone(response);
      }
    } catch (error) {
      if (!isCurrentFastStream()) {
        return;
      }
      const payload = getErrorPayload(error);
      state.errorMessage = payload.message || '请求失败，请稍后重试。';
    } finally {
      playback.destroy();
      if (isCurrentFastStream()) {
        fastStreamTask = undefined;
        if (!state.pendingRunId) {
          state.loading = false;
        }
        persistState();
      }
    }
  }

  async function submitDurableRun(
    requestPayload: Parameters<typeof knowledgeApi.startChatRun>[0],
    assistantMessage: KnowledgeMessage,
    submitGeneration: number,
  ) {
    assistantMessage.status = normalizeRunStatus('RUNNING');
    state.status = normalizeRunStatus('RUNNING');
    persistState();
    const response = await knowledgeApi.startChatRun(requestPayload);
    const run = response.data.data;
    if (submitGeneration !== fastStreamGeneration
      || requestPayload.conversationId !== state.conversationId
      || !state.messages.includes(assistantMessage)) {
      return;
    }
    state.conversationId = run.conversationId || state.conversationId;
    const conversation = state.conversations.find((item) => item.conversationId === state.conversationId);
    if (conversation) {
      conversation.lastRunId = run.runId;
      conversation.updatedAt = run.updatedAt || run.queuedAt || conversation.updatedAt;
    } else {
      state.conversations = [{
        conversationId: state.conversationId,
        projectId: state.activeProjectId ?? undefined,
        title: requestPayload.question.slice(0, 32),
        status: 'ACTIVE',
        lastRunId: run.runId,
        updatedAt: run.updatedAt || run.queuedAt,
      }, ...state.conversations];
    }
    emitKnowledgeConversationsChanged({ projectId: state.activeProjectId });
    beginRunTracking(run, assistantMessage);
  }

  async function resumePendingRun() {
    if (!state.pendingRunId) {
      return;
    }
    const runId = state.pendingRunId;
    const conversationId = state.conversationId;
    const generation = trackingGeneration;
    const assistantMessage = ensurePendingRunAssistantMessage();
    state.loading = true;
    try {
      const response = await knowledgeApi.getChatRun(runId);
      if (state.pendingRunId !== runId || state.conversationId !== conversationId) {
        return;
      }
      const run = response.data.data;
      beginRunTracking(run, assistantMessage);
    } catch {
      if (generation === trackingGeneration
        && state.pendingRunId === runId
        && state.conversationId === conversationId) {
        trackedAssistantMessage = assistantMessage;
        state.loading = true;
        state.status = '正在恢复后台回答';
        assistantMessage.status = state.status;
        durableRunPollStartedAt = Date.now();
        scheduleChatRunPoll(runId, assistantMessage, generation);
        persistState();
      }
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

  function scheduleChatRunPoll(
    runId: string,
    assistantMessage: KnowledgeMessage,
    generation = trackingGeneration,
  ) {
    if (typeof window === 'undefined') {
      return;
    }
    if (document.visibilityState === 'hidden') {
      return;
    }
    if (durableRunPollTimer !== undefined) {
      window.clearTimeout(durableRunPollTimer);
    }
    durableRunPollTimer = window.setTimeout(() => {
      pollChatRun(runId, assistantMessage, generation).catch(() => {
        if (isCurrentRunTracking(runId, generation, assistantMessage)) {
          scheduleChatRunPoll(runId, assistantMessage, generation);
        }
      });
    }, DURABLE_RUN_POLL_INTERVAL_MS);
  }

  async function pollChatRun(
    runId: string,
    assistantMessage: KnowledgeMessage,
    generation: number,
  ) {
    if (document.visibilityState === 'hidden'
      || !isCurrentRunTracking(runId, generation, assistantMessage)) {
      return;
    }
    if (durableRunPollStartedAt && Date.now() - durableRunPollStartedAt > DURABLE_RUN_MAX_ACTIVE_MS) {
      state.loading = false;
      state.errorMessage = '后台回答仍在执行，稍后可回到本会话继续查看结果。';
      persistState();
      return;
    }
    await replayRunEvents(runId, assistantMessage, generation);
    if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
      return;
    }
    const response = await knowledgeApi.getChatRun(runId);
    if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
      return;
    }
    const run = response.data.data;
    applyRunProgress(run, assistantMessage);
    if (isTerminalRun(run)) {
      applyCompletedRun(run, assistantMessage);
      return;
    }
    scheduleChatRunPoll(runId, assistantMessage, generation);
  }

  function applyRunProgress(run: KnowledgeChatRun, assistantMessage: KnowledgeMessage) {
    const status = run.progressMessage || run.progressPhase
      ? normalizeProgressStatus({ phase: run.progressPhase, message: run.progressMessage })
      : normalizeRunStatus(run.status || state.status);
    state.status = status;
    assistantMessage.status = status;
    assistantMessage.runId = run.runId;
    assistantMessage.process = buildRunProcess(run, [], assistantMessage.process);
    const snapshotSequence = Math.max(0, Number(run.snapshotSequenceNo) || 0);
    if (run.answer && (
      isTerminalRun(run)
      || state.lastEventSequence === 0
      || snapshotSequence >= state.lastEventSequence
    )) {
      assistantMessage.content = run.answer;
      state.answer = run.answer;
    }
    if (run.traceId) {
      state.traceId = run.traceId;
      assistantMessage.traceId = run.traceId;
    }
    if (snapshotSequence > state.lastEventSequence) {
      state.lastEventSequence = snapshotSequence;
    }
  }

  function applyCompletedRun(run: KnowledgeChatRun, assistantMessage: KnowledgeMessage) {
    const stored = parseRunResultJson(run.resultJson);
    const envelope = readRunResponseEnvelope(stored);
    const response: KnowledgeChatResponse = {
      status: envelope.status
        || (run.status === 'ANSWERED' ? 'answered' : String(run.status || '').toLowerCase()),
      answer: run.answer || envelope.answer,
      candidates: envelope.candidates,
      sources: envelope.sources,
      actions: envelope.actions,
      resultJson: envelope.resultJson,
    };
    const terminalStatus = String(run.status || '').toUpperCase();
    if (terminalStatus === 'FAILED') {
      state.errorMessage = run.errorMessage || '后台回答失败，请稍后重试。';
    }
    if (terminalStatus === 'CANCELLED') {
      state.errorMessage = '后台回答已取消。';
    }
    state.pendingRunId = '';
    stopRunTransport();
    state.loading = false;
    state.status = normalizeRunStatus(terminalStatus);
    assistantMessage.status = state.status;
    assistantMessage.process = buildRunProcess(run, [], assistantMessage.process, true);
    if (terminalStatus === 'ANSWERED') {
      applyResponse(response, assistantMessage);
    } else {
      if (run.answer) {
        assistantMessage.content = run.answer;
        state.answer = run.answer;
      }
      persistState();
    }
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

  // 持久化 run 的 result_json 有两种形状：
  // 新写入是完整响应信封 {status, answer, candidates, sources, actions, resultJson}；
  // 历史记录是扁平的 worker resultJson，附带 _runStatus / _actions / _sources / _candidates。
  function readRunResponseEnvelope(stored: Record<string, unknown>) {
    const isEnvelope = isRunResponseEnvelope(stored);
    const resultJson = (
      isEnvelope ? parseJsonObject(stored.resultJson) : omitInternalRunKeys(stored)
    ) as KnowledgeChatResponse['resultJson'];
    const pick = (envelopeKey: string, flatKey: string) => (
      isEnvelope ? stored[envelopeKey] : stored[flatKey]
    );
    const asArray = <T>(value: unknown): T[] => (Array.isArray(value) ? value as T[] : []);
    const status = pick('status', '_runStatus');
    return {
      status: typeof status === 'string' ? status : '',
      answer: isEnvelope && typeof stored.answer === 'string' ? stored.answer : '',
      candidates: asArray<KnowledgeBookCandidate>(pick('candidates', '_candidates')),
      sources: asArray<KnowledgeSource>(pick('sources', '_sources')),
      actions: asArray<unknown>(pick('actions', '_actions')).map((item) => String(item)),
      resultJson,
    };
  }

  function omitInternalRunKeys(stored: Record<string, unknown>) {
    return Object.fromEntries(
      Object.entries(stored).filter(([key]) => !key.startsWith('_')),
    );
  }

  function isTerminalRun(run: KnowledgeChatRun) {
    return ['ANSWERED', 'FAILED', 'CANCELLED'].includes(String(run.status || '').toUpperCase());
  }

  async function loadConversationRun(conversationId: string) {
    const normalizedConversationId = conversationId.trim();
    if (!normalizedConversationId) {
      return;
    }
    const generation = ++conversationLoadGeneration;
    const projectId = state.activeProjectId;
    const keepCachedConversation = state.conversationId === normalizedConversationId
      && state.messages.length > 0;
    if (!keepCachedConversation) {
      detachRunTracking();
    }
    state.errorMessage = '';
    try {
      if (!keepCachedConversation) {
        clearVolatileConversation();
        state.loading = true;
      } else {
        state.loading = Boolean(state.pendingRunId);
      }
      state.conversationId = normalizedConversationId;
      let messagesLoaded = false;
      const [messageResult, runResult] = await Promise.allSettled([
        knowledgeApi.listConversationMessages(normalizedConversationId, projectId),
        knowledgeApi.listConversationRuns(normalizedConversationId, MAX_HISTORY_MESSAGES),
      ]);
      if (generation !== conversationLoadGeneration || projectId !== state.activeProjectId) {
        return;
      }
      const conversationRuns = runResult.status === 'fulfilled'
        ? runResult.value.data.data ?? []
        : [];
      if (messageResult.status === 'fulfilled') {
        state.messages = (messageResult.value.data.data ?? [])
          .slice(-MAX_HISTORY_MESSAGES)
          .map(mapConversationMessage);
        messagesLoaded = true;
      } else if (conversationRuns.length && !keepCachedConversation) {
        state.messages = messagesFromRuns(conversationRuns);
      } else if (!keepCachedConversation) {
        state.messages = await loadLegacyConversationRuns(normalizedConversationId);
      }
      const latestAssistant = [...state.messages].reverse().find((message) => message.role === 'assistant');
      state.answer = latestAssistant?.content ?? '';
      state.sources = latestAssistant?.sources ?? [];
      state.loading = Boolean(state.pendingRunId);
      persistState();
      const conversation = state.conversations.find(
        (item) => item.conversationId === normalizedConversationId,
      );
      attachRunSummaries(conversationRuns);
      const run = await loadLatestConversationRun(
        normalizedConversationId,
        conversation?.lastRunId,
        conversationRuns,
      );
      if (generation !== conversationLoadGeneration || projectId !== state.activeProjectId) {
        return;
      }
      if (run) {
        const assistantMessage = ensureRunAssistantMessage(run);
        beginRunTracking(run, assistantMessage);
      } else {
        state.loading = false;
        if (!messagesLoaded && !state.messages.length && !conversationRuns.length) {
          state.errorMessage = '这个会话暂时没有可恢复的回答';
        }
      }
      persistState();
    } catch {
      if (generation === conversationLoadGeneration && projectId === state.activeProjectId) {
        state.loading = false;
        state.errorMessage = '会话恢复失败，请稍后重试';
        persistState();
      }
    }
  }

  function beginRunTracking(run: KnowledgeChatRun, assistantMessage: KnowledgeMessage) {
    const previousRunId = state.pendingRunId;
    detachRunTracking();
    const snapshotSequence = Math.max(0, Number(run.snapshotSequenceNo) || 0);
    state.lastEventSequence = previousRunId === run.runId
      ? Math.max(state.lastEventSequence, snapshotSequence)
      : snapshotSequence;
    state.pendingRunId = isTerminalRun(run) ? '' : run.runId;
    trackedAssistantMessage = assistantMessage;
    applyRunProgress(run, assistantMessage);
    persistState();
    if (isTerminalRun(run)) {
      applyCompletedRun(run, assistantMessage);
      return;
    }
    state.loading = true;
    durableRunPollStartedAt = Date.now();
    connectRunEventStream(run.runId, assistantMessage);
  }

  function connectRunEventStream(runId: string, assistantMessage: KnowledgeMessage) {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
      return;
    }
    stopRunTransport();
    const generation = ++trackingGeneration;
    const task = knowledgeApi.streamChatRunEvents(runId, state.lastEventSequence, {
      onSnapshot(snapshot) {
        if (!isCurrentRunTracking(runId, generation)) {
          return;
        }
        applyRunSnapshot(snapshot, assistantMessage);
      },
      onEvent(event) {
        if (!isCurrentRunTracking(runId, generation)) {
          return;
        }
        applyRunEvent(event, assistantMessage);
      },
    });
    durableRunStreamTask = task;
    task.result
      .then(async () => {
        if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
          return;
        }
        const response = await knowledgeApi.getChatRun(runId);
        if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
          return;
        }
        const run = response.data.data;
        applyRunProgress(run, assistantMessage);
        if (isTerminalRun(run)) {
          applyCompletedRun(run, assistantMessage);
        } else {
          connectRunEventStream(runId, assistantMessage);
        }
      })
      .catch(() => {
        if (isCurrentRunTracking(runId, generation, assistantMessage)) {
          scheduleChatRunPoll(runId, assistantMessage, generation);
        }
      });
  }

  function applyRunSnapshot(snapshot: KnowledgeChatRunSnapshot, assistantMessage: KnowledgeMessage) {
    const sequence = Number(snapshot.snapshotSequenceNo) || 0;
    if (sequence < state.lastEventSequence) {
      return;
    }
    state.lastEventSequence = sequence;
    assistantMessage.content = snapshot.answer || '';
    state.answer = assistantMessage.content;
    assistantMessage.process = {
      ...(assistantMessage.process ?? emptyRunProcess('processing')),
      status: 'processing',
      startedAtMs: assistantMessage.process?.startedAtMs ?? Date.now(),
      finishedAtMs: undefined,
    };
    persistState();
  }

  function applyRunEvent(event: KnowledgeChatRunEvent, assistantMessage: KnowledgeMessage) {
    const sequence = Number(event.sequenceNo || event.sequence) || 0;
    if (sequence <= state.lastEventSequence) {
      return;
    }
    if (sequence > state.lastEventSequence + 1) {
      const generation = trackingGeneration;
      void replayRunEvents(event.runId, assistantMessage, generation).catch(() => {
        if (isCurrentRunTracking(event.runId, generation, assistantMessage)) {
          scheduleChatRunPoll(event.runId, assistantMessage, generation);
        }
      });
      return;
    }
    state.lastEventSequence = sequence;
    const eventType = String(event.eventType || '').toUpperCase();
    const payload = parseJsonObject(event.payload);
    if (eventType === 'DELTA') {
      const delta = typeof payload.delta === 'string' ? payload.delta : '';
      assistantMessage.content += delta;
      state.answer = assistantMessage.content;
    } else if (RUN_PROGRESS_EVENT_TYPES.has(eventType)) {
      const progressPayload = normalizeRunProgressPayload(eventType, payload);
      const status = normalizeProgressStatus({
        phase: typeof progressPayload.phase === 'string' ? progressPayload.phase : '',
        message: typeof progressPayload.message === 'string' ? progressPayload.message : '',
      });
      state.status = status;
      assistantMessage.status = status;
      recordProcessProgress(
        assistantMessage,
        typeof progressPayload.phase === 'string' ? progressPayload.phase : '',
        typeof progressPayload.message === 'string' ? progressPayload.message : '',
      );
      applyContextBudgetProgress(progressPayload, assistantMessage);
    } else if (eventType === 'CANCEL_REQUESTED') {
      state.status = '正在取消后台回答';
      assistantMessage.status = state.status;
    } else if (eventType === 'RUNNING' || eventType === 'EXECUTE') {
      state.status = normalizeRunStatus(eventType);
      assistantMessage.status = state.status;
    } else if (['ANSWERED', 'FAILED', 'CANCELLED'].includes(eventType)) {
      if (typeof payload.answer === 'string') {
        assistantMessage.content = payload.answer;
        state.answer = payload.answer;
      }
      void finalizeTrackedRun(
        event.runId,
        assistantMessage,
        eventType,
        trackingGeneration,
      );
    }
    persistState();
  }

  async function finalizeTrackedRun(
    runId: string,
    assistantMessage: KnowledgeMessage,
    fallbackStatus: string,
    generation: number,
  ) {
    if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
      return;
    }
    try {
      const response = await knowledgeApi.getChatRun(runId);
      if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
        return;
      }
      applyCompletedRun(response.data.data, assistantMessage);
    } catch {
      if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
        return;
      }
      applyCompletedRun({
        runId,
        conversationId: state.conversationId,
        status: fallbackStatus,
        answer: assistantMessage.content,
      }, assistantMessage);
    }
  }

  async function replayRunEvents(
    runId: string,
    assistantMessage: KnowledgeMessage,
    generation: number,
  ) {
    const response = await knowledgeApi.listChatRunEvents(runId, state.lastEventSequence, 200);
    if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
      return;
    }
    for (const event of response.data.data ?? []) {
      applyRunEvent(event, assistantMessage);
      if (!isCurrentRunTracking(runId, generation, assistantMessage)) {
        break;
      }
    }
  }

  async function loadLatestConversationRun(
    conversationId: string,
    lastRunId?: string,
    runs: KnowledgeChatRun[] = [],
  ) {
    const cached = lastRunId
      ? runs.find((run) => run.runId === lastRunId)
      : runs[0];
    if (cached) {
      return cached;
    }
    if (lastRunId) {
      try {
        const response = await knowledgeApi.getChatRun(lastRunId);
        return response.data.data;
      } catch {
        // Fall through to the legacy Run list while read rollout is incomplete.
      }
    }
    const response = await knowledgeApi.listConversationRuns(conversationId, 1);
    return response.data.data?.[0];
  }

  function attachRunSummaries(runs: KnowledgeChatRun[]) {
    const runById = new Map(runs.map((run) => [run.runId, run]));
    for (const message of state.messages) {
      if (message.role !== 'assistant' || !message.runId) {
        continue;
      }
      const run = runById.get(message.runId);
      if (!run) {
        continue;
      }
      message.status = normalizeRunStatus(run.status);
      message.traceId = run.traceId || message.traceId;
      message.process = buildRunProcess(run, [], message.process);
    }
  }

  async function loadLegacyConversationRuns(conversationId: string) {
    const response = await knowledgeApi.listConversationRuns(conversationId, 20);
    return messagesFromRuns(response.data.data ?? []);
  }

  function messagesFromRuns(sourceRuns: KnowledgeChatRun[]) {
    const runs = [...sourceRuns].reverse();
    return runs.flatMap((run) => {
      const messages: KnowledgeMessage[] = [{
        role: 'user',
        content: run.question || '历史会话',
        runId: run.runId,
      }];
      if (run.answer) {
        messages.push({
          role: 'assistant',
          content: run.answer,
          status: normalizeRunStatus(run.status),
          traceId: run.traceId,
          runId: run.runId,
          sources: [],
        });
      }
      return messages;
    }).slice(-MAX_HISTORY_MESSAGES);
  }

  function ensureRunAssistantMessage(run: KnowledgeChatRun) {
    const existing = [...state.messages].reverse().find(
      (message) => message.role === 'assistant' && (!message.runId || message.runId === run.runId),
    );
    if (existing) {
      return existing;
    }
    const placeholder: KnowledgeMessage = {
      role: 'assistant',
      content: run.answer || '',
      status: normalizeRunStatus(run.status),
      runId: run.runId,
      sources: [],
    };
    state.messages.push(placeholder);
    return placeholder;
  }

  function mapConversationMessage(message: KnowledgeConversationMessage): KnowledgeMessage {
    const metadata = parseJsonObject(message.contentJson);
    return {
      role: String(message.role || '').toUpperCase() === 'ASSISTANT' ? 'assistant' : 'user',
      content: message.content || '',
      status: typeof metadata.status === 'string'
        ? knowledgeUserStatusLabel(metadata.status)
        : undefined,
      answerStatus: typeof metadata.answerStatus === 'string' ? metadata.answerStatus : undefined,
      intent: typeof metadata.intent === 'string' ? metadata.intent : undefined,
      answerBoundary: typeof metadata.answerBoundary === 'string' ? metadata.answerBoundary : undefined,
      traceId: typeof metadata.traceId === 'string' ? metadata.traceId : undefined,
      runId: message.runId,
      sources: Array.isArray(metadata.sources) ? metadata.sources as KnowledgeSource[] : [],
    };
  }

  function parseJsonObject(raw: unknown): Record<string, unknown> {
    if (!raw) {
      return {};
    }
    if (typeof raw === 'object' && !Array.isArray(raw)) {
      return raw as Record<string, unknown>;
    }
    try {
      const parsed = JSON.parse(String(raw));
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? parsed as Record<string, unknown>
        : {};
    } catch {
      return {};
    }
  }

  function applyResponse(
    response: KnowledgeChatResponse,
    existingMessage?: KnowledgeMessage | null,
    options: { preserveStreamingContent?: boolean } = {},
  ) {
    const displayStatus = knowledgeUserStatusLabel(response.status);
    state.status = displayStatus;
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
        existingMessage.status = displayStatus;
        existingMessage.answerStatus = answerStatus;
        existingMessage.intent = intent;
        existingMessage.answerBoundary = answerBoundary;
        existingMessage.sources = response.sources ?? [];
        existingMessage.fallbackUsed = fallbackUsed;
        existingMessage.degraded = degraded;
        existingMessage.degradationReasons = degradationReasons;
        existingMessage.contextBudget = contextBudget ?? undefined;
        existingMessage.traceId = traceId || existingMessage.traceId;
        existingMessage.process = buildResponseProcess(response, existingMessage.process);
        state.answer = existingMessage.content;
      } else {
        state.messages.push({
          role: 'assistant',
          content: response.answer,
          status: displayStatus,
          answerStatus,
          intent,
          answerBoundary,
          sources: response.sources ?? [],
          fallbackUsed,
          degraded,
          degradationReasons,
          contextBudget: contextBudget ?? undefined,
          traceId: traceId || undefined,
          process: buildResponseProcess(response),
        });
        state.answer = response.answer;
      }
    }

    updateContextSummary(response, {
      assistantContent: options.preserveStreamingContent ? existingMessage?.content : undefined,
    });
    trimMessages();
    persistState();
    emitKnowledgeConversationsChanged({ projectId: state.activeProjectId });
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
    const contextCompaction = parseJsonObject(response.resultJson?.contextCompaction);
    const compactedSummary = typeof contextCompaction.compactedSummary === 'string'
      ? contextCompaction.compactedSummary.trim()
      : '';
    if (compactedSummary) {
      const continuationParts = [
        compactedSummary,
        latestUser ? `最新用户问题：${truncateForContext(latestUser, 2_400)}` : '',
        latestAssistant ? `最新回答：${truncateForContext(latestAssistant, assistantBudget)}` : '',
      ].filter(Boolean);
      state.contextSummary = truncateForContext(
        continuationParts.join('\n'),
        MAX_CONTEXT_SUMMARY_LENGTH,
      );
      return;
    }
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
    const budget = value as ContextBudget & { memoryLayers?: unknown };
    const rawMemoryLayers = budget.memoryLayers;
    const memoryLayers = Array.isArray(rawMemoryLayers)
      ? rawMemoryLayers
      : rawMemoryLayers && typeof rawMemoryLayers === 'object'
        ? Object.entries(rawMemoryLayers).map(([name, rawLayer]) => {
          const layer = rawLayer && typeof rawLayer === 'object' && !Array.isArray(rawLayer)
            ? rawLayer as Record<string, unknown>
            : {};
          return { ...layer, name };
        })
        : [];
    return {
      ...budget,
      memoryLayers: memoryLayers.map((layer) => {
        const rawCount = Number(layer.itemCount ?? layer.count);
        const keyCount = Array.isArray(layer.keys) ? layer.keys.length : undefined;
        const sourceCount = Array.isArray(layer.sourceIds) ? layer.sourceIds.length : undefined;
        const itemCount = Number.isFinite(rawCount)
          ? rawCount
          : keyCount ?? sourceCount;
        return {
          ...layer,
          name: String(layer.name ?? ''),
          status: layer.status === undefined ? undefined : String(layer.status),
          itemCount,
        };
      }),
      warnings: Array.isArray(budget.warnings) ? budget.warnings.map(String) : [],
    };
  }

  function applyContextBudgetProgress(raw: unknown, assistantMessage: KnowledgeMessage) {
    const payload = parseJsonObject(raw);
    const progressEvent = String(payload.progressEvent || payload.event || '').trim();
    if (progressEvent !== 'context_compacting' && progressEvent !== 'context_compacted') {
      return;
    }
    const current = state.contextBudget ?? {};
    const maxInputTokens = firstNonNegativeNumber(
      payload.contextWindowTokens,
      current.maxInputTokens,
    );
    const beforeInputTokens = firstNonNegativeNumber(payload.beforeInputTokens);
    const afterInputTokens = firstNonNegativeNumber(payload.afterInputTokens);
    const observedInputTokens = progressEvent === 'context_compacted'
      ? afterInputTokens ?? beforeInputTokens
      : beforeInputTokens ?? afterInputTokens;
    const remainingTokens = maxInputTokens !== undefined && observedInputTokens !== undefined
      ? Math.max(0, maxInputTokens - observedInputTokens)
      : firstNonNegativeNumber(current.remainingTokens);
    const remainingRatio = maxInputTokens !== undefined && maxInputTokens > 0 && remainingTokens !== undefined
      ? Math.max(0, Math.min(1, remainingTokens / maxInputTokens))
      : normalizedRatio(current.remainingRatio);
    const nextBudget = normalizeContextBudget({
      ...current,
      maxInputTokens,
      usedTokens: observedInputTokens,
      observedInputTokens,
      estimatedUsedTokens: observedInputTokens ?? current.estimatedUsedTokens,
      remainingTokens,
      remainingRatio,
      compressionThresholdTokens: firstNonNegativeNumber(
        payload.thresholdTokens,
        current.compressionThresholdTokens,
        current.compressionThreshold,
      ),
      compacting: progressEvent === 'context_compacting',
      compressed: progressEvent === 'context_compacted' ? true : current.compressed,
      compactionGeneration: firstNonNegativeNumber(payload.generation, current.compactionGeneration),
      lastCompactedFromTokens: progressEvent === 'context_compacted'
        ? beforeInputTokens ?? current.lastCompactedFromTokens
        : current.lastCompactedFromTokens,
    });
    if (!nextBudget) {
      return;
    }
    state.contextBudget = nextBudget;
    assistantMessage.contextBudget = nextBudget;
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

  function normalizedRatio(value: unknown) {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? Math.max(0, Math.min(1, numeric)) : undefined;
  }

  function normalizeRunProgressPayload(
    eventType: string,
    payload: Record<string, unknown>,
  ): Record<string, unknown> {
    if (payload.progressEvent === 'context_compacting' || payload.progressEvent === 'context_compacted') {
      return payload;
    }
    if (eventType === 'CONTEXT_COMPACTING') {
      return { ...payload, progressEvent: 'context_compacting' };
    }
    if (eventType === 'CONTEXT_COMPACTED') {
      return { ...payload, progressEvent: 'context_compacted' };
    }
    return payload;
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
        state.activeWorkId = getStoredKnowledgeWorkId(activeProjectId);
        state.referenceWorkIds = getStoredKnowledgeReferenceWorkIds(activeProjectId)
          .filter((workId) => workId !== state.activeWorkId);
      }
      const raw = window.localStorage.getItem(storageKey());
      if (!raw) {
        return;
      }
      const saved = JSON.parse(raw) as PersistedKnowledgeChatState;
      if (!activeProjectId) {
        state.activeProjectId = normalizeProjectId(saved.activeProjectId);
      }
      if (!state.activeWorkId) {
        state.activeWorkId = normalizeKnowledgeWorkId(saved.activeWorkId)
          ?? getStoredKnowledgeWorkId(state.activeProjectId);
      }
      state.messages = Array.isArray(saved.messages) ? saved.messages.slice(-MAX_PERSISTED_MESSAGES) : [];
      state.contextSummary = typeof saved.contextSummary === 'string' ? saved.contextSummary : '';
      state.chapterCount = normalizeChapterCount(saved.chapterCount);
      state.reasoningMode = normalizeReasoningMode(saved.reasoningMode);
      state.reasoningEffort = normalizeReasoningEffort(saved.reasoningEffort);
      state.modelKey = typeof saved.modelKey === 'string' ? saved.modelKey : '';
      state.bookName = typeof saved.bookName === 'string' ? saved.bookName : '';
      state.selectedCandidate = saved.selectedCandidate ?? null;
      state.candidates = Array.isArray(saved.candidates) ? saved.candidates : [];
      state.sources = Array.isArray(saved.sources) ? saved.sources : [];
      state.status = typeof saved.status === 'string'
        ? knowledgeUserStatusLabel(saved.status, '')
        : '';
      state.answer = typeof saved.answer === 'string' ? saved.answer : '';
      state.conversationId = typeof saved.conversationId === 'string' ? saved.conversationId : '';
      state.pendingRunId = typeof saved.pendingRunId === 'string' ? saved.pendingRunId : '';
      state.lastEventSequence = Math.max(0, Number(saved.lastEventSequence) || 0);
      state.traceId = typeof saved.traceId === 'string' ? saved.traceId : '';
      state.contextBudget = normalizeContextBudget(saved.contextBudget)
        ?? [...state.messages].reverse().find((message) => message.contextBudget)?.contextBudget
        ?? null;
    } catch {
      try {
        window.localStorage.removeItem(storageKey());
      } catch {
        // The server remains the source of truth when browser storage is unavailable.
      }
    }
  }

  function schedulePersistState() {
    if (typeof window === 'undefined' || persistTimer !== undefined) {
      return;
    }
    persistTimer = window.setTimeout(() => {
      persistTimer = undefined;
      persistState();
    }, 180);
  }

  function flushPersistState() {
    if (persistTimer !== undefined && typeof window !== 'undefined') {
      window.clearTimeout(persistTimer);
      persistTimer = undefined;
    }
    persistState();
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
        runId: message.runId,
        process: message.process,
      })),
      contextSummary: state.contextSummary,
      chapterCount: state.chapterCount,
      reasoningMode: state.reasoningMode,
      reasoningEffort: state.reasoningEffort,
      modelKey: state.modelKey,
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
      lastEventSequence: state.lastEventSequence,
      activeProjectId: state.activeProjectId,
      activeWorkId: state.activeWorkId,
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
    return [3, 5, 10].includes(parsed) ? parsed : 10;
  }

  async function loadMessageProcess(message: KnowledgeMessage) {
    const runId = message.runId?.trim();
    if (!runId || message.role !== 'assistant' || message.process?.loading || message.process?.loaded) {
      return;
    }
    message.process = {
      ...(message.process ?? emptyRunProcess('processed')),
      loading: true,
    };
    const [runResult, eventResult] = await Promise.allSettled([
        knowledgeApi.getChatRun(runId),
        knowledgeApi.listChatRunEvents(runId, 0, 200),
    ]);
    if (!state.messages.includes(message) || message.runId !== runId) {
      return;
    }
    if (runResult.status === 'fulfilled') {
      const events = eventResult.status === 'fulfilled'
        ? eventResult.value.data.data ?? []
        : [];
      message.process = buildRunProcess(
        runResult.value.data.data,
        events,
        message.process,
        true,
      );
    } else {
      message.process = {
        ...(message.process ?? emptyRunProcess('processed')),
        loaded: false,
        loading: false,
      };
    }
    persistState();
  }

  function normalizeReasoningMode(value: unknown): KnowledgeReasoningMode {
    return value === 'deep' ? 'deep' : DEFAULT_REASONING_MODE;
  }

  function normalizeReasoningEffort(value: unknown): KnowledgeReasoningEffort | '' {
    const tier = String(value ?? '').trim().toLowerCase();
    return CANONICAL_REASONING_TIERS.includes(tier as KnowledgeReasoningEffort)
      ? (tier as KnowledgeReasoningEffort)
      : '';
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
    return state.status || '正在后台执行';
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
      case 'EXECUTE':
        return '后台任务已提交';
      case 'CANCELLING':
      case 'CANCEL_REQUESTED':
        return '正在取消后台回答';
      case 'ANSWERED':
        return '后台回答已完成';
      case 'FAILED':
        return '后台回答失败';
      case 'CANCELLED':
        return '后台回答已取消';
      default:
        return state.status || '正在后台执行';
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
    setStoredKnowledgeWorkId(state.activeProjectId, state.activeWorkId);
  }

  function clearConversation() {
    conversationLoadGeneration++;
    newConversationGeneration++;
    state.creatingConversation = false;
    clearVolatileConversation();
    persistState();
  }

  async function startNewConversation() {
    if (state.creatingConversation) {
      return;
    }
    const generation = ++newConversationGeneration;
    const projectId = state.activeProjectId;
    state.creatingConversation = true;
    try {
      const response = await knowledgeApi.createConversation({
        projectId,
        title: '新会话',
      });
      if (generation !== newConversationGeneration || projectId !== state.activeProjectId) {
        return;
      }
      const conversation = response.data.data;
      conversationLoadGeneration++;
      clearVolatileConversation();
      state.conversationId = conversation.conversationId;
      state.conversations = [
        conversation,
        ...state.conversations.filter((item) => item.conversationId !== conversation.conversationId),
      ];
      persistState();
      emitKnowledgeConversationsChanged({ projectId });
    } catch {
      if (generation === newConversationGeneration && projectId === state.activeProjectId) {
        state.errorMessage = '新建会话失败，请稍后重试';
      }
    } finally {
      if (generation === newConversationGeneration) {
        state.creatingConversation = false;
      }
    }
  }

  function storageKey() {
    return state.activeProjectId ? `${PROJECT_STORAGE_PREFIX}${state.activeProjectId}` : STORAGE_KEY;
  }

  function latestUserQuestion() {
    return [...state.messages].reverse().find((message) => message.role === 'user')?.content.trim() ?? '';
  }

  function clearVolatileConversation() {
    detachFastStream();
    detachRunTracking();
    state.loading = false;
    state.errorMessage = '';
    state.status = '';
    state.answer = '';
    state.bookName = '';
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
    state.lastEventSequence = 0;
  }

  async function cancelActiveRun() {
    if (!state.pendingRunId) {
      return;
    }
    const runId = state.pendingRunId;
    const assistantMessage = trackedAssistantMessage ?? ensurePendingRunAssistantMessage();
    try {
      const response = await knowledgeApi.cancelChatRun(runId);
      if (runId !== state.pendingRunId || assistantMessage !== trackedAssistantMessage) {
        return;
      }
      const run = response.data.data;
      applyRunProgress(run, assistantMessage);
      if (isTerminalRun(run)) {
        applyCompletedRun(run, assistantMessage);
      } else {
        state.status = '正在取消后台回答';
        assistantMessage.status = state.status;
        persistState();
      }
    } catch {
      if (runId === state.pendingRunId && assistantMessage === trackedAssistantMessage) {
        state.errorMessage = '取消后台回答失败，请稍后重试';
      }
    }
  }

  function handleVisibilityChange() {
    if (!state.pendingRunId || !trackedAssistantMessage) {
      return;
    }
    if (document.visibilityState === 'hidden') {
      stopRunTransport();
      return;
    }
    connectRunEventStream(state.pendingRunId, trackedAssistantMessage);
  }

  function stopRunTransport() {
    if (durableRunPollTimer !== undefined && typeof window !== 'undefined') {
      window.clearTimeout(durableRunPollTimer);
      durableRunPollTimer = undefined;
    }
    durableRunStreamTask?.abort();
    durableRunStreamTask = undefined;
  }

  function detachRunTracking() {
    trackingGeneration++;
    stopRunTransport();
    trackedAssistantMessage = null;
  }

  function detachFastStream() {
    fastStreamGeneration++;
    fastStreamTask?.abort();
    fastStreamTask = undefined;
  }

  function isCurrentRunTracking(
    runId: string,
    generation: number,
    assistantMessage?: KnowledgeMessage,
  ) {
    return generation === trackingGeneration
      && runId === state.pendingRunId
      && (!assistantMessage || assistantMessage === trackedAssistantMessage);
  }

  function dispose() {
    conversationLoadGeneration++;
    conversationListGeneration++;
    workLoadGeneration++;
    newConversationGeneration++;
    detachFastStream();
    detachRunTracking();
    flushPersistState();
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
    selectedModel,
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
    loadProjectConversations,
    createProject,
    selectProject,
    loadConversationRun,
    clearConversation,
    startNewConversation,
    cancelActiveRun,
    handleVisibilityChange,
    loadMessageProcess,
    dispose,
    deleteMessage,
  };
}

const PROCESS_NODE_LABELS: Record<string, string> = {
  assemble_context: '整理会话与项目上下文',
  classify_intent: '识别写作意图',
  build_task_graph: '规划任务步骤',
  plan_tasks: '规划任务步骤',
  validate_preconditions: '检查任务前置条件',
  execute_tools: '检索并调用资料',
  collect_evidence: '整理可用证据',
  review_evidence: '校验证据充分性',
  evidence_review: '校验证据充分性',
  route_experts: '选择领域专家',
  analyze_market_evidence: '分析榜单样本',
  market_evidence_analysis: '分析榜单样本',
  compose_answer: '生成完整回答',
  review_answer: '审查回答质量',
  revise_answer: '修订回答',
  extract_memory: '更新会话记忆',
  finalize_trace: '完成运行记录',
};

const PROCESS_PHASE_LABELS: Record<string, string> = {
  prepare: '整理会话与项目上下文',
  intent: '识别写作意图',
  plan: '规划任务步骤',
  preconditions: '检查任务前置条件',
  retrieve: '检索并调用资料',
  evidence: '整理可用证据',
  evidence_review: '校验证据充分性',
  analysis: '分析榜单样本',
  generate: '生成完整回答',
  answer: '生成完整回答',
  review: '审查回答质量',
  revise: '修订回答',
  memory: '更新会话记忆',
  trace: '完成运行记录',
};

function emptyRunProcess(status: KnowledgeRunProcess['status']): KnowledgeRunProcess {
  return {
    status,
    startedAtMs: status === 'processing' ? Date.now() : undefined,
    steps: [],
    loaded: false,
  };
}

function buildResponseProcess(
  response: KnowledgeChatResponse,
  existing?: KnowledgeRunProcess,
): KnowledgeRunProcess {
  const terminalStatus = responseProcessStatus(response.status);
  const current = existing ?? emptyRunProcess(terminalStatus);
  const finishedAtMs = terminalStatus === 'processing'
    ? undefined
    : current.finishedAtMs ?? Date.now();
  const durationMs = current.startedAtMs !== undefined && finishedAtMs !== undefined
    ? Math.max(0, finishedAtMs - current.startedAtMs)
    : current.durationMs;
  return buildProcessFromResultJson(response.resultJson, {
    ...current,
    status: terminalStatus,
    currentStep: terminalStatus === 'processing' ? existing?.currentStep : undefined,
    finishedAtMs,
    durationMs,
    loaded: true,
    loading: false,
  });
}

function responseProcessStatus(status: string): KnowledgeRunProcess['status'] {
  switch (String(status || '').trim().toLowerCase()) {
    case 'failed':
    case 'error':
      return 'failed';
    case 'cancelled':
      return 'cancelled';
    case 'pending':
    case 'running':
    case 'processing':
    case 'streaming':
    case 'cancelling':
    case 'cancel_requested':
    case '':
      return 'processing';
    default:
      return 'processed';
  }
}

function buildRunProcess(
  run: KnowledgeChatRun,
  events: KnowledgeChatRunEvent[],
  existing?: KnowledgeRunProcess,
  loaded = false,
): KnowledgeRunProcess {
  const status = runProcessStatus(run.status);
  const parsedStartedAtMs = parseTimestampMs(run.startedAt || run.queuedAt);
  const startedAtMs = parsedStartedAtMs
    ?? existing?.startedAtMs
    ?? (status === 'processing' ? Date.now() : undefined);
  const parsedFinishedAtMs = parseTimestampMs(
    run.finishedAt || (status === 'processing' ? undefined : run.updatedAt),
  );
  const finishedAtMs = status === 'processing'
    ? undefined
    : parsedFinishedAtMs ?? existing?.finishedAtMs ?? (startedAtMs !== undefined ? Date.now() : undefined);
  const durationMs = startedAtMs !== undefined && finishedAtMs !== undefined
    ? Math.max(0, finishedAtMs - startedAtMs)
    : existing?.durationMs;
  const base = buildProcessFromResultJson(parseRunResultJsonValue(run.resultJson), {
    ...(existing ?? emptyRunProcess(status)),
    status,
    currentStep: status === 'processing' ? existing?.currentStep : undefined,
    startedAtMs,
    finishedAtMs,
    durationMs,
    loaded,
    loading: false,
  });
  const rawEventSteps = events
    .filter((event) => RUN_PROGRESS_EVENT_TYPES.has(String(event.eventType || '').toUpperCase()))
    .map((event) => {
      const payload = parseJsonRecord(event.payload);
      return processStepFromProgress(
        String(payload.phase || ''),
        String(payload.message || ''),
        Number(event.sequenceNo || event.sequence) || 0,
      );
    })
    .filter((step): step is KnowledgeRunProcessStep => step !== null);
  const eventSteps = rawEventSteps.map((step, index) => ({
    ...step,
    status: status === 'processing' && index === rawEventSteps.length - 1
      ? 'running' as const
      : 'completed' as const,
  }));
  const runProgressStep = status === 'processing'
    ? processStepFromProgress(run.progressPhase || '', run.progressMessage || '', 0, 'running')
    : null;
  const eventCurrentStep = [...eventSteps].reverse().find((step) => step.status === 'running');
  const currentStep = status === 'processing'
    ? eventCurrentStep ?? existing?.currentStep ?? runProgressStep ?? undefined
    : undefined;
  const settledBaseSteps = base.steps.map((step) => (
    step.status === 'running' ? { ...step, status: 'completed' as const } : step
  ));
  base.steps = mergeProcessSteps(
    settledBaseSteps,
    [...eventSteps, ...(runProgressStep ? [runProgressStep] : [])],
  ).map((step) => ({
    ...step,
    status: currentStep?.id === step.id
      ? 'running' as const
      : step.status === 'running' ? 'completed' as const : step.status,
  }));
  base.currentStep = currentStep;
  if (!base.steps.length && status !== 'processing') {
    base.steps = [{ id: 'compose_answer', label: '生成完整回答', status: status === 'failed' ? 'failed' : 'completed' }];
  }
  return base;
}

function buildProcessFromResultJson(
  resultJson: Record<string, unknown>,
  process: KnowledgeRunProcess,
): KnowledgeRunProcess {
  const trace = parseJsonRecord(resultJson.trace);
  const nodes = Array.isArray(trace.nodes) ? trace.nodes : [];
  const traceSteps = nodes
    .map((item, index) => processStepFromTraceNode(item, index))
    .filter((step): step is KnowledgeRunProcessStep => step !== null);
  const modelSummary = parseJsonRecord(resultJson.modelCallSummary);
  const traceModelSummary = parseJsonRecord(trace.modelCallSummary);
  const topLevelProviderCalls = Array.isArray(resultJson.providerCalls) ? resultJson.providerCalls : [];
  const traceProviderCalls = Array.isArray(trace.providerCalls) ? trace.providerCalls : [];
  const providerCalls = topLevelProviderCalls.length ? topLevelProviderCalls : traceProviderCalls;
  const modelCalls = providerCalls
    .map((item, index) => modelCallFromProviderCall(item, index))
    .filter((call): call is KnowledgeRunModelCall => call !== null);
  const providerRequestCount = providerCalls.reduce((total, value) => {
    const call = parseJsonRecord(value);
    return total + (positiveNumber(call.providerRequestCount) ?? 1);
  }, 0);
  const modelCallCount = positiveNumber(modelSummary.total)
    ?? positiveNumber(traceModelSummary.total)
    ?? (providerCalls.length ? providerRequestCount : undefined);
  const operationalSummaries = buildOperationalSummaries(
    resultJson,
    trace,
    providerCalls,
    modelCallCount,
  );
  const promptCache = promptCacheSummary(
    Object.keys(parseJsonRecord(modelSummary.promptCache)).length
      ? modelSummary.promptCache
      : traceModelSummary.promptCache,
    providerCalls,
  );
  return {
    ...process,
    modelCallCount: modelCallCount ?? process.modelCallCount,
    modelCalls: modelCalls.length ? modelCalls : process.modelCalls,
    promptCache: promptCache ?? process.promptCache,
    contextCompaction: contextCompactionSummary(resultJson, trace) ?? process.contextCompaction,
    degradationReasons: degradationReasonList(resultJson, trace) ?? process.degradationReasons,
    operationalSummaries: operationalSummaries.length
      ? operationalSummaries
      : process.operationalSummaries,
    steps: mergeProcessSteps(process.steps, traceSteps),
  };
}

function modelCallFromProviderCall(value: unknown, index: number): KnowledgeRunModelCall | null {
  const call = parseJsonRecord(value);
  if (!Object.keys(call).length) {
    return null;
  }
  const node = String(call.node || '').trim();
  const statusValue = String(call.status || '').trim().toLowerCase();
  const status: KnowledgeRunModelCall['status'] = statusValue === 'succeeded'
    ? 'succeeded'
    : statusValue === 'failed'
      ? 'failed'
      : 'unknown';
  const reasoningValue = String(call.requestedReasoningMode || '').trim().toLowerCase();
  const reasoningMode = reasoningValue === 'deep' || reasoningValue === 'fast'
    ? reasoningValue
    : undefined;
  const routedModel = safeModelName(call.routedModel);
  const requestSummary = providerRequestSummary(call.requestSummary);
  return {
    id: `model-call-${index}`,
    label: modelCallLabel(node, index),
    model: safeModelName(call.actualModel || call.model || call.requestedModel),
    status,
    durationMs: positiveNumber(call.durationMs),
    tokenUsed: positiveNumber(call.tokenUsed),
    reasoningMode,
    providerRequestCount: positiveNumber(call.providerRequestCount),
    attemptIndex: positiveNumber(call.attemptIndex),
    profileKeyUsed: trimmedText(call.profileKeyUsed),
    failureClass: trimmedText(call.failureClass),
    wireApi: providerWireApi(call.wireApi),
    providerTransportFallback: providerTransportFallback(call.providerTransportFallback),
    usage: providerUsage(call.usage),
    requestSummary,
    responseSummary: providerResponseSummary(call.responseSummary),
    // 顶层标志比 usage 里的更可信：usage 缺失时它依然存在。
    usageReported: call.usageReported === true ? true : undefined,
    cacheUsageReported: call.cacheUsageReported === true ? true : undefined,
    routedModel,
    modelSubstituted: call.modelSubstituted === true ? true : undefined,
    requestFamily: requestSummary?.requestFamily,
  };
}

function providerRequestSummary(value: unknown): KnowledgeProviderRequestSummary | undefined {
  const summary = parseJsonRecord(value);
  if (!Object.keys(summary).length) {
    return undefined;
  }
  const cachePrefixFingerprint = trimmedText(summary.cachePrefixFingerprint);
  return {
    messageCount: positiveNumber(summary.messageCount),
    roleCounts: Object.fromEntries(
      Object.entries(parseJsonRecord(summary.roleCounts))
        .map(([role, count]) => [role, positiveNumber(count)])
        .filter((entry): entry is [string, number] => entry[1] !== undefined),
    ),
    messageChars: positiveNumber(summary.messageChars),
    toolSchemaCount: positiveNumber(summary.toolSchemaCount),
    reasoningRequested: summary.reasoningRequested === true,
    bodyRedacted: summary.bodyRedacted === true,
    requestFamily: trimmedText(summary.requestFamily),
    cacheAffinityPresent: typeof summary.cacheAffinityPresent === 'boolean'
      ? summary.cacheAffinityPresent
      : undefined,
    // 前缀 0 字符本身就是结论（供应商根本没东西可缓存），不能折叠成 undefined。
    cachePrefixChars: nonNegativeNumber(summary.cachePrefixChars),
    cachePrefixFingerprint: cachePrefixFingerprint && /^[0-9a-f]{64}$/.test(cachePrefixFingerprint)
      ? cachePrefixFingerprint
      : undefined,
  };
}

function providerResponseSummary(value: unknown): KnowledgeProviderResponseSummary | undefined {
  const summary = parseJsonRecord(value);
  if (!Object.keys(summary).length) {
    return undefined;
  }
  return {
    outputChars: positiveNumber(summary.outputChars),
    toolCallCount: positiveNumber(summary.toolCallCount),
    emptyResponse: summary.emptyResponse === true,
    bodyRedacted: summary.bodyRedacted === true,
  };
}

function providerWireApi(value: unknown): KnowledgeRunModelCall['wireApi'] {
  const normalized = String(value || '').trim().toLowerCase().replace(/-/g, '_');
  return normalized === 'responses' || normalized === 'chat_completions'
    ? normalized
    : undefined;
}

function providerTransportFallback(value: unknown): KnowledgeProviderTransportFallback | undefined {
  const fallback = parseJsonRecord(value);
  const from = providerWireApi(fallback.from);
  const to = providerWireApi(fallback.to);
  if (!from || !to || fallback.reason !== 'model_not_responses_capable') {
    return undefined;
  }
  return {
    from,
    to,
    reason: 'model_not_responses_capable',
    model: safeModelName(fallback.model),
  };
}

function providerUsage(value: unknown): KnowledgeProviderUsage | undefined {
  const usage = parseJsonRecord(value);
  if (!Object.keys(usage).length) {
    return undefined;
  }
  // 上游确认回报过用量时，0 是结论（这一刀真的一次没命中），必须留住；没回报时
  // 0 只是 _usage_summary 的占位，折叠掉才不会把"不知道"画成"上下文 0 / 命中 0"。
  const tokenNumber = reportedNumber(usage.usageReported === true);
  const cacheNumber = reportedNumber(usage.cacheUsageReported === true);
  return {
    inputTokens: tokenNumber(usage.inputTokens),
    outputTokens: tokenNumber(usage.outputTokens),
    reasoningTokens: tokenNumber(usage.reasoningTokens),
    cachedInputTokens: cacheNumber(usage.cachedInputTokens),
    promptTokens: tokenNumber(usage.promptTokens),
    completionTokens: tokenNumber(usage.completionTokens),
    promptCacheHitTokens: cacheNumber(usage.promptCacheHitTokens),
    promptCacheMissTokens: cacheNumber(usage.promptCacheMissTokens),
    promptCacheWriteTokens: cacheNumber(usage.promptCacheWriteTokens),
    promptCacheMissTokensDerived: usage.promptCacheMissTokensDerived === true ? true : undefined,
    cacheWriteInputTokens: cacheNumber(usage.cacheWriteInputTokens),
    totalTokens: tokenNumber(usage.totalTokens),
    usageReported: usage.usageReported === true ? true : undefined,
    cacheUsageReported: usage.cacheUsageReported === true ? true : undefined,
  };
}

/** 上报过就保留 0，没上报就把 0 当占位丢掉——老 run 没有标志，靠非零值兜底。 */
function reportedNumber(reported: boolean) {
  return (value: unknown): number | undefined => {
    const parsed = nonNegativeNumber(value);
    if (parsed === undefined) {
      return undefined;
    }
    return reported || parsed > 0 ? parsed : undefined;
  };
}

/** 0 和 null 必须分开：0 是观测到的数量，null 是"上游没说"。 */
function nonNegativeNumber(value: unknown): number | undefined {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean') {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : undefined;
}

function promptCacheSummary(
  value: unknown,
  providerCalls: unknown[],
): KnowledgeRunPromptCacheSummary | undefined {
  const summary = parseJsonRecord(value);
  if (Object.keys(summary).length) {
    const hitTokens = nonNegativeNumber(summary.hitTokens) ?? 0;
    const missTokens = nonNegativeNumber(summary.missTokens) ?? 0;
    const accounted = hitTokens + missTokens;
    return {
      calls: nonNegativeNumber(summary.calls) ?? providerCalls.length,
      reportingCalls: nonNegativeNumber(summary.reportingCalls) ?? 0,
      measured: summary.measured === true,
      hitTokens,
      missTokens,
      hitRatioPercent: nonNegativeNumber(summary.hitRatioPercent)
        ?? (accounted ? Math.round((hitTokens * 1000) / accounted) / 10 : null),
    };
  }
  // 老 run 只有 0 占位、没有上报标志，这时任何比率都是编的。只在真的有标志的
  // 调用上重算；一条都没有就整块不显示，免得和逐条显示的原始数字自相矛盾。
  if (!providerCalls.length) {
    return undefined;
  }
  let reportingCalls = 0;
  let hitTokens = 0;
  let missTokens = 0;
  providerCalls.forEach((item) => {
    const call = parseJsonRecord(item);
    const usage = parseJsonRecord(call.usage);
    if (call.cacheUsageReported !== true && usage.cacheUsageReported !== true) {
      return;
    }
    reportingCalls += 1;
    hitTokens += nonNegativeNumber(call.promptCacheHitTokens)
      ?? nonNegativeNumber(usage.promptCacheHitTokens)
      ?? nonNegativeNumber(usage.cachedInputTokens)
      ?? 0;
    missTokens += nonNegativeNumber(call.promptCacheMissTokens)
      ?? nonNegativeNumber(usage.promptCacheMissTokens)
      ?? 0;
  });
  if (!reportingCalls) {
    return undefined;
  }
  const accounted = hitTokens + missTokens;
  return {
    calls: providerCalls.length,
    reportingCalls,
    measured: true,
    hitTokens,
    missTokens,
    hitRatioPercent: accounted ? Math.round((hitTokens * 1000) / accounted) / 10 : null,
  };
}

/** trace 里的 code 类字段：resultJson 是不透明存储，任意长字符串都可能塞进来。 */
function safeTraceCode(value: unknown): string | undefined {
  const normalized = String(value ?? '').replace(/[\r\n\t]+/g, ' ').trim();
  return normalized ? normalized.slice(0, 64) : undefined;
}

/**
 * 上下文压缩的白名单投影。顶层 `contextCompaction` 会带 `compactedSummary`——那是压缩后
 * 的会话正文，trace 里的那份不带。这里两边都只挑枚举和计数字段，正文和
 * `coverageFingerprint` 一律不取。worker 只在 status 既非 `not_needed` 也非 `disabled`
 * 时才写这个对象，所以拿到它就等于"这次压过"。
 */
function contextCompactionSummary(
  resultJson: Record<string, unknown>,
  trace: Record<string, unknown>,
): KnowledgeRunContextCompaction | undefined {
  const source = parseJsonRecord(resultJson.contextCompaction || trace.contextCompaction);
  const status = safeTraceCode(source.status);
  if (!status) {
    return undefined;
  }
  const compaction: KnowledgeRunContextCompaction = { status };
  const reason = safeTraceCode(source.reason);
  if (reason) {
    compaction.reason = reason;
  }
  const model = safeModelName(source.model);
  if (model) {
    compaction.model = model;
  }
  const counters: Array<['contextWindowTokens' | 'thresholdTokens' | 'beforeInputTokens'
    | 'afterInputTokens' | 'retainedTurnCount' | 'summarizedMessageCount'
    | 'reusedMessageCount' | 'generation', unknown]> = [
    ['contextWindowTokens', source.contextWindowTokens],
    ['thresholdTokens', source.thresholdTokens],
    ['beforeInputTokens', source.beforeInputTokens],
    ['afterInputTokens', source.afterInputTokens],
    ['retainedTurnCount', source.retainedTurnCount],
    ['summarizedMessageCount', source.summarizedMessageCount],
    ['reusedMessageCount', source.reusedMessageCount],
    ['generation', source.generation],
  ];
  for (const [key, raw] of counters) {
    const parsed = nonNegativeNumber(raw);
    if (parsed !== undefined) {
      compaction[key] = parsed;
    }
  }
  return compaction;
}

/** 降级原因去重后最多留 8 条，空数组当作"没降级"而不是"未知"。 */
function degradationReasonList(
  resultJson: Record<string, unknown>,
  trace: Record<string, unknown>,
): string[] | undefined {
  const raw = Array.isArray(resultJson.degradationReasons)
    ? resultJson.degradationReasons
    : Array.isArray(trace.degradationReasons)
      ? trace.degradationReasons
      : [];
  const reasons: string[] = [];
  for (const value of raw) {
    const code = safeTraceCode(value);
    if (code && !reasons.includes(code)) {
      reasons.push(code);
    }
    if (reasons.length >= 8) {
      break;
    }
  }
  return reasons.length ? reasons : undefined;
}

function buildOperationalSummaries(
  resultJson: Record<string, unknown>,
  trace: Record<string, unknown>,
  providerCalls: unknown[],
  modelCallCount?: number,
): KnowledgeRunProcessSummary[] {
  const summaries: KnowledgeRunProcessSummary[] = [];
  const intentDecision = parseJsonRecord(resultJson.intentDecision || trace.intentDecision);
  const intent = String(
    intentDecision.primaryIntent
      || resultJson.domainIntent
      || resultJson.intent
      || '',
  ).trim();
  if (intent) {
    summaries.push({
      id: 'task',
      label: '任务判断',
      detail: knowledgeIntentLabel(intent),
    });
  }

  const contextBudget = parseJsonRecord(resultJson.contextBudget || trace.contextBudget);
  const continuity = parseJsonRecord(contextBudget.conversationContinuity);
  const historyTotal = positiveNumber(continuity.historyTotalCount);
  const historyIncluded = positiveNumber(continuity.historyIncludedCount);
  if (historyTotal !== undefined || positiveNumber(continuity.contextSummaryChars) !== undefined) {
    const historyChars = positiveNumber(continuity.historyIncludedChars) ?? 0;
    const summaryChars = positiveNumber(continuity.contextSummaryChars) ?? 0;
    const truncation = continuity.historyTruncated === true || continuity.contextSummaryTruncated === true
      ? '，已按上下文预算裁剪'
      : '，未裁剪';
    summaries.push({
      id: 'context',
      label: '会话上下文',
      detail: `携带 ${historyIncluded ?? 0}/${historyTotal ?? 0} 条历史消息，${historyChars} 字符；摘要 ${summaryChars} 字符${truncation}`,
    });
  }

  if (modelCallCount !== undefined || providerCalls.length) {
    const deepCalls = providerCalls.filter((value) => (
      String(parseJsonRecord(value).requestedReasoningMode || '').toLowerCase() === 'deep'
    )).length;
    summaries.push({
      id: 'model',
      label: '模型执行',
      detail: `完成 ${modelCallCount ?? providerCalls.length} 次真实模型请求${deepCalls ? `，其中 ${deepCalls} 次深度模式` : ''}`,
    });
  }

  const answerReview = parseJsonRecord(resultJson.answerReview || trace.answerReview);
  const reviewed = providerCalls.some((value) => parseJsonRecord(value).node === 'review_answer')
    || Object.keys(answerReview).length > 0;
  if (reviewed) {
    const revised = providerCalls.some((value) => parseJsonRecord(value).node === 'revise_answer');
    summaries.push({
      id: 'review',
      label: '质量审查',
      detail: revised ? '已完成回答审查并执行修订' : '已完成回答审查，未触发修订',
    });
  }
  return summaries;
}

function modelCallLabel(node: string, index: number) {
  const labels: Record<string, string> = {
    classify_intent: '意图识别',
    compose_answer: '回答生成',
    review_answer: '回答审查',
    revise_answer: '回答修订',
    market_evidence_analysis: '市场证据分析',
  };
  if (labels[node]) {
    return labels[node];
  }
  if (node.startsWith('specialist.')) {
    return processNodeLabel(node);
  }
  return `第 ${index + 1} 次模型调用`;
}

function safeModelName(value: unknown) {
  const normalized = String(value || '').replace(/[\r\n\t]+/g, ' ').trim();
  if (
    !normalized
    || /^(?:[a-z]:[\\/]|\\\\|\/|file:)/i.test(normalized)
    || normalized.includes('\\')
  ) {
    return undefined;
  }
  return normalized.slice(0, 80);
}

function processStepFromTraceNode(value: unknown, index: number): KnowledgeRunProcessStep | null {
  const node = parseJsonRecord(value);
  const name = String(node.name || '').trim();
  const label = processNodeLabel(name);
  const status = processStepStatus(String(node.status || ''));
  if (!label || status === 'pending') {
    return null;
  }
  return {
    id: name || `step-${index}`,
    label,
    status,
    durationMs: positiveNumber(node.durationMs),
  };
}

function processStepFromProgress(
  phase: string,
  message: string,
  sequence: number,
  status: KnowledgeRunProcessStep['status'] = 'completed',
): KnowledgeRunProcessStep | null {
  const normalizedPhase = phase.trim().toLowerCase();
  const label = PROCESS_PHASE_LABELS[normalizedPhase] || processNodeLabel(normalizedPhase);
  if (!label) {
    return null;
  }
  return {
    id: normalizedPhase || `progress-${sequence}`,
    label,
    status,
  };
}

function processNodeLabel(name: string) {
  if (PROCESS_NODE_LABELS[name]) {
    return PROCESS_NODE_LABELS[name];
  }
  if (name.startsWith('specialist.')) {
    const specialist = name.slice('specialist.'.length);
    const labels: Record<string, string> = {
      market: '榜单分析专家处理',
      outline: '大纲专家处理',
      chapter_outline: '章节细纲专家处理',
      chapter: '章节分析专家处理',
      book: '作品拆解专家处理',
      editor: '编辑专家处理',
      writing: '写作专家处理',
    };
    return labels[specialist] || '领域专家处理';
  }
  return '';
}

function processStepStatus(status: string): KnowledgeRunProcessStep['status'] {
  switch (status.trim().toLowerCase()) {
    case 'completed':
    case 'succeeded':
    case 'answered':
      return 'completed';
    case 'running':
    case 'in_progress':
      return 'running';
    case 'failed':
    case 'error':
      return 'failed';
    default:
      return 'pending';
  }
}

function runProcessStatus(status: string): KnowledgeRunProcess['status'] {
  switch (String(status || '').toUpperCase()) {
    case 'ANSWERED':
      return 'processed';
    case 'FAILED':
      return 'failed';
    case 'CANCELLED':
      return 'cancelled';
    default:
      return 'processing';
  }
}

function mergeProcessSteps(
  first: KnowledgeRunProcessStep[],
  second: KnowledgeRunProcessStep[],
) {
  const merged = new Map<string, KnowledgeRunProcessStep>();
  for (const step of [...first, ...second]) {
    const key = step.id || step.label;
    merged.set(key, { ...(merged.get(key) ?? {}), ...step });
  }
  return [...merged.values()];
}

function recordProcessProgress(message: KnowledgeMessage, phase?: string, detail?: string) {
  const step = processStepFromProgress(phase || '', detail || '', 0, 'running');
  const process = message.process ?? emptyRunProcess('processing');
  const settledSteps = process.steps.map((item) => (
    item.status === 'running' ? { ...item, status: 'completed' as const } : item
  ));
  message.process = {
    ...process,
    status: 'processing',
    startedAtMs: process.startedAtMs ?? Date.now(),
    finishedAtMs: undefined,
    currentStep: step ?? process.currentStep,
    steps: step ? mergeProcessSteps(settledSteps, [step]) : settledSteps,
  };
}

function parseTimestampMs(value?: string) {
  if (!value) {
    return undefined;
  }
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : undefined;
}

function parseRunResultJsonValue(raw: string | undefined) {
  if (!raw) {
    return {};
  }
  try {
    const stored = parseJsonRecord(JSON.parse(raw));
    // 完整响应信封时，运行过程数据在内层 resultJson 上。
    return isRunResponseEnvelope(stored) ? parseJsonRecord(stored.resultJson) : stored;
  } catch {
    return {};
  }
}

// 只有同时具备内层 resultJson 对象和响应级字段时才判定为信封，
// 避免把恰好带 resultJson 键的扁平 worker 结果误判。
function isRunResponseEnvelope(stored: Record<string, unknown>) {
  const nested = stored.resultJson;
  if (nested === null || typeof nested !== 'object' || Array.isArray(nested)) {
    return false;
  }
  return Array.isArray(stored.candidates)
    || Array.isArray(stored.sources)
    || Array.isArray(stored.actions)
    || typeof stored.answer === 'string';
}

function parseJsonRecord(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? parsed as Record<string, unknown>
        : {};
    } catch {
      return {};
    }
  }
  return {};
}

function positiveNumber(value: unknown) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : undefined;
}

function trimmedText(value: unknown) {
  const text = typeof value === 'string' ? value.trim() : '';
  return text.length ? text : undefined;
}
