import { applyTokenResponse, clearCurrentSession, getAccessToken } from '@/lib/auth-session';
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
  KnowledgeProject,
  KnowledgeProjectRequest,
  ProjectChapter,
  ProjectChapterImportRequest,
  ProjectWork,
  ProjectWorkRequest,
  MemoryAdminQuery,
  SkillCandidate,
  SkillCandidateCreatePayload,
  SkillDashboardQuery,
  SkillGovernanceDashboard,
} from '@/types/knowledge';
import type { TokenResponse } from '@/types/auth';

const KNOWLEDGE_CHAT_TIMEOUT_MS = 600000;

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
  getChatRun(runId: string) {
    return httpClient.get<ApiResponse<KnowledgeChatRun>>(`/api/knowledge/chat-runs/${runId}`);
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
      `/api/knowledge/conversations/${conversationId}/runs`,
      { params: { limit } },
    );
  },
  cancelChatRun(runId: string) {
    return httpClient.post<ApiResponse<KnowledgeChatRun>>(`/api/knowledge/chat-runs/${runId}/cancel`);
  },
  listProjects() {
    return httpClient.get<ApiResponse<KnowledgeProject[]>>('/api/knowledge/projects');
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
