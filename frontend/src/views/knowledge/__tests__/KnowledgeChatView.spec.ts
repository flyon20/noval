import ElementPlus from 'element-plus';
import fs from 'node:fs';
import path from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import KnowledgeChatView from '../KnowledgeChatView.vue';
import { knowledgeApi } from '@/api/knowledge';
import { systemConfigApi } from '@/api/config';
import {
  KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY,
  KNOWLEDGE_ACTIVE_WORK_STORAGE_PREFIX,
  KNOWLEDGE_CONVERSATION_SELECT_EVENT,
  KNOWLEDGE_PROJECT_CHANGE_EVENT,
  KNOWLEDGE_REFERENCE_WORK_STORAGE_PREFIX,
} from '@/composables/useKnowledgeProjectSelection';
import type { KnowledgeChatResponse } from '@/types/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    streamChat: vi.fn(),
    startChatRun: vi.fn(),
    getChatRun: vi.fn(),
    listChatRunEvents: vi.fn(),
    streamChatRunEvents: vi.fn(),
    listConversations: vi.fn(),
    createConversation: vi.fn(),
    listConversationMessages: vi.fn(),
    listConversationRuns: vi.fn(),
    cancelChatRun: vi.fn(),
    listSkillShortcuts: vi.fn(),
    listProjects: vi.fn(),
    listProjectWorks: vi.fn(),
    createProject: vi.fn(),
  },
}));

vi.mock('@/api/config', () => ({
  systemConfigApi: {
    getModelOptions: vi.fn(),
  },
}));

const DEEPSEEK_MODEL_OPTION = {
  modelKey: 'deepseek-chat',
  displayName: 'DeepSeek',
  providerType: 'deepseek',
  isDefault: true,
  supportsReasoning: true,
  reasoningTiers: ['minimal', 'low', 'medium', 'high'],
};

const KIMI_MODEL_OPTION = {
  modelKey: 'kimi-main',
  displayName: 'Kimi K3',
  // 注册表里填的是默认的 openai-compatible，分栏要用 worker 判定的族。
  providerType: 'openai-compatible',
  providerFamily: 'moonshot',
  isDefault: false,
  supportsReasoning: true,
  reasoningTiers: ['low', 'high', 'max'],
};

const GPT56_MODEL_OPTION = {
  modelKey: 'gpt-5.6-sol',
  displayName: 'GPT-5.6 Sol',
  providerType: 'openai',
  isDefault: false,
  supportsReasoning: true,
  // gpt-5.6 一代把枚举两头都改了：底档换成 none，high 之上多了 xhigh/max。
  reasoningTiers: ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
};

const QWEN_MODEL_OPTION = {
  modelKey: 'qwen-main',
  displayName: 'Qwen3',
  providerType: 'qwen',
  isDefault: false,
  supportsReasoning: true,
  reasoningTiers: ['minimal', 'high'],
};

const CLAUDE_MODEL_OPTION = {
  modelKey: 'claude-main',
  displayName: 'Claude Sonnet',
  providerType: 'anthropic',
  isDefault: false,
  supportsReasoning: false,
  reasoningTiers: [],
};

function mockModelOptions(options: unknown[]) {
  vi.mocked(systemConfigApi.getModelOptions).mockResolvedValue({
    data: { code: 200, message: 'success', data: options },
  } as never);
}

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

const mountedWrappers: ReturnType<typeof mount>[] = [];

function mountView() {
  const wrapper = mount(KnowledgeChatView, {
    global: {
      plugins: [ElementPlus],
    },
  });
  mountedWrappers.push(wrapper);
  return wrapper;
}

