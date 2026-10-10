import { flushPromises, mount } from '@vue/test-utils';
import { h } from 'vue';
import { useKnowledgeChat } from '../useKnowledgeChat';
import {
  emitKnowledgeConversationSelect,
  KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY,
} from '../useKnowledgeProjectSelection';
import { knowledgeApi } from '@/api/knowledge';
import type {
  KnowledgeChatRun,
  KnowledgeChatRunEventStreamCallbacks,
  KnowledgeConversationMessage,
} from '@/types/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listProjects: vi.fn(),
    listProjectWorks: vi.fn(),
    listConversations: vi.fn(),
    listConversationMessages: vi.fn(),
    listConversationRuns: vi.fn(),
    listChatRunEvents: vi.fn(),
    getChatRun: vi.fn(),
    startChatRun: vi.fn(),
    createConversation: vi.fn(),
    streamChatRunEvents: vi.fn(),
    streamChat: vi.fn(),
    cancelChatRun: vi.fn(),
  },
}));

vi.mock('@/api/config', () => ({ systemConfigApi: { getModelOptions: vi.fn() } }));

function response<T>(data: T) {
  return { data: { code: 200, message: 'success', data } } as never;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

const chats: ReturnType<typeof useKnowledgeChat>[] = [];
const callbacks = new Map<string, KnowledgeChatRunEventStreamCallbacks>();

function createChat() {
  const chat = useKnowledgeChat();
  chats.push(chat);
  return chat;
}

function saveDraft(value: Record<string, unknown>) {
  window.localStorage.setItem('noval:knowledge-chat:project:v1:99', JSON.stringify({
    activeProjectId: 99,
    conversationId: 'conv-a',
    reasoningMode: 'deep',
    ...value,
  }));
}

function runningRun(runId = 'run-new'): KnowledgeChatRun {
  return {
    runId,
    conversationId: 'conv-a',
    question: '新问题',
    status: 'RUNNING',
    answer: '',
    snapshotSequenceNo: 0,
  };
}

const oldMessages: KnowledgeConversationMessage[] = [
  { messageId: 1, conversationId: 'conv-a', role: 'USER', content: '旧问题' },
  { messageId: 2, conversationId: 'conv-a', role: 'ASSISTANT', content: '旧答案' },
];

describe('knowledge conversation and run ownership', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    callbacks.clear();
    window.localStorage.clear();
    window.localStorage.setItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY, '99');
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue(response([{ projectId: 99, name: '测试项目' }]));
    vi.mocked(knowledgeApi.listProjectWorks).mockResolvedValue(response([]));
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue(response([
      { conversationId: 'conv-a', projectId: 99, title: '最近更新的会话' },
      { conversationId: 'conv-b', projectId: 99, title: '上次查看的会话' },
    ]));
    vi.mocked(knowledgeApi.listConversationMessages).mockImplementation(async (conversationId) => response([
      { messageId: 10, conversationId, role: 'USER', content: `${conversationId} 的问题` },
      { messageId: 11, conversationId, role: 'ASSISTANT', content: `${conversationId} 的答案` },
    ]));
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue(response([]));
    vi.mocked(knowledgeApi.listChatRunEvents).mockResolvedValue(response([]));
    vi.mocked(knowledgeApi.getChatRun).mockImplementation(async (runId) => response(runningRun(runId)));
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValue(response(runningRun()));
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementation((runId, _sequence, streamCallbacks) => {
      callbacks.set(runId, streamCallbacks);
      return { abort: vi.fn(), result: new Promise<void>(() => undefined) };
    });
  });

  afterEach(() => {
    for (const chat of chats.splice(0)) chat.dispose();
  });

  const toolItem = (itemStatus: 'running' | 'completed' | 'unknown' = 'running') => ({
    schemaVersion: 1 as const, itemId: `tool_${'a'.repeat(32)}`, itemType: 'tool' as const,
    itemStatus, toolName: 'knowledge.search',
  });

  test.each(['new', 'resumed'])('renders live tool updates for a %s answer before it completes', async (kind) => {
    saveDraft({
      pendingRunId: kind === 'resumed' ? 'run-new' : undefined,
      messages: kind === 'resumed' ? [{ role: 'user', content: '新问题' }] : [],
    });
    const chat = createChat();
    const wrapper = mount({
      setup: () => () => h('output', chat.state.messages
        .flatMap((message) => message.process?.toolItems ?? [])
        .map((item) => item.itemStatus).join(',')),
    });
    try {
      if (kind === 'new') {
        chat.state.question = '新问题';
        await chat.sendQuestion();
      }
      await flushPromises();
      const stream = callbacks.get('run-new')!;
      stream.onEvent({ runId: 'run-new', sequenceNo: 1, eventType: 'PROGRESS',
        payload: JSON.stringify(toolItem()) });
      await flushPromises();
      expect(chat.state.loading).toBe(true);
      expect(chat.state.answer).toBe('');
      expect(wrapper.text()).toBe('running');
      stream.onEvent({ runId: 'run-new', sequenceNo: 2, eventType: 'PROGRESS',
        payload: JSON.stringify(toolItem('completed')) });
      await flushPromises();
      expect(wrapper.text()).toBe('completed');
    } finally {
      wrapper.unmount();
    }
  });

  test('updates only the owning tool item before answer completion and ignores replay', async () => {
    saveDraft({ pendingRunId: 'run-new', messages: [{ role: 'user', content: '新问题' }] });
    const chat = createChat();
    await flushPromises();
    const stream = callbacks.get('run-new')!;
    const event = (sequenceNo: number, itemStatus: 'running' | 'completed' = 'running') => ({
      runId: 'run-new', sequenceNo, eventType: 'PROGRESS', payload: JSON.stringify(toolItem(itemStatus)),
    });
    stream.onEvent({ ...event(1), runId: 'run-other' });
    stream.onEvent(event(1));
    const message = chat.state.messages.at(-1)!;
    expect(message.content).toBe('');
    expect(message.process?.toolItems?.[0].itemStatus).toBe('running');
    expect(message.process?.modelCallCount).toBeUndefined();
    expect(chat.state.loading).toBe(true);
    stream.onEvent(event(2, 'completed'));
    stream.onEvent(event(1));
    stream.onEvent(event(3));
    expect(message.process?.toolItems).toHaveLength(1);
    expect(message.process?.toolItems?.[0].itemStatus).toBe('completed');
    expect(chat.state.pendingRunId).toBe('run-new');
    expect(knowledgeApi.startChatRun).not.toHaveBeenCalled();
  });

  test('restores tools and answer at the same snapshot cursor with old snapshot compatibility', async () => {
    saveDraft({ pendingRunId: 'run-new', messages: [{ role: 'user', content: '新问题' }] });
    const chat = createChat();
    await flushPromises();
    const stream = callbacks.get('run-new')!;
    stream.onSnapshot({ runId: 'run-new', snapshotSequenceNo: 10, answer: '已恢复正文',
      toolItemsSchemaVersion: 1, toolItems: [toolItem('completed')] });
    stream.onEvent({ runId: 'run-new', sequenceNo: 2, eventType: 'PROGRESS', payload: JSON.stringify(toolItem()) });
    stream.onEvent({ runId: 'run-new', sequenceNo: 11, eventType: 'DELTA', payload: '{"delta":"后续"}' });
    const message = chat.state.messages.at(-1)!;
    expect(message.content).toBe('已恢复正文后续');
    expect(message.process?.toolItems?.[0].itemStatus).toBe('completed');
    stream.onSnapshot({ runId: 'run-new', snapshotSequenceNo: 12, answer: '旧格式快照' });
    expect(message.process?.toolItems?.[0].itemStatus).toBe('completed');
    expect(chat.state.lastEventSequence).toBe(12);
    expect(knowledgeApi.cancelChatRun).not.toHaveBeenCalled();
  });

  test('hydrates progress from the Run snapshot before subscribing after its cursor', async () => {
    saveDraft({ pendingRunId: 'run-new', messages: [{ role: 'user', content: '新问题' }] });
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValue(response({
      ...runningRun(), snapshotSequenceNo: 10, toolItemsSchemaVersion: 1, toolItems: [toolItem()],
    }));
    const chat = createChat();
    await flushPromises();
    expect(knowledgeApi.streamChatRunEvents).toHaveBeenCalledWith('run-new', 10, expect.any(Object));
    expect(chat.state.messages.at(-1)?.process?.toolItems?.[0].itemStatus).toBe('running');
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValue(response({ ...runningRun(), status: 'FAILED' }));
    callbacks.get('run-new')!.onEvent({ runId: 'run-new', sequenceNo: 11, eventType: 'FAILED', payload: '{}' });
    await flushPromises();
    expect(chat.state.messages.at(-1)?.process?.toolItems?.[0].itemStatus).toBe('unknown');
    expect(chat.state.messages.at(-1)?.process?.status).toBe('failed');
  });

  test('a disconnected subscription reconnects without declaring the business Run complete', async () => {
    saveDraft({ pendingRunId: 'run-new', messages: [{ role: 'user', content: '新问题' }] });
    const connection = deferred<void>();
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementationOnce((runId, _sequence, handlers) => {
      callbacks.set(runId, handlers);
      return { abort: vi.fn(), result: connection.promise };
    });
    const chat = createChat();
    await flushPromises();
    callbacks.get('run-new')!.onEvent({ runId: 'run-new', sequenceNo: 1, eventType: 'PROGRESS',
      payload: JSON.stringify(toolItem()) });
    connection.resolve();
    await flushPromises();
    expect(chat.state.pendingRunId).toBe('run-new');
    expect(chat.state.loading).toBe(true);
    expect(chat.state.messages.at(-1)?.process?.toolItems?.[0].itemStatus).toBe('running');
    expect(knowledgeApi.streamChatRunEvents).toHaveBeenCalledTimes(2);
    expect(knowledgeApi.cancelChatRun).not.toHaveBeenCalled();
  });

  test('host progress never enters the next question history or request settings', async () => {
    saveDraft({ messages: [{ role: 'user', content: '旧问题' }, {
      role: 'assistant', content: '旧答案', process: {
        status: 'processed', steps: [], loaded: true, toolItems: [toolItem('completed')],
      },
    }] });
    const chat = createChat();
    chat.state.question = '新问题';
    await chat.sendQuestion();
    const request = vi.mocked(knowledgeApi.startChatRun).mock.calls[0][0];
    expect(request.history?.slice(0, 2)).toEqual([
      { role: 'user', content: '旧问题' }, { role: 'assistant', content: '旧答案' },
    ]);
    expect(JSON.stringify(request)).not.toContain('itemId');
    expect(JSON.stringify(request)).not.toContain('toolItems');
  });

  test('fast stream failure leaves unfinished tools unknown and never marks the answer processed', async () => {
    saveDraft({ messages: [], reasoningMode: 'fast', reasoningEffort: 'low' });
    const chat = createChat();
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_request, handlers) => {
      handlers.onProgress?.({ event: 'progress', phase: 'tools', message: '正在调用工具', ...toolItem() });
      return { abort: vi.fn(), result: Promise.reject(new Error('fixture disconnected')) };
    });
    chat.state.question = '新问题';
    await chat.sendQuestion();
    const message = chat.state.messages.at(-1)!;
    expect(message.process?.status).toBe('failed');
    expect(message.process?.toolItems?.[0].itemStatus).toBe('unknown');
    expect(message.content).toBe('');
    expect(knowledgeApi.startChatRun).not.toHaveBeenCalled();
  });

  test('restores a conversation selected while the chat page was not mounted', async () => {
    saveDraft({ messages: [{ role: 'assistant', content: 'conv-a 的旧缓存' }] });
    emitKnowledgeConversationSelect({ projectId: 99, conversationId: 'conv-b' });

    const chat = createChat();
    expect(chat.state.messages.some((message) => message.content.includes('旧缓存'))).toBe(false);
    await chat.loadProjects();

    expect(chat.state.conversationId).toBe('conv-b');
    expect(chat.state.messages.at(-1)?.content).toBe('conv-b 的答案');
    chat.dispose();
    const reopened = createChat();
    await reopened.loadProjects();
    expect(reopened.state.conversationId).toBe('conv-b');
  });

  test('keeps an explicitly selected conversation when the first list page omits it', async () => {
    emitKnowledgeConversationSelect({ projectId: 99, conversationId: 'conv-older' });
    const chat = createChat();
    await chat.loadProjects();

    expect(chat.state.conversationId).toBe('conv-older');
    expect(knowledgeApi.listConversationMessages).toHaveBeenCalledWith('conv-older', 99);
  });

  test('remembers a selection immediately when leaving before its history request finishes', async () => {
    saveDraft({ messages: oldMessages.map((message) => ({ role: message.role.toLowerCase(), content: message.content })) });
    const history = deferred<Awaited<ReturnType<typeof knowledgeApi.listConversationMessages>>>();
    vi.mocked(knowledgeApi.listConversationMessages).mockReturnValueOnce(history.promise);
    const chat = createChat();
    const loading = chat.loadConversationRun('conv-b');

    const reopened = createChat();
    expect(reopened.state.conversationId).toBe('conv-b');
    history.resolve(response([]));
    await loading;
  });

  test('resumes a missing assistant draft after the new question without reusing an older answer', async () => {
    saveDraft({
      pendingRunId: 'run-new',
      messages: [
        { role: 'user', content: '旧问题' },
        { role: 'assistant', content: '旧答案', process: { status: 'processed', modelCalls: [{ id: 'old-call' }], steps: [] } },
        { role: 'user', content: '新问题' },
      ],
    });
    const chat = createChat();
    await flushPromises();

    expect(chat.state.messages.map((message) => message.content)).toEqual(['旧问题', '旧答案', '新问题', '']);
    expect(chat.state.messages.at(-1)?.runId).toBe('run-new');
    expect(chat.state.messages.at(-1)?.process?.modelCalls ?? []).toEqual([]);
    callbacks.get('run-new')!.onEvent({
      runId: 'run-new', sequenceNo: 1, eventType: 'DELTA', payload: JSON.stringify({ delta: '新答案' }),
    });
    expect(chat.state.messages.map((message) => message.content)).toEqual(['旧问题', '旧答案', '新问题', '新答案']);
  });

  test('does not bind a restored running turn to an untagged completed server answer', async () => {
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue(response([
      ...oldMessages,
      { messageId: 3, conversationId: 'conv-a', role: 'USER', content: '新问题', runId: 'run-new' },
    ]));
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue(response([runningRun()]));
    const chat = createChat();
    await chat.loadProjects();

    expect(chat.state.messages.map((message) => message.content)).toEqual(['旧问题', '旧答案', '新问题', '']);
    expect(chat.state.messages[1].runId).toBeUndefined();
    expect(chat.state.messages[1].process).toBeUndefined();
    expect(chat.state.messages[3].runId).toBe('run-new');
  });

  test.each([true, false])('tracks the newest run with stale conversation metadata (old run in page: %s)', async (oldRunInPage) => {
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue(response([
      { conversationId: 'conv-a', projectId: 99, lastRunId: 'run-old' },
    ]));
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue(response([
      ...oldMessages.map((message) => ({ ...message, runId: 'run-old' })),
      { messageId: 3, conversationId: 'conv-a', role: 'USER', content: '新问题', runId: 'run-new' },
    ]));
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue(response([
      runningRun(),
      ...(oldRunInPage ? [{ ...runningRun('run-old'), question: '旧问题', status: 'ANSWERED', answer: '旧答案' }] : []),
    ]));
    const chat = createChat();
    await chat.loadProjects();

    expect(chat.state.pendingRunId).toBe('run-new');
    expect(chat.state.messages.at(-1)?.runId).toBe('run-new');
    expect(knowledgeApi.streamChatRunEvents).toHaveBeenCalledWith('run-new', 0, expect.any(Object));
  });

  test('does not replace a newly submitted turn with a slower history refresh', async () => {
    saveDraft({ messages: [{ role: 'user', content: '旧问题' }, { role: 'assistant', content: '旧答案' }] });
    const history = deferred<Awaited<ReturnType<typeof knowledgeApi.listConversationMessages>>>();
    vi.mocked(knowledgeApi.listConversationMessages).mockReturnValueOnce(history.promise);
    const chat = createChat();
    const loading = chat.loadConversationRun('conv-a');
    chat.state.question = '新问题';
    await chat.sendQuestion();
    history.resolve(response(oldMessages));
    await loading;

    expect(chat.state.pendingRunId).toBe('run-new');
    expect(chat.state.messages.map((message) => message.content)).toEqual(['旧问题', '旧答案', '新问题', '']);
    expect(chat.state.messages.at(-1)?.runId).toBe('run-new');
  });

  test('ignores events and snapshots for another run without consuming the current sequence', async () => {
    saveDraft({ pendingRunId: 'run-new', messages: [{ role: 'user', content: '新问题' }] });
    const chat = createChat();
    await flushPromises();
    const stream = callbacks.get('run-new')!;
    stream.onSnapshot({ runId: 'run-other', snapshotSequenceNo: 10, answer: '别的回答' });
    stream.onEvent({ runId: 'run-other', sequenceNo: 11, eventType: 'DELTA', payload: '{"delta":"串入内容"}' });
    stream.onEvent({ runId: 'run-new', sequenceNo: 1, eventType: 'DELTA', payload: '{"delta":"本轮回答"}' });

    expect(chat.state.messages.at(-1)?.content).toBe('本轮回答');
    expect(chat.state.lastEventSequence).toBe(1);
  });
});
