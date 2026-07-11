import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import KnowledgeChatView from '../KnowledgeChatView.vue';
import { knowledgeApi } from '@/api/knowledge';
import {
  KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY,
  KNOWLEDGE_PROJECT_CHANGE_EVENT,
} from '@/composables/useKnowledgeProjectSelection';
import type { KnowledgeChatResponse } from '@/types/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    streamChat: vi.fn(),
    startChatRun: vi.fn(),
    getChatRun: vi.fn(),
    listConversationRuns: vi.fn(),
    cancelChatRun: vi.fn(),
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
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-default',
          conversationId: 'conv-default',
          question: 'default',
          status: 'ANSWERED',
          answer: finalResponse.answer,
          resultJson: '{}',
        },
      },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-default',
          conversationId: 'conv-default',
          question: 'default',
          status: 'ANSWERED',
          answer: finalResponse.answer,
          resultJson: '{}',
        },
      },
    } as never);
  });

  test('sends chat with the first loaded project id', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '新书项目' }] },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('帮我做前三章细纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      projectId: 99,
      question: '帮我做前三章细纲',
    });

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('uses project selected from the project space event when sending chat', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();
    await flushPromises();

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_PROJECT_CHANGE_EVENT, {
      detail: { projectId: 42 },
    }));
    await flushPromises();
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续做题材定位');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      projectId: 42,
      question: '继续做题材定位',
    });

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('restores the last selected project after remounting', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);
    window.localStorage.setItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY, '99');
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { projectId: 7, name: '旧项目' },
          { projectId: 99, name: '新书项目' },
        ],
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续做世界观');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      projectId: 99,
      question: '继续做世界观',
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

  test('shows degraded fallback state on assistant answers', async () => {
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
    const degradedResponse: KnowledgeChatResponse = {
      ...finalResponse,
      answer: '模型失败后的降级回答 [1]',
      resultJson: {
        answerStatus: 'degraded_model_fallback',
        fallbackUsed: true,
        degraded: true,
        degradationReasons: ['provider_exception'],
      },
    };

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('测试降级回答');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    callbacks.onDone({ event: 'done', data: degradedResponse });
    resolveResult(degradedResponse);
    await flushPromises();

    expect(wrapper.text()).toContain('模型失败后的降级回答');
    expect(wrapper.text()).toContain('降级回答');
    expect(wrapper.text()).toContain('provider_exception');
  });

  test('shows Chinese context budget status after the final response', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续扩写上一轮大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      resultJson: {
        conversationId: 'conv-context-budget',
        traceId: 'trace-context-1',
        contextBudget: {
          maxInputTokens: 1000000,
          estimatedUsedTokens: 1234,
          remainingTokens: 998766,
          remainingRatio: 0.998766,
          compressionThresholdTokens: 900000,
          compressed: false,
          memoryLayers: [
            { name: 'conversation', status: 'loaded', itemCount: 1 },
            { name: 'project', status: 'empty', itemCount: 0 },
          ],
        },
      },
    });
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-context-budget"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('1,000,000 tokens');
    expect(wrapper.text()).toContain('0.12%');
    expect(wrapper.text()).toContain('998,766 tokens');
    expect(wrapper.text()).toContain('900,000 tokens');
    expect(wrapper.text()).toContain('conversation loaded');
    expect(wrapper.text()).toContain('project empty');
    expect(wrapper.text()).toContain('上下文容量');
    expect(wrapper.text()).toContain('已用 1,234 tokens');
    expect(wrapper.text()).toContain('剩余 99.88%');
    expect(wrapper.text()).toContain('记忆层 2');
    expect(wrapper.text()).toContain('Trace trace-context-1');

    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.messages.at(-1).contextBudget.remainingRatio).toBeCloseTo(0.998766);
  });

  test('clears the composer after sending while preserving candidate continuation question', async () => {
    let resolveFirst!: (response: KnowledgeChatResponse) => void;
    let resolveSecond!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat)
      .mockImplementationOnce((_payload, _streamCallbacks) => ({
        abort: vi.fn(),
        result: new Promise<KnowledgeChatResponse>((resolve) => {
          resolveFirst = resolve;
        }),
      }) as never)
      .mockImplementationOnce((_payload, _streamCallbacks) => ({
        abort: vi.fn(),
        result: new Promise<KnowledgeChatResponse>((resolve) => {
          resolveSecond = resolve;
        }),
      }) as never);

    const wrapper = mountView();
    const input = wrapper.find<HTMLTextAreaElement>('[data-test="knowledge-question-input"] textarea');

    await input.setValue('分析《测试书》的开篇卖点');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(input.element.value).toBe('');

    resolveFirst({
      status: 'candidates_required',
      answer: '请选择正确作品后继续。',
      candidates: [{
        bookId: 101,
        platform: 'fanqie',
        platformBookId: '101',
        bookName: '测试书',
        author: '测试作者',
        local: true,
      }],
      sources: [],
      actions: ['select_candidate'],
      resultJson: {},
    });
    await flushPromises();

    await wrapper.find('[data-test="candidate-select-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[1][0]).toMatchObject({
      question: '分析《测试书》的开篇卖点',
      bookName: '测试书',
      selectedCandidate: {
        bookId: 101,
        bookName: '测试书',
      },
    });

    resolveSecond(finalResponse);
    await flushPromises();
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
    const firstPayload = vi.mocked(knowledgeApi.streamChat).mock.calls[0][0];
    expect(firstPayload.conversationId).toEqual(expect.any(String));
    expect(String(firstPayload.conversationId)).not.toHaveLength(0);
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

  test('keeps rank limit independent from selected chapter count', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('最近男频都市脑洞题材趋势是什么？');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    const payload = vi.mocked(knowledgeApi.streamChat).mock.calls[0][0];
    expect(payload.limits).toMatchObject({
      chapterCount: 3,
      evidenceLimit: 5,
      rankLimit: 30,
    });

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('uses a durable background run for deep reasoning answers', async () => {
    const wrapper = mountView();

    await wrapper
      .find('[data-test="knowledge-reasoning-mode"] .el-segmented__group label:nth-of-type(2) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('deep reasoning question');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.startChatRun).mock.calls[0][0]).toMatchObject({
      question: 'deep reasoning question',
      reasoningMode: 'deep',
    });
    expect(vi.mocked(knowledgeApi.streamChat)).not.toHaveBeenCalled();

    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.reasoningMode).toBe('deep');
    expect(saved.pendingRunId).toBe('');
    expect(wrapper.text()).toContain('第一段 第二段[1]');
    await flushPromises();
  });

  test('shows durable run progress and partial answer before the final result', async () => {
    vi.useFakeTimers();
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-progress',
          conversationId: 'conv-progress',
          question: 'deep progress question',
          status: 'RUNNING',
          progressPhase: 'intent',
          progressMessage: '正在识别写作意图',
          answer: '第一段部分答案',
          traceId: 'trace-progress',
          resultJson: '{}',
        },
      },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-progress',
          conversationId: 'conv-progress',
          question: 'deep progress question',
          status: 'ANSWERED',
          progressPhase: 'done',
          progressMessage: '后台回答已完成',
          answer: '最终完整答案[1]',
          traceId: 'trace-progress',
          resultJson: '{"traceId":"trace-progress","conversationId":"conv-progress"}',
        },
      },
    } as never);

    try {
      const wrapper = mountView();

      await wrapper
        .find('[data-test="knowledge-reasoning-mode"] .el-segmented__group label:nth-of-type(2) input')
        .setValue(true);
      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('deep progress question');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      await flushPromises();

      expect(wrapper.text()).toContain('正在识别写作意图');
      expect(wrapper.text()).toContain('第一段部分答案');
      expect(wrapper.text()).toContain('trace-progress');

      await vi.runOnlyPendingTimersAsync();
      await flushPromises();

      expect(wrapper.text()).toContain('最终完整答案');
      expect(window.localStorage.getItem('noval:knowledge-chat:draft:v1')).not.toContain('run-progress');
    } finally {
      vi.useRealTimers();
    }
  });

  test('normalizes raw durable run status before rendering it to users', async () => {
    vi.useFakeTimers();
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-raw-status',
          conversationId: 'conv-raw-status',
          question: 'deep raw status question',
          status: 'RUNNING',
          resultJson: '{}',
        },
      },
    } as never);

    try {
      const wrapper = mountView();

      await wrapper
        .find('[data-test="knowledge-reasoning-mode"] .el-segmented__group label:nth-of-type(2) input')
        .setValue(true);
      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('deep raw status question');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      await flushPromises();

      expect(wrapper.text()).not.toContain('RUNNING');
      expect(wrapper.text()).toContain('正在后台执行');
    } finally {
      vi.useRealTimers();
    }
  });

  test('restores an answered durable run after remounting the page', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      conversationId: 'conv-resume',
      pendingRunId: 'run-resume',
      messages: [
        { role: 'user', content: '闀垮洖绛旈棶棰?' },
        { role: 'assistant', content: '', status: 'RUNNING', sources: [] },
      ],
      status: 'RUNNING',
      reasoningMode: 'deep',
    }));
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-resume',
          conversationId: 'conv-resume',
          question: '闀垮洖绛旈棶棰?',
          status: 'ANSWERED',
          answer: '恢复式后台回答 [1]',
          resultJson: '{"traceId":"trace-resume","conversationId":"conv-resume"}',
          traceId: 'trace-resume',
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await flushPromises();

    expect(vi.mocked(knowledgeApi.getChatRun)).toHaveBeenCalledWith('run-resume');
    expect(wrapper.text()).toContain('恢复式后台回答');
    expect(window.localStorage.getItem('noval:knowledge-chat:draft:v1')).not.toContain('run-resume');
  });

  test('restores a pending durable run even when the assistant draft is missing', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      conversationId: 'conv-missing-assistant',
      pendingRunId: 'run-missing-assistant',
      messages: [
        { role: 'user', content: '给出完整的大纲设计' },
      ],
      status: 'RUNNING',
      reasoningMode: 'deep',
    }));
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-missing-assistant',
          conversationId: 'conv-missing-assistant',
          question: '给出完整的大纲设计',
          status: 'ANSWERED',
          answer: '恢复后的完整大纲回答[1]',
          resultJson: '{"traceId":"trace-missing-assistant","conversationId":"conv-missing-assistant"}',
          traceId: 'trace-missing-assistant',
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await flushPromises();

    expect(vi.mocked(knowledgeApi.getChatRun)).toHaveBeenCalledWith('run-missing-assistant');
    expect(wrapper.text()).toContain('恢复后的完整大纲回答');
    const saved = window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '';
    expect(saved).toContain('trace-missing-assistant');
    expect(saved).not.toContain('run-missing-assistant');
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

  test('keeps complete streamed answer when done payload only contains a shorter summary', async () => {
    vi.useFakeTimers();
    let callbacks: any;
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    const completeStreamText = '## Complete outline\nFirst arc keeps the full streamed draft.\nSecond arc should still be visible after done.';
    const summaryOnlyResponse: KnowledgeChatResponse = {
      ...finalResponse,
      answer: 'Short summary only.',
      resultJson: {
        answerStatus: 'partial_answer',
        domainIntent: 'mixed_creation_research',
      },
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

      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('urban outline question');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      callbacks.onDelta({ event: 'delta', delta: completeStreamText });
      await flushPromises();

      expect(wrapper.text()).toContain('Compl');
      expect(wrapper.text()).not.toContain(completeStreamText);

      callbacks.onDone({ event: 'done', data: summaryOnlyResponse });
      await flushPromises();

      expect(wrapper.text()).toContain('Compl');
      expect(wrapper.text()).not.toContain(summaryOnlyResponse.answer);

      await vi.runAllTimersAsync();
      resolveResult(summaryOnlyResponse);
      await flushPromises();

      expect(wrapper.text()).toContain('Second arc should still be visible after done.');
      expect(wrapper.text()).not.toContain(summaryOnlyResponse.answer);
      expect(wrapper.text()).toContain('部分证据');
    } finally {
      vi.useRealTimers();
    }
  });

  test('keeps streamed answer visible when citation repair returns a shorter final answer', async () => {
    vi.useFakeTimers();
    let callbacks: any;
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    const streamedAnswer = '## Streamed Answer\nFull outline with market analysis [1]\nThird beat stays visible.';
    const repairedFallback: KnowledgeChatResponse = {
      ...finalResponse,
      answer: '## Final Answer\nShort repaired summary [1]',
      resultJson: {
        citationRepairUsed: true,
      },
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

      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('最近男频都市脑洞题材趋势是什么？');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      callbacks.onDelta({ event: 'delta', delta: streamedAnswer });
      await flushPromises();

      callbacks.onDone({ event: 'done', data: repairedFallback });
      resolveResult(repairedFallback);
      await flushPromises();
      await vi.runAllTimersAsync();
      await flushPromises();

      expect(wrapper.text()).toContain('Full outline with market analysis');
      expect(wrapper.text()).toContain('Third beat stays visible.');
      expect(wrapper.text()).not.toContain('Short repaired summary');
      expect(wrapper.html()).toContain('<h2>Streamed Answer</h2>');
      expect(wrapper.text()).toContain('引用来源 1');
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

    expect(wrapper.text()).toContain('正在检索资料');
    expect(wrapper.text()).not.toContain('searching knowledge');

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

  test('starts a new chat session without carrying the old conversation id or history', async () => {
    window.localStorage.setItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY, '99');
    window.localStorage.setItem('noval:knowledge-chat:project:v1:99', JSON.stringify({
      messages: [
        { role: 'user', content: '旧问题' },
        { role: 'assistant', content: '旧回答[1]', status: 'answered', sources: [] },
      ],
      contextSummary: '旧摘要',
      conversationId: 'conv-old',
      answer: '旧回答[1]',
      reasoningMode: 'deep',
      chapterCount: 5,
      activeProjectId: 99,
    }));
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '新书项目' }] },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('旧回答[1]');

    await wrapper.find('[data-test="knowledge-new-chat"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('新会话问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    const payload = vi.mocked(knowledgeApi.startChatRun).mock.calls[0][0];
    expect(payload).toMatchObject({
      projectId: 99,
      question: '新会话问题',
      reasoningMode: 'deep',
    });
    expect(payload.conversationId).toEqual(expect.any(String));
    expect(payload.conversationId).not.toBe('conv-old');
    expect(payload.contextSummary).toBe('');
    expect(payload.history).toEqual([{ role: 'user', content: '新会话问题' }]);

    await flushPromises();
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
  test('keeps mobile project entry out of the chat content and keeps the composer mounted', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-mobile-project-open"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="knowledge-question-input"] textarea').exists()).toBe(true);
    expect(wrapper.find('.knowledge-chat__composer').exists()).toBe(true);
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
