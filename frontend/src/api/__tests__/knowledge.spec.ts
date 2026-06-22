import { beforeEach, describe, expect, test, vi } from 'vitest';

const post = vi.fn();
const rawPost = vi.fn();
const run = vi.fn();
let capturedStreamDeps: {
  fallbackRequest(payload: unknown): Promise<unknown>;
  allowBlockingFallback?: boolean;
} | null = null;

vi.mock('@/lib/http', () => ({
  API_BASE_URL: '',
  httpClient: {
    post,
  },
  rawHttpClient: {
    post: rawPost,
  },
}));

vi.mock('@/lib/auth-session', () => ({
  applyTokenResponse: vi.fn(),
  clearCurrentSession: vi.fn(),
  getAccessToken: vi.fn(() => 'token-1'),
}));

vi.mock('@/lib/analysis-stream', () => ({
  createAnalysisStreamRunner: vi.fn((deps) => {
    capturedStreamDeps = deps;
    return {
      run,
    };
  }),
}));

describe('knowledge api', () => {
  beforeEach(() => {
    post.mockReset();
    rawPost.mockReset();
    run.mockReset();
    capturedStreamDeps = null;
  });

  test('uses long timeout for blocking knowledge chat', async () => {
    post.mockResolvedValue({
      data: {
        data: {
          status: 'answered',
          answer: 'answer',
          candidates: [],
          sources: [],
          actions: [],
          resultJson: {},
        },
        traceId: 'trace-knowledge-1',
      },
    });

    const { knowledgeApi } = await import('../knowledge');

    await knowledgeApi.chat({
      question: '生成完整大纲',
      limits: {
        timeoutMillis: 600000,
      },
    });

    expect(post).toHaveBeenCalledWith(
      '/api/knowledge/chat',
      {
        question: '生成完整大纲',
        limits: {
          timeoutMillis: 600000,
        },
      },
      expect.objectContaining({
        timeout: 600000,
      }),
    );
  });

  test('disables blocking fallback for stream knowledge chat', async () => {
    post.mockResolvedValue({
      data: {
        data: {
          status: 'answered',
          answer: 'fallback answer',
          candidates: [],
          sources: [],
          actions: [],
          resultJson: {},
        },
        traceId: 'trace-knowledge-fallback',
      },
    });

    const { knowledgeApi } = await import('../knowledge');

    knowledgeApi.streamChat(
      {
        question: '生成完整大纲',
        limits: {
          timeoutMillis: 600000,
        },
      },
      {
        onStart: vi.fn(),
        onDelta: vi.fn(),
        onDone: vi.fn(),
        onError: vi.fn(),
      },
    );

    expect(capturedStreamDeps?.allowBlockingFallback).toBe(false);
    expect(post).not.toHaveBeenCalled();
  });
});
