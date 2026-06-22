import { applyTokenResponse, clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import { createAnalysisStreamRunner, type AnalysisStreamCallbacks } from '@/lib/analysis-stream';
import { API_BASE_URL, httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  AgentTraceSummary,
  KnowledgeChatRequest,
  KnowledgeChatResponse,
  KnowledgeProject,
  KnowledgeProjectRequest,
  SkillCandidate,
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
  listAgentTraces() {
    return httpClient.get<ApiResponse<AgentTraceSummary[]>>('/api/knowledge/admin/agent-traces');
  },
  getAgentTrace(traceId: number) {
    return httpClient.get<ApiResponse<AgentTraceSummary>>(`/api/knowledge/admin/agent-traces/${traceId}`);
  },
  listSkillCandidates() {
    return httpClient.get<ApiResponse<SkillCandidate[]>>('/api/knowledge/admin/skill-candidates');
  },
  reviewSkillCandidate(candidateId: number, payload: { decision: 'APPROVED' | 'REJECTED'; note?: string }) {
    return httpClient.post<ApiResponse<SkillCandidate>>(`/api/knowledge/admin/skill-candidates/${candidateId}/review`, payload);
  },
};