describe('KnowledgeChatView', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    window.localStorage.clear();
    mockModelOptions([DEEPSEEK_MODEL_OPTION, KIMI_MODEL_OPTION]);
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.listProjectWorks).mockImplementation((projectId) => Promise.resolve({
      data: {
        code: 200,
        message: 'success',
        data: [{ workId: projectId * 10, projectId, title: `作品 ${projectId}`, status: 'ACTIVE' }],
      },
    }) as never);
    vi.mocked(knowledgeApi.listSkillShortcuts).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.createProject).mockResolvedValue({
      data: { code: 200, message: 'success', data: { projectId: 99, name: '新书项目' } },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.createConversation).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          conversationId: 'conv-created',
          projectId: 99,
          title: '新会话',
          status: 'ACTIVE',
          messages: [],
        },
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.listChatRunEvents).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.streamChatRunEvents).mockReturnValue({
      abort: vi.fn(),
      result: new Promise<void>(() => undefined),
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

  afterEach(() => {
    while (mountedWrappers.length) {
      mountedWrappers.pop()?.unmount();
    }
  });

  test('uses compact mobile controls, bounded capacity details, and restrained message surfaces', () => {
    const viewSource = fs.readFileSync(path.resolve(__dirname, '../KnowledgeChatView.vue'), 'utf-8');
    const messageSource = fs.readFileSync(
      path.resolve(__dirname, '../../../components/knowledge/KnowledgeMessageBubble.vue'),
      'utf-8',
    );
    const mobileComposerStyles = viewSource.slice(viewSource.indexOf('@media (max-width: 768px)'));
    const mobileMessageStyles = messageSource.slice(messageSource.indexOf('@media (max-width: 768px)'));

    expect(viewSource).toContain('class="knowledge-chat__composer-main"');
    expect(mobileComposerStyles).toContain('grid-template-columns: minmax(0, 1fr) 44px;');
    expect(mobileComposerStyles).toContain('box-shadow: none;');
    expect(mobileComposerStyles).toContain('background: transparent;');
    expect(mobileComposerStyles).toContain('border-radius: 18px;');
    expect(mobileComposerStyles).toContain('touch-action: pan-x;');
    expect(mobileComposerStyles).toContain('overflow-y: hidden;');
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__tool-options\s*\{[\s\S]*?flex-wrap:\s*nowrap;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__send\s*\{[\s\S]*?flex:\s*0 0 44px;[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__toolbar\s*\{[\s\S]*?min-height:\s*36px;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__toolbar-actions :deep\(\.el-button\)\s*\{[\s\S]*?width:\s*36px;[\s\S]*?height:\s*36px;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__toolbar-actions :deep\(\.el-button\)::after\s*\{[\s\S]*?inset:\s*-4px;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__skill-option\s*\{[\s\S]*?min-height:\s*36px;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__skill-option::after\s*\{[\s\S]*?inset:\s*-4px 0;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__context-trigger\s*\{[\s\S]*?flex:\s*0 0 36px;[\s\S]*?width:\s*36px;[\s\S]*?height:\s*36px;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__context-trigger::after\s*\{[\s\S]*?inset:\s*-4px;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__context-budget\s*\{[\s\S]*?position:\s*static;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__context-popover\s*\{[\s\S]*?right:\s*0\.75rem;[\s\S]*?width:\s*min\(300px, calc\(100% - 1\.5rem\)\);[\s\S]*?max-height:[\s\S]*?220px,[\s\S]*?overflow-y:\s*auto;/);
    expect(viewSource).toContain('class="knowledge-chat__context-secondary"');
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__context-secondary\s*\{[\s\S]*?display:\s*none;/);
    expect(messageSource).toContain('background: color-mix(in srgb, var(--color-primary) 8%, var(--color-surface-strong));');
    expect(mobileMessageStyles).toMatch(/\.knowledge-message\.is-assistant\s*\{[\s\S]*?max-width:\s*100%;/);
    expect(mobileMessageStyles).toContain('font-size: 0.95rem;');
    // 复制/删除是 codex 那种小键：桌面 28px，移动 32px，热区靠 ::after 补到不误触。
    expect(messageSource).toMatch(/\.knowledge-message__action\s*\{[\s\S]*?width:\s*28px;[\s\S]*?height:\s*28px;/);
    expect(mobileMessageStyles).toMatch(/\.knowledge-message__action\s*\{[\s\S]*?width:\s*32px;[\s\S]*?height:\s*32px;/);
    expect(mobileMessageStyles).toMatch(/\.knowledge-message__action::after\s*\{[\s\S]*?inset:\s*-6px -4px;/);
    // 回到底部键叠在 messages 区，不许新增 grid 行。
    expect(viewSource).toMatch(/\.knowledge-chat__scroll-dock\s*\{[\s\S]*?grid-area:\s*messages;[\s\S]*?pointer-events:\s*none;/);
    expect(mobileComposerStyles).toMatch(/\.knowledge-chat__scroll-bottom::after\s*\{[\s\S]*?inset:\s*-4px;/);
  });

  test('keeps the empty-state composer in its own grid row when optional rows are absent', () => {
    const source = fs
      .readFileSync(path.resolve(__dirname, '../KnowledgeChatView.vue'), 'utf-8')
      .replace(/\r\n/g, '\n');

    expect(source).toContain("grid-template-areas:\n    'toolbar'\n    'messages'\n    'error'\n    'composer';");
    expect(source).toMatch(/\.knowledge-chat__messages\s*\{[\s\S]*?grid-area:\s*messages;/);
    expect(source).toMatch(/\.knowledge-chat__composer\s*\{[\s\S]*?grid-area:\s*composer;/);
  });

  test('loads governed Skill shortcuts without rendering private Skill fields', async () => {
    vi.mocked(knowledgeApi.listSkillShortcuts).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          skillId: 'webnovel-market-scan',
          title: '榜单分析',
          description: '分析当前榜单',
          appliesTo: ['market_scan'],
          content: 'private governed skill body',
          guardrails: 'private guardrails',
        }],
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    expect(knowledgeApi.listSkillShortcuts).toHaveBeenCalledOnce();
    expect(wrapper.get('[data-test="knowledge-skill-auto"]').text()).toBe('自动路由');
    expect(wrapper.get('[data-test="knowledge-skill-webnovel-market-scan"]').text()).toBe('榜单分析');
    expect(wrapper.text()).not.toContain('private governed skill body');
    expect(wrapper.text()).not.toContain('private guardrails');
  });

  test('sends a selected governed Skill as a non-authoritative request hint', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.listSkillShortcuts).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          skillId: 'webnovel-outline-building',
          title: '大纲构思',
          description: '构建网文大纲',
          appliesTo: ['outline_building'],
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();
    await flushPromises();
    const outlineSkill = wrapper.get('[data-test="knowledge-skill-webnovel-outline-building"]');
    await outlineSkill.trigger('click');
    await outlineSkill.trigger('click');
    expect(outlineSkill.attributes('aria-checked')).toBe('true');
    expect(wrapper.get('[data-test="knowledge-skill-auto"]').attributes('aria-checked')).toBe('false');
    await wrapper.get('[data-test="knowledge-question-input"] textarea').setValue('构思一部长篇都市异能大纲');
    await wrapper.get('[data-test="knowledge-send-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      question: '构思一部长篇都市异能大纲',
      preferredSkillId: 'webnovel-outline-building',
    });

    resolveResult(finalResponse);
    await flushPromises();
  });

  test('returns to automatic routing and omits preferredSkillId from the request', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.listSkillShortcuts).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          skillId: 'webnovel-book-breakdown',
          title: '章节分析',
          appliesTo: ['book_breakdown'],
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.get('[data-test="knowledge-skill-webnovel-book-breakdown"]').trigger('click');
    await wrapper.get('[data-test="knowledge-skill-auto"]').trigger('click');
    await wrapper.get('[data-test="knowledge-question-input"] textarea').setValue('分析这一章的节奏');
    await wrapper.get('[data-test="knowledge-send-button"]').trigger('click');

    expect(wrapper.get('[data-test="knowledge-skill-auto"]').attributes('aria-checked')).toBe('true');
    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).not.toHaveProperty('preferredSkillId');

    resolveResult(finalResponse);
    await flushPromises();
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
      workId: 990,
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
      workId: 420,
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
    window.localStorage.setItem(`${KNOWLEDGE_ACTIVE_WORK_STORAGE_PREFIX}99`, '992');
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
    vi.mocked(knowledgeApi.listProjectWorks).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { workId: 990, projectId: 99, title: '旧作品', status: 'ACTIVE' },
          { workId: 992, projectId: 99, title: '当前作品', status: 'ACTIVE' },
        ],
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续做世界观');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      projectId: 99,
      workId: 992,
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
    expect(wrapper.text()).toContain('模型服务暂时异常');
    expect(wrapper.text()).not.toContain('provider_exception');
  });

  test('shows Chinese context budget status after the final response', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    let streamCallbacks: any;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, callbacks) => {
      streamCallbacks = callbacks;
      return {
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
      } as never;
    });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续扩写上一轮大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    streamCallbacks.onProgress({
      event: 'context_compacting',
      phase: 'context',
      message: '会话接近模型上下文上限，正在自动压缩',
      contextWindowTokens: 1000000,
      thresholdTokens: 900000,
      beforeInputTokens: 920000,
      generation: 2,
    });
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-context-percent"]').text()).toBe('92%');
    expect(wrapper.get('[data-test="knowledge-context-trigger"]').classes()).toContain('is-compacting');
    expect(wrapper.get('[data-test="knowledge-context-ring-value"]').attributes('stroke-dasharray'))
      .toBe('92.0 100');
    expect(wrapper.get('[data-test="knowledge-context-status"]').text()).toContain('正在自动压缩');

    streamCallbacks.onProgress({
      event: 'context_compacted',
      phase: 'context',
      message: '上下文已自动压缩',
      contextWindowTokens: 1000000,
      thresholdTokens: 900000,
      beforeInputTokens: 920000,
      afterInputTokens: 240000,
      retainedTurnCount: 8,
      summarizedMessageCount: 24,
      generation: 2,
    });
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-context-percent"]').text()).toBe('24%');
    expect(wrapper.get('[data-test="knowledge-context-trigger"]').classes()).not.toContain('is-compacting');
    expect(wrapper.get('[data-test="knowledge-context-status"]').text()).toContain('容量已刷新');

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
          compressed: true,
          memoryLayers: {
            projectProfile: {
              status: 'loaded',
              keys: ['premise', 'constraint'],
              sourceIds: [101],
            },
            threadSummary: {
              status: 'provided',
              keys: ['summary'],
              sourceIds: [],
            },
            userProfile: {
              status: 'empty',
              keys: [],
              sourceIds: [],
            },
          },
        },
      },
    });
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-context-budget"]').exists()).toBe(true);
    expect(wrapper.find('.knowledge-chat__toolbar [data-test="knowledge-context-budget"]').exists()).toBe(true);
    expect(wrapper.get('[data-test="knowledge-context-percent"]').text()).toBe('0%');
    expect(wrapper.get('[data-test="knowledge-context-ring-value"]').attributes('stroke-dasharray'))
      .toBe('0.1 100');
    expect(wrapper.find('[data-test="knowledge-context-trigger"]').attributes('aria-label'))
      .toContain('已用 1,234 tokens');
    expect(wrapper.text()).toContain('1,000,000 tokens');
    expect(wrapper.text()).toContain('0.12%');
    expect(wrapper.text()).toContain('998,766 tokens');
    expect(wrapper.text()).toContain('900,000 tokens');
    expect(wrapper.text()).toContain('项目资料 已加载 2');
    expect(wrapper.text()).toContain('会话摘要 已提供 1');
    expect(wrapper.text()).toContain('用户偏好 无数据 0');
    expect(wrapper.text()).toContain('上下文容量');
    expect(wrapper.text()).toContain('1,234 tokens');
    expect(wrapper.text()).toContain('99.88%');
    expect(wrapper.get('[data-test="knowledge-context-status"]').text()).toContain('容量已刷新');
    expect(wrapper.text()).toContain('记忆层 3');
    expect(wrapper.text()).toContain('Trace trace-context-1');

    const contextTrigger = wrapper.get('[data-test="knowledge-context-trigger"]');
    const contextPopover = wrapper.get('[data-test="knowledge-context-popover"]');
    document.body.appendChild(wrapper.element);
    try {
      expect(contextTrigger.attributes('aria-expanded')).toBe('false');
      expect(contextPopover.attributes('aria-hidden')).toBe('true');
      await contextTrigger.trigger('click');
      expect(contextTrigger.attributes('aria-expanded')).toBe('true');
      expect(contextPopover.attributes('aria-hidden')).toBe('false');
      await contextPopover.trigger('pointerdown');
      expect(contextTrigger.attributes('aria-expanded')).toBe('true');
      await wrapper.get('.knowledge-chat__messages').trigger('pointerdown');
      expect(contextTrigger.attributes('aria-expanded')).toBe('false');
      await contextTrigger.trigger('click');
      (contextPopover.element as HTMLElement).focus();
      await contextPopover.trigger('keydown', { key: 'Escape' });
      await flushPromises();
      expect(contextTrigger.attributes('aria-expanded')).toBe('false');
      expect(contextPopover.attributes('tabindex')).toBe('-1');
      expect(document.activeElement).toBe(contextTrigger.element);
    } finally {
      wrapper.element.remove();
    }

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
      chapterCount: 10,
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
    let callbacks: any;
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementation((_runId, _sequence, streamCallbacks) => {
      callbacks = streamCallbacks;
      return { abort: vi.fn(), result: new Promise<void>(() => undefined) } as never;
    });
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
          resultJson: JSON.stringify({
            traceId: 'trace-progress',
            conversationId: 'conv-progress',
            contextBudget: {
              maxInputTokens: 10000,
              estimatedUsedTokens: 2100,
              remainingTokens: 7900,
              remainingRatio: 0.79,
              compressionThresholdTokens: 9000,
              compressed: true,
            },
          }),
        },
      },
    } as never);

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
    expect(wrapper.get('[data-test="knowledge-process-toggle"]').attributes('aria-expanded')).toBe('true');
    expect(wrapper.get('[data-test="knowledge-process-current"]').text()).toContain('识别写作意图');
    expect(wrapper.get('[data-test="knowledge-process-detail"]').isVisible()).toBe(true);

    callbacks.onEvent({
      runId: 'run-progress',
      sequenceNo: 1,
      eventType: 'CONTEXT_COMPACTING',
      payload: JSON.stringify({
        phase: 'context',
        message: '会话接近模型上下文上限，正在自动压缩',
        contextWindowTokens: 10000,
        thresholdTokens: 9000,
        beforeInputTokens: 9200,
        generation: 1,
      }),
    });
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-context-percent"]').text()).toBe('92%');
    expect(wrapper.get('[data-test="knowledge-context-trigger"]').classes()).toContain('is-compacting');

    callbacks.onEvent({
      runId: 'run-progress',
      sequenceNo: 2,
      eventType: 'CONTEXT_COMPACTED',
      payload: JSON.stringify({
        phase: 'context',
        message: '上下文已自动压缩',
        contextWindowTokens: 10000,
        thresholdTokens: 9000,
        beforeInputTokens: 9200,
        afterInputTokens: 2400,
        generation: 1,
      }),
    });
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-context-percent"]').text()).toBe('24%');
    expect(wrapper.get('[data-test="knowledge-context-status"]').text()).toContain('容量已刷新');

    callbacks.onEvent({
      runId: 'run-progress',
      sequenceNo: 3,
      eventType: 'PROGRESS',
      payload: '{"phase":"review","message":"正在校验回答是否准确完整"}',
    });
    await flushPromises();

    expect(wrapper.get('[data-test="knowledge-process-current"]').text()).toContain('审查回答质量');
    const liveSteps = wrapper.findAll('[data-test="knowledge-process-step"]');
    expect(liveSteps.some((step) => step.text().includes('识别写作意图') && step.classes().includes('is-completed'))).toBe(true);
    expect(liveSteps.some((step) => step.text().includes('审查回答质量') && step.classes().includes('is-running'))).toBe(true);

    await wrapper.get('[data-test="knowledge-process-toggle"]').trigger('click');
    expect(wrapper.get('[data-test="knowledge-process-toggle"]').attributes('aria-expanded')).toBe('false');
    expect(wrapper.find('[data-test="knowledge-process-detail"]').exists()).toBe(false);

    callbacks.onEvent({
      runId: 'run-progress',
      sequenceNo: 4,
      eventType: 'ANSWERED',
      payload: '{"status":"ANSWERED","answer":"最终完整答案[1]"}',
    });
    await flushPromises();

    expect(wrapper.text()).toContain('最终完整答案');
    expect(wrapper.get('[data-test="knowledge-context-percent"]').text()).toBe('21%');
    expect(window.localStorage.getItem('noval:knowledge-chat:draft:v1')).toContain('run-progress');
  });

  test('normalizes raw durable run status before rendering it to users', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-09T08:00:00.000Z'));
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-raw-status',
          conversationId: 'conv-raw-status',
          question: 'deep raw status question',
          status: 'RUNNING',
          startedAt: '2026-08-09T07:58:55.000Z',
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
      expect(wrapper.get('[data-test="knowledge-process-duration"]').text()).toBe('1 分钟 5 秒');

      await vi.advanceTimersByTimeAsync(1_000);
      expect(wrapper.get('[data-test="knowledge-process-duration"]').text()).toBe('1 分钟 6 秒');
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
    expect(window.localStorage.getItem('noval:knowledge-chat:draft:v1')).toContain('run-resume');
  });

  test('renders the candidate picker for a deep-mode run that needs book selection', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      conversationId: 'conv-deep-candidates',
      pendingRunId: 'run-deep-candidates',
      messages: [
        { role: 'user', content: '帮我找找有没有一本书，这个文明有神眷顾' },
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
          runId: 'run-deep-candidates',
          conversationId: 'conv-deep-candidates',
          question: '帮我找找有没有一本书，这个文明有神眷顾',
          status: 'ANSWERED',
          answer: '找到了多个可能的书籍，请选择正确作品后继续。',
          resultJson: JSON.stringify({
            status: 'candidates_required',
            answer: '找到了多个可能的书籍，请选择正确作品后继续。',
            actions: ['select_candidate'],
            sources: [],
            candidates: [
              {
                bookName: '候选甲',
                author: '作者甲',
                platform: 'fanqie',
                platformBookId: 'pa',
                bookUrl: 'https://example.test/a',
                local: false,
                contentType: 'novel',
                readableNovel: true,
              },
              {
                bookName: '候选乙',
                author: '作者乙',
                platform: 'fanqie',
                platformBookId: 'pb',
                bookUrl: 'https://example.test/b',
                local: false,
                contentType: 'audiobook',
                readableNovel: false,
                unavailableReason: 'search_result_is_audiobook',
              },
            ],
            resultJson: {
              traceId: 'trace-deep-candidates',
              conversationId: 'conv-deep-candidates',
              candidateCount: 2,
            },
          }),
          traceId: 'trace-deep-candidates',
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await flushPromises();

    const buttons = wrapper.findAll('[data-test="candidate-select-button"]');
    expect(buttons).toHaveLength(2);
    expect(buttons[0].attributes('disabled')).toBeUndefined();
    expect(buttons[1].attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('候选甲');
    expect(wrapper.text()).toContain('听书结果，暂不支持章节采集');
    expect(wrapper.text()).not.toContain('candidates_required');
  });

  test('restores candidates from a legacy flat durable run payload', async () => {
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      conversationId: 'conv-legacy-candidates',
      pendingRunId: 'run-legacy-candidates',
      messages: [
        { role: 'user', content: '帮我找找这本书' },
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
          runId: 'run-legacy-candidates',
          conversationId: 'conv-legacy-candidates',
          question: '帮我找找这本书',
          status: 'ANSWERED',
          answer: '找到了多个可能的书籍，请选择正确作品后继续。',
          resultJson: JSON.stringify({
            traceId: 'trace-legacy-candidates',
            conversationId: 'conv-legacy-candidates',
            _runStatus: 'candidates_required',
            _actions: ['select_candidate'],
            _sources: [],
            _candidates: [
              {
                bookName: '旧候选',
                platform: 'fanqie',
                platformBookId: 'pl',
                bookUrl: 'https://example.test/l',
                local: false,
                readableNovel: true,
              },
            ],
          }),
          traceId: 'trace-legacy-candidates',
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await flushPromises();

    expect(wrapper.findAll('[data-test="candidate-select-button"]')).toHaveLength(1);
    expect(wrapper.text()).toContain('旧候选');
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
    expect(saved).toContain('run-missing-assistant');
  });

  test('normalizes raw fast response statuses before rendering them to users', async () => {
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
    await flushPromises();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('检查状态中文化');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    callbacks.onStart({ event: 'start', traceId: 'trace-fast-status' });
    await flushPromises();

    expect(wrapper.text()).toContain('正在后台执行');
    expect(wrapper.text()).not.toContain('running');

    const candidatesResponse: KnowledgeChatResponse = {
      status: 'candidates_required',
      answer: '请选择作品',
      candidates: [],
      sources: [],
      actions: [],
      resultJson: {},
    };
    callbacks.onDone({ event: 'done', data: candidatesResponse });
    resolveResult(candidatesResponse);
    await flushPromises();

    expect(wrapper.text()).toContain('需要选择候选项');
    expect(wrapper.text()).not.toContain('candidates_required');
  });

  test('starts a new conversation while a durable run continues in the background', async () => {
    const abortBackgroundStream = vi.fn();
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '并行会话项目' }] },
    } as never);
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-background-new-chat',
          conversationId: 'conv-background-new-chat',
          question: '后台长任务',
          status: 'RUNNING',
          resultJson: '{}',
        },
      },
    } as never);
    vi.mocked(knowledgeApi.streamChatRunEvents).mockReturnValueOnce({
      abort: abortBackgroundStream,
      result: new Promise<void>(() => undefined),
    } as never);
    vi.mocked(knowledgeApi.streamChat).mockReturnValueOnce({
      abort: vi.fn(),
      result: Promise.resolve(finalResponse),
    } as never);

    const wrapper = mountView();
    await flushPromises();
    // 四档模型：第 4 档(高)推导出深度模式，走可持久化运行。
    await wrapper
      .find('[data-test="knowledge-reasoning-effort"] .el-segmented__group label:nth-of-type(4) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('后台长任务');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    const newChatButton = wrapper.find('[data-test="knowledge-new-chat"]');
    expect(newChatButton.attributes('disabled')).toBeUndefined();
    await newChatButton.trigger('click');
    await flushPromises();

    expect(abortBackgroundStream).toHaveBeenCalled();
    expect(knowledgeApi.cancelChatRun).not.toHaveBeenCalled();
    expect(wrapper.text()).not.toContain('后台长任务');
    expect(wrapper.text()).toContain('新会话');
    expect(wrapper.find('[data-test="knowledge-question-input"] textarea').attributes('disabled')).toBeUndefined();

    await wrapper
      .find('[data-test="knowledge-reasoning-effort"] .el-segmented__group label:nth-of-type(1) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('另一个会话的问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      conversationId: 'conv-created',
      projectId: 99,
      question: '另一个会话的问题',
    });
  });

  test('groups the model picker by provider and sends the selected model key', async () => {
    mockModelOptions([DEEPSEEK_MODEL_OPTION, KIMI_MODEL_OPTION, QWEN_MODEL_OPTION]);
    vi.mocked(knowledgeApi.streamChat).mockReturnValueOnce({
      abort: vi.fn(),
      result: Promise.resolve(finalResponse),
    } as never);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-model-picker"]').exists()).toBe(true);
    const groups = wrapper.findAllComponents({ name: 'ElOptionGroup' });
    expect(groups.map((group) => group.props('label'))).toEqual(['DeepSeek', 'Kimi', '通义千问']);

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('默认模型问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).toMatchObject({
      modelKey: 'deepseek-chat',
      reasoningEffort: 'minimal',
      reasoningMode: 'fast',
    });
  });

  test('narrows thinking effort tiers to what the selected provider accepts', async () => {
    mockModelOptions([DEEPSEEK_MODEL_OPTION, KIMI_MODEL_OPTION]);
    const wrapper = mountView();
    await flushPromises();

    const tierLabels = () => wrapper
      .findAll('[data-test="knowledge-reasoning-effort"] .el-segmented__item-label')
      .map((item) => item.text());
    expect(tierLabels()).toEqual(['最小', '低', '中', '高']);

    await wrapper.findComponent({ name: 'ElSelect' }).setValue('kimi-main');
    await flushPromises();

    // Kimi 只有 low/high/max，原来的「最小」不在里面，收敛到最接近的「低」而不是原样送出。
    expect(tierLabels()).toEqual(['低', '高', '最高']);
    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') ?? '{}');
    expect(saved.modelKey).toBe('kimi-main');
    expect(saved.reasoningEffort).toBe('low');
  });

  test('routes a high thinking tier through the durable run and sends the tier verbatim', async () => {
    mockModelOptions([KIMI_MODEL_OPTION]);
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-max-tier',
          conversationId: 'conv-max-tier',
          question: '最高档问题',
          status: 'ANSWERED',
          answer: finalResponse.answer,
          resultJson: '{}',
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await wrapper
      .find('[data-test="knowledge-reasoning-effort"] .el-segmented__group label:nth-of-type(3) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('最高档问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.streamChat).not.toHaveBeenCalled();
    expect(vi.mocked(knowledgeApi.startChatRun).mock.calls[0][0]).toMatchObject({
      modelKey: 'kimi-main',
      reasoningEffort: 'max',
      reasoningMode: 'deep',
    });
  });

  test('exposes the gpt-5.6 exclusive xhigh tier and routes it through the durable run', async () => {
    mockModelOptions([GPT56_MODEL_OPTION]);
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-xhigh-tier',
          conversationId: 'conv-xhigh-tier',
          question: '极高档问题',
          status: 'ANSWERED',
          answer: finalResponse.answer,
          resultJson: '{}',
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    // 六档必须全渲染出来：xhigh 只有这一代报得出来，少一段就等于用户买了档位却选不到。
    expect(wrapper
      .findAll('[data-test="knowledge-reasoning-effort"] .el-segmented__item-label')
      .map((item) => item.text())).toEqual(['最小', '低', '中', '高', '极高', '最高']);

    await wrapper
      .find('[data-test="knowledge-reasoning-effort"] .el-segmented__group label:nth-of-type(5) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('极高档问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.streamChat).not.toHaveBeenCalled();
    expect(vi.mocked(knowledgeApi.startChatRun).mock.calls[0][0]).toMatchObject({
      modelKey: 'gpt-5.6-sol',
      reasoningEffort: 'xhigh',
      reasoningMode: 'deep',
    });
  });

  test('preserves the conversation, history and xhigh selection across durable followups', async () => {
    mockModelOptions([GPT56_MODEL_OPTION]);
    let runSequence = 0;
    vi.mocked(knowledgeApi.startChatRun).mockImplementation(async (payload) => ({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: `run-continuity-${++runSequence}`,
          conversationId: payload.conversationId,
          question: payload.question,
          status: 'ANSWERED',
          answer: 'Synthetic prior answer.',
          resultJson: '{}',
        },
      },
    }) as never);

    const wrapper = mountView();
    await flushPromises();
    await wrapper
      .find('[data-test="knowledge-reasoning-effort"] .el-segmented__group label:nth-of-type(5) input')
      .setValue(true);
    for (const question of ['Synthetic first question.', 'Synthetic followup question.']) {
      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue(question);
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      await flushPromises();
      await flushPromises();
    }

    expect(knowledgeApi.streamChat).not.toHaveBeenCalled();
    const calls = vi.mocked(knowledgeApi.startChatRun).mock.calls;
    expect(calls).toHaveLength(2);
    const first = calls[0][0];
    const second = calls[1][0];
    expect(first.conversationId).toEqual(expect.any(String));
    expect(first.conversationId).toBeTruthy();
    for (const payload of [first, second]) {
      expect(payload).toMatchObject({
        conversationId: first.conversationId,
        modelKey: 'gpt-5.6-sol', reasoningMode: 'deep', reasoningEffort: 'xhigh',
      });
    }
    expect(second.history).toEqual(expect.arrayContaining([
      { role: 'user', content: 'Synthetic first question.' },
      { role: 'assistant', content: 'Synthetic prior answer.' },
    ]));
    expect(second.history?.filter((message) => message.role === 'user' && message.content === second.question)).toHaveLength(1);
  });

  test('renders a thinking switch for providers that only expose an on/off contract', async () => {
    mockModelOptions([QWEN_MODEL_OPTION]);
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-qwen-thinking',
          conversationId: 'conv-qwen-thinking',
          question: '开思考',
          status: 'ANSWERED',
          answer: finalResponse.answer,
          resultJson: '{}',
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-reasoning-effort"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="knowledge-reasoning-toggle"]').exists()).toBe(true);

    await wrapper.findComponent({ name: 'ElSwitch' }).setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('开思考');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    expect(vi.mocked(knowledgeApi.startChatRun).mock.calls[0][0]).toMatchObject({
      modelKey: 'qwen-main',
      reasoningEffort: 'high',
    });
  });

  test('keeps the fast and deep control for models without a reasoning contract', async () => {
    mockModelOptions([CLAUDE_MODEL_OPTION]);
    vi.mocked(knowledgeApi.streamChat).mockReturnValueOnce({
      abort: vi.fn(),
      result: Promise.resolve(finalResponse),
    } as never);

    const wrapper = mountView();
    await flushPromises();

    // Claude 没有 OpenAI 风格的推理档位，档位控件整块隐藏；快速/深度必须留着，
    // 否则这些模型会丢掉可持久化运行的进度和取消能力。
    expect(wrapper.find('[data-test="knowledge-reasoning-effort"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="knowledge-reasoning-toggle"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="knowledge-reasoning-mode"]').exists()).toBe(true);

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('Claude 问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    const payload = vi.mocked(knowledgeApi.streamChat).mock.calls[0][0];
    expect(payload).toMatchObject({ modelKey: 'claude-main', reasoningMode: 'fast' });
    expect(payload.reasoningEffort).toBeUndefined();
  });

  test('hides the model picker when the model list cannot be loaded', async () => {
    vi.mocked(systemConfigApi.getModelOptions).mockRejectedValue(new Error('worker unavailable'));
    vi.mocked(knowledgeApi.streamChat).mockReturnValueOnce({
      abort: vi.fn(),
      result: Promise.resolve(finalResponse),
    } as never);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-model-picker"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="knowledge-reasoning-mode"]').exists()).toBe(true);

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('拿不到模型列表');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    // 模型列表不可用时不能拦住提问：后端仍按注册表默认模型执行。
    const payload = vi.mocked(knowledgeApi.streamChat).mock.calls[0][0];
    expect(payload.modelKey).toBeUndefined();
    expect(payload.reasoningEffort).toBeUndefined();
  });

  test('retries pending run recovery after a transient request failure', async () => {
    vi.useFakeTimers();
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      conversationId: 'conv-retry-resume',
      pendingRunId: 'run-retry-resume',
      messages: [{ role: 'user', content: '恢复重试问题' }],
      status: 'RUNNING',
      reasoningMode: 'deep',
    }));
    vi.mocked(knowledgeApi.getChatRun)
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            runId: 'run-retry-resume',
            conversationId: 'conv-retry-resume',
            status: 'ANSWERED',
            answer: '恢复重试成功',
          },
        },
      } as never);

    try {
      const wrapper = mountView();
      await flushPromises();
      expect(wrapper.text()).toContain('正在恢复后台回答');

      await vi.advanceTimersByTimeAsync(3000);
      await flushPromises();

      expect(wrapper.text()).toContain('恢复重试成功');
      expect(knowledgeApi.getChatRun).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
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

  test('reuses the private compacted summary without rendering its body', async () => {
    const compactedSummary = [
      '【结构化长期上下文】',
      '长期目标：完成三卷网文大纲',
      '内部续接标记：compaction-private-marker',
    ].join('\n');
    let callIndex = 0;
    vi.mocked(knowledgeApi.streamChat).mockImplementation(() => {
      const response: KnowledgeChatResponse = callIndex++ === 0
        ? {
            ...finalResponse,
            answer: '本轮公开回答',
            resultJson: {
              domainIntent: 'outline_generation',
              contextCompaction: {
                status: 'compacted',
                compactedSummary,
              },
            },
          }
        : finalResponse;
      return {
        abort: vi.fn(),
        result: Promise.resolve(response),
      } as never;
    });

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('先压缩旧会话并继续大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    const saved = JSON.parse(window.localStorage.getItem('noval:knowledge-chat:draft:v1') || '{}');
    expect(saved.contextSummary).toContain('compaction-private-marker');
    expect(saved.contextSummary).toContain('最新用户问题：先压缩旧会话并继续大纲');
    expect(saved.contextSummary).toContain('最新回答：本轮公开回答');
    expect(wrapper.text()).not.toContain('compaction-private-marker');

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续下一章');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    const nextPayload = vi.mocked(knowledgeApi.streamChat).mock.calls[1][0];
    expect(nextPayload.contextSummary).toContain('compaction-private-marker');
    expect(wrapper.text()).not.toContain('compaction-private-marker');
  });

  test('quantifies compaction and degradation in the process panel without leaking the summary', async () => {
    vi.mocked(knowledgeApi.streamChat).mockImplementation(() => ({
      abort: vi.fn(),
      result: Promise.resolve({
        ...finalResponse,
        answer: '本轮公开回答',
        resultJson: {
          contextCompaction: {
            status: 'compacted',
            model: 'gpt-5.6-sol',
            contextWindowTokens: 300000,
            thresholdTokens: 240000,
            beforeInputTokens: 262144,
            afterInputTokens: 100944,
            retainedTurnCount: 6,
            summarizedMessageCount: 18,
            generation: 2,
            coverageFingerprint: 'c'.repeat(64),
            compactedSummary: '内部续接标记：panel-private-marker',
          },
          degradationReasons: ['run_token_budget_exceeded', 'run_token_budget_exceeded'],
        },
      } as KnowledgeChatResponse),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('压缩后继续写大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    await wrapper.get('[data-test="knowledge-process-toggle"]').trigger('click');
    await flushPromises();

    const compaction = wrapper.get('[data-test="knowledge-process-compaction"]').text();
    expect(compaction).toContain('上下文已自动压缩');
    expect(compaction).toContain('262,144 → 100,944 tokens');
    expect(compaction).toContain('保留 6 轮');
    expect(wrapper.get('[data-test="knowledge-process-degradation"]').text())
      .toContain('本轮生成预算已达到上限');
    // 压缩正文与覆盖指纹都只用于服务端续接，界面上一个字都不给。
    expect(wrapper.text()).not.toContain('panel-private-marker');
    expect(wrapper.text()).not.toContain('c'.repeat(64));
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

  test('clears stale book identity and restores only the target project references', async () => {
    window.localStorage.setItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY, '7');
    window.localStorage.setItem('noval:knowledge-chat:project:v1:7', JSON.stringify({
      bookName: '旧项目小说',
      selectedCandidate: { bookName: '旧项目小说', local: true },
    }));
    window.localStorage.setItem(`${KNOWLEDGE_REFERENCE_WORK_STORAGE_PREFIX}9`, JSON.stringify([901]));
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { projectId: 7, name: '旧项目' },
          { projectId: 9, name: '新项目' },
        ],
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    expect((wrapper.vm as any).state.bookName).toBe('旧项目小说');

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_PROJECT_CHANGE_EVENT, {
      detail: { projectId: 9, workId: 90, referenceWorkIds: [901] },
    }));
    await flushPromises();

    expect((wrapper.vm as any).state.bookName).toBe('');
    expect((wrapper.vm as any).state.selectedCandidate).toBeNull();
    expect((wrapper.vm as any).state.referenceWorkIds).toEqual([901]);
  });

  test('sends only explicitly selected reference works and omits an empty selection', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '当前项目' }] },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.get('[data-test="knowledge-question-input"] textarea').setValue('先看当前作品');
    await wrapper.get('[data-test="knowledge-send-button"]').trigger('click');
    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[0][0]).not.toHaveProperty('referenceWorkIds');
    resolveResult(finalResponse);
    await flushPromises();

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_PROJECT_CHANGE_EVENT, {
      detail: { projectId: 99, workId: 990, referenceWorkIds: [701, 801] },
    }));
    await flushPromises();
    await wrapper.get('[data-test="knowledge-question-input"] textarea').setValue('结合两本旧作分析');
    await wrapper.get('[data-test="knowledge-send-button"]').trigger('click');
    expect(vi.mocked(knowledgeApi.streamChat).mock.calls[1][0]).toMatchObject({
      projectId: 99,
      workId: 990,
      referenceWorkIds: [701, 801],
    });
  });

  test('does not apply a late new-conversation response after switching projects', async () => {
    let resolveCreate!: (value: any) => void;
    window.localStorage.setItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY, '99');
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { projectId: 99, name: '项目A' },
          { projectId: 42, name: '项目B' },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockImplementation(async (projectId) => ({
      data: {
        code: 200,
        message: 'success',
        data: projectId === 99
          ? [{ conversationId: 'conv-existing', projectId: 99, title: '已有会话', status: 'ACTIVE' }]
          : [],
      },
    }) as never);
    vi.mocked(knowledgeApi.createConversation).mockReturnValue(new Promise((resolve) => {
      resolveCreate = resolve;
    }) as never);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-test="knowledge-new-chat"]').trigger('click');
    window.dispatchEvent(new CustomEvent(KNOWLEDGE_PROJECT_CHANGE_EVENT, {
      detail: { projectId: 42 },
    }));
    resolveCreate({
      data: {
        code: 200,
        message: 'success',
        data: { conversationId: 'conv-late-a', projectId: 99, title: '迟到会话', status: 'ACTIVE' },
      },
    });
    await flushPromises();

    expect(wrapper.text()).not.toContain('迟到会话');
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

  test('copies the raw message body from an inline copy action on both roles', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    window.localStorage.setItem('noval:knowledge-chat:draft:v1', JSON.stringify({
      messages: [
        { role: 'user', content: '原始提问' },
        { role: 'assistant', content: '带引注的回答[1]', status: 'answered', sources: [] },
      ],
      answer: '带引注的回答[1]',
    }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-test="knowledge-copy-message-1"]').trigger('click');
    await flushPromises();

    // 复制的是原始 markdown，不是渲染后的 HTML，[1] 引注要留着。
    expect(writeText).toHaveBeenCalledWith('带引注的回答[1]');
    expect(wrapper.get('[data-test="knowledge-copy-message-1"]').attributes('aria-label')).toBe('已复制');

    await wrapper.get('[data-test="knowledge-copy-message-0"]').trigger('click');
    await flushPromises();

    expect(writeText).toHaveBeenLastCalledWith('原始提问');
  });

  test('stops yanking the reader down mid-stream and offers a back-to-bottom control', async () => {
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
    await flushPromises();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('会滚很长的问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    callbacks.onDelta({ event: 'delta', delta: '第一段 ' });
    await flushPromises();

    const messages = wrapper.get('.knowledge-chat__messages');
    const element = messages.element as HTMLElement;
    // jsdom 没有布局：scrollHeight/clientHeight 恒为 0，scrollTop 的写入还会被直接丢弃。
    // 三个几何量都得自己接管，才能造出"内容超出一屏"的形状并观察置底动作。
    let scrollTop = 0;
    Object.defineProperty(element, 'scrollTop', {
      get: () => scrollTop,
      set: (value: number) => {
        scrollTop = value;
      },
      configurable: true,
    });
    Object.defineProperty(element, 'scrollHeight', { value: 4_000, configurable: true });
    Object.defineProperty(element, 'clientHeight', { value: 600, configurable: true });

    expect(wrapper.find('[data-test="knowledge-scroll-bottom"]').exists()).toBe(false);

    element.scrollTop = 400;
    await messages.trigger('scroll');

    expect(wrapper.find('[data-test="knowledge-scroll-bottom"]').exists()).toBe(true);

    callbacks.onDelta({ event: 'delta', delta: '第二段 ' });
    // 流式回放是 24ms 一跳的打字机，不推时钟增量就写不进 state.messages，watch 也就不会触发。
    await vi.advanceTimersByTimeAsync(200);
    await flushPromises();

    expect(wrapper.text()).toContain('第二段');
    expect(element.scrollTop).toBe(400);
    expect(wrapper.find('[data-test="knowledge-scroll-bottom"]').exists()).toBe(true);

    await wrapper.get('[data-test="knowledge-scroll-bottom"]').trigger('click');
    await flushPromises();

    expect(element.scrollTop).toBe(4_000);
    expect(wrapper.find('[data-test="knowledge-scroll-bottom"]').exists()).toBe(false);

    // 回到底部之后重新跟随流式增量。
    // 回到底部之后重新跟随后续的状态推进（收尾时 loading 落地会再触发一次置底）。
    element.scrollTop = 3_400;
    callbacks.onDone({ event: 'done', data: finalResponse });
    resolveResult(finalResponse);
    await vi.advanceTimersByTimeAsync(2_000);
    await flushPromises();

    expect(element.scrollTop).toBe(4_000);

    // 自己按下发送就该看最新一条，哪怕刚才正翻在半空。
    element.scrollTop = 400;
    await messages.trigger('scroll');
    expect(wrapper.find('[data-test="knowledge-scroll-bottom"]').exists()).toBe(true);

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('追问一句');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await vi.advanceTimersByTimeAsync(200);
    await flushPromises();

    expect(element.scrollTop).toBe(4_000);
    expect(wrapper.find('[data-test="knowledge-scroll-bottom"]').exists()).toBe(false);
    vi.useRealTimers();
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

  test('marks terminal out-of-scope responses as processed', async () => {
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

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('生产验收：输出网文表格');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    const terminalResponse: KnowledgeChatResponse = {
      ...finalResponse,
      status: 'out_of_scope',
      answer: '我只能回答网文创作、作品分析和榜单趋势问题。',
      resultJson: {
        answerStatus: 'needs_data',
        domainIntent: 'out_of_scope',
      },
    };
    callbacks.onDone({ event: 'done', data: terminalResponse });
    resolveResult(terminalResponse);
    await flushPromises();

    const toggle = wrapper.find('[data-test="knowledge-process-toggle"]');
    expect(toggle.text()).toContain('已处理');
    expect(toggle.text()).not.toContain('处理中');
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

  test('shows Chinese project knowledge intent and evidence boundary badges', async () => {
    let resolveResult!: (response: KnowledgeChatResponse) => void;
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, _streamCallbacks) => ({
      abort: vi.fn(),
      result: new Promise<KnowledgeChatResponse>((resolve) => {
        resolveResult = resolve;
      }),
    }) as never);

    const wrapper = mountView();

    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('检查这本书还有哪些伏笔没回收');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    resolveResult({
      ...finalResponse,
      resultJson: {
        answerStatus: 'answered_with_evidence',
        domainIntent: 'project_knowledge_qa',
        answerBoundary: 'project_knowledge',
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('作品知识问答');
    expect(wrapper.text()).toContain('作品知识证据');
    expect(wrapper.text()).not.toContain('project_knowledge_qa');
    expect(wrapper.text()).not.toContain('project_knowledge');
  });

  test('aborts a fast stream and ignores its late callbacks after switching conversations', async () => {
    let callbacks: any;
    let rejectStream!: (reason: Error) => void;
    const abort = vi.fn(() => rejectStream(new Error('aborted')));
    vi.mocked(knowledgeApi.streamChat).mockImplementation((_payload, streamCallbacks) => {
      callbacks = streamCallbacks;
      return {
        abort,
        result: new Promise<KnowledgeChatResponse>((_resolve, reject) => {
          rejectStream = reject;
        }),
      } as never;
    });
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { messageId: 1, conversationId: 'conv-fast-b', projectId: 42, role: 'USER', content: '会话B问题' },
          { messageId: 2, conversationId: 'conv-fast-b', projectId: 42, role: 'ASSISTANT', content: '会话B回答' },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);

    const wrapper = mountView();
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('快速流问题');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_CONVERSATION_SELECT_EVENT, {
      detail: { projectId: 42, conversationId: 'conv-fast-b' },
    }));
    await flushPromises();
    expect(abort).toHaveBeenCalled();

    callbacks.onDelta({ event: 'delta', delta: '迟到快速流' });
    callbacks.onDone({ event: 'done', data: finalResponse });
    await flushPromises();

    expect(wrapper.text()).toContain('会话B回答');
    expect(wrapper.text()).not.toContain('迟到快速流');
  });

  test('restores the latest 20 turns from server messages without localStorage', async () => {
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '长篇项目' }] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          conversationId: 'conv-history',
          projectId: 99,
          title: '主线讨论',
          status: 'ACTIVE',
          lastRunId: 'run-history',
          lastRunStatus: 'ANSWERED',
          messages: [],
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: Array.from({ length: 50 }, (_, index) => ({
          messageId: index + 1,
          conversationId: 'conv-history',
          projectId: 99,
          role: index % 2 === 0 ? 'USER' : 'ASSISTANT',
          content: `server-message-${index + 1}`,
          createdAt: `2026-07-15T00:${String(index).padStart(2, '0')}:00`,
        })),
      },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-history',
          conversationId: 'conv-history',
          status: 'ANSWERED',
          answer: 'server-message-50',
          snapshotSequenceNo: 8,
        },
      },
    } as never);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).not.toContain('server-message-10');
    expect(wrapper.text()).toContain('server-message-11');
    expect(wrapper.text()).toContain('server-message-50');
    expect(wrapper.text()).not.toContain('正在思考');
    expect(wrapper.find('.knowledge-chat__typing').exists()).toBe(false);
    expect(knowledgeApi.listConversationMessages).toHaveBeenCalledWith('conv-history', 99);
  });

  test('keeps multiple turns in one conversation with per-answer expandable process summaries', async () => {
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '多轮项目' }] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          conversationId: 'conv-multi-turn',
          projectId: 99,
          title: '榜单与大纲讨论',
          status: 'ACTIVE',
          lastRunId: 'run-turn-2',
          lastRunStatus: 'ANSWERED',
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { messageId: 1, conversationId: 'conv-multi-turn', projectId: 99, runId: 'run-turn-1', role: 'USER', content: '先看榜单' },
          { messageId: 2, conversationId: 'conv-multi-turn', projectId: 99, runId: 'run-turn-1', role: 'ASSISTANT', content: '第一轮榜单回答' },
          { messageId: 3, conversationId: 'conv-multi-turn', projectId: 99, runId: 'run-turn-2', role: 'USER', content: '再结合我的大纲' },
          { messageId: 4, conversationId: 'conv-multi-turn', projectId: 99, runId: 'run-turn-2', role: 'ASSISTANT', content: '第二轮大纲回答' },
        ],
      },
    } as never);
    const runs = [
      {
        runId: 'run-turn-2',
        conversationId: 'conv-multi-turn',
        status: 'ANSWERED',
        answer: '第二轮大纲回答',
        startedAt: '2026-08-02T10:01:00.000Z',
        finishedAt: '2026-08-02T10:01:02.500Z',
        resultJson: JSON.stringify({
          modelCallSummary: { total: 4 },
          trace: {
            nodes: [
              { name: 'classify_intent', status: 'completed', sequenceNo: 1, durationMs: 90 },
              { name: 'specialist.outline', status: 'completed', sequenceNo: 2, durationMs: 650 },
              { name: 'compose_answer', status: 'completed', sequenceNo: 3, durationMs: 1200 },
              { name: 'review_answer', status: 'completed', sequenceNo: 4, durationMs: 350 },
            ],
          },
        }),
      },
      {
        runId: 'run-turn-1',
        conversationId: 'conv-multi-turn',
        status: 'ANSWERED',
        answer: '第一轮榜单回答',
        startedAt: '2026-08-02T10:00:00.000Z',
        finishedAt: '2026-08-02T10:00:01.500Z',
        resultJson: JSON.stringify({
          providerCalls: [],
          intentDecision: { primaryIntent: 'outline_building' },
          contextBudget: {
            conversationContinuity: {
              historyTotalCount: 6,
              historyIncludedCount: 4,
              historyIncludedChars: 11215,
              historyTruncated: true,
              contextSummaryChars: 18114,
              contextSummaryTruncated: false,
            },
          },
          answerReview: { status: 'passed' },
          trace: {
            providerCalls: [
              {
                node: 'classify_intent',
                model: 'intent-fast-model',
                requestedReasoningMode: 'fast',
                status: 'succeeded',
                durationMs: 80,
                tokenUsed: 31,
                providerRequestCount: 1,
                wireApi: 'responses',
                usage: {
                  inputTokens: 1200,
                  outputTokens: 31,
                  reasoningTokens: 5,
                  cachedInputTokens: 800,
                  totalTokens: 1231,
                },
                requestSummary: {
                  messageCount: 3,
                  roleCounts: { system: 1, user: 1, assistant: 1 },
                  messageChars: 30981,
                  toolSchemaCount: 0,
                  reasoningRequested: false,
                  bodyRedacted: true,
                },
                responseSummary: {
                  outputChars: 512,
                  toolCallCount: 0,
                  emptyResponse: false,
                  bodyRedacted: true,
                },
              },
              {
                node: 'specialist.outline',
                model: 'deepseek-chat',
                requestedReasoningMode: 'fast',
                status: 'succeeded',
                durationMs: 420,
                tokenUsed: 47,
                providerRequestCount: 1,
                attemptIndex: 2,
                profileKeyUsed: 'gateway-standby',
                failureClass: 'HTTP_401',
              },
              {
                node: 'compose_answer',
                model: 'deepseek-chat',
                requestedReasoningMode: 'deep',
                status: 'succeeded',
                durationMs: 900,
                tokenUsed: 620,
                providerRequestCount: 1,
              },
              {
                node: 'review_answer',
                requestedModel: 'C:\\private\\prompt.txt',
                requestedReasoningMode: 'fast',
                status: 'succeeded',
                durationMs: 310,
                tokenUsed: 23,
                providerRequestCount: 1,
              },
            ],
            nodes: [
              { name: 'classify_intent', status: 'completed', sequenceNo: 1, durationMs: 80 },
              { name: 'execute_tools', status: 'completed', sequenceNo: 2, durationMs: 420 },
              { name: 'C:\\private\\prompt.txt', status: 'completed', sequenceNo: 3, durationMs: 1 },
              { name: 'compose_answer', status: 'completed', sequenceNo: 4, durationMs: 900 },
            ],
          },
        }),
      },
    ];
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue({
      data: { code: 200, message: 'success', data: runs },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockImplementation(async (runId) => ({
      data: {
        code: 200,
        message: 'success',
        data: runs.find((run) => run.runId === runId),
      },
    }) as never);
    vi.mocked(knowledgeApi.listChatRunEvents).mockRejectedValueOnce(new Error('events temporarily unavailable'));

    const wrapper = mountView();
    await flushPromises();

    const toggles = wrapper.findAll('[data-test="knowledge-process-toggle"]');
    expect(toggles).toHaveLength(2);
    expect(toggles[0].text()).toContain('已处理');
    expect(toggles[0].text()).toContain('1.5 秒');
    expect(toggles[1].text()).toContain('2.5 秒');
    expect(knowledgeApi.listChatRunEvents).not.toHaveBeenCalled();

    await toggles[0].trigger('click');
    await flushPromises();

    expect(toggles[0].attributes('aria-expanded')).toBe('true');
    expect(knowledgeApi.listChatRunEvents).toHaveBeenCalledWith('run-turn-1', 0, 200);
    expect(wrapper.text()).toContain('识别写作意图');
    expect(wrapper.text()).toContain('检索并调用资料');
    expect(wrapper.text()).toContain('模型调用 4 次');
    expect(wrapper.text()).toContain('模型调用记录');
    const modelCalls = wrapper.findAll('[data-test="knowledge-model-call"]');
    expect(modelCalls).toHaveLength(4);
    expect(modelCalls[0].text()).toContain('意图识别');
    expect(modelCalls[0].text()).toContain('intent-fast-model');
    expect(modelCalls[0].text()).toContain('31 Token');
    expect(modelCalls[0].text()).toContain('Responses API');
    expect(modelCalls[0].find('[data-test="knowledge-model-call-usage"]').text()).toContain('1200');
    expect(modelCalls[0].find('[data-test="knowledge-model-call-usage"]').text()).toContain('31');
    expect(modelCalls[0].find('[data-test="knowledge-model-call-usage"]').text()).toContain('5');
    expect(modelCalls[0].find('[data-test="knowledge-model-call-usage"]').text()).toContain('800');
    expect(modelCalls[0].text()).toContain('80 毫秒');
    expect(modelCalls[0].text()).toContain('快速');
    expect(modelCalls[2].text()).toContain('回答生成');
    expect(modelCalls[2].text()).toContain('深度');
    expect(modelCalls[3].text()).toContain('回答审查');
    // 换 key 之后才成功的那次调用要能看出轨迹；正常调用不该多出这一行。
    expect(modelCalls[1].find('[data-test="knowledge-model-call-failover"]').text())
      .toBe('第 2 次尝试 · 使用 gateway-standby · 凭证被拒 401');
    expect(modelCalls[0].find('[data-test="knowledge-model-call-failover"]').exists()).toBe(false);
    expect(modelCalls[0].text()).toContain('3');
    expect(modelCalls[0].text()).toContain('30981');
    expect(modelCalls[0].text()).toContain('512');
    expect(modelCalls[0].text()).toContain('正文已省略');
    const processSummaries = wrapper.findAll('[data-test="knowledge-process-summary"]');
    expect(processSummaries).toHaveLength(4);
    expect(processSummaries[0].text()).toContain('任务判断');
    expect(processSummaries[1].text()).toContain('4/6');
    expect(processSummaries[1].text()).toContain('11215');
    expect(processSummaries[2].text()).toContain('模型执行');
    expect(processSummaries[3].text()).toContain('质量审查');
    expect(processSummaries[3].text()).toContain('未触发修订');
    expect(wrapper.text()).not.toContain('private');
    expect(wrapper.text()).not.toContain('prompt.txt');
  });

  test('surfaces prefix cache evidence, routed model and prefix fingerprint from a finished run', async () => {
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '缓存排查项目' }] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          conversationId: 'conv-cache',
          projectId: 99,
          title: '缓存命中排查',
          status: 'ACTIVE',
          lastRunId: 'run-cache-2',
          lastRunStatus: 'ANSWERED',
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { messageId: 1, conversationId: 'conv-cache', projectId: 99, runId: 'run-cache-1', role: 'USER', content: '第一问' },
          { messageId: 2, conversationId: 'conv-cache', projectId: 99, runId: 'run-cache-1', role: 'ASSISTANT', content: '第一答' },
          { messageId: 3, conversationId: 'conv-cache', projectId: 99, runId: 'run-cache-2', role: 'USER', content: '第二问' },
          { messageId: 4, conversationId: 'conv-cache', projectId: 99, runId: 'run-cache-2', role: 'ASSISTANT', content: '第二答' },
        ],
      },
    } as never);
    const runs = [
      {
        runId: 'run-cache-2',
        conversationId: 'conv-cache',
        status: 'ANSWERED',
        answer: '第二答',
        startedAt: '2026-09-02T10:01:00.000Z',
        finishedAt: '2026-09-02T10:01:02.000Z',
        resultJson: JSON.stringify({
          modelCallSummary: {
            total: 2,
            promptCache: {
              calls: 2,
              reportingCalls: 1,
              measured: true,
              hitTokens: 4096,
              missTokens: 1024,
              hitRatioPercent: 80,
            },
          },
          providerCalls: [
            {
              node: 'compose_answer',
              model: 'gpt-5.6-sol',
              status: 'succeeded',
              durationMs: 1200,
              providerRequestCount: 1,
              usageReported: true,
              cacheUsageReported: true,
              routedModel: 'deepseek-v4-flash',
              modelSubstituted: true,
              usage: {
                inputTokens: 5120,
                outputTokens: 240,
                promptCacheHitTokens: 4096,
                promptCacheMissTokens: 1024,
                usageReported: true,
                cacheUsageReported: true,
              },
              requestSummary: {
                messageCount: 4,
                messageChars: 69627,
                toolSchemaCount: 0,
                reasoningRequested: true,
                bodyRedacted: true,
                cacheAffinityPresent: true,
                cachePrefixChars: 69627,
                cachePrefixFingerprint: 'b'.repeat(64),
              },
            },
            {
              node: 'review_answer',
              model: 'gpt-5.6-sol',
              status: 'succeeded',
              durationMs: 310,
              providerRequestCount: 1,
              usageReported: false,
              cacheUsageReported: false,
              // 中继什么都没回时 _usage_summary 依然补 0，映射层必须把这些 0 折掉。
              usage: {
                inputTokens: 0,
                outputTokens: 0,
                promptCacheHitTokens: 0,
                promptCacheMissTokens: 0,
                usageReported: false,
                cacheUsageReported: false,
              },
            },
          ],
        }),
      },
      {
        runId: 'run-cache-1',
        conversationId: 'conv-cache',
        status: 'ANSWERED',
        answer: '第一答',
        startedAt: '2026-09-02T10:00:00.000Z',
        finishedAt: '2026-09-02T10:00:01.000Z',
        // 这一轮没有会话级 promptCache，只能从带上报标志的调用里重算。
        resultJson: JSON.stringify({
          modelCallSummary: { total: 2 },
          providerCalls: [
            {
              node: 'compose_answer',
              model: 'deepseek-chat',
              status: 'succeeded',
              durationMs: 900,
              providerRequestCount: 1,
              usage: {
                inputTokens: 1000,
                outputTokens: 120,
                promptCacheHitTokens: 300,
                promptCacheMissTokens: 700,
                usageReported: true,
                cacheUsageReported: true,
              },
            },
            {
              node: 'review_answer',
              model: 'deepseek-chat',
              status: 'succeeded',
              durationMs: 200,
              providerRequestCount: 1,
              usage: { inputTokens: 400, outputTokens: 20, usageReported: true },
            },
          ],
        }),
      },
    ];
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue({
      data: { code: 200, message: 'success', data: runs },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockImplementation(async (runId) => ({
      data: {
        code: 200,
        message: 'success',
        data: runs.find((run) => run.runId === runId),
      },
    }) as never);

    const wrapper = mountView();
    await flushPromises();

    const toggles = wrapper.findAll('[data-test="knowledge-process-toggle"]');
    expect(toggles).toHaveLength(2);

    await toggles[0].trigger('click');
    await flushPromises();

    // 没有会话级汇总时，比率只从上报过的那一刀算出来：300/(300+700)。
    const legacyCache = wrapper.get('[data-test="knowledge-prompt-cache"]');
    expect(legacyCache.text()).toContain('前缀缓存命中率 30%');
    expect(legacyCache.text()).toContain('1/2 次上报');

    await toggles[1].trigger('click');
    await flushPromises();

    const cacheLines = wrapper.findAll('[data-test="knowledge-prompt-cache"]');
    expect(cacheLines).toHaveLength(2);
    expect(cacheLines[1].text()).toContain('前缀缓存命中率 80%');
    expect(cacheLines[1].text()).toContain('1/2 次上报');
    expect(cacheLines[1].text()).toContain('命中 4096 / 未命中 1024');

    const modelCalls = wrapper.findAll('[data-test="knowledge-model-call"]');
    expect(modelCalls).toHaveLength(4);
    const routed = modelCalls[2].get('[data-test="knowledge-model-call-routed"]');
    expect(routed.text()).toBe('实际路由 deepseek-v4-flash');
    const usage = modelCalls[2].get('[data-test="knowledge-model-call-usage"]').text();
    expect(usage).toContain('缓存命中 4096');
    expect(usage).toContain('未命中 1024');
    const prefix = modelCalls[2].get('[data-test="knowledge-model-call-cache-prefix"]').text();
    expect(prefix).toContain('缓存前缀 69627 字符');
    expect(prefix).toContain('带缓存亲和键');
    expect(prefix).toContain('前缀指纹 bbbbbbbb');
    // 完整指纹是哈希，页面上只留够比对的前 8 位。
    expect(wrapper.text()).not.toContain('b'.repeat(64));
    // 上游一个用量字段都没回的那一刀，不能显示成"上下文 0 / 命中 0"。
    expect(modelCalls[3].get('[data-test="knowledge-model-call-usage"]').text()).toBe('用量未上报');
    expect(modelCalls[3].find('[data-test="knowledge-model-call-routed"]').exists()).toBe(false);
    expect(modelCalls[0].find('[data-test="knowledge-model-call-routed"]').exists()).toBe(false);
  });

  test('restores server conversation when browser storage is unavailable', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '无本地缓存项目' }] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{ conversationId: 'conv-storage-free', projectId: 99, title: '服务端会话', status: 'ACTIVE' }],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationMessages).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { messageId: 1, conversationId: 'conv-storage-free', projectId: 99, role: 'USER', content: '服务端问题' },
          { messageId: 2, conversationId: 'conv-storage-free', projectId: 99, role: 'ASSISTANT', content: '服务端回答' },
        ],
      },
    } as never);

    try {
      const wrapper = mountView();
      await flushPromises();

      expect(wrapper.text()).toContain('服务端会话');
      expect(wrapper.text()).toContain('服务端回答');
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
      removeItem.mockRestore();
    }
  });

  test('lists project conversations and switches the complete server history', async () => {
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '多会话项目' }] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { conversationId: 'conv-a', projectId: 99, title: '大纲讨论', status: 'ACTIVE', messages: [] },
          { conversationId: 'conv-b', projectId: 99, title: '人物讨论', status: 'ACTIVE', messages: [] },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationMessages).mockImplementation(async (conversationId) => ({
      data: {
        code: 200,
        message: 'success',
        data: [
          { messageId: 1, conversationId, projectId: 99, role: 'USER', content: `${conversationId}-question` },
          { messageId: 2, conversationId, projectId: 99, role: 'ASSISTANT', content: `${conversationId}-answer` },
        ],
      },
    }) as never);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-test="knowledge-current-conversation"]').text()).toBe('大纲讨论');
    expect(wrapper.text()).not.toContain('人物讨论');
    expect(wrapper.text()).toContain('conv-a-answer');

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_CONVERSATION_SELECT_EVENT, {
      detail: { projectId: 99, conversationId: 'conv-b' },
    }));
    await flushPromises();

    expect(wrapper.text()).not.toContain('conv-a-answer');
    expect(wrapper.text()).toContain('conv-b-answer');
    expect(wrapper.find('[data-test="knowledge-current-conversation"]').text()).toBe('人物讨论');
  });

  test('keeps a server run in background without leaking stale events into another conversation', async () => {
    let firstCallbacks: any;
    const firstAbort = vi.fn();
    vi.mocked(knowledgeApi.listProjects).mockResolvedValue({
      data: { code: 200, message: 'success', data: [{ projectId: 99, name: '后台任务项目' }] },
    } as never);
    vi.mocked(knowledgeApi.listConversations).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          { conversationId: 'conv-bg-a', projectId: 99, title: '执行中的会话', status: 'ACTIVE', lastRunId: 'run-bg-a' },
          { conversationId: 'conv-bg-b', projectId: 99, title: '另一个会话', status: 'ACTIVE' },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listConversationMessages).mockImplementation(async (conversationId) => ({
      data: {
        code: 200,
        message: 'success',
        data: conversationId === 'conv-bg-a'
          ? [{ messageId: 1, conversationId, projectId: 99, role: 'USER', content: '后台问题' }]
          : [
              { messageId: 2, conversationId, projectId: 99, role: 'USER', content: '会话B问题' },
              { messageId: 3, conversationId, projectId: 99, role: 'ASSISTANT', content: '会话B回答' },
            ],
      },
    }) as never);
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.getChatRun)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            runId: 'run-bg-a',
            conversationId: 'conv-bg-a',
            status: 'RUNNING',
            answer: '后台部分回答',
            snapshotSequenceNo: 2,
          },
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            runId: 'run-bg-a',
            conversationId: 'conv-bg-a',
            status: 'ANSWERED',
            answer: '后台最终回答',
            snapshotSequenceNo: 5,
          },
        },
      } as never);
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementation((_runId, _sequence, callbacks) => {
      firstCallbacks ??= callbacks;
      return { abort: firstAbort, result: new Promise<void>(() => undefined) } as never;
    });

    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('后台部分回答');

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_CONVERSATION_SELECT_EVENT, {
      detail: { projectId: 99, conversationId: 'conv-bg-b' },
    }));
    await flushPromises();
    expect(firstAbort).toHaveBeenCalled();
    expect(wrapper.text()).toContain('会话B回答');

    firstCallbacks.onEvent({
      runId: 'run-bg-a',
      sequenceNo: 3,
      eventType: 'DELTA',
      payload: '{"delta":"迟到污染"}',
    });
    await flushPromises();
    expect(wrapper.text()).not.toContain('迟到污染');

    window.dispatchEvent(new CustomEvent(KNOWLEDGE_CONVERSATION_SELECT_EVENT, {
      detail: { projectId: 99, conversationId: 'conv-bg-a' },
    }));
    await flushPromises();
    expect(wrapper.text()).toContain('后台最终回答');
  });

  test('ignores an older run snapshot after a newer delta was replayed', async () => {
    let callbacks: any;
    let closeStream!: () => void;
    let streamCount = 0;
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementation((_runId, _sequence, streamCallbacks) => {
      callbacks = streamCallbacks;
      streamCount++;
      return {
        abort: vi.fn(),
        result: streamCount === 1
          ? new Promise<void>((resolve) => { closeStream = resolve; })
          : new Promise<void>(() => undefined),
      } as never;
    });
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-stale-snapshot',
          conversationId: 'conv-stale-snapshot',
          status: 'RUNNING',
          answer: '快照',
          snapshotSequenceNo: 2,
        },
      },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-stale-snapshot',
          conversationId: 'conv-stale-snapshot',
          status: 'RUNNING',
          answer: '快照',
          snapshotSequenceNo: 2,
        },
      },
    } as never);

    const wrapper = mountView();
    await wrapper
      .find('[data-test="knowledge-reasoning-mode"] .el-segmented__group label:nth-of-type(2) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('快照竞态');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    callbacks.onEvent({
      runId: 'run-stale-snapshot',
      sequenceNo: 3,
      eventType: 'DELTA',
      payload: '{"delta":"增量"}',
    });
    closeStream();
    await flushPromises();

    expect(wrapper.text()).toContain('快照增量');
    expect(wrapper.text()).not.toContain('快照快照增量');
  });

  test('ignores a slower conversation response after the user switches again', async () => {
    let resolveA!: (value: any) => void;
    let resolveB!: (value: any) => void;
    vi.mocked(knowledgeApi.listConversationMessages).mockImplementation((conversationId) => {
      return new Promise((resolve) => {
        if (conversationId === 'conv-race-a') {
          resolveA = resolve;
        } else {
          resolveB = resolve;
        }
      }) as never;
    });
    vi.mocked(knowledgeApi.listConversationRuns).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);

    const wrapper = mountView();
    await flushPromises();
    window.dispatchEvent(new CustomEvent(KNOWLEDGE_CONVERSATION_SELECT_EVENT, {
      detail: { projectId: 42, conversationId: 'conv-race-a' },
    }));
    window.dispatchEvent(new CustomEvent(KNOWLEDGE_CONVERSATION_SELECT_EVENT, {
      detail: { projectId: 42, conversationId: 'conv-race-b' },
    }));

    resolveB({
      data: {
        code: 200,
        message: 'success',
        data: [
          { messageId: 2, conversationId: 'conv-race-b', projectId: 42, role: 'ASSISTANT', content: '会话B最新回答' },
        ],
      },
    });
    await flushPromises();
    resolveA({
      data: {
        code: 200,
        message: 'success',
        data: [
          { messageId: 1, conversationId: 'conv-race-a', projectId: 42, role: 'ASSISTANT', content: '会话A迟到回答' },
        ],
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('会话B最新回答');
    expect(wrapper.text()).not.toContain('会话A迟到回答');
  });

  test('resumes durable answer from the persisted event sequence without duplicating snapshot text', async () => {
    let callbacks: any;
    let resolveStream!: () => void;
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementation((_runId, _afterSequence, streamCallbacks) => {
      callbacks = streamCallbacks;
      return {
        abort: vi.fn(),
        result: new Promise<void>((resolve) => {
          resolveStream = resolve;
        }),
      } as never;
    });
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-sequence',
          conversationId: 'conv-sequence',
          status: 'RUNNING',
          answer: '快照',
          snapshotSequenceNo: 2,
        },
      },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-sequence',
          conversationId: 'conv-sequence',
          status: 'ANSWERED',
          answer: '快照增量',
          resultJson: '{}',
          snapshotSequenceNo: 4,
        },
      },
    } as never);

    const wrapper = mountView();
    await wrapper
      .find('[data-test="knowledge-reasoning-mode"] .el-segmented__group label:nth-of-type(2) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('继续大纲');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.streamChatRunEvents).toHaveBeenCalledWith(
      'run-sequence',
      2,
      expect.any(Object),
    );

    callbacks.onEvent({
      eventId: 3,
      runId: 'run-sequence',
      sequenceNo: 3,
      eventType: 'DELTA',
      payload: '{"delta":"增量"}',
    });
    callbacks.onEvent({
      eventId: 4,
      runId: 'run-sequence',
      sequenceNo: 4,
      eventType: 'ANSWERED',
      payload: '{"status":"ANSWERED","answer":"快照增量"}',
    });
    resolveStream();
    await flushPromises();

    expect(wrapper.text()).toContain('快照增量');
    expect(wrapper.text()).not.toContain('快照快照增量');
  });

  test('pauses polling while hidden and reconnects SSE from the last sequence when visible', async () => {
    vi.useFakeTimers();
    const originalVisibility = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
    let visibilityState: DocumentVisibilityState = 'hidden';
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibilityState,
    });
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementation(() => ({
      abort: vi.fn(),
      result: Promise.reject(new Error('stream unavailable')),
    }) as never);
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runId: 'run-hidden',
          conversationId: 'conv-hidden',
          status: 'RUNNING',
          snapshotSequenceNo: 5,
        },
      },
    } as never);

    try {
      const wrapper = mountView();
      await wrapper
        .find('[data-test="knowledge-reasoning-mode"] .el-segmented__group label:nth-of-type(2) input')
        .setValue(true);
      await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('后台继续');
      await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
      await flushPromises();
      await vi.advanceTimersByTimeAsync(10_000);

      expect(knowledgeApi.getChatRun).not.toHaveBeenCalledWith('run-hidden');

      visibilityState = 'visible';
      document.dispatchEvent(new Event('visibilitychange'));
      await flushPromises();

      expect(knowledgeApi.streamChatRunEvents).toHaveBeenLastCalledWith(
        'run-hidden',
        5,
        expect.any(Object),
      );
    } finally {
      if (originalVisibility) {
        Object.defineProperty(document, 'visibilityState', originalVisibility);
      }
      vi.useRealTimers();
    }
  });

  test('requests cancellation and keeps tracking until the terminal cancelled state', async () => {
    let callbacks: any;
    vi.mocked(knowledgeApi.streamChatRunEvents).mockImplementation((_runId, _afterSequence, streamCallbacks) => {
      callbacks = streamCallbacks;
      return { abort: vi.fn(), result: new Promise<void>(() => undefined) } as never;
    });
    vi.mocked(knowledgeApi.startChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { runId: 'run-cancel', conversationId: 'conv-cancel', status: 'RUNNING' },
      },
    } as never);
    vi.mocked(knowledgeApi.cancelChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { runId: 'run-cancel', conversationId: 'conv-cancel', status: 'CANCELLING' },
      },
    } as never);
    vi.mocked(knowledgeApi.getChatRun).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { runId: 'run-cancel', conversationId: 'conv-cancel', status: 'CANCELLED' },
      },
    } as never);

    const wrapper = mountView();
    await wrapper
      .find('[data-test="knowledge-reasoning-mode"] .el-segmented__group label:nth-of-type(2) input')
      .setValue(true);
    await wrapper.find('[data-test="knowledge-question-input"] textarea').setValue('取消测试');
    await wrapper.find('[data-test="knowledge-send-button"]').trigger('click');
    await flushPromises();

    await wrapper.find('[data-test="knowledge-cancel-run"]').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('正在取消后台回答');

    callbacks.onEvent({
      eventId: 1,
      runId: 'run-cancel',
      sequenceNo: 1,
      eventType: 'CANCELLED',
      payload: '{"status":"CANCELLED"}',
    });
    await flushPromises();

    expect(knowledgeApi.cancelChatRun).toHaveBeenCalledWith('run-cancel');
    expect(wrapper.text()).toContain('后台回答已取消');
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
