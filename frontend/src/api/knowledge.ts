import { applyTokenResponse, clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import type { AxiosResponse } from 'axios';
import { createAnalysisStreamRunner, type AnalysisStreamCallbacks } from '@/lib/analysis-stream';
import { API_BASE_URL, httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  AgentEvalCaseResult,
  AgentEvalRun,
  AgentEvalRunRequest,
  AgentExpertProfile,
  AgentCacheTokenStats,
  GoldenCandidateDraft,
  AgentRuntimeConfig,
  AgentTracePage,
  AgentTraceQuery,
  AgentTraceSummary,
  AiMemory,
  KnowledgeChatRequest,
  KnowledgeChatResponse,
  KnowledgeChatRun,
  KnowledgeChatRunEvent,
  KnowledgeChatRunEventStreamCallbacks,
  KnowledgeChatRunEventStreamTask,
  KnowledgeChatRunSnapshot,
  KnowledgeConversation,
  KnowledgeConversationMessage,
  KnowledgeProject,
  KnowledgeProjectRequest,
  ProjectChapter,
  ProjectChapterImportRequest,
  ProjectExtractionCandidate,
  ProjectExtractionReviewRequest,
  ProjectIngestJob,
  ProjectIngestSubmitRequest,
  ProjectDocumentBatch,
  ProjectDocumentKind,
  ProjectDocumentQuestion,
  ProjectMemoryOverview,
  ProjectWork,
  ProjectWorkRequest,
  StoryGraphResult,
  MemoryAdminQuery,
  SkillCandidate,
  SkillCandidateCreatePayload,
  SkillDashboardQuery,
  SkillGovernanceDashboard,
  SkillShortcut,
} from '@/types/knowledge';
import type { TokenResponse } from '@/types/auth';

const KNOWLEDGE_CHAT_TIMEOUT_MS = 600000;
const PROJECT_DOCUMENT_BATCH_UPLOAD_TIMEOUT_MS = 120000;
type KnowledgeApiResponse<T> = Promise<AxiosResponse<ApiResponse<T>>>;

let projectsRequest: KnowledgeApiResponse<KnowledgeProject[]> | undefined;
const conversationRequests = new Map<string, KnowledgeApiResponse<KnowledgeConversation[]>>();

function conversationRequestKey(projectId?: number | null) {
  return projectId == null ? 'unassigned' : String(projectId);
}

interface RunSseFrame {
  id?: string;
  event: string;
  data: unknown;
}

function parseRunSseFrames(buffer: string, flush = false) {
  let normalized = buffer.replace(/\r\n/g, '\n');
  if (flush && normalized.trim() && !normalized.endsWith('\n\n')) {
    normalized += '\n\n';
  }
  const frames: RunSseFrame[] = [];
  let cursor = 0;
  while (cursor < normalized.length) {
    const boundary = normalized.indexOf('\n\n', cursor);
    if (boundary < 0) {
      break;
    }
    const block = normalized.slice(cursor, boundary);
    cursor = boundary + 2;
    let id: string | undefined;
    let event = 'message';
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) {
        continue;
      }
      if (line.startsWith('id:')) {
        id = line.slice(3).trim();
      } else if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
    if (!dataLines.length) {
      continue;
    }
    try {
      frames.push({ id, event, data: JSON.parse(dataLines.join('\n')) });
    } catch {
      // Ignore malformed historical frames and resume from the last valid sequence.
    }
  }
  return { frames, rest: normalized.slice(cursor) };
}

