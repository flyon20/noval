import { mount } from '@vue/test-utils';
import AnalysisContextBar from '@/components/analysis/AnalysisContextBar.vue';
import AnalysisEmptyState from '@/components/analysis/AnalysisEmptyState.vue';
import AnalysisModeTabs from '@/components/analysis/AnalysisModeTabs.vue';
import AnalysisResultCard from '@/components/analysis/AnalysisResultCard.vue';
import AnalysisToolbar from '@/components/analysis/AnalysisToolbar.vue';

describe('AnalysisResultCard', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  test('renders skeleton during preparing phase', () => {
    const wrapper = mount(AnalysisResultCard, {
      props: {
        phase: 'preparing',
      },
    });

    expect(wrapper.get('[data-test="analysis-result-card"]').attributes('data-phase')).toBe('preparing');
    expect(wrapper.find('.analysis-result__status').text()).toContain('分析');
  });

  test('shows streaming delta text', () => {
    const wrapper = mount(AnalysisResultCard, {
      props: {
        phase: 'streaming',
        streamingText: '第一段输出',
      },
    });

    expect(wrapper.text()).toContain('第一段输出');
    expect(wrapper.find('.analysis-result__cursor').exists()).toBe(true);
  });

  test('flushes streaming text immediately once the chunk arrives', async () => {
    const wrapper = mount(AnalysisResultCard, {
      props: {
        phase: 'streaming',
        streamingText: '',
      },
    });

    await wrapper.setProps({
      streamingText: 'abcdefghijklmnopqrstuvwxyz',
    });
    expect(wrapper.text()).toContain('abcdefghijklmnopqrstuvwxyz');
  });

  test('does not expose raw analysis progress markers inside preserved partial text after an error', () => {
    const wrapper = mount(AnalysisResultCard, {
      props: {
        phase: 'error',
        errorMessage: 'internal server error',
        streamingText: '[analysis-progress] 正在分析中，请稍候...\n[analysis-progress] 正在分析中，请稍候...\n# 正文片段',
      },
    });

    expect(wrapper.text()).toContain('正文片段');
    expect(wrapper.text()).not.toContain('[analysis-progress]');
  });

  test('displays markdown result when done', () => {
    const result = '# 生成成功\n- 第一条\n```ts\nconsole.log("ok")\n```';
    const wrapper = mount(AnalysisResultCard, {
      props: {
        phase: 'done',
        resultContent: result,
        resultMeta: {
          analysisModeLabel: '分析方式：分段汇总 · 4 段',
          analysisDetailLabel: '章节数：10 · 分段数：4',
          traceId: 'trace-result',
          modelName: 'dify',
          tokenUsed: 120,
        },
      },
    });

    expect(wrapper.html()).toContain('<h1');
    expect(wrapper.text()).toContain('trace-result');
    expect(wrapper.text()).toContain('分段汇总');
    expect(wrapper.text()).toContain('章节数：10');
    expect(wrapper.text()).toContain('总 Token：120');
    expect(wrapper.html()).toContain('analysis-result__markdown');
    const markdown = wrapper.find('.analysis-result__markdown');
    expect(markdown.exists()).toBe(true);
    expect(markdown.find('li').text()).toBe('第一条');
    expect(markdown.find('code').text()).toContain('console.log("ok")');
  });
});

test('also strips preserved progress markers when they use a space separator', () => {
  const wrapper = mount(AnalysisResultCard, {
    props: {
      phase: 'error',
      errorMessage: 'internal server error',
      streamingText: '[analysis progress] 正在分析中，请稍候...\n# 正文片段',
    },
  });

  expect(wrapper.text()).toContain('正文片段');
  expect(wrapper.text()).not.toContain('[analysis progress]');
});

describe('AnalysisContextBar', () => {
  test('falls back when metadata is missing', () => {
    const wrapper = mount(AnalysisContextBar);

    expect(wrapper.attributes('data-role')).toBe('analysis-context');
    expect(wrapper.find('[data-chip="platform"]').text()).toContain('fanqie');
    expect(wrapper.find('[data-chip="chapter"]').text()).toContain('-');
  });

  test('renders provided metadata and author info', () => {
    const wrapper = mount(AnalysisContextBar, {
      props: {
        bookTitle: '红叶日记',
        author: '易烬千弦',
        bookId: 42,
        platform: 'fanqie',
        chapterCount: 5,
        analysisType: '结构分析',
      },
    });

    expect(wrapper.find('.analysis-context__title').text()).toBe('红叶日记');
    expect(wrapper.find('.analysis-context__meta').text()).toContain('易烬千弦');
    expect(wrapper.find('.analysis-context__meta').text()).toContain('作品 ID');
    expect(wrapper.find('[data-chip="chapter"]').text()).toContain('5');
  });
});

describe('AnalysisModeTabs', () => {
  test('marks the active tab with aria-pressed', () => {
    const wrapper = mount(AnalysisModeTabs, {
      props: {
        modelValue: 'structure',
        statusByMode: {
          deconstruct: {
            phaseLabel: '等待开始',
          },
          structure: {
            phaseLabel: '流式输出中',
            tone: 'running',
          },
        },
      },
    });

    const buttons = wrapper.findAll('button.analysis-tab');
    expect(buttons).toHaveLength(3);
    expect(buttons[1].attributes('aria-pressed')).toBe('true');
    expect(buttons[0].attributes('aria-pressed')).toBe('false');
    expect(wrapper.text()).toContain('流式输出中');
  });

  test('emits update when a different tab is clicked', async () => {
    const wrapper = mount(AnalysisModeTabs, {
      props: {
        modelValue: 'deconstruct',
      },
    });

    await wrapper.findAll('button.analysis-tab')[2].trigger('click');
    expect(wrapper.emitted('update:modelValue')).toEqual([['plot']]);
  });
});

describe('AnalysisToolbar', () => {
  test('stop button is disabled when not running and emits when active', async () => {
    const wrapper = mount(AnalysisToolbar, {
      props: {
        running: false,
      },
    });

    const stopButtonInactive = wrapper.get('[data-test="analysis-toolbar-stop"]');
    expect(stopButtonInactive.attributes('disabled')).toBe('true');

    await wrapper.setProps({ running: true });
    const stopButtonActive = wrapper.get('[data-test="analysis-toolbar-stop"]');
    await stopButtonActive.trigger('click');
    expect(wrapper.emitted('stop')).toHaveLength(1);
  });

  test('rerun button respects disabling prop and copy button emits', async () => {
    const wrapper = mount(AnalysisToolbar, {
      props: {
        running: true,
        disabling: true,
      },
    });

    expect(wrapper.get('[data-test="analysis-toolbar-rerun"]').attributes('disabled')).toBe('true');
    await wrapper.get('[data-test="analysis-toolbar-copy"]').trigger('click');
    expect(wrapper.emitted('copy')).toHaveLength(1);
  });
});

describe('AnalysisEmptyState', () => {
  test('renders helpful copy and button emits goBack', async () => {
    const wrapper = mount(AnalysisEmptyState);
    expect(wrapper.text()).toContain('扫榜');

    await wrapper.get('[data-test="analysis-empty-go-back"]').trigger('click');
    expect(wrapper.emitted('goBack')).toHaveLength(1);
  });
});
