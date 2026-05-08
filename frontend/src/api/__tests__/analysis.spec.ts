import { beforeEach, describe, expect, test, vi } from 'vitest';

const post = vi.fn();
const get = vi.fn();
const rawPost = vi.fn();

vi.mock('@/lib/http', () => ({
  API_BASE_URL: '',
  httpClient: {
    post,
    get,
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
  createAnalysisStreamRunner: vi.fn(),
}));

describe('analysis api', () => {
  beforeEach(() => {
    post.mockReset();
    get.mockReset();
    rawPost.mockReset();
  });

  test('uses longer blocking timeout for ten chapter single-book analysis', async () => {
    post.mockResolvedValue({
      data: {
        data: {
          id: 1,
          bookId: 1001,
          analysisType: 'deconstruct',
          modelName: 'deepseek-chat',
          resultContent: 'result',
          resultJson: {},
          tokenUsed: 123,
        },
        traceId: 'trace-1',
      },
    });

    const { analysisApi } = await import('../analysis');

    await analysisApi.runDeconstruct({
      platform: 'fanqie',
      bookId: 1001,
      chapterCount: 10,
    });

    expect(post).toHaveBeenCalledWith(
      '/api/analysis/deconstruct',
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 10,
      },
      expect.objectContaining({
        timeout: 180000,
      }),
    );
  });
});
