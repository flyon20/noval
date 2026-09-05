import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import KnowledgeMessageBubble from '../KnowledgeMessageBubble.vue';
import type { KnowledgeRunProcess } from '@/types/knowledge';

function mountBubble(process: KnowledgeRunProcess) {
  return mount(KnowledgeMessageBubble, {
    props: {
      role: 'assistant',
      content: '已脱敏的回答内容',
      process,
    },
    global: {
      plugins: [ElementPlus],
    },
  });
}

describe('KnowledgeMessageBubble elapsed process time', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-09T08:00:00.000Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  test('updates an active reconnect-safe timer locally once per second', async () => {
    const wrapper = mountBubble({
      status: 'processing',
      startedAtMs: Date.now() - 65_000,
      currentStep: {
        id: 'context',
        label: '整理会话上下文',
        status: 'running',
      },
      steps: [],
      loaded: false,
    });

    expect(wrapper.get('[data-test="knowledge-process-duration"]').text()).toBe('1 分钟 5 秒');
    expect(wrapper.get('[data-test="knowledge-process-current"]').attributes('aria-live')).toBe('polite');

    await vi.advanceTimersByTimeAsync(1_000);
    await nextTick();

    expect(wrapper.get('[data-test="knowledge-process-duration"]').text()).toBe('1 分钟 6 秒');
  });

  test('freezes elapsed time after the task reaches a terminal state', async () => {
    const startedAtMs = Date.now() - 65_000;
    const wrapper = mountBubble({
      status: 'processing',
      startedAtMs,
      steps: [],
      loaded: false,
    });

    await wrapper.setProps({
      process: {
        status: 'processed',
        startedAtMs,
        finishedAtMs: Date.now(),
        steps: [],
        loaded: true,
      },
    });
    expect(wrapper.get('[data-test="knowledge-process-duration"]').text()).toBe('1 分钟 5 秒');

    await vi.advanceTimersByTimeAsync(5_000);
    await nextTick();

    expect(wrapper.get('[data-test="knowledge-process-duration"]').text()).toBe('1 分钟 5 秒');
  });
});

describe('KnowledgeMessageBubble prompt cache evidence', () => {
  async function openDetail(process: KnowledgeRunProcess) {
    const wrapper = mountBubble(process);
    await wrapper.get('[data-test="knowledge-process-toggle"]').trigger('click');
    await nextTick();
    return wrapper;
  }

  test('says the cache usage was never reported instead of showing a fabricated 0%', async () => {
    const wrapper = await openDetail({
      status: 'processed',
      steps: [],
      loaded: true,
      modelCallCount: 2,
      promptCache: {
        calls: 2,
        reportingCalls: 0,
        measured: false,
        hitTokens: 0,
        missTokens: 0,
        hitRatioPercent: null,
      },
    });

    const promptCache = wrapper.get('[data-test="knowledge-prompt-cache"]');
    expect(promptCache.text()).toContain('前缀缓存未上报');
    expect(promptCache.text()).toContain('2 次调用');
    expect(promptCache.text()).not.toContain('%');
  });

  test('shows the measured ratio, the routed model and the prefix evidence', async () => {
    const wrapper = await openDetail({
      status: 'processed',
      steps: [],
      loaded: true,
      modelCallCount: 1,
      promptCache: {
        calls: 2,
        reportingCalls: 1,
        measured: true,
        hitTokens: 800,
        missTokens: 200,
        hitRatioPercent: 80,
      },
      modelCalls: [{
        id: 'model-call-0',
        label: '回答生成',
        model: 'gpt-5.6-sol',
        status: 'succeeded',
        routedModel: 'deepseek-v4-flash',
        modelSubstituted: true,
        cacheUsageReported: true,
        usage: {
          inputTokens: 1200,
          outputTokens: 31,
          promptCacheHitTokens: 0,
          promptCacheMissTokens: 1200,
          cacheUsageReported: true,
        },
        requestSummary: {
          messageCount: 3,
          messageChars: 69627,
          toolSchemaCount: 0,
          reasoningRequested: true,
          bodyRedacted: true,
          cacheAffinityPresent: true,
          cachePrefixChars: 69627,
          cachePrefixFingerprint: 'a'.repeat(64),
        },
      }],
    });

    expect(wrapper.get('[data-test="knowledge-prompt-cache"]').text()).toContain('前缀缓存命中率 80%');
    expect(wrapper.get('[data-test="knowledge-prompt-cache"]').text()).toContain('1/2 次上报');
    expect(wrapper.get('[data-test="knowledge-model-call-routed"]').text()).toBe('实际路由 deepseek-v4-flash');
    // 上游确实回报过缓存用量，所以 0 命中是结论，必须显示出来。
    const usage = wrapper.get('[data-test="knowledge-model-call-usage"]').text();
    expect(usage).toContain('缓存命中 0');
    expect(usage).toContain('未命中 1200');
    const prefix = wrapper.get('[data-test="knowledge-model-call-cache-prefix"]').text();
    expect(prefix).toContain('缓存前缀 69627 字符');
    expect(prefix).toContain('带缓存亲和键');
    // 指纹只露前 8 位：够比对两次调用是否同一前缀，又不像一串哈希堆在页面上。
    expect(prefix).toContain('前缀指纹 aaaaaaaa');
    expect(prefix).not.toContain('a'.repeat(64));
  });

  test('marks a call whose upstream returned no usage at all', async () => {
    const wrapper = await openDetail({
      status: 'processed',
      steps: [],
      loaded: true,
      modelCalls: [{
        id: 'model-call-0',
        label: '回答生成',
        model: 'gpt-5.6-sol',
        status: 'succeeded',
        usage: {},
      }],
    });

    expect(wrapper.get('[data-test="knowledge-model-call-usage"]').text()).toBe('用量未上报');
    expect(wrapper.find('[data-test="knowledge-prompt-cache"]').exists()).toBe(false);
  });
});

