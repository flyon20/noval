import { createAnalysisStreamRunner, parseSseFrames } from '@/lib/analysis-stream';
import type { AnalysisResult, AnalysisRequest } from '@/types/analysis';

function createSseResponse(body: string, status = 200, contentType = 'text/event-stream;charset=UTF-8') {
  const encoder = new TextEncoder();

  return new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(body));
        controller.close();
      },
    }),
    {
      status,
      headers: {
        'Content-Type': contentType,
      },
    },
  );
}

describe('analysis stream runtime', () => {
  const payload: AnalysisRequest = {
    platform: 'fanqie',
    bookId: 1001,
    chapterCount: 3,
  };

  const result: AnalysisResult = {
    id: 1,
    bookId: 1001,
    analysisType: 'deconstruct',
    modelName: 'dify',
    resultContent: '完整输出',
    resultJson: {},
    tokenUsed: 123,
  };

  test('parses complete frames and returns remainder', () => {
    const parsed = parseSseFrames(
      [
        'event: start',
        'data: {"event":"start","traceId":"trace-1","analysisType":"deconstruct"}',
        '',
        'event: delta',
        'data: {"event":"delta","delta":"第一段","chunkIndex":0}',
        '',
        'event: done',
      ].join('\n'),
    );

    expect(parsed.events).toHaveLength(2);
    expect(parsed.events[0]?.event).toBe('start');
    expect(parsed.events[1]?.event).toBe('delta');
    expect(parsed.rest).toContain('event: done');
  });

  test('streams start delta done in order', async () => {
    const runtime = createAnalysisStreamRunner({
      getAccessToken: () => 'token-1',
      refreshToken: vi.fn(),
      applyTokenResponse: vi.fn(),
      clearSession: vi.fn(),
      fetchImpl: vi.fn().mockResolvedValue(
        createSseResponse(
          [
            'event: start',
            'data: {"event":"start","traceId":"trace-1","analysisType":"deconstruct"}',
            '',
            'event: delta',
            'data: {"event":"delta","delta":"第一段","chunkIndex":0}',
            '',
            'event: done',
            `data: ${JSON.stringify({ event: 'done', data: result })}`,
            '',
          ].join('\n'),
        ),
      ),
      fallbackRequest: vi.fn(),
    });

    const events: string[] = [];
    const task = runtime.run('/api/analysis/deconstruct/stream', payload, {
      onStart: () => events.push('start'),
      onDelta: () => events.push('delta'),
      onDone: () => events.push('done'),
      onError: () => events.push('error'),
    });

    const doneResult = await task.result;

    expect(events).toEqual(['start', 'delta', 'done']);
    expect(doneResult).toEqual(result);
  });

  test('falls back to blocking request before first delta on unsupported stream response', async () => {
    const fallbackRequest = vi.fn().mockResolvedValue(result);
    const runtime = createAnalysisStreamRunner({
      getAccessToken: () => 'token-1',
      refreshToken: vi.fn(),
      applyTokenResponse: vi.fn(),
      clearSession: vi.fn(),
      fetchImpl: vi.fn().mockResolvedValue(createSseResponse('not-sse', 404, 'application/json')),
      fallbackRequest,
    });

    const task = runtime.run('/api/analysis/deconstruct/stream', payload, {
      onStart: vi.fn(),
      onDelta: vi.fn(),
      onDone: vi.fn(),
      onError: vi.fn(),
    });

    await expect(task.result).resolves.toEqual(result);
    expect(fallbackRequest).toHaveBeenCalledWith(payload);
  });

  test('refreshes and retries stream request once on 401', async () => {
    const refreshToken = vi.fn().mockResolvedValue({
      accessToken: 'token-2',
      tokenType: 'Bearer',
      expiresIn: 7200,
    });
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        createSseResponse(
          [
            'event: start',
            'data: {"event":"start","traceId":"trace-2","analysisType":"deconstruct"}',
            '',
            'event: done',
            `data: ${JSON.stringify({ event: 'done', data: result })}`,
            '',
          ].join('\n'),
        ),
      );
    const runtime = createAnalysisStreamRunner({
      getAccessToken: vi.fn().mockReturnValueOnce('token-1').mockReturnValue('token-2'),
      refreshToken,
      applyTokenResponse: vi.fn(),
      clearSession: vi.fn(),
      fetchImpl,
      fallbackRequest: vi.fn(),
    });

    const task = runtime.run('/api/analysis/deconstruct/stream', payload, {
      onStart: vi.fn(),
      onDelta: vi.fn(),
      onDone: vi.fn(),
      onError: vi.fn(),
    });

    await expect(task.result).resolves.toEqual(result);
    expect(refreshToken).toHaveBeenCalledWith('token-1');
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  test('aborts stream and rejects the task', async () => {
    let capturedSignal: AbortSignal | undefined;
    const fetchImpl = vi.fn().mockImplementation(async (_url: string, init?: RequestInit) => {
      capturedSignal = init?.signal ?? undefined;

      return new Response(
        new ReadableStream({
          start() {},
        }),
        {
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream;charset=UTF-8',
          },
        },
      );
    });
    const runtime = createAnalysisStreamRunner({
      getAccessToken: () => 'token-1',
      refreshToken: vi.fn(),
      applyTokenResponse: vi.fn(),
      clearSession: vi.fn(),
      fetchImpl,
      fallbackRequest: vi.fn(),
    });

    const task = runtime.run('/api/analysis/deconstruct/stream', payload, {
      onStart: vi.fn(),
      onDelta: vi.fn(),
      onDone: vi.fn(),
      onError: vi.fn(),
    });

    task.abort();

    await expect(task.result).rejects.toThrow();
    expect(capturedSignal?.aborted).toBe(true);
  });
});