function createRunEventStreamTask(
  runId: string,
  afterSequence: number,
  callbacks: KnowledgeChatRunEventStreamCallbacks,
): KnowledgeChatRunEventStreamTask {
  const controller = new AbortController();
  const result = (async () => {
    let token = getAccessToken();
    if (!token) {
      clearCurrentSession();
      throw new Error('Missing access token');
    }
    let retried = false;
    while (true) {
      const query = new URLSearchParams({ afterSequence: String(Math.max(0, afterSequence)) });
      const response = await fetch(
        `${API_BASE_URL}/api/knowledge/chat-runs/${encodeURIComponent(runId)}/events/stream?${query}`,
        {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'text/event-stream',
            'Last-Event-ID': String(Math.max(0, afterSequence)),
          },
          signal: controller.signal,
        },
      );
      if (response.status === 401 && !retried) {
        if (controller.signal.aborted) {
          throw new DOMException('Aborted', 'AbortError');
        }
        const refreshed = await rawHttpClient.post<ApiResponse<TokenResponse>>(
          '/api/auth/refresh',
          undefined,
          { withCredentials: true, signal: controller.signal },
        );
        if (controller.signal.aborted) {
          throw new DOMException('Aborted', 'AbortError');
        }
        applyTokenResponse(refreshed.data.data);
        token = refreshed.data.data.accessToken;
        retried = true;
        continue;
      }
      if (response.status === 401) {
        if (controller.signal.aborted) {
          throw new DOMException('Aborted', 'AbortError');
        }
        clearCurrentSession();
        throw new Error('Unauthorized stream request');
      }
      const contentType = response.headers.get('Content-Type') ?? '';
      if (!response.ok || !contentType.includes('text/event-stream') || !response.body) {
        throw new Error('Run event stream unavailable');
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            buffer += decoder.decode();
            const parsed = parseRunSseFrames(buffer, true);
            consumeRunFrames(parsed.frames, callbacks);
            return;
          }
          buffer += decoder.decode(value, { stream: true });
          const parsed = parseRunSseFrames(buffer);
          buffer = parsed.rest;
          consumeRunFrames(parsed.frames, callbacks);
        }
      } finally {
        reader.releaseLock();
      }
    }
  })();
  return {
    abort() {
      controller.abort();
    },
    result,
  };
}

function consumeRunFrames(frames: RunSseFrame[], callbacks: KnowledgeChatRunEventStreamCallbacks) {
  for (const frame of frames) {
    if (frame.event === 'snapshot') {
      callbacks.onSnapshot(frame.data as KnowledgeChatRunSnapshot);
      continue;
    }
    const event = frame.data as KnowledgeChatRunEvent;
    if ((!event.sequenceNo || event.sequenceNo < 1) && frame.id) {
      event.sequenceNo = Number(frame.id) || 0;
    }
    callbacks.onEvent(event);
  }
}

async function runBlocking(payload: KnowledgeChatRequest) {
  const response = await httpClient.post<ApiResponse<KnowledgeChatResponse>>('/api/knowledge/chat', payload, {
    timeout: KNOWLEDGE_CHAT_TIMEOUT_MS,
  });
  return {
    ...response.data.data,
    traceId: response.data.traceId,
  };
}

function createStreamTask(payload: KnowledgeChatRequest, callbacks: AnalysisStreamCallbacks<KnowledgeChatResponse>) {
  const runner = createAnalysisStreamRunner<KnowledgeChatRequest, KnowledgeChatResponse>({
    getAccessToken,
    refreshToken: async () => {
      const response = await rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/refresh', undefined, {
        withCredentials: true,
      });

      return response.data.data;
    },
    applyTokenResponse,
    clearSession: clearCurrentSession,
    fallbackRequest: runBlocking,
    allowBlockingFallback: false,
  });

  return runner.run(`${API_BASE_URL}/api/knowledge/chat/stream`, payload, callbacks);
}