describe('KnowledgeMessageBubble context compaction disclosure', () => {
  async function openDetail(process: KnowledgeRunProcess) {
    const wrapper = mountBubble(process);
    await wrapper.get('[data-test="knowledge-process-toggle"]').trigger('click');
    await nextTick();
    return wrapper;
  }

  test('quantifies how much the context shrank instead of only saying it compacted', async () => {
    const wrapper = await openDetail({
      status: 'processed',
      steps: [],
      loaded: true,
      contextCompaction: {
        status: 'compacted',
        model: 'gpt-5.6-sol',
        contextWindowTokens: 300_000,
        thresholdTokens: 240_000,
        beforeInputTokens: 262_144,
        afterInputTokens: 100_944,
        retainedTurnCount: 6,
        summarizedMessageCount: 18,
        generation: 2,
      },
    });

    const compaction = wrapper.get('[data-test="knowledge-process-compaction"]').text();
    expect(compaction).toContain('上下文已自动压缩');
    expect(compaction).toContain('阈值 240,000');
    expect(compaction).toContain('262,144 → 100,944 tokens');
    expect(compaction).toContain('保留 6 轮');
    expect(compaction).toContain('摘要 18 条');
    expect(compaction).toContain('第 2 代');
  });

  test('distinguishes a reused summary from a fresh compaction', async () => {
    const wrapper = await openDetail({
      status: 'processed',
      steps: [],
      loaded: true,
      contextCompaction: { status: 'reused', reusedMessageCount: 12, generation: 3 },
    });

    const compaction = wrapper.get('[data-test="knowledge-process-compaction"]').text();
    expect(compaction).toContain('复用上一代压缩摘要');
    expect(compaction).not.toContain('上下文已自动压缩');
  });

  test('never leaks the compacted prose or the coverage fingerprint', async () => {
    // worker 顶层那份对象还带着 compactedSummary（压缩后的会话正文）和 coverageFingerprint。
    // 映射层已经挡掉，这里再钉一次组件层：脏对象进来也不能被整体铺开渲染。
    const dirty = {
      status: 'compacted',
      beforeInputTokens: 262_144,
      afterInputTokens: 100_944,
      compactedSummary: '内部策略细节与真实书名',
      coverageFingerprint: 'f'.repeat(64),
    } as unknown as NonNullable<KnowledgeRunProcess['contextCompaction']>;

    const wrapper = await openDetail({
      status: 'processed',
      steps: [],
      loaded: true,
      contextCompaction: dirty,
    });

    expect(wrapper.get('[data-test="knowledge-process-compaction"]').text())
      .toContain('262,144 → 100,944 tokens');
    expect(wrapper.html()).not.toContain('内部策略细节与真实书名');
    expect(wrapper.html()).not.toContain('f'.repeat(64));
  });

  test('reserves no row when nothing was compacted and nothing degraded', async () => {
    const wrapper = await openDetail({
      status: 'processed',
      steps: [],
      loaded: true,
      modelCallCount: 1,
    });

    expect(wrapper.find('[data-test="knowledge-process-compaction"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="knowledge-process-degradation"]').exists()).toBe(false);
  });
});

describe('KnowledgeMessageBubble degradation disclosure inside the process panel', () => {
  async function openDetail(props: Record<string, unknown>) {
    const wrapper = mount(KnowledgeMessageBubble, {
      props: { role: 'assistant', content: '已脱敏的回答内容', ...props },
      global: { plugins: [ElementPlus] },
    });
    await wrapper.get('[data-test="knowledge-process-toggle"]').trigger('click');
    await nextTick();
    return wrapper;
  }

  test('labels every known reason once and never prints the raw code', async () => {
    const wrapper = await openDetail({
      process: {
        status: 'processed',
        steps: [],
        loaded: true,
        degradationReasons: [
          'run_token_budget_exceeded',
          'evidence_commit_rejected',
          'run_token_budget_exceeded',
          'some_future_code_we_never_shipped',
        ],
      } satisfies KnowledgeRunProcess,
    });

    const degradation = wrapper.get('[data-test="knowledge-process-degradation"]').text();
    expect(degradation).toContain('本轮生成预算已达到上限');
    expect(degradation).toContain('证据入库被拒绝，本轮结论未落库');
    expect(degradation).toContain('系统能力暂时降级');
    expect(degradation).not.toContain('run_token_budget_exceeded');
    expect(degradation).not.toContain('some_future_code_we_never_shipped');
    // 去重之后同一条文案只出现一次。
    expect(degradation.split('本轮生成预算已达到上限')).toHaveLength(2);
  });

  test('falls back to the message-level reasons when resultJson has not landed yet', async () => {
    const wrapper = await openDetail({
      degraded: true,
      degradationReasons: ['provider_exception'],
      process: { status: 'processed', steps: [], loaded: true } satisfies KnowledgeRunProcess,
    });

    expect(wrapper.get('[data-test="knowledge-process-degradation"]').text())
      .toContain('模型服务暂时异常');
  });
});
