import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AnalysisView from '../AnalysisView.vue';
import type { AnalysisResult, AnalysisType } from '@/types/analysis';

const push = vi.fn();

vi.mock('@/api/analysis', () => ({
  analysisApi: {
    streamDeconstruct: vi.fn(),
    streamStructure: vi.fn(),
    streamPlot: vi.fn(),
  },
}));

function createResult(
  analysisType: AnalysisType,
  resultContent = `${analysisType} result`,
  resultJson: Record<string, unknown> = {},
): AnalysisResult {
  return {
    id: 1,
    bookId: 1001,
    analysisType,
    modelName: 'dify',
    resultContent,
    resultJson,
    tokenUsed: 128,
  };
}

function createStreamTask(result: AnalysisResult) {
  return vi.fn().mockImplementation((_payload, callbacks) => {
    callbacks.onStart({
      event: 'start',
      traceId: `trace-${result.analysisType}`,
      analysisType: result.analysisType,
    });
    callbacks.onDelta({
      event: 'delta',
      delta: result.resultContent,
      chunkIndex: 0,
    });
    callbacks.onDone({
      event: 'done',
      data: result,
    });

    return {
      abort: vi.fn(),
      result: Promise.resolve(result),
    };
  });
}

describe('AnalysisView', () => {
  beforeEach(() => {
    push.mockReset();
    Object.defineProperty(window.navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: vi.fn().mockResolvedValue(undefined),
      },
    });
  });

  test('shows empty state when required query is missing', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/analysis', component: AnalysisView },
        { path: '/rank', component: { template: '<div />' } },
      ],
    });
    await router.push('/analysis');
    router.push = push as typeof router.push;

    const wrapper = mount(AnalysisView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    expect(wrapper.text()).toContain('扫榜');

    await wrapper.get('[data-test="analysis-empty-go-back"]').trigger('click');
    expect(push).toHaveBeenCalledWith('/rank');
  });

  test('auto-runs deconstruct mode from route query', async () => {
    const { analysisApi } = await import('@/api/analysis');
    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(
      createStreamTask(
        createResult('deconstruct', '# 拆文结果\n第一段', {
          analysisMode: 'chunk_merge',
          segmentCount: 4,
          inputChapterCount: 3,
        }),
      ),
    );
    vi.mocked(analysisApi.streamStructure).mockImplementation(
      createStreamTask(createResult('structure', '# 结构结果')),
    );
    vi.mocked(analysisApi.streamPlot).mockImplementation(
      createStreamTask(createResult('plot', '# 情节结果')),
    );

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/analysis', component: AnalysisView }],
    });
    await router.push('/analysis?bookId=1001&platform=fanqie&chapterCount=3');

    const wrapper = mount(AnalysisView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(analysisApi.streamDeconstruct).toHaveBeenCalledWith(
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      expect.any(Object),
    );
    expect(wrapper.text()).toContain('拆文结果');
    expect(wrapper.find('[data-role="analysis-context"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('分段汇总');
    expect(wrapper.text()).toContain('章节数：3');
    expect(wrapper.text()).toContain('分段数：4');
  });

  test('switches mode and reruns current analysis with forceReanalyze', async () => {
    const { analysisApi } = await import('@/api/analysis');
    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(
      createStreamTask(createResult('deconstruct', '# 拆文结果')),
    );
    vi.mocked(analysisApi.streamStructure).mockImplementation(
      createStreamTask(createResult('structure', '# 结构结果')),
    );
    vi.mocked(analysisApi.streamPlot).mockImplementation(
      createStreamTask(createResult('plot', '# 情节结果')),
    );

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/analysis', component: AnalysisView }],
    });
    await router.push('/analysis?bookId=1001&platform=fanqie&chapterCount=3');

    const wrapper = mount(AnalysisView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    await wrapper.findAll('[data-test="analysis-tab"]')[1].trigger('click');
    await flushPromises();

    expect(analysisApi.streamStructure).toHaveBeenCalledWith(
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      expect.any(Object),
    );

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(analysisApi.streamStructure).toHaveBeenLastCalledWith(
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
        forceReanalyze: true,
      },
      expect.any(Object),
    );
  });
});