export const knowledgeApi = {
  chat(payload: KnowledgeChatRequest) {
    return httpClient.post<ApiResponse<KnowledgeChatResponse>>('/api/knowledge/chat', payload, {
      timeout: KNOWLEDGE_CHAT_TIMEOUT_MS,
    });
  },
  streamChat(payload: KnowledgeChatRequest, callbacks: AnalysisStreamCallbacks<KnowledgeChatResponse>) {
    return createStreamTask(payload, callbacks);
  },
  startChatRun(payload: KnowledgeChatRequest) {
    return httpClient.post<ApiResponse<KnowledgeChatRun>>('/api/knowledge/chat-runs', payload, {
      timeout: KNOWLEDGE_CHAT_TIMEOUT_MS,
    });
  },
  listSkillShortcuts() {
    return httpClient.get<ApiResponse<SkillShortcut[]>>('/api/knowledge/skills/shortcuts');
  },
  getChatRun(runId: string) {
    return httpClient.get<ApiResponse<KnowledgeChatRun>>(
      `/api/knowledge/chat-runs/${encodeURIComponent(runId)}`,
    );
  },
  listChatRunEvents(runId: string, afterSequence = 0, limit = 200) {
    return httpClient.get<ApiResponse<KnowledgeChatRunEvent[]>>(
      `/api/knowledge/chat-runs/${encodeURIComponent(runId)}/events`,
      { params: { afterSequence, limit } },
    );
  },
  streamChatRunEvents(
    runId: string,
    afterSequence: number,
    callbacks: KnowledgeChatRunEventStreamCallbacks,
  ) {
    return createRunEventStreamTask(runId, afterSequence, callbacks);
  },
  listChatRuns(params?: { projectId?: number | null; limit?: number }) {
    const requestParams: Record<string, number> = {};
    if (params?.projectId) {
      requestParams.projectId = params.projectId;
    }
    if (params?.limit) {
      requestParams.limit = params.limit;
    }
    return httpClient.get<ApiResponse<KnowledgeChatRun[]>>('/api/knowledge/chat-runs', {
      params: requestParams,
    });
  },
  listConversationRuns(conversationId: string, limit = 20) {
    return httpClient.get<ApiResponse<KnowledgeChatRun[]>>(
      `/api/knowledge/conversations/${encodeURIComponent(conversationId)}/runs`,
      { params: { limit } },
    );
  },
  listConversations(projectId?: number | null) {
    const key = conversationRequestKey(projectId);
    const existing = conversationRequests.get(key);
    if (existing) {
      return existing;
    }
    const request: KnowledgeApiResponse<KnowledgeConversation[]> = httpClient.get<ApiResponse<KnowledgeConversation[]>>(
      '/api/knowledge/conversations',
      { params: projectId ? { projectId } : undefined },
    );
    conversationRequests.set(key, request);
    const clearRequest = () => {
      if (conversationRequests.get(key) === request) {
        conversationRequests.delete(key);
      }
    };
    void request.then(clearRequest, clearRequest);
    return request;
  },
  createConversation(payload?: { projectId?: number | null; title?: string }) {
    return httpClient.post<ApiResponse<KnowledgeConversation>>('/api/knowledge/conversations', payload ?? {});
  },
  getConversation(conversationId: string, projectId?: number | null) {
    return httpClient.get<ApiResponse<KnowledgeConversation>>(
      `/api/knowledge/conversations/${encodeURIComponent(conversationId)}`,
      { params: projectId ? { projectId } : undefined },
    );
  },
  listConversationMessages(conversationId: string, projectId?: number | null) {
    return httpClient.get<ApiResponse<KnowledgeConversationMessage[]>>(
      `/api/knowledge/conversations/${encodeURIComponent(conversationId)}/messages`,
      { params: projectId ? { projectId } : undefined },
    );
  },
  cancelChatRun(runId: string) {
    return httpClient.post<ApiResponse<KnowledgeChatRun>>(
      `/api/knowledge/chat-runs/${encodeURIComponent(runId)}/cancel`,
    );
  },
  listProjects() {
    if (projectsRequest) {
      return projectsRequest;
    }
    const request: KnowledgeApiResponse<KnowledgeProject[]> = httpClient.get<ApiResponse<KnowledgeProject[]>>(
      '/api/knowledge/projects',
    );
    projectsRequest = request;
    const clearRequest = () => {
      if (projectsRequest === request) {
        projectsRequest = undefined;
      }
    };
    void request.then(clearRequest, clearRequest);
    return request;
  },
  createProject(payload: KnowledgeProjectRequest) {
    return httpClient.post<ApiResponse<KnowledgeProject>>('/api/knowledge/projects', payload);
  },
  renameProject(projectId: number, payload: KnowledgeProjectRequest) {
    return httpClient.put<ApiResponse<KnowledgeProject>>(`/api/knowledge/projects/${projectId}`, payload);
  },
  archiveProject(projectId: number) {
    return httpClient.post<ApiResponse<void>>(`/api/knowledge/projects/${projectId}/archive`);
  },
  listProjectWorks(projectId: number) {
    return httpClient.get<ApiResponse<ProjectWork[]>>(`/api/knowledge/projects/${projectId}/works`);
  },
  listWorkLibrary() {
    return httpClient.get<ApiResponse<ProjectWork[]>>('/api/knowledge/projects/work-library');
  },
  createProjectWork(projectId: number, payload: ProjectWorkRequest) {
    return httpClient.post<ApiResponse<ProjectWork>>(`/api/knowledge/projects/${projectId}/works`, payload);
  },
  listProjectChapters(projectId: number, workId: number) {
    return httpClient.get<ApiResponse<ProjectChapter[]>>(
      `/api/knowledge/projects/${projectId}/works/${workId}/chapters`,
    );
  },
  importProjectChapter(projectId: number, workId: number, payload: ProjectChapterImportRequest) {
    return httpClient.post<ApiResponse<ProjectChapter>>(
      `/api/knowledge/projects/${projectId}/works/${workId}/chapters/import`,
      payload,
    );
  },
  submitProjectIngestJob(projectId: number, workId: number, payload: ProjectIngestSubmitRequest) {
    return httpClient.post<ApiResponse<ProjectIngestJob>>(
      `/api/knowledge/projects/${projectId}/works/${workId}/ingest-jobs`,
      payload,
    );
  },
  listProjectIngestJobs(projectId: number, params?: { workId?: number; limit?: number }) {
    return httpClient.get<ApiResponse<ProjectIngestJob[]>>(
      `/api/knowledge/projects/${projectId}/ingest-jobs`,
      { params },
    );
  },
  getProjectIngestJob(projectId: number, ingestJobId: number) {
    return httpClient.get<ApiResponse<ProjectIngestJob>>(
      `/api/knowledge/projects/${projectId}/ingest-jobs/${ingestJobId}`,
    );
  },
  retryProjectIngestJob(projectId: number, ingestJobId: number) {
    return httpClient.post<ApiResponse<ProjectIngestJob>>(
      `/api/knowledge/projects/${projectId}/ingest-jobs/${ingestJobId}/retry`,
    );
  },
  createProjectDocumentBatch(
    projectId: number,
    workId: number,
    files: File[],
    relativePaths: string[],
    declaredKind: ProjectDocumentKind,
    idempotencyKey: string,
  ) {
    const body = new FormData();
    files.forEach((file, index) => {
      body.append('files', file, file.name);
      body.append('relativePaths', relativePaths[index] || file.name);
    });
    body.append('declaredKind', declaredKind);
    body.append('idempotencyKey', idempotencyKey);
    return httpClient.post<ApiResponse<ProjectDocumentBatch>>(
      `/api/knowledge/projects/${projectId}/works/${workId}/document-batches`,
      body,
      { timeout: PROJECT_DOCUMENT_BATCH_UPLOAD_TIMEOUT_MS },
    );
  },
  listProjectDocumentBatches(projectId: number, workId: number, limit = 20) {
    return httpClient.get<ApiResponse<ProjectDocumentBatch[]>>(
      `/api/knowledge/projects/${projectId}/works/${workId}/document-batches`,
      { params: { limit } },
    );
  },
  getProjectDocumentBatch(projectId: number, batchId: number) {
    return httpClient.get<ApiResponse<ProjectDocumentBatch>>(
      `/api/knowledge/projects/${projectId}/document-batches/${batchId}`,
    );
  },
  listProjectDocumentQuestions(projectId: number, batchId: number) {
    return httpClient.get<ApiResponse<ProjectDocumentQuestion[]>>(
      `/api/knowledge/projects/${projectId}/document-batches/${batchId}/questions`,
    );
  },
  answerProjectDocumentQuestion(projectId: number, batchId: number, questionId: number, answer: ProjectDocumentKind) {
    return httpClient.patch<ApiResponse<ProjectDocumentQuestion>>(
      `/api/knowledge/projects/${projectId}/document-batches/${batchId}/questions/${questionId}`,
      { answer },
    );
  },
  retryProjectDocumentBatch(projectId: number, batchId: number) {
    return httpClient.post<ApiResponse<ProjectDocumentBatch>>(
      `/api/knowledge/projects/${projectId}/document-batches/${batchId}/retry`,
    );
  },
  cancelProjectDocumentBatch(projectId: number, batchId: number) {
    return httpClient.post<ApiResponse<ProjectDocumentBatch>>(
      `/api/knowledge/projects/${projectId}/document-batches/${batchId}/cancel`,
    );
  },
  discardProjectDocumentBatch(projectId: number, batchId: number) {
    return httpClient.delete<ApiResponse<void>>(
      `/api/knowledge/projects/${projectId}/document-batches/${batchId}`,
    );
  },
  listExtractionCandidates(projectId: number, params?: { workId?: number; status?: string; limit?: number }) {
    return httpClient.get<ApiResponse<ProjectExtractionCandidate[]>>(
      `/api/knowledge/projects/${projectId}/extraction-candidates`,
      { params },
    );
  },
  reviewExtractionCandidate(projectId: number, candidateId: number, payload: ProjectExtractionReviewRequest) {
    return httpClient.post<ApiResponse<ProjectExtractionCandidate>>(
      `/api/knowledge/projects/${projectId}/extraction-candidates/${candidateId}/review`,
      payload,
    );
  },
  getStoryGraph(projectId: number, workId: number, params?: { nodeLimit?: number }) {
    return httpClient.get<ApiResponse<StoryGraphResult>>(
      `/api/knowledge/projects/${projectId}/works/${workId}/story-graph`,
      { params },
    );
  },
  getProjectMemoryOverview(projectId: number, workId: number) {
    return httpClient.get<ApiResponse<ProjectMemoryOverview>>(
      `/api/knowledge/projects/${projectId}/works/${workId}/memory-overview`,
    );
  },
  listAgentTraces(params?: AgentTraceQuery) {
    return httpClient.get<ApiResponse<AgentTracePage>>('/api/knowledge/admin/agent-traces', { params });
  },
  getAgentTrace(traceId: number) {
    return httpClient.get<ApiResponse<AgentTraceSummary>>(`/api/knowledge/admin/agent-traces/${traceId}`);
  },
  createGoldenCandidate(traceId: number) {
    return httpClient.post<ApiResponse<GoldenCandidateDraft>>(
      `/api/knowledge/admin/agent-traces/${traceId}/golden-candidate`,
    );
  },
  getAgentRuntimeConfig() {
    return httpClient.get<ApiResponse<AgentRuntimeConfig>>('/api/knowledge/admin/agent/runtime-config');
  },
  updateAgentRuntimeConfig(key: keyof AgentRuntimeConfig | string, payload: { value: string }) {
    return httpClient.put<ApiResponse<AgentRuntimeConfig>>(`/api/knowledge/admin/agent/runtime-config/${key}`, payload);
  },
  listAgentExperts() {
    return httpClient.get<ApiResponse<AgentExpertProfile[]>>('/api/knowledge/admin/agent/experts');
  },
  updateAgentExpert(expertName: string, payload: Partial<AgentExpertProfile>) {
    return httpClient.put<ApiResponse<AgentExpertProfile>>(`/api/knowledge/admin/agent/experts/${expertName}`, payload);
  },
  getAgentCacheTokenStats() {
    return httpClient.get<ApiResponse<AgentCacheTokenStats>>('/api/knowledge/admin/agent/cache-token-stats');
  },
  listAgentEvalRuns(limit = 20) {
    return httpClient.get<ApiResponse<AgentEvalRun[]>>('/api/knowledge/admin/agent/eval-runs', { params: { limit } });
  },
  runAgentEval(payload: AgentEvalRunRequest) {
    return httpClient.post<ApiResponse<AgentEvalRun>>('/api/knowledge/admin/agent/eval-runs', payload);
  },
  cancelAgentEvalRun(runId: number) {
    return httpClient.post<ApiResponse<AgentEvalRun>>(`/api/knowledge/admin/agent/eval-runs/${runId}/cancel`);
  },
  retryAgentEvalRun(runId: number) {
    return httpClient.post<ApiResponse<AgentEvalRun>>(`/api/knowledge/admin/agent/eval-runs/${runId}/retry`);
  },
  listAgentEvalCaseResults(runId: number, limit = 50) {
    return httpClient.get<ApiResponse<AgentEvalCaseResult[]>>(
      `/api/knowledge/admin/agent/eval-runs/${runId}/cases`,
      { params: { limit } },
    );
  },
  listSkillCandidates() {
    return httpClient.get<ApiResponse<SkillCandidate[]>>('/api/knowledge/admin/skill-candidates');
  },
  getSkillDashboard(params?: SkillDashboardQuery) {
    return httpClient.get<ApiResponse<SkillGovernanceDashboard>>('/api/knowledge/admin/skills', { params });
  },
  createSkillCandidate(payload: SkillCandidateCreatePayload) {
    return httpClient.post<ApiResponse<SkillCandidate>>('/api/knowledge/admin/skill-candidates', payload);
  },
  reviewSkillCandidate(candidateId: number, payload: { decision: 'APPROVED' | 'REJECTED'; note?: string }) {
    return httpClient.post<ApiResponse<SkillCandidate>>(`/api/knowledge/admin/skill-candidates/${candidateId}/review`, payload);
  },
  publishSkillCandidate(candidateId: number) {
    return httpClient.post<ApiResponse<SkillCandidate>>(`/api/knowledge/admin/skill-candidates/${candidateId}/publish`);
  },
  disableSkillCandidate(candidateId: number) {
    return httpClient.post<ApiResponse<SkillCandidate>>(`/api/knowledge/admin/skill-candidates/${candidateId}/disable`);
  },
  rollbackSkillCandidate(candidateId: number) {
    return httpClient.post<ApiResponse<SkillCandidate>>(`/api/knowledge/admin/skill-candidates/${candidateId}/rollback`);
  },
  listMemories(params?: MemoryAdminQuery) {
    return httpClient.get<ApiResponse<AiMemory[]>>('/api/knowledge/admin/memories', { params });
  },
  listMemoryCandidates(params?: MemoryAdminQuery) {
    return httpClient.get<ApiResponse<AiMemory[]>>('/api/knowledge/admin/memory-candidates', { params });
  },
  approveMemoryCandidate(candidateId: number) {
    return httpClient.post<ApiResponse<AiMemory>>(`/api/knowledge/admin/memory-candidates/${candidateId}/approve`);
  },
  rejectMemoryCandidate(candidateId: number) {
    return httpClient.post<ApiResponse<AiMemory>>(`/api/knowledge/admin/memory-candidates/${candidateId}/reject`);
  },
  deleteMemory(memoryId: number) {
    return httpClient.post<ApiResponse<void>>(`/api/knowledge/admin/memories/${memoryId}/delete`);
  },
};
