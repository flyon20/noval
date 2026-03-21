import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import TrendView from '../TrendView.vue';
import type { TrendAnalysisResult } from '@/types/trend';

vi.mock('@/api/analysis', () => ({
  analysisApi: {
    streamTrend: vi.fn(),
    getTrend: vi.fn(),
  },
}));

vi.mock('@/api/data', () => ({
  dataApi: {
    getVisual: vi.fn(),
  },
}));

function createTrendResult(overrides: Partial<TrendAnalysisResult> = {}): TrendAnalysisResult {
  return {
    analysisType: 'theme',
    platform: 'fanqie',
    category: 'male-hot-a',
    modelName: 'dify',
    resultContent: '# 趋势结论\n榜单热词继续上扬，权谋成长仍然最强。',
    resultJson: {},
    sourceSnapshotCount: 3,
    ...overrides,
  };
}

function createVisualPayload() {
  return {
    analysisTypeDistribution: [
      { name: 'deconstruct', value: 5 },
      { name: 'theme', value: 2 },
    ],
    analysisDailyTrend: [
      { date: '2026-03-20', value: 3 },
      { date: '2026-03-21', value: 4 },
    ],
    rankCategoryDistribution: [
      { name: 'male-hot-a', value: 10 },
      { name: 'male-hot-b', value: 8 },
    ],
    latestSnapshots: [
      { category: 'male-hot-a', crawlTime: '2026-03-21 10:00:00', bookCount: 50 },
    ],
    wordCloud: [
      { name: '权谋', value: 18 },
      { name: '热血', value: 12 },
    ],
    themeTable: [
      { theme: '权谋成长', count: 8, trend: 'up' },
    ],
    comparisonSummary: '近三次快照中，权谋成长主题持续升温。',
    snapshotComparisons: [
      { snapshotTime: '2026-03-21 10:00:00', topTheme: '权谋成长', change: '较上次上升' },
    ],
  };
}

function createStreamTask(result: TrendAnalysisResult) {
  return vi.fn().mockImplementation((_payload, callbacks) => {
    callbacks.onStart({
      event: 'start',
      traceId: 'trace-trend',
      analysisType: 'theme',
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

describe('TrendView', () => {
  test('loads visual data and starts trend streaming on mount', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');

    vi.mocked(dataApi.getVisual).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createVisualPayload(),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });
    vi.mocked(analysisApi.streamTrend).mockImplementation(createStreamTask(createTrendResult()));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/trend', component: TrendView }],
    });
    await router.push('/trend');

    const wrapper = mount(TrendView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(dataApi.getVisual).toHaveBeenCalledWith('fanqie');
    expect(analysisApi.streamTrend).toHaveBeenCalledWith(
      {
        platform: 'fanqie',
        category: 'male-hot-a',
      },
      expect.any(Object),
    );
    expect(wrapper.get('[data-test="trend-result-panel"]').text()).toContain('趋势结论');
    expect(wrapper.get('[data-test="trend-summary-snapshot-count"]').text()).toContain('3');
  });

  test('switches category and reruns trend analysis', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');

    vi.mocked(dataApi.getVisual).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createVisualPayload(),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });
    vi.mocked(analysisApi.streamTrend)
      .mockImplementationOnce(createStreamTask(createTrendResult()))
      .mockImplementationOnce(createStreamTask(createTrendResult({ category: 'male-hot-b' })));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/trend', component: TrendView }],
    });
    await router.push('/trend');

    const wrapper = mount(TrendView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    await wrapper.get('[data-test="trend-category-male-hot-b"]').trigger('click');
    await flushPromises();

    expect(analysisApi.streamTrend).toHaveBeenLastCalledWith(
      {
        platform: 'fanqie',
        category: 'male-hot-b',
      },
      expect.any(Object),
    );
  });

  test('renders visual summary and chart section after data load', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');

    vi.mocked(dataApi.getVisual).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createVisualPayload(),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });
    vi.mocked(analysisApi.streamTrend).mockImplementation(createStreamTask(createTrendResult()));

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/trend', component: TrendView }],
    });
    await router.push('/trend');

    const wrapper = mount(TrendView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.get('[data-test="trend-visual-section"]').text()).toContain('近三次快照中');
    expect(wrapper.get('[data-test="trend-tag-cloud"]').text()).toContain('权谋');
    expect(wrapper.get('[data-test="trend-snapshot-table"]').text()).toContain('male-hot-a');
  });
});
