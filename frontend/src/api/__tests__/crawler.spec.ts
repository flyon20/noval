import { beforeEach, describe, expect, test, vi } from 'vitest';

const post = vi.fn();

vi.mock('@/lib/http', () => ({
  httpClient: {
    post,
    get: vi.fn(),
  },
}));

describe('crawler api', () => {
  beforeEach(() => {
    post.mockReset();
    post.mockResolvedValue({ data: { data: {} } });
  });

  test('forwards the caller-provided rank refresh idempotency key unchanged', async () => {
    const { crawlerApi } = await import('../crawler');

    await crawlerApi.refreshRankBoard({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'AUTO',
      idempotencyKey: 'rank-refresh-intent-1',
    });

    expect(post).toHaveBeenCalledWith(
      '/api/crawler/rank/refresh',
      {
        platform: 'fanqie',
        channelCode: 'male-new',
        boardCode: 'urban-brain',
        refreshMode: 'AUTO',
        idempotencyKey: 'rank-refresh-intent-1',
      },
      { timeout: 120000 },
    );
  });

  test.each([undefined, '', '   '])('rejects a missing or blank rank refresh idempotency key: %j', async (idempotencyKey) => {
    const { crawlerApi } = await import('../crawler');
    const invalidPayload = {
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'AUTO',
      idempotencyKey,
    } as unknown as Parameters<typeof crawlerApi.refreshRankBoard>[0];

    expect(() => crawlerApi.refreshRankBoard(invalidPayload)).toThrow('rank refresh idempotencyKey is required');
    expect(post).not.toHaveBeenCalled();
  });

  test('uses a crawler-appropriate timeout for chapter reads and refreshes', async () => {
    const { crawlerApi } = await import('../crawler');
    const payload = {
      platform: 'fanqie' as const,
      bookId: 1001,
      chapterCount: 3,
    };

    await crawlerApi.getChapters(payload);
    await crawlerApi.getChapterStatus(payload);
    await crawlerApi.refreshChapters(payload);

    expect(post).toHaveBeenNthCalledWith(1, '/api/crawler/chapters', payload, {
      timeout: 180000,
    });
    expect(post).toHaveBeenNthCalledWith(2, '/api/crawler/chapters/status', payload);
    expect(post).toHaveBeenNthCalledWith(3, '/api/crawler/chapters/refresh', payload, {
      timeout: 180000,
    });
  });
});
