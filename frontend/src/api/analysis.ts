import { applyTokenResponse, clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import { createAnalysisStreamRunner, type AnalysisStreamCallbacks } from '@/lib/analysis-stream';
import { API_BASE_URL, httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { AnalysisRequest, AnalysisResult } from '@/types/analysis';
import type { TrendAnalysisResult, TrendRequest } from '@/types/trend';
import type { TokenResponse } from '@/types/auth';

async function runBlocking(path: string, payload: AnalysisRequest) {
  const response = await httpClient.post<ApiResponse<AnalysisResult>>(path, payload);
  return {
    ...response.data.data,
    traceId: response.data.traceId,
  };
}

function createStreamTask(
  streamPath: string,
  blockingPath: string,
  payload: AnalysisRequest,
  callbacks: AnalysisStreamCallbacks,
) {
  const runner = createAnalysisStreamRunner({
    getAccessToken,
    refreshToken: async (token) => {
      const response = await rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/refresh', {
        token,
      });

      return response.data.data;
    },
    applyTokenResponse,
    clearSession: clearCurrentSession,
    fallbackRequest: async (blockingPayload) => runBlocking(blockingPath, blockingPayload),
  });

  return runner.run(`${API_BASE_URL}${streamPath}`, payload, callbacks);
}

async function runTrendBlocking(payload: TrendRequest) {
  const response = await httpClient.get<ApiResponse<TrendAnalysisResult>>('/api/analysis/trend', {
    params: payload,
  });

  return {
    ...response.data.data,
    traceId: response.data.traceId,
  };
}

function createTrendStreamTask(payload: TrendRequest, callbacks: AnalysisStreamCallbacks<TrendAnalysisResult>) {
  const runner = createAnalysisStreamRunner<TrendRequest, TrendAnalysisResult>({
    getAccessToken,
    refreshToken: async (token) => {
      const response = await rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/refresh', {
        token,
      });

      return response.data.data;
    },
    applyTokenResponse,
    clearSession: clearCurrentSession,
    fallbackRequest: async (blockingPayload) => runTrendBlocking(blockingPayload),
  });

  return runner.run(`${API_BASE_URL}/api/analysis/trend/stream`, payload, callbacks);
}

export const analysisApi = {
  runDeconstruct(payload: AnalysisRequest) {
    return runBlocking('/api/analysis/deconstruct', payload);
  },
  runStructure(payload: AnalysisRequest) {
    return runBlocking('/api/analysis/structure', payload);
  },
  runPlot(payload: AnalysisRequest) {
    return runBlocking('/api/analysis/plot', payload);
  },
  streamDeconstruct(payload: AnalysisRequest, callbacks: AnalysisStreamCallbacks) {
    return createStreamTask('/api/analysis/deconstruct/stream', '/api/analysis/deconstruct', payload, callbacks);
  },
  streamStructure(payload: AnalysisRequest, callbacks: AnalysisStreamCallbacks) {
    return createStreamTask('/api/analysis/structure/stream', '/api/analysis/structure', payload, callbacks);
  },
  streamPlot(payload: AnalysisRequest, callbacks: AnalysisStreamCallbacks) {
    return createStreamTask('/api/analysis/plot/stream', '/api/analysis/plot', payload, callbacks);
  },
  getTrend(payload: TrendRequest) {
    return runTrendBlocking(payload);
  },
  streamTrend(payload: TrendRequest, callbacks: AnalysisStreamCallbacks<TrendAnalysisResult>) {
    return createTrendStreamTask(payload, callbacks);
  },
};
