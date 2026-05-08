import { applyTokenResponse, clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import { createAnalysisStreamRunner, type AnalysisStreamCallbacks } from '@/lib/analysis-stream';
import { API_BASE_URL, httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { KnowledgeChatRequest, KnowledgeChatResponse } from '@/types/knowledge';
import type { TokenResponse } from '@/types/auth';

async function runBlocking(payload: KnowledgeChatRequest) {
  const response = await httpClient.post<ApiResponse<KnowledgeChatResponse>>('/api/knowledge/chat', payload);
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
  });

  return runner.run(`${API_BASE_URL}/api/knowledge/chat/stream`, payload, callbacks);
}

export const knowledgeApi = {
  chat(payload: KnowledgeChatRequest) {
    return httpClient.post<ApiResponse<KnowledgeChatResponse>>('/api/knowledge/chat', payload);
  },
  streamChat(payload: KnowledgeChatRequest, callbacks: AnalysisStreamCallbacks<KnowledgeChatResponse>) {
    return createStreamTask(payload, callbacks);
  },
};
