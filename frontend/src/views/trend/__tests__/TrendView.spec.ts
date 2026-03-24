import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import TrendView from '../TrendView.vue';

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

vi.mock('@/api/crawler', () => ({
  crawlerApi: {
    getBoards: vi.fn(),
    getPreference: vi.fn(),
  },
}));

function createBoardCatalog() {
  return [
    {
      channelCode: 'male-new',
      channelName: '男频新书榜',
      boards: [
        { boardCode: 'urban-brain', boardName: '都市脑洞' },
        { boardCode: 'fantasy-rise', boardName: '玄幻热升' },
      ],
    },
    {
      channelCode: 'female-hot',
      channelName: '女频热门榜',
      boards: [
        { boardCode: 'romance-push', boardName: '甜宠爆款' },
        { boardCode: 'ancient-love', boardName: '古言热推' },
      ],
    },
  ];
}

function createPreference(boardCode = 'urban-brain', channelCode = 'male-new') {
  return {
    userId: 1,
    platform: 'fanqie',
    channelCode,
    boardCode,
  };
}

function createVisualPayload(overrides: Record<string, unknown> = {}) {
  return {
    platform: 'fanqie',
    channelCode: 'male-new',
    boardCode: 'urban-brain',
    boardName: '都市脑洞',
    sourceSnapshotCount: 3,
    historyAnalysisCount: 3,
    latestSnapshots: [
      {
        snapshotTime: '2026-03-20 11:30:00',
        bookCount: 20,
        topBookName: '脑洞之王',
        topBookAuthor: '作者甲',
      },
      {
        snapshotTime: '2026-03-19 11:30:00',
        bookCount: 20,
        topBookName: '城市异想',
        topBookAuthor: '作者乙',
      },
      {
        snapshotTime: '2026-03-18 11:30:00',
        bookCount: 20,
        topBookName: '都市升级记',
        topBookAuthor: '作者丙',
      },
    ],
    historicalWordCloud: [
      { name: '都市脑洞', value: 24 },
      { name: '系统流', value: 15 },
    ],
    themeTable: [
      { theme: '都市脑洞', count: 3, trend: '持续升温' },
      { theme: '系统流', count: 2, trend: '稳定' },
    ],
    snapshotComparisons: [
      { snapshotTime: '2026-03-18 11:30:00', topTheme: '系统流', change: '起点样本' },
      { snapshotTime: '2026-03-19 11:30:00', topTheme: '都市脑洞', change: '热度上升' },
      { snapshotTime: '2026-03-20 11:30:00', topTheme: '都市脑洞', change: '继续走高' },
    ],
    insightCards: [
      { label: '主赛道', value: '都市脑洞', note: '近三次样本中出现频率最高' },
      { label: '代表风格', value: '轻快高概念', note: '简介和书名高度集中' },
    ],
    hotBooks: [
      { bookName: '脑洞之王', author: '作者甲', rankLabel: '第 1 名', reason: '题材辨识度高' },
    ],
    comparisonSummary: '最近三次样本从系统流转向都市脑洞，题材聚焦明显。',
    trendPreview: '最近三次榜单样本持续向都市脑洞集中，系统流仍有稳定留存。',
    detailContent: '完整趋势内容',
    ...overrides,
  };
}

function createTrendResult(overrides: Record<string, unknown> = {}) {
  return {
    analysisType: 'theme',
    platform: 'fanqie',
    channelCode: 'male-new',
    boardCode: 'urban-brain',
    boardName: '都市脑洞',
    modelName: 'deepseek-chat',
    resultContent: '# 趋势结论\n' + '这是一个很长的趋势分析结果。'.repeat(60),
    resultJson: {
      summary: '最近三次样本从系统流快速切向都市脑洞，都市高概念作品占位更稳。'.repeat(10),
      historicalWordCloud: [
        { name: '都市脑洞', value: 24 },
        { name: '系统流', value: 15 },
      ],
    },
    sourceSnapshotCount: 3,
    ...overrides,
  };
}

function createStreamTask(result: Record<string, unknown>) {
  return vi.fn().mockImplementation((_payload, callbacks) => {
    callbacks.onStart({
      event: 'start',
      traceId: 'trace-trend',
      analysisType: 'theme',
    });
    callbacks.onDelta({
      event: 'delta',
      delta: String(result.resultContent),
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

  test('loads board context and visual data without auto starting analysis', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');

    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createBoardCatalog(),
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPreference(),
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });
    vi.mocked(dataApi.getVisual).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createVisualPayload(),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });

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

    expect(crawlerApi.getBoards).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(crawlerApi.getPreference).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(dataApi.getVisual).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
    });
    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('都市脑洞');
  });

  test('switching board refreshes context but does not auto rerun trend analysis', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');

    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createBoardCatalog(),
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPreference(),
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });
    vi.mocked(dataApi.getVisual)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: createVisualPayload(),
          timestamp: 1,
          traceId: 'trace-visual-1',
        },
      })
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: createVisualPayload({
            boardCode: 'fantasy-rise',
            boardName: '玄幻热升',
          }),
          timestamp: 1,
          traceId: 'trace-visual-2',
        },
      });

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
    await wrapper.get('[data-test="trend-category-fantasy-rise"]').trigger('click');
    await flushPromises();

    expect(dataApi.getVisual).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'fantasy-rise',
    });
    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
  });

  test('switching channel from select loads the first board without auto rerunning analysis', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');

    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createBoardCatalog(),
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPreference(),
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });
    vi.mocked(dataApi.getVisual)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: createVisualPayload(),
          timestamp: 1,
          traceId: 'trace-visual-1',
        },
      })
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: createVisualPayload({
            channelCode: 'female-hot',
            boardCode: 'romance-push',
            boardName: '甜宠爆款',
          }),
          timestamp: 1,
          traceId: 'trace-visual-2',
        },
      });

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
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'female-hot');
    await flushPromises();

    expect(dataApi.getVisual).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      channelCode: 'female-hot',
      boardCode: 'romance-push',
    });
    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
  });

  test('starts trend analysis only after explicit action and supports detail drawer', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');

    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createBoardCatalog(),
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPreference(),
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });
    vi.mocked(dataApi.getVisual).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createVisualPayload(),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });
    vi.mocked(analysisApi.streamTrend).mockImplementation(createStreamTask(createTrendResult()) as never);

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
    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(analysisApi.streamTrend).toHaveBeenCalledWith(
      {
        platform: 'fanqie',
        channelCode: 'male-new',
        boardCode: 'urban-brain',
      },
      expect.any(Object),
    );
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
