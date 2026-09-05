import { beforeEach, describe, expect, test, vi } from 'vitest';

const post = vi.fn();
const get = vi.fn();
const patch = vi.fn();
const del = vi.fn();
const rawPost = vi.fn();
const run = vi.fn();
const applyTokenResponse = vi.fn();
const clearCurrentSession = vi.fn();
let capturedStreamDeps: {
  fallbackRequest(payload: unknown): Promise<unknown>;
  allowBlockingFallback?: boolean;
} | null = null;

vi.mock('@/lib/http', () => ({
  API_BASE_URL: '',
  httpClient: {
    post,
    get,
    patch,
    delete: del,
  },
  rawHttpClient: {
    post: rawPost,
  },
}));

vi.mock('@/lib/auth-session', () => ({
  applyTokenResponse,
  clearCurrentSession,
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
    get.mockReset();
    patch.mockReset();
    del.mockReset();
    rawPost.mockReset();
    run.mockReset();
    applyTokenResponse.mockReset();
    clearCurrentSession.mockReset();
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

  test('uses canonical conversation and event replay endpoints', async () => {
    get.mockResolvedValue({ data: { data: [] } });
    const { knowledgeApi } = await import('../knowledge');

    await knowledgeApi.listConversations(9);
    await knowledgeApi.listConversationMessages('conv-9', 9);
    await knowledgeApi.listChatRunEvents('run-9', 17, 200);

    expect(get).toHaveBeenNthCalledWith(1, '/api/knowledge/conversations', {
      params: { projectId: 9 },
    });
    expect(get).toHaveBeenNthCalledWith(2, '/api/knowledge/conversations/conv-9/messages', {
      params: { projectId: 9 },
    });
    expect(get).toHaveBeenNthCalledWith(3, '/api/knowledge/chat-runs/run-9/events', {
      params: { afterSequence: 17, limit: 200 },
    });
  });

  test('loads governed skill shortcuts from the sanitized user endpoint', async () => {
    get.mockResolvedValue({ data: { data: [] } });
    const { knowledgeApi } = await import('../knowledge');

    await knowledgeApi.listSkillShortcuts();

    expect(get).toHaveBeenCalledWith('/api/knowledge/skills/shortcuts');
  });

  test('creates one multipart project document batch with relative paths', async () => {
    post.mockResolvedValue({ data: { data: { batchId: 31 } } });
    const { knowledgeApi } = await import('../knowledge');
    const first = new File(['one'], 'one.md');
    const second = new File(['two'], 'two.txt');

    await knowledgeApi.createProjectDocumentBatch(
      7, 11, [first, second], ['novel/one.md', 'novel/two.txt'], 'NOVEL_TEXT', 'batch-key',
    );

    expect(post).toHaveBeenCalledTimes(1);
    const [url, body, config] = post.mock.calls[0];
    expect(url).toBe('/api/knowledge/projects/7/works/11/document-batches');
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).getAll('files')).toEqual([first, second]);
    expect((body as FormData).getAll('relativePaths')).toEqual(['novel/one.md', 'novel/two.txt']);
    expect((body as FormData).get('declaredKind')).toBe('NOVEL_TEXT');
    expect((body as FormData).get('idempotencyKey')).toBe('batch-key');
    expect(config).toEqual({ timeout: 120000 });
  });

  test('discards one cancelled project document batch through its resource endpoint', async () => {
    del.mockResolvedValue({ data: { data: null } });
    const { knowledgeApi } = await import('../knowledge');

    await knowledgeApi.discardProjectDocumentBatch(7, 31);

    expect(del).toHaveBeenCalledWith('/api/knowledge/projects/7/document-batches/31');
  });

  test('loads the durable memory overview for one project work', async () => {
    get.mockResolvedValue({ data: { data: {} } });
    const { knowledgeApi } = await import('../knowledge');

    await knowledgeApi.getProjectMemoryOverview(7, 11);

    expect(get).toHaveBeenCalledWith('/api/knowledge/projects/7/works/11/memory-overview');
  });

  test('shares an in-flight project request and releases it after settlement', async () => {
    let resolveProjects!: (value: unknown) => void;
    get.mockReturnValueOnce(new Promise((resolve) => {
      resolveProjects = resolve;
    }));
    const { knowledgeApi } = await import('../knowledge');

    const first = knowledgeApi.listProjects();
    const second = knowledgeApi.listProjects();

    expect(second).toBe(first);
    expect(get).toHaveBeenCalledTimes(1);

    resolveProjects({ data: { data: [] } });
    await first;
    get.mockResolvedValueOnce({ data: { data: [] } });
    await knowledgeApi.listProjects();

    expect(get).toHaveBeenCalledTimes(2);
  });

  test('deduplicates conversations per project without merging different project requests', async () => {
    let resolveProjectSeven!: (value: unknown) => void;
    let resolveProjectNine!: (value: unknown) => void;
    get
      .mockReturnValueOnce(new Promise((resolve) => {
        resolveProjectSeven = resolve;
      }))
      .mockReturnValueOnce(new Promise((resolve) => {
        resolveProjectNine = resolve;
      }));
    const { knowledgeApi } = await import('../knowledge');

    const first = knowledgeApi.listConversations(7);
    const duplicate = knowledgeApi.listConversations(7);
    const otherProject = knowledgeApi.listConversations(9);

    expect(duplicate).toBe(first);
    expect(otherProject).not.toBe(first);
    expect(get).toHaveBeenCalledTimes(2);

    resolveProjectSeven({ data: { data: [] } });
    resolveProjectNine({ data: { data: [] } });
    await Promise.all([first, otherProject]);
  });

  test('streams authenticated run events from the requested sequence', async () => {
    const encoder = new TextEncoder();
    const body = [
      'id: 3\nevent: snapshot\ndata: {"runId":"run-sse","answer":"快照","snapshotSequenceNo":3}\n\n',
      'id: 4\nevent: delta\ndata: {"runId":"run-sse","sequenceNo":4,"eventType":"DELTA","payload":"{\\"delta\\":\\"增量\\"}"}\n\n',
    ].join('');
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode(body));
          controller.close();
        },
      }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    ));
    vi.stubGlobal('fetch', fetchMock);
    const onSnapshot = vi.fn();
    const onEvent = vi.fn();
    const { knowledgeApi } = await import('../knowledge');

    try {
      const task = knowledgeApi.streamChatRunEvents('run-sse', 3, { onSnapshot, onEvent });
      await task.result;

      expect(fetchMock).toHaveBeenCalledWith(
        '/api/knowledge/chat-runs/run-sse/events/stream?afterSequence=3',
        expect.objectContaining({
          method: 'GET',
          headers: expect.objectContaining({
            Authorization: 'Bearer token-1',
            'Last-Event-ID': '3',
          }),
        }),
      );
      expect(onSnapshot).toHaveBeenCalledWith({
        runId: 'run-sse',
        answer: '快照',
        snapshotSequenceNo: 3,
      });
      expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({
        runId: 'run-sse',
        sequenceNo: 4,
        eventType: 'DELTA',
      }));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  test('does not apply a refreshed token after the run stream was aborted', async () => {
    let resolveRefresh!: (value: unknown) => void;
    rawPost.mockReturnValue(new Promise((resolve) => {
      resolveRefresh = resolve;
    }));
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);
    const { knowledgeApi } = await import('../knowledge');

    try {
      const task = knowledgeApi.streamChatRunEvents('run-refresh-abort', 0, {
        onSnapshot: vi.fn(),
        onEvent: vi.fn(),
      });
      await vi.waitFor(() => expect(rawPost).toHaveBeenCalled());
      task.abort();
      resolveRefresh({
        data: {
          data: {
            accessToken: 'token-refreshed',
            tokenType: 'Bearer',
            expiresIn: 3600,
          },
        },
      });

      await expect(task.result).rejects.toMatchObject({ name: 'AbortError' });
      expect(applyTokenResponse).not.toHaveBeenCalled();
      expect(clearCurrentSession).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
