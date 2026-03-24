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

vi.mock('@/api/config', () => ({
  systemConfigApi: {
    getAvailableModels: vi.fn().mockResolvedValue({
      data: {
        data: ['deepseek-chat'],
      },
    }),
  },
  userConfigApi: {
    get: vi.fn().mockResolvedValue({
      data: {
        data: {
          configValue: 'deepseek-chat',
        },
      },
    }),
    update: vi.fn().mockResolvedValue({
      data: {
        data: {
          configValue: 'deepseek-chat',
        },
      },
    }),
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
    modelName: 'deepseek-chat',
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

  test('waits for manual start, then runs all three analysis modes in parallel', async () => {
    const { analysisApi } = await import('@/api/analysis');
    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(
      createStreamTask(
        createResult('deconstruct', '# deconstruct result', {
          analysisMode: 'chunk_merge',
          segmentCount: 4,
          inputChapterCount: 3,
        }),
      ),
    );
    vi.mocked(analysisApi.streamStructure).mockImplementation(
      createStreamTask(createResult('structure', '# structure result')),
    );
    vi.mocked(analysisApi.streamPlot).mockImplementation(
      createStreamTask(createResult('plot', '# plot result')),
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

    expect(analysisApi.streamDeconstruct).not.toHaveBeenCalled();
    expect(analysisApi.streamStructure).not.toHaveBeenCalled();
    expect(analysisApi.streamPlot).not.toHaveBeenCalled();

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(analysisApi.streamDeconstruct).toHaveBeenCalledWith(
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      expect.any(Object),
    );
    expect(analysisApi.streamStructure).toHaveBeenCalledWith(
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      expect.any(Object),
    );
    expect(analysisApi.streamPlot).toHaveBeenCalledWith(
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      expect.any(Object),
    );
    expect(wrapper.findAll('[data-test="analysis-mode-panel"]')).toHaveLength(1);
    expect(wrapper.get('[data-test="analysis-mode-panel"]').attributes('data-mode')).toBe('deconstruct');
    expect(wrapper.text()).toContain('deconstruct result');
    expect(wrapper.text()).not.toContain('structure result');
    expect(wrapper.text()).not.toContain('plot result');

    await wrapper.findAll('[data-test="analysis-tab"]')[2].trigger('click');

    expect(wrapper.findAll('[data-test="analysis-mode-panel"]')).toHaveLength(1);
    expect(wrapper.get('[data-test="analysis-mode-panel"]').attributes('data-mode')).toBe('plot');
    expect(wrapper.text()).toContain('plot result');
    expect(wrapper.text()).not.toContain('deconstruct result');
  });

  test('stops and reruns only the targeted panel', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const deconstructAbort = vi.fn();
    const structureAbort = vi.fn();
    const plotAbort = vi.fn();

    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(
      vi.fn().mockImplementation(() => ({
        abort: deconstructAbort,
        result: new Promise<AnalysisResult>(() => undefined),
      })),
    );
    vi.mocked(analysisApi.streamStructure).mockImplementation(
      vi
        .fn()
        .mockImplementationOnce(() => ({
          abort: structureAbort,
          result: new Promise<AnalysisResult>(() => undefined),
        }))
        .mockImplementationOnce(createStreamTask(createResult('structure', '# rerun structure result'))),
    );
    vi.mocked(analysisApi.streamPlot).mockImplementation(
      vi.fn().mockImplementation(() => ({
        abort: plotAbort,
        result: new Promise<AnalysisResult>(() => undefined),
      })),
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
    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    await wrapper.findAll('[data-test="analysis-tab"]')[1].trigger('click');

    const structurePanel = wrapper.get('[data-test="analysis-mode-panel"][data-mode="structure"]');
    await structurePanel.get('[data-test="analysis-toolbar-stop"]').trigger('click');

    expect(structureAbort).toHaveBeenCalledTimes(1);
    expect(deconstructAbort).not.toHaveBeenCalled();
    expect(plotAbort).not.toHaveBeenCalled();

    await structurePanel.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
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
