import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import AnalysisView from '../AnalysisView.vue';
import { analysisApi } from '@/api/analysis';
import { dataApi } from '@/api/data';
import { userConfigApi } from '@/api/config';
import type { AnalysisResult, AnalysisType } from '@/types/analysis';

const push = vi.fn();

vi.mock('@/api/analysis', () => ({
  analysisApi: {
    streamDeconstruct: vi.fn(),
    streamStructure: vi.fn(),
    streamPlot: vi.fn(),
  },
}));

vi.mock('@/api/data', () => ({
  dataApi: {
    getHistory: vi.fn().mockResolvedValue({
      data: {
        data: [],
      },
    }),
  },
}));

vi.mock('@/api/config', () => ({
  systemConfigApi: {
    getModelOptions: vi.fn().mockResolvedValue({
      data: {
        data: [
          {
            modelKey: 'deepseek-chat',
            displayName: 'DeepSeek Chat',
            providerType: 'openai-compatible',
            isDefault: true,
            defaultTemperature: 1,
            maxTokens: 8192,
            temperatureSpecJson: '{"min":0,"max":2}',
          },
        ],
      },
    }),
    getAvailableModels: vi.fn().mockResolvedValue({
      data: {
        data: ['deepseek-chat'],
      },
    }),
  },
  userConfigApi: {
    get: vi.fn().mockImplementation((configKey: string) => {
      if (configKey === 'analysis.current-context') {
        return Promise.resolve({
          data: {
            data: {
              configValue: null,
            },
          },
        });
      }
      return Promise.resolve({
        data: {
          data: {
            configValue: 'deepseek-chat',
          },
        },
      });
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
    vi.clearAllMocks();
    push.mockReset();
    vi.mocked(analysisApi.streamDeconstruct).mockReset();
    vi.mocked(analysisApi.streamStructure).mockReset();
    vi.mocked(analysisApi.streamPlot).mockReset();
    vi.mocked(dataApi.getHistory).mockResolvedValue({
      data: {
        data: [],
      },
    } as never);
    vi.mocked(userConfigApi.get).mockImplementation((configKey: string) => {
      if (configKey === 'analysis.current-context') {
        return Promise.resolve({
          data: {
            data: {
              configValue: null,
            },
          },
        });
      }

      return Promise.resolve({
        data: {
          data: {
            configValue: 'deepseek-chat',
          },
        },
      });
    });
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

  test('waits for manual start, then runs only the active analysis mode on first trigger', async () => {
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
    expect(analysisApi.streamStructure).not.toHaveBeenCalled();
    expect(analysisApi.streamPlot).not.toHaveBeenCalled();
    expect(wrapper.findAll('[data-test="analysis-mode-panel"]')).toHaveLength(1);
    expect(wrapper.get('[data-test="analysis-mode-panel"]').attributes('data-mode')).toBe('deconstruct');
    expect(wrapper.text()).toContain('deconstruct result');
    expect(wrapper.text()).not.toContain('structure result');
    expect(wrapper.text()).not.toContain('plot result');

    await wrapper.findAll('[data-test="analysis-tab"]')[2].trigger('click');

    expect(wrapper.findAll('[data-test="analysis-mode-panel"]')).toHaveLength(1);
    expect(wrapper.get('[data-test="analysis-mode-panel"]').attributes('data-mode')).toBe('plot');
    expect(wrapper.text()).not.toContain('plot result');
    expect(wrapper.text()).not.toContain('deconstruct result');
  });

  test('shows full streaming text instead of preview truncation while analysis is still running', async () => {
    vi.useFakeTimers();
    const { analysisApi } = await import('@/api/analysis');
    const fullText = 'LONG-STREAM-SEGMENT-'.repeat(80);

    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(
      vi.fn().mockImplementation((_payload, callbacks) => {
        callbacks.onStart({
          event: 'start',
          traceId: 'trace-streaming',
          analysisType: 'deconstruct',
        });
        callbacks.onDelta({
          event: 'delta',
          delta: fullText,
          chunkIndex: 0,
        });

        return {
          abort: vi.fn(),
          result: new Promise<AnalysisResult>(() => undefined),
        };
      }),
    );

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/analysis', component: AnalysisView }],
    });
    await router.push('/analysis?bookId=1001&platform=fanqie&chapterCount=10');

    const wrapper = mount(AnalysisView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();

    expect(wrapper.text()).toContain(fullText.slice(-80));
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
    await structurePanel.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();
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

  test('shows actual chapter count when fetched chapters are fewer than requested', async () => {
    const { analysisApi } = await import('@/api/analysis');
    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(
      createStreamTask(
        createResult('deconstruct', '# deconstruct result', {
          requestedChapterCount: 10,
          actualChapterCount: 8,
          inputChapterCount: 8,
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
    await router.push('/analysis?bookId=1001&platform=fanqie&chapterCount=10');

    const wrapper = mount(AnalysisView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('抓取章节：8/10');
  });

  test('restores persisted analysis context and results when route query is missing', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { userConfigApi } = await import('@/api/config');

    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(createStreamTask(createResult('deconstruct')));
    vi.mocked(analysisApi.streamStructure).mockImplementation(createStreamTask(createResult('structure')));
    vi.mocked(analysisApi.streamPlot).mockImplementation(createStreamTask(createResult('plot')));
    vi.mocked(userConfigApi.get).mockImplementation((configKey: string) => {
      if (configKey === 'analysis.current-context') {
        return Promise.resolve({
          data: {
            data: {
              configValue: JSON.stringify({
                platform: 'fanqie',
                bookId: 1001,
                chapterCount: 5,
                bookTitle: 'Persisted Book',
                author: 'Persisted Author',
                activeMode: 'plot',
              }),
            },
          },
        });
      }

      return Promise.resolve({
        data: {
          data: {
            configValue: 'deepseek-chat',
          },
        },
      });
    });
    vi.mocked(dataApi.getHistory).mockResolvedValue({
      data: {
        data: [
          {
            id: 11,
            bookId: 1001,
            bookName: 'Persisted Book',
            analysisType: 'plot',
            chapterCount: 5,
            modelName: 'deepseek-chat',
            resultContent: '# plot restored',
            resultJson: {},
            createdAt: '2026-03-26 20:00:00',
          },
          {
            id: 10,
            bookId: 1001,
            bookName: 'Persisted Book',
            analysisType: 'deconstruct',
            chapterCount: 5,
            modelName: 'deepseek-chat',
            resultContent: '# deconstruct restored',
            resultJson: {},
            createdAt: '2026-03-26 19:59:00',
          },
        ],
      },
    } as never);

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/analysis', component: AnalysisView }],
    });
    await router.push('/analysis');

    const wrapper = mount(AnalysisView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(dataApi.getHistory).toHaveBeenCalledWith({
      platform: 'fanqie',
      bookId: 1001,
      limit: 20,
    });
    expect(wrapper.text()).toContain('Persisted Book');
    expect(wrapper.get('[data-test="analysis-mode-panel"]').attributes('data-mode')).toBe('plot');
    expect(wrapper.text()).toContain('plot restored');
    expect(analysisApi.streamDeconstruct).not.toHaveBeenCalled();
    expect(analysisApi.streamStructure).not.toHaveBeenCalled();
    expect(analysisApi.streamPlot).not.toHaveBeenCalled();
  });

  test('keeps rendering single-book metadata from existing resultJson names', async () => {
    const { analysisApi } = await import('@/api/analysis');
    vi.mocked(analysisApi.streamDeconstruct).mockImplementation(
      createStreamTask(
        createResult('deconstruct', '# deconstruct result', {
          analysisMode: 'chunk_merge',
          segmentCount: 4,
          requestedChapterCount: 10,
          actualChapterCount: 8,
          inputChapterCount: 8,
          chapterFetchDegraded: true,
          promptRuntime: 'langgraph',
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
    await router.push('/analysis?bookId=1001&platform=fanqie&chapterCount=10');

    const wrapper = mount(AnalysisView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('分析方式：分段汇总');
    expect(wrapper.text()).toContain('4 段');
    expect(wrapper.text()).toContain('抓取章节：8/10');
  });
});
