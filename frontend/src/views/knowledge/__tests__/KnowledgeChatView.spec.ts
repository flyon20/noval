import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import KnowledgeChatView from '../KnowledgeChatView.vue';
import { knowledgeApi } from '@/api/knowledge';
import type { KnowledgeChatResponse } from '@/types/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    streamChat: vi.fn(),
    listProjects: vi.fn(),
    createProject: vi.fn(),
  },
}));

const finalResponse: KnowledgeChatResponse = {
  status: 'answered',
  answer: '第一段 第二段[1]',
  candidates: [],
  sources: [
    {
      chunkId: 1,
      bookName: '测试书',
      chapterNo: 1,
      title: '第一章',
      preview: '来源摘要',
      score: 0.9,
    },
  ],
  actions: [],
  resultJson: {},
};

function mountView() {
  return mount(KnowledgeChatView, {
    global: {
      plugins: [ElementPlus],
    },
  });
}

describe('KnowledgeChatView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.createProject).mockResolvedValue({
      data: { code: 200, message: 'success', data: { projectId: 99, name: '新书项目' } },
    } as never);
  });

  test('creates a project and sends chat with selected project id', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-project-name"] input').setValue('新书项目');
    await wrapper.find('[data-test="knowledge-create-project"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('帮我做前三章细纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(knowledgeApi.createProject).toHaveBeenCalledWith({ name: '新书项目' });
    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      projectId: 99,
      question: '帮我做前三章细纲',
    });

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('renders streaming deltas before final response resolves', async () => {
    vi.useFakeTimers();
    let callbacks: any;
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, streamCallbacks) => {
      callbacks = streamCallbacks;
      return {
        abort: vi.fn(),
        result: new Promise<KnowledgeChatResponse>((resolve) => {
          resolveResult = resolve;
        }),
      } as never;
    });

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('测试问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    callbacks.onDelta({ event: 'delta', delta: '第一段 ' });
    await flushPromises();

    expect(wrapper.text()).toContain('测试问题');
    expect(wrapper.text()).toContain('第一段');
    expect(wrapper.text()).not.toContain('第二段');

    callbacks.onDelta({ event: 'delta', delta: '第二段[1]' });
    callbacks.onDone({ event: 'done', data: finalResponse });
    resolveResult(finalResponse);
    await flushPromises();
    await vi.runAllTimersAsync();
    await flushPromises();

    expect(wrapper.text()).toContain('第一段 第二段[1]');
    expect(wrapper.text()).toContain('引用来源 1');
    vi.useRealTimers();
  });

  test('persists and reuses server conversation id', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('第一轮问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      resultJson: { conversationId: 'conv-frontend-1' },
    });
    await flushPromises();
    await flushPromises();

    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.conversationId).toBe('conv-frontend-1');

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('第二轮问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[1][0]).toMatchObject({
      conversationId: 'conv-frontend-1',
      question: '第二轮问题',
    });
  });

  test('sends long timeout for knowledge answer generation', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('生成完整大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    const payload = vi.mocked(knowledgeApi.streamChat).mock.calls[0][0];
    expect(payload.limits).toMatchObject({
      timeoutMillis: 600000,
    });

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('sends expanded context and recent history for long outline followups', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);
    const longAssistantAnswer = '上一版男频都市脑洞大纲'.padEnd(80_000, '甲');
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      messages: [
        { role: 'user', content: '第1轮大纲'.padEnd(80_000, '乙') },
        { role: 'assistant', content: longAssistantAnswer, status: 'answered', sources: [] },
        { role: 'user', content: '第2轮大纲'.padEnd(80_000, '丙') },
        { role: 'assistant', content: '第二版回答'.padEnd(80_000, '丁'), status: 'answered', sources: [] },
      ],
      contextSummary: '男频定位摘要'.padEnd(700_000, '戊'),
      answer: longAssistantAnswer,
    }));

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续上一版，扩成完整三卷大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    const payload = vi.mocked(knowledgeApi.streamChat).mock.calls[0][0];
    expect(payload.contextSummary.length).toBeGreaterThan(600_000);
    expect(payload.history).toHaveLength(5);
    expect(payload.history[1].content.length).toBeGreaterThan(60_000);

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('does not send stale selected book context for a new trend question', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      bookName: '凡人修仙传',
      selectedCandidate: {
        bookId: 1001,
        platform: 'fanqie',
        bookName: '凡人修仙传',
        local: true,
      },
      messages: [
        { role: 'user', content: '上一轮分析这本书' },
        { role: 'assistant', content: '单书分析结果', status: 'answered', sources: [] },
      ],
    }));

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('最近男频题材趋势是什么？');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    const payload = vi.mocked(knowledgeApi.streamChat).mock.calls[0][0];
    expect(payload.bookName).toBeUndefined();
    expect(payload.selectedCandidate).toBeUndefined();

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('keeps layered context summary faithful after a long outline answer', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);
    const longOutline = [
      '## 核心定位',
      '男频都市脑洞，长生自首流，目标是扫榜后开书。',
      '## 三卷大纲',
      '第一卷国家接触，第二卷市场扩张，第三卷高维敌人。',
      '## 细纲',
      '第1章自首，第2章检测，第3章直播舆论。',
    ].join('\n').padEnd(50_000, '纲');

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('最近男频题材趋势是什么，给我做大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      answer: longOutline,
      resultJson: {
        intent: 'creative_advice',
        domainIntent: 'outline_generation',
        answerBoundary: 'creative_inference',
      },
    });
    await flushPromises();

    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.contextSummary).toContain('最近意图：outline_generation');
    expect(saved.contextSummary).toContain('男频');
    expect(saved.contextSummary).toContain('三卷大纲');
    expect(saved.contextSummary).toContain('细纲');
    expect(saved.contextSummary.length).toBeGreaterThan(20_000);
  });

  test('renders very large stream deltas immediately without slow playback lag', async () => {
    vi.useFakeTimers();
    let callbacks: any;
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    const largeDelta = '男频长文分段输出'.padEnd(20_000, '流');
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, streamCallbacks) => {
      callbacks = streamCallbacks;
      return {
        abort: vi.fn(),
        result: new Promise<KnowledgeChatResponse>((resolve) => {
          resolveResult = resolve;
        }),
      } as never;
    });

    try {
      const wrapper = mountView();

      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('输出完整大纲');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      callbacks.onDelta({ event: 'delta', delta: largeDelta });
      await flushPromises();

      expect(wrapper.text()).toContain(largeDelta.slice(0, 2000));

      callbacks.onDone({ event: 'done', data: { ...finalResponse, answer: largeDelta } });
      resolveResult({ ...finalResponse, answer: largeDelta });
      await flushPromises();
      await vi.runAllTimersAsync();
      await flushPromises();
    } finally {
      vi.useRealTimers();
    }
  });

  test('falls back to a trimmed persisted snapshot when localStorage is full', async () => {
    const originalSetItem = Storage.prototype.setItem;
    let quotaThrown = false;
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function setItemWithQuota(key, value) {
      if (!quotaThrown && String(value).includes('outline_generation')) {
        quotaThrown = true;
        throw new DOMException('Quota exceeded', 'QuotaExceededError');
      }
      return originalSetItem.call(this, key, value);
    });

    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    try {
      const wrapper = mountView();

      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('长大纲保存测试');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      resolveResult({
        ...finalResponse,
        answer: '大纲'.repeat(5000),
        resultJson: { intent: 'creative_advice', domainIntent: 'outline_generation' },
      });
      await flushPromises();

      expect(setItem).toHaveBeenCalled();
      expect(quotaThrown).toBe(true);
      expect(window.localStorage.getItem('noval:knowledge-chat:draft:v1')).toContain('outline_generation');
      expect(wrapper.text()).toContain('长大纲保存测试');
    } finally {
      setItem.mockRestore();
    }
  });

  test('restores short term chat memory after remounting the page', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      messages: [
        { role: 'user', content: '上一轮问题' },
        { role: 'assistant', content: '上一轮回答[1]', status: 'answered', sources: [] },
      ],
      contextSummary: '上一轮摘要',
      chapterCount: 5,
      status: 'answered',
      answer: '上一轮回答[1]',
    }));

    const wrapper = mountView();

    expect(wrapper.text()).toContain('上一轮问题');
    expect(wrapper.text()).toContain('上一轮回答[1]');
  });

  test('restores messages in chronological order with newest answer at the bottom', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      messages: [
        { role: 'user', content: 'old question' },
        { role: 'assistant', content: 'old answer', status: 'answered', sources: [] },
        { role: 'user', content: 'new question' },
        { role: 'assistant', content: 'new answer', status: 'answered', sources: [] },
      ],
      answer: 'new answer',
    }));

    const wrapper = mountView();
    const messageText = wrapper.find('.knowledge-chat__messages').text();

    expect(messageText.indexOf('old question')).toBeLessThan(messageText.indexOf('old answer'));
    expect(messageText.indexOf('old answer')).toBeLessThan(messageText.indexOf('new question'));
    expect(messageText.indexOf('new question')).toBeLessThan(messageText.indexOf('new answer'));
  });

  test('keeps large stream chunks progressive and applies shorter final answer correction', async () => {
    vi.useFakeTimers();
    let callbacks: any;
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    const staleStreamText = 'old stale rank answer that should not survive final correction';
    const correctedResponse: KnowledgeChatResponse = {
      ...finalResponse,
      answer: 'correct current rank answer [1]',
    };
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, streamCallbacks) => {
      callbacks = streamCallbacks;
      return {
        abort: vi.fn(),
        result: new Promise<KnowledgeChatResponse>((resolve) => {
          resolveResult = resolve;
        }),
      } as never;
    });

    try {
      const wrapper = mountView();

      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('rank trend question');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      callbacks.onDelta({ event: 'delta', delta: staleStreamText });
      await flushPromises();

      expect(wrapper.text()).toContain(staleStreamText.slice(0, 8));
      expect(wrapper.text()).not.toContain(staleStreamText);

      callbacks.onDone({ event: 'done', data: correctedResponse });
      await flushPromises();

      expect(wrapper.text()).not.toContain(staleStreamText);
      expect(wrapper.text()).not.toContain(correctedResponse.answer);

      await vi.runAllTimersAsync();
      resolveResult(correctedResponse);
      await flushPromises();

      expect(wrapper.text()).toContain(correctedResponse.answer);
      expect(wrapper.text()).not.toContain(staleStreamText);
    } finally {
      vi.useRealTimers();
    }
  });

  test('shows stream progress before the first answer delta', async () => {
    let callbacks: any;
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, streamCallbacks) => {
      callbacks = streamCallbacks;
      return {
        abort: vi.fn(),
        result: new Promise<KnowledgeChatResponse>((resolve) => {
          resolveResult = resolve;
        }),
      } as never;
    });

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('progress question');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    callbacks.onProgress({ event: 'progress', phase: 'retrieve', message: 'searching knowledge' });
    await flushPromises();

    expect(wrapper.text()).toContain('searching knowledge');

    callbacks.onDone({ event: 'done', data: finalResponse });
    resolveResult(finalResponse);
    await flushPromises();
  });

  test('clears the restored conversation from toolbar action', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      messages: [
        { role: 'user', content: '旧问题' },
        { role: 'assistant', content: '旧回答[1]', status: 'answered', sources: [] },
      ],
      contextSummary: '旧摘要',
      conversationId: 'conv-old',
      answer: '旧回答[1]',
    }));

    const wrapper = mountView();

    expect(wrapper.text()).toContain('旧回答[1]');

    await wrapper.find('[data-test="knowledge-clear-chat"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).not.toContain('旧回答[1]');
    expect(wrapper.text()).toContain('网文 AI 问答');
    expect(window.localStorage.getItem('noval:knowledge-chat:draft:v1')).toContain('"messages":[]');
  });

  test('deletes a single message without clearing the whole conversation', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      messages: [
        { role: 'user', content: '保留问题' },
        { role: 'assistant', content: '删除回答[1]', status: 'answered', sources: [] },
      ],
      answer: '删除回答[1]',
    }));

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-delete-message-1"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('保留问题');
    expect(wrapper.text()).not.toContain('删除回答[1]');
    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.messages).toHaveLength(1);
    expect(saved.answer).toBe('');
  });

  test('shows compact answer boundary status from result json', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('最近男频都市脑洞热门题材');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      resultJson: { answerStatus: 'partial_answer' },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('部分证据');
  });

  test('shows intent and answer boundary badges from result json', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('给我扫榜并给开书建议');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      resultJson: {
        answerStatus: 'partial_answer',
        intent: 'trend_research',
        answerBoundary: 'evidence_plus_author_inference',
        domainIntent: 'mixed_creation_research',
        domainAnswerBoundary: 'market_evidence_plus_author_inference',
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('复合任务');
    expect(wrapper.text()).toContain('证据+作者推演');

    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.messages.at(-1).intent).toBe('mixed_creation_research');
    expect(saved.messages.at(-1).answerBoundary).toBe('evidence_plus_author_inference');
  });

  test('persists final answer boundary before domain diagnostic boundary', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('扫榜但证据不足');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      resultJson: {
        answerStatus: 'needs_data',
        answerBoundary: 'needs_more_data',
        domainIntent: 'market_scan',
        domainAnswerBoundary: 'market_evidence',
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('需要补数据');
    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.messages.at(-1).answerBoundary).toBe('needs_more_data');
  });

  test('renders rank evidence as compact source rows', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('最近男频新书榜都市脑洞第一是谁');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      sources: [{
        sourceType: 'RANK',
        rankNo: 1,
        bookName: '入伍两次！我被原部队拉进黑名单',
        author: '朝朝和',
        title: '男频新书榜 / 都市脑洞 #1',
        category: '都市脑洞',
        score: 1,
      }],
      resultJson: { answerStatus: 'answered_with_evidence' },
    });
    await flushPromises();

    await wrapper.find('.knowledge-message__sources-toggle').trigger('click');

    expect(wrapper.text()).toContain('#1');
    expect(wrapper.text()).toContain('入伍两次！我被原部队拉进黑名单');
    expect(wrapper.text()).toContain('朝朝和');
  });
  test('tracks mobile keyboard offset through visual viewport for the composer', async () => {
    const addEventListener = vi.fn();
    const removeEventListener = vi.fn();
    Object.defineProperty(window, 'innerHeight', {
      configurable: true,
      writable: true,
      value: 812,
    });
    Object.defineProperty(window, 'visualViewport', {
      configurable: true,
      writable: true,
      value: {
        width: 390,
        height: 520,
        offsetTop: 0,
        offsetLeft: 0,
        scale: 1,
        addEventListener,
        removeEventListener,
      },
    });

    const wrapper = mountView();
    await flushPromises();

    expect(addEventListener).toHaveBeenCalledWith('resize', expect.any(Function));
    expect(wrapper.get('.knowledge-chat').attributes('style')).toContain('--keyboard-offset: 292px');

    wrapper.unmount();
    expect(removeEventListener).toHaveBeenCalledWith('resize', expect.any(Function));
  });
});
