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
    resultContent: '# 趋势结论\n' + '这是一个很长的趋势分析结果。'.repeat(60),
    resultJson: {
      summary: '这是用于摘要预览的趋势分析总结。'.repeat(30),
    },
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
      { name: 'male-read:261', value: 20 },
      { name: 'female-new:1017', value: 10 },
    ],
    latestSnapshots: [
      { category: 'male-read:261', crawlTime: '2026-03-21 10:00:00', bookCount: 50 },
    ],
    wordCloud: [],
    themeTable: [],
    comparisonSummary: null,
    snapshotComparisons: [],
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
  beforeEach(() => {
    vi.useRealTimers();
  });

  test('loads visual data and starts trend streaming on mount', async () => {
    vi.useFakeTimers();
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
    await vi.advanceTimersByTimeAsync(500);

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

  test('maps fetched trend data into Chinese labels and summaries', async () => {
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

    expect(wrapper.get('[data-test="trend-visual-section"]').text()).toContain('趋势图表');
    expect(wrapper.get('[data-test="trend-comparison-list"]').text()).toContain('拆文分析');
    expect(wrapper.get('[data-test="trend-snapshot-table"]').text()).toContain('男频在读榜');
    expect(wrapper.get('[data-test="trend-snapshot-table"]').text()).not.toContain('male-read:261');
  });

  test('shows preview first and opens full detail drawer for long trend results', async () => {
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

    expect(wrapper.get('[data-test="trend-result-preview"]').text().length).toBeLessThan(380);
    expect(wrapper.get('[data-test="trend-result-detail-open"]').text()).toContain('查看详情');

    await wrapper.get('[data-test="trend-result-detail-open"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="trend-result-detail"]').text()).toContain('这是一个很长的趋势分析结果');

    await wrapper.get('[data-test="trend-result-detail-close"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-test="trend-result-detail"]').exists()).toBe(false);
  });
});
