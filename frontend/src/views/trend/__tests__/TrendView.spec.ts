import ElementPlus from 'element-plus';
import { ElMessage } from 'element-plus';
import { nextTick } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import TrendView from '../TrendView.vue';
import { userConfigApi } from '@/api/config';

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
          },
        ],
      },
    }),
  },
  userConfigApi: {
    get: vi.fn().mockImplementation((configKey: string) => {
      if (configKey === 'trend.current-context') {
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

vi.mock('@/api/crawler', () => ({
  crawlerApi: {
    getBoards: vi.fn(),
    getPreference: vi.fn(),
    savePreference: vi.fn(),
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

function createPreference(boardCode = 'urban-brain', channelCode = 'male-new', rankFetchCount = 40) {
  return {
    userId: 1,
    platform: 'fanqie',
    channelCode,
    boardCode,
    rankFetchCount,
  };
}

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  });
  window.dispatchEvent(new Event('resize'));
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

function createPendingStreamTask(delta: string) {
  return vi.fn().mockImplementation((_payload, callbacks) => {
    let rejectResult: ((reason?: unknown) => void) | null = null;

    callbacks.onStart({
      event: 'start',
      traceId: 'trace-trend-streaming',
      analysisType: 'theme',
    });
    callbacks.onDelta({
      event: 'delta',
      delta,
      chunkIndex: 0,
    });

    return {
      abort: vi.fn(() => {
        rejectResult?.(new Error('Analysis stream aborted'));
      }),
      result: new Promise<never>((_resolve, reject) => {
        rejectResult = reject;
      }),
    };
  });
}

async function mountTrendView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/trend', component: TrendView }],
  });
  await router.push('/trend');

  const wrapper = mount(TrendView, {
    attachTo: document.body,
    global: {
      plugins: [router, ElementPlus],
    },
  });

  await flushPromises();
  return wrapper;
}

function getTrendSelects(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAllComponents({ name: 'ElSelect' }).slice(0, 2);
}

describe('TrendView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
    setViewportWidth(1280);
    vi.mocked(userConfigApi.get).mockImplementation((configKey: string) => {
      if (configKey === 'trend.current-context') {
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

    const wrapper = await mountTrendView();

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

  test('restores persisted trend context instead of following rank preference', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');
    const { userConfigApi } = await import('@/api/config');

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
        data: createPreference('urban-brain', 'male-new'),
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });
    vi.mocked(userConfigApi.get).mockImplementation((configKey: string) => {
      if (configKey === 'trend.current-context') {
        return Promise.resolve({
          data: {
            data: {
              configValue: JSON.stringify({
                platform: 'fanqie',
                channelCode: 'female-hot',
                boardCode: 'ancient-love',
                boardName: '古言热推',
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
    vi.mocked(dataApi.getVisual).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createVisualPayload({
          channelCode: 'female-hot',
          boardCode: 'ancient-love',
          boardName: '古言热推',
        }),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });

    const wrapper = await mountTrendView();

    expect(dataApi.getVisual).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'female-hot',
      boardCode: 'ancient-love',
    });
    expect(wrapper.text()).toContain('古言热推');
    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
  });

  test('shows the available snapshot count instead of waiting for three samples', async () => {
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
        data: createVisualPayload({
          sourceSnapshotCount: 1,
          historyAnalysisCount: 1,
          latestSnapshots: [
            {
              snapshotTime: '2026-03-20 11:30:00',
              bookCount: 20,
              topBookName: '脑洞之王',
              topBookAuthor: '作者甲',
            },
          ],
          historicalWordCloud: [{ name: '都市脑洞', value: 1 }],
          themeTable: [{ theme: '都市脑洞', count: 1, trend: '样本积累中' }],
          snapshotComparisons: [
            { snapshotTime: '2026-03-20 11:30:00', topTheme: '都市脑洞', change: '单次快照' },
          ],
          insightCards: [
            { label: '当前焦点', value: '都市脑洞', note: '先基于现有 1 次快照展示' },
            { label: '代表作品', value: '脑洞之王', note: '先按已抓到的榜首作品展示' },
          ],
        }),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });

    const wrapper = await mountTrendView();

    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
    expect(wrapper.get('[data-test="trend-summary-snapshot-count"]').text()).toBe('1');
    expect(wrapper.get('[data-test="trend-snapshot-title"]').text()).toContain('1');
  });

  test('extracts readable summary from stored json text when no fresh result is running', async () => {
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
        data: createVisualPayload({
          trendPreview: `\`\`\`json
{
  "summary": {
    "overview": "基于两日榜单快照分析，榜单头部（Top 3）格局稳定，题材集中度继续抬升。"
  },
  "comparisonSummary": "当前榜单已经明显向头部题材收敛。"
}
\`\`\``,
          detailContent: `\`\`\`json
{
  "summary": {
    "overview": "基于两日榜单快照分析，榜单头部（Top 3）格局稳定，题材集中度继续抬升。"
  },
  "comparisonSummary": "当前榜单已经明显向头部题材收敛。"
}
\`\`\``,
        }),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });

    const wrapper = await mountTrendView();

    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
    expect(wrapper.get('[data-test="trend-result-preview"]').text()).toContain('榜单头部（Top 3）格局稳定');
    expect(wrapper.get('[data-test="trend-result-preview"]').text()).not.toContain('"summary"');
  });

  test('falls back to json-like field extraction when stored preview text is truncated or malformed', async () => {
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
        data: createVisualPayload({
          trendPreview: '```json { "summary": { "overview": "这是一段被截断的预览',
          detailContent: `\`\`\`json
{
  "summary": {
    "overview": "即使 detailContent 不是严格 JSON，也要优先把这句结构化结论展示出来。"
  },
  "comparisonSummary": "趋势结论应该优先于原始 JSON 文本。"
  "broken": “still malformed”
}
\`\`\``,
        }),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });

    const wrapper = await mountTrendView();

    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
    expect(wrapper.get('[data-test="trend-result-preview"]').text()).toContain('优先把这句结构化结论展示出来');
    expect(wrapper.get('[data-test="trend-result-preview"]').text()).not.toContain('这是一段被截断的预览');
  });

  test('switching board from select refreshes context but does not auto rerun trend analysis', async () => {
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
    vi.mocked(crawlerApi.savePreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPreference('fantasy-rise', 'male-new', 40),
        timestamp: 1,
        traceId: 'trace-save-preference',
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

    const wrapper = await mountTrendView();

    const [, boardSelect] = getTrendSelects(wrapper);
    boardSelect.vm.$emit('update:modelValue', 'fantasy-rise');
    await flushPromises();

    expect(dataApi.getVisual).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'fantasy-rise',
    });
    expect(crawlerApi.savePreference).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'fantasy-rise',
      rankFetchCount: 40,
    });
    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('玄幻热升');
  });


  test('switching board clears the previous board result immediately before the new visual payload returns', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');

    let resolveNextVisual:
      | ((value: {
        data: {
          code: number;
          message: string;
          data: Record<string, unknown>;
          timestamp: number;
          traceId: string;
        };
      }) => void)
      | null = null;

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
    vi.mocked(crawlerApi.savePreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPreference('fantasy-rise', 'male-new', 40),
        timestamp: 1,
        traceId: 'trace-save-preference',
      },
    });
    vi.mocked(dataApi.getVisual)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: createVisualPayload({
            boardSummary: 'old board summary',
            detailContent: 'old board detail',
            trendPreview: 'old board preview',
          }),
          timestamp: 1,
          traceId: 'trace-visual-1',
        },
      })
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveNextVisual = resolve as typeof resolveNextVisual;
      }) as never);

    const wrapper = await mountTrendView();

    expect(wrapper.text()).toContain('old board summary');

    const [, boardSelect] = getTrendSelects(wrapper);
    boardSelect.vm.$emit('update:modelValue', 'fantasy-rise');
    await nextTick();
    await flushPromises();

    expect(wrapper.find('[data-test="trend-result-preview"]').exists()).toBe(false);
    expect(wrapper.get('[data-test="analysis-result-card"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('old board summary');
    expect(analysisApi.streamTrend).not.toHaveBeenCalled();

    resolveNextVisual?.({
      data: {
        code: 200,
        message: 'success',
        data: createVisualPayload({
          boardCode: 'fantasy-rise',
          boardName: '??????',
          boardSummary: 'new board summary',
          detailContent: 'new board detail',
          trendPreview: 'new board preview',
        }),
        timestamp: 1,
        traceId: 'trace-visual-2',
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('new board summary');
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
    vi.mocked(crawlerApi.savePreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPreference('romance-push', 'female-hot', 40),
        timestamp: 1,
        traceId: 'trace-save-preference',
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

    const wrapper = await mountTrendView();

    const [channelSelect] = getTrendSelects(wrapper);
    channelSelect.vm.$emit('update:modelValue', 'female-hot');
    await flushPromises();

    expect(dataApi.getVisual).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      channelCode: 'female-hot',
      boardCode: 'romance-push',
    });
    expect(crawlerApi.savePreference).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'female-hot',
      boardCode: 'romance-push',
      rankFetchCount: 40,
    });
    expect(analysisApi.streamTrend).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('甜宠爆款');
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

    const wrapper = await mountTrendView();

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
    expect(document.body.textContent).toContain('这是一个很长的趋势分析结果');

    (document.body.querySelector('[data-test="trend-result-detail-close"]') as HTMLElement)?.click();
    await flushPromises();
    expect(document.body.querySelector('[data-test="trend-result-detail"]')).toBeNull();

    wrapper.unmount();
  });

  test('does not render raw json fragments while trend streaming is in progress', async () => {
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
    vi.mocked(analysisApi.streamTrend).mockImplementation(createPendingStreamTask(`{
  "analysisType": "theme",
  "summary": "当前榜单热钱继续集中在都市脑洞和系统混合流上，代表热书已经显著向高概念书名靠拢",
  "boardSummary": "都市脑洞持续领跑"
}`) as never);

    const wrapper = await mountTrendView();

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="analysis-result-card"]').text()).toContain('都市脑洞持续领跑');
    expect(wrapper.get('[data-test="analysis-result-card"]').text()).not.toContain('"analysisType"');
    expect(wrapper.get('[data-test="analysis-result-card"]').text()).not.toContain('"summary"');

    wrapper.unmount();
  });


  test('rerunning trend hides stale stored summary while the new stream is still pending', async () => {
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
        data: createVisualPayload({
          boardSummary: 'stale board summary',
          detailContent: 'stale board detail',
          trendPreview: 'stale board preview',
        }),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });
    vi.mocked(analysisApi.streamTrend).mockImplementation(createPendingStreamTask(`{
  "analysisType": "theme",
  "boardSummary": "????????"
}`) as never);

    const wrapper = await mountTrendView();

    expect(wrapper.text()).toContain('stale board summary');

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).not.toContain('stale board summary');
    expect(wrapper.get('[data-test="analysis-result-card"]').text()).toContain('????????');

    wrapper.unmount();
  });

  test('does not show an error toast when the user stops trend streaming manually', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => null as never);

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
    vi.mocked(analysisApi.streamTrend).mockImplementation(createPendingStreamTask(`{
  "analysisType": "theme",
  "boardSummary": "都市脑洞持续领跑"
}`) as never);

    const wrapper = await mountTrendView();

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-test="analysis-toolbar-stop"]').trigger('click');
    await flushPromises();

    expect(errorSpy).not.toHaveBeenCalled();

    wrapper.unmount();
    errorSpy.mockRestore();
  });

  test('prefers structured trend sections over raw content after analysis completes', async () => {
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
    vi.mocked(analysisApi.streamTrend).mockImplementation(createStreamTask(createTrendResult({
      resultContent: '# 原始全文\n' + 'RAW-MARKER-ONLY '.repeat(80),
      resultJson: {
        summary: '最近三次样本快速向都市脑洞集中，系统流仍保留稳定基本盘。',
        comparisonSummary: '榜单趋势已经从多题材并行转向都市高概念主导。',
        insightCards: [
          { label: '核心判断', value: '都市脑洞领跑', note: '近三次样本中热度和出现频率都更高。' },
          { label: '副线题材', value: '系统流留存', note: '仍然是稳定补充题材。' },
        ],
        themeTable: [
          { theme: '都市脑洞', count: 3, trend: '持续升温' },
          { theme: '系统流', count: 2, trend: '稳定跟随' },
        ],
        hotBooks: [
          { bookName: '脑洞之王', author: '作者甲', rankLabel: '第 1 名', reason: '概念强，书名识别度高。' },
        ],
        snapshotComparisons: [
          { snapshotTime: '2026-03-18 11:30:00', topTheme: '系统流', change: '起点样本' },
          { snapshotTime: '2026-03-20 11:30:00', topTheme: '都市脑洞', change: '完成反超' },
        ],
        historicalWordCloud: [
          { name: '都市脑洞', value: 24 },
          { name: '系统流', value: 15 },
        ],
      },
    })) as never);

    const wrapper = await mountTrendView();

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="trend-result-key-points"]').text()).toContain('都市脑洞领跑');
    expect(wrapper.get('[data-test="trend-result-theme-table"]').text()).toContain('持续升温');
    expect(wrapper.get('[data-test="trend-result-hot-books"]').text()).toContain('脑洞之王');
    expect(wrapper.get('[data-test="trend-result-preview"]').text()).not.toContain('RAW-MARKER-ONLY');
  });

  test('renders structured companion panels to fill the desktop result column after analysis', async () => {
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
    vi.mocked(analysisApi.streamTrend).mockImplementation(createStreamTask(createTrendResult({
      resultJson: {
        summary: '最近三次样本继续向都市脑洞聚焦。',
        comparisonSummary: '频道切换不影响当前榜单趋势判断，当前结果应该补齐结构区。',
        insightCards: [
          { label: '当前赛道', value: '都市脑洞', note: '仍然是这一轮分析的头部主题。' },
        ],
        themeTable: [
          { theme: '都市脑洞', count: 3, trend: '持续升温' },
        ],
        hotBooks: [
          { bookName: '脑洞之王', author: '作者甲', rankLabel: '第 1 名', reason: '书名和题材识别都很强。' },
        ],
        historicalWordCloud: [
          { name: '都市脑洞', value: 24 },
        ],
      },
    })) as never);

    const wrapper = await mountTrendView();

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="trend-result-support-grid"]').text()).toContain('都市脑洞');
    expect(wrapper.get('[data-test="trend-result-support-grid"]').text()).toContain('脑洞之王');
    expect(wrapper.get('[data-test="trend-result-support-grid"]').text()).toContain('持续升温');
  });

  test('renders theme cards instead of table rows on mobile', async () => {
    setViewportWidth(390);
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
    vi.mocked(analysisApi.streamTrend).mockImplementation(createStreamTask(createTrendResult({
      resultJson: {
        summary: '最近三次样本继续向都市脑洞聚焦。',
        themeTable: [
          { theme: '都市脑洞', count: 3, ratio: 50, trend: '持续升温', representativeBooks: [{ bookName: '脑洞之王' }] },
        ],
      },
    })) as never);

    const wrapper = await mountTrendView();

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-test="trend-result-theme-cards"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="trend-result-theme-table"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('脑洞之王');
  });
  test('keeps rendering trend compatibility fields from existing resultJson names', async () => {
    const { analysisApi } = await import('@/api/analysis');
    const { dataApi } = await import('@/api/data');
    const { crawlerApi } = await import('@/api/crawler');
    const trendPreview = 'compat trend preview';
    const themeDistribution = [{ theme: 'urban-brain-live-fortune-good-evil', count: 3, ratio: 60 }];
    const themeTable = [
      {
        theme: 'urban-brain-live-fortune-good-evil',
        count: 3,
        ratio: 60,
        trend: 'still rising',
      },
    ];
    const hotBooks = [{ bookName: 'Brain King', author: 'Author A', rankLabel: 'Top 1', reason: 'lane rep' }];
    const insightCards = [{ label: 'Core Lane', value: 'urban-brain', note: 'compat field' }];
    const snapshotComparisons = [{ snapshotTime: '2026-03-20 11:30:00', topTheme: 'urban-brain', change: 'still rising' }];
    const historicalWordCloud = [{ name: 'urban-brain', value: 24 }];

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
        data: createVisualPayload({
          boardSummary: '',
          trendPreview: '',
          detailContent: '',
          historicalWordCloud: [],
          themeDistribution: [],
          themeTable: [],
          hotBooks: [],
          insightCards: [],
          snapshotComparisons: [],
        }),
        timestamp: 1,
        traceId: 'trace-visual',
      },
    });
    vi.mocked(analysisApi.streamTrend).mockImplementation(createStreamTask(createTrendResult({
      resultJson: {
        summary: 'compat summary',
        boardSummary: 'compat board summary',
        trendPreview,
        historicalWordCloud,
        themeDistribution,
        themeTable,
        hotBooks,
        insightCards,
        snapshotComparisons,
      },
    })) as never);

    const wrapper = await mountTrendView();

    await wrapper.get('[data-test="analysis-toolbar-rerun"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="trend-result-preview"]').text()).toContain('compat summary');
    expect(wrapper.get('[data-test="trend-result-support-grid"]').text()).toContain('still rising');
    expect(wrapper.get('[data-test="trend-result-theme-table"]').text()).toContain(themeTable[0].theme);
    expect(wrapper.get('[data-test="trend-result-hot-books"]').text()).toContain(hotBooks[0].bookName);
    expect(wrapper.get('[data-test="trend-result-key-points"]').text()).toContain(insightCards[0].label);
    expect(wrapper.text()).toContain(snapshotComparisons[0].change);

    const trendComparisonList = wrapper.findComponent({ name: 'TrendComparisonList' });
    expect(trendComparisonList.exists()).toBe(true);
    expect(trendComparisonList.props('insightCards')).toEqual([
      expect.objectContaining(insightCards[0]),
    ]);
    expect(trendComparisonList.props('comparisons')).toEqual([
      expect.objectContaining(snapshotComparisons[0]),
    ]);

    const trendTagCloud = wrapper.findComponent({ name: 'TrendTagCloud' });
    expect(trendTagCloud.exists()).toBe(true);
    expect(trendTagCloud.props('items')).toEqual([
      expect.objectContaining(historicalWordCloud[0]),
    ]);

    const chartCards = wrapper.findAllComponents({ name: 'TrendChartCard' });
    expect(chartCards).toHaveLength(2);
    expect(chartCards[0].props('option').series[0].data).toEqual([
      expect.objectContaining({
        name: themeDistribution[0].theme,
        value: themeDistribution[0].count,
      }),
    ]);

    const summaryCards = wrapper.findComponent({ name: 'TrendSummaryCards' });
    expect(summaryCards.exists()).toBe(true);
    expect(summaryCards.props('summary')).toContain('compat board summary');

    const trendState = wrapper.vm as {
      trend: {
        state: {
          result?: {
            resultJson?: Record<string, unknown>;
          };
        };
      };
    };
    expect(trendState.trend.state.result?.resultJson?.trendPreview).toBe(trendPreview);
  });
});

test('polls the current board visual data again so the page hot-updates without manual refresh', async () => {
  vi.useFakeTimers();
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

  await mountTrendView();
  await flushPromises();
  vi.mocked(dataApi.getVisual).mockClear();

  await vi.advanceTimersByTimeAsync(12000);
  await flushPromises();

  expect(dataApi.getVisual).toHaveBeenCalledWith({
    platform: 'fanqie',
    channelCode: 'male-new',
    boardCode: 'urban-brain',
  });
});
