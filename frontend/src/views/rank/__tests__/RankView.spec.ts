import ElementPlus, { ElMessage } from 'element-plus';
import fs from 'node:fs';
import path from 'node:path';
import { createPinia, setActivePinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import ChapterPreviewDrawer from '@/components/rank/ChapterPreviewDrawer.vue';
import RankView from '../RankView.vue';
import { userConfigApi } from '@/api/config';
import { setCurrentSession } from '@/lib/auth-session';

const push = vi.fn();

vi.mock('@/api/crawler', () => ({
  createRankRefreshIdempotencyKey: vi.fn(() => 'rank-refresh-test'),
  crawlerApi: {
    getBoards: vi.fn(),
    getPreference: vi.fn(),
    savePreference: vi.fn(),
    refreshRankBoard: vi.fn(),
    getRankStatus: vi.fn(),
    getRankPage: vi.fn(),
    getBookDetail: vi.fn(),
    getChapters: vi.fn(),
    getChapterStatus: vi.fn(),
    refreshChapters: vi.fn(),
  },
}));

vi.mock('@/api/config', () => ({
  userConfigApi: {
    get: vi.fn().mockResolvedValue({
      data: {
        data: {
          configValue: null,
        },
      },
    }),
    update: vi.fn().mockResolvedValue({
      data: {
        data: {
          configValue: '5',
        },
      },
    }),
  },
}));

function buildPageItems(startRank = 1, count = 5) {
  return Array.from({ length: count }, (_, index) => {
    const rank = startRank + index;
    return {
      bookId: 1000 + rank,
      rankNo: rank,
      bookName: `Book ${rank}`,
      author: `Author ${rank}`,
      intro: `Intro ${rank} `.repeat(20),
      bookUrl: `https://book.test/${rank}`,
      platform: 'fanqie' as const,
      category: 'male-new:urban-brain',
    };
  });
}

function mockInitializedRankWorkspace(crawlerApi: typeof import('@/api/crawler')['crawlerApi']) {
  vi.mocked(crawlerApi.getBoards).mockResolvedValue({
    data: {
      code: 200,
      message: 'success',
      data: [
        {
          channelCode: 'male-new',
          channelName: 'Male New',
          boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
        },
      ],
      timestamp: 1,
      traceId: 'trace-boards',
    },
  });
  vi.mocked(crawlerApi.getPreference).mockResolvedValue({
    data: {
      code: 404,
      message: 'not found',
      data: null,
      timestamp: 1,
      traceId: 'trace-preference',
    },
  } as never);
  vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
    data: {
      code: 200,
      message: 'success',
      data: {
        snapshotId: 6001,
        snapshotTime: '2026-03-22T10:00:00',
        total: 12,
        page: 1,
        pageSize: 10,
        items: buildPageItems(),
      },
      timestamp: 1,
      traceId: 'trace-page',
    },
  });
}

function buildRankRefreshResponse() {
  return {
    data: {
      code: 200,
      message: 'success',
      data: {
        channelCode: 'male-new',
        boardCode: 'urban-brain',
        snapshotId: 6001,
        snapshotTime: '2026-03-22T10:00:00',
        total: 12,
        reused: false,
        refreshLimited: false,
        analysisTriggered: false,
      },
      timestamp: 1,
      traceId: 'trace-refresh',
    },
  };
}

async function mountInitializedRankWorkspace() {
  const { crawlerApi } = await import('@/api/crawler');
  mockInitializedRankWorkspace(crawlerApi);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/rank', component: RankView }],
  });
  await router.push('/rank');
  const wrapper = mount(RankView, {
    global: {
      plugins: [router, ElementPlus],
    },
  });
  await flushPromises();
  return { crawlerApi, wrapper };
}

let intersectionCallback: IntersectionObserverCallback | null = null;

class MockIntersectionObserver {
  constructor(callback: IntersectionObserverCallback) {
    intersectionCallback = callback;
  }

  disconnect = vi.fn();
  observe = vi.fn();
  unobserve = vi.fn();
  takeRecords = vi.fn(() => []);
}

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  });
  window.dispatchEvent(new Event('resize'));
}

function triggerIntersection(isIntersecting = true) {
  intersectionCallback?.(
    [{ isIntersecting } as IntersectionObserverEntry],
    {} as IntersectionObserver,
  );
}

enableAutoUnmount(afterEach);

describe('RankView', () => {
  beforeEach(async () => {
    setActivePinia(createPinia());
    setCurrentSession({
      userId: 1,
      username: 'admin',
      roles: ['ADMIN'],
      accessToken: 'test-token',
      tokenType: 'Bearer',
      expiresIn: 3600,
      expireAt: Date.now() + 3_600_000,
      jwtExp: Math.floor(Date.now() / 1000) + 3600,
    });
    push.mockReset();
    vi.clearAllMocks();
    vi.useRealTimers();
    intersectionCallback = null;
    vi.stubGlobal('IntersectionObserver', MockIntersectionObserver as unknown as typeof IntersectionObserver);
    Object.defineProperty(window, 'scrollY', {
      configurable: true,
      writable: true,
      value: 0,
    });
    Object.defineProperty(window, 'scrollTo', {
      configurable: true,
      writable: true,
      value: vi.fn(),
    });
    window.sessionStorage.clear();
    setViewportWidth(1280);
    const { createRankRefreshIdempotencyKey } = await import('@/api/crawler');
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(createRankRefreshIdempotencyKey).mockReset();
    vi.mocked(createRankRefreshIdempotencyKey).mockReturnValue('rank-refresh-test');
    vi.mocked(crawlerApi.getRankStatus).mockReset();
    vi.mocked(crawlerApi.getChapterStatus).mockReset();
    vi.mocked(userConfigApi.get).mockResolvedValue({
      data: {
        data: {
          configValue: null,
        },
      },
    } as never);
  });

  test('keeps the desktop rank toolbar sticky without forcing mobile sticky layout', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../RankView.vue'), 'utf-8');

    expect(source).toContain('.rank-page__toolbar');
    expect(source).toContain('position: sticky;');
    expect(source).toContain('@media (max-width: 768px)');
    expect(source).toContain('position: static;');
  });

  test('keeps mobile rank records dense without shrinking action touch targets', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../RankView.vue'), 'utf-8');
    const mobileStyles = source.slice(source.indexOf('@media (max-width: 768px)'));

    expect(mobileStyles).toContain('grid-template-columns: 44px minmax(0, 1fr);');
    expect(mobileStyles).toMatch(/\.rank-page__item-intro\s*\{[\s\S]*?-webkit-line-clamp:\s*2;/);
    expect(mobileStyles).toMatch(/\.rank-page__item-actions \.el-button\s*\{[\s\S]*?min-height:\s*44px;/);
    expect(mobileStyles).toContain('border-radius: 8px;');
    expect(mobileStyles).toContain('margin-inline: -0.875rem;');
    expect(mobileStyles).toContain('padding: 0.75rem;');
    expect(mobileStyles).toContain('border-inline: 0;');
  });

  test('requests board catalog and user preference in parallel during initialization', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    let resolveBoards: ((value: unknown) => void) | null = null;

    vi.mocked(crawlerApi.getBoards).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveBoards = resolve;
        }) as never,
    );
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.getRankStatus).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
        },
        timestamp: 1,
        traceId: 'trace-status',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 10,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await Promise.resolve();
    expect(crawlerApi.getPreference).toHaveBeenCalledWith({ platform: 'fanqie' });

    resolveBoards?.({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });

    await flushPromises();
    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 10,
    });
  });

  test('loads board catalog then fetches the cached first page without auto refresh', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [
              { boardCode: 'urban-brain', boardName: 'Urban Brain' },
              { boardCode: 'urban-power', boardName: 'Urban Power' },
            ],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 10,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-power',
          rankFetchCount: 40,
        },
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');
    router.push = push as typeof router.push;

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(crawlerApi.getBoards).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(crawlerApi.getPreference).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(crawlerApi.refreshRankBoard).not.toHaveBeenCalled();
    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-power',
      page: 1,
      pageSize: 10,
    });
    expect(wrapper.text()).toContain('Urban Power');
    expect(wrapper.text()).toContain('Book 1');
    expect(wrapper.text()).toContain('Intr...');
  });

  test('falls back to auto refresh only when the database snapshot is missing', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.getRankPage)
      .mockRejectedValueOnce({
        response: {
          data: {
            code: 404,
            message: 'rank snapshot not found',
            traceId: 'trace-page-miss',
          },
        },
      })
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            snapshotId: 6001,
            snapshotTime: '2026-03-22T10:00:00',
            total: 12,
            page: 1,
            pageSize: 10,
            items: buildPageItems(),
          },
          timestamp: 1,
          traceId: 'trace-page',
        },
      });
    vi.mocked(crawlerApi.refreshRankBoard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          reused: false,
          refreshLimited: false,
          analysisTriggered: false,
        },
        timestamp: 1,
        traceId: 'trace-refresh',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(crawlerApi.getRankPage).toHaveBeenNthCalledWith(1, {
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 10,
    });
    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith(expect.objectContaining({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'AUTO',
      idempotencyKey: expect.stringMatching(/^rank-refresh-/),
      rankFetchCount: 30,
    }));
    expect(crawlerApi.getRankPage).toHaveBeenNthCalledWith(2, {
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 10,
    });
  });

  test('manual refresh uses force mode and pagination only requests page data', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.refreshRankBoard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          reused: false,
          refreshLimited: false,
          analysisTriggered: false,
        },
        timestamp: 1,
        traceId: 'trace-refresh',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 10,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.savePreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          rankFetchCount: 50,
        },
        timestamp: 1,
        traceId: 'trace-save-preference',
      },
    });

    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          rankFetchCount: 50,
        },
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    expect(crawlerApi.refreshRankBoard).not.toHaveBeenCalled();
    vi.mocked(crawlerApi.getRankPage).mockClear();

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith(expect.objectContaining({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'FORCE',
      idempotencyKey: expect.stringMatching(/^rank-refresh-/),
      rankFetchCount: 50,
    }));
    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 10,
    });

    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 5,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page-size-5',
      },
    });

    await wrapper.get('[data-testid="rank-page-size-5"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 5,
    });

    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 2,
          pageSize: 5,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page-2',
      },
    });

    vi.mocked(crawlerApi.getRankPage).mockClear();
    const pagination = wrapper.findComponent({ name: 'ElPagination' });
    pagination.vm.$emit('current-change', 2);
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledTimes(1);
    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 2,
      pageSize: 5,
    });
    wrapper.unmount();
  });

  test('ordinary users refresh the visible board in cache-first auto mode', async () => {
    setCurrentSession({
      userId: 2,
      username: 'writer',
      roles: ['USER'],
      accessToken: 'writer-token',
      tokenType: 'Bearer',
      expiresIn: 3600,
      expireAt: Date.now() + 3_600_000,
      jwtExp: Math.floor(Date.now() / 1000) + 3600,
    });
    const { crawlerApi, wrapper } = await mountInitializedRankWorkspace();
    vi.mocked(crawlerApi.refreshRankBoard).mockResolvedValue(buildRankRefreshResponse());

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith(expect.objectContaining({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'AUTO',
      rankFetchCount: 30,
    }));
    wrapper.unmount();
  });

  test('keeps the current rank snapshot visible when refresh fails', async () => {
    const { crawlerApi, wrapper } = await mountInitializedRankWorkspace();
    vi.mocked(crawlerApi.refreshRankBoard).mockRejectedValue({
      response: {
        status: 503,
        data: {
          code: 503,
          message: 'rank refresh temporarily unavailable',
          traceId: 'trace-refresh-failed',
        },
      },
    });

    expect(wrapper.text()).toContain('Book 1');
    expect(wrapper.get('[data-testid="rank-current-total"]').text()).toContain('12');
    expect(wrapper.text()).toContain('6001');

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('Book 1');
    expect(wrapper.get('[data-testid="rank-current-total"]').text()).toContain('12');
    expect(wrapper.text()).toContain('6001');
    expect(wrapper.text()).toContain('rank refresh temporarily unavailable');
    expect(crawlerApi.getRankPage).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  test('recovers a timed-out rank refresh by polling the latest snapshot', async () => {
    const { crawlerApi, wrapper } = await mountInitializedRankWorkspace();
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => null as never);
    vi.mocked(crawlerApi.refreshRankBoard).mockRejectedValue({
      code: 'ECONNABORTED',
      message: 'timeout of 120000ms exceeded',
    });
    vi.mocked(crawlerApi.getRankStatus).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6002,
          snapshotTime: '2026-03-22T10:05:00',
          total: 15,
        },
        timestamp: 2,
        traceId: 'trace-status-recovered',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6002,
          snapshotTime: '2026-03-22T10:05:00',
          total: 15,
          page: 1,
          pageSize: 10,
          items: buildPageItems(1, 10),
        },
        timestamp: 2,
        traceId: 'trace-page-recovered',
      },
    });

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.getRankStatus).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
    });
    expect(wrapper.text()).toContain('6002');
    expect(wrapper.get('[data-testid="rank-current-total"]').text()).toContain('15');
    expect(wrapper.find('[data-testid="rank-operation-status"]').exists()).toBe(false);
    expect(successSpy).toHaveBeenCalledWith('榜单后台抓取完成，已自动更新');
    wrapper.unmount();
  });

  test('preserves the explicit refresh result after reloading the refreshed page', async () => {
    const { crawlerApi, wrapper } = await mountInitializedRankWorkspace();
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => null as never);
    vi.mocked(crawlerApi.refreshRankBoard).mockResolvedValue(buildRankRefreshResponse());

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('当前展示最新整榜');
    expect(wrapper.text()).not.toContain('当前展示缓存快照');
    expect(successSpy).toHaveBeenCalledWith('榜单已刷新');
    wrapper.unmount();
  });

  test('warns when a refresh reuses an existing snapshot', async () => {
    const { crawlerApi, wrapper } = await mountInitializedRankWorkspace();
    const warningSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => null as never);
    const reusedResponse = buildRankRefreshResponse();
    reusedResponse.data.data.reused = true;
    vi.mocked(crawlerApi.refreshRankBoard).mockResolvedValue(reusedResponse);

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('当前展示缓存快照');
    expect(warningSpy).toHaveBeenCalledWith('本次未生成新快照，仍展示缓存数据');
    wrapper.unmount();
  });

  test('reuses an ambiguous refresh key after unmount and clears it after explicit success', async () => {
    const { crawlerApi, createRankRefreshIdempotencyKey } = await import('@/api/crawler');
    vi.mocked(createRankRefreshIdempotencyKey)
      .mockReturnValueOnce('rank-refresh-ambiguous')
      .mockReturnValueOnce('rank-refresh-after-success');
    vi.mocked(crawlerApi.refreshRankBoard)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValue(buildRankRefreshResponse());

    const firstMount = await mountInitializedRankWorkspace();
    await firstMount.wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenNthCalledWith(1, expect.objectContaining({
      idempotencyKey: 'rank-refresh-ambiguous',
    }));
    firstMount.wrapper.unmount();

    const secondMount = await mountInitializedRankWorkspace();
    await secondMount.wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenNthCalledWith(2, expect.objectContaining({
      idempotencyKey: 'rank-refresh-ambiguous',
    }));

    await secondMount.wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenNthCalledWith(3, expect.objectContaining({
      idempotencyKey: 'rank-refresh-after-success',
    }));
    expect(createRankRefreshIdempotencyKey).toHaveBeenCalledTimes(2);
    secondMount.wrapper.unmount();
  });

  test('clears a refresh key after an explicit business terminal response', async () => {
    const { crawlerApi, createRankRefreshIdempotencyKey } = await import('@/api/crawler');
    vi.mocked(createRankRefreshIdempotencyKey)
      .mockReturnValueOnce('rank-refresh-rejected')
      .mockReturnValueOnce('rank-refresh-retry');
    vi.mocked(crawlerApi.refreshRankBoard)
      .mockRejectedValueOnce({
        response: {
          status: 400,
          data: {
            code: 400,
            message: 'refresh request rejected',
            traceId: 'trace-rejected',
          },
        },
      })
      .mockResolvedValueOnce(buildRankRefreshResponse());

    const { wrapper } = await mountInitializedRankWorkspace();
    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenNthCalledWith(1, expect.objectContaining({
      idempotencyKey: 'rank-refresh-rejected',
    }));
    expect(crawlerApi.refreshRankBoard).toHaveBeenNthCalledWith(2, expect.objectContaining({
      idempotencyKey: 'rank-refresh-retry',
    }));
    wrapper.unmount();
  });

  test('coalesces an in-progress duplicate while polling the current snapshot', async () => {
    vi.useFakeTimers();
    const { crawlerApi, createRankRefreshIdempotencyKey } = await import('@/api/crawler');
    vi.mocked(createRankRefreshIdempotencyKey)
      .mockReturnValueOnce('rank-refresh-in-progress')
      .mockReturnValueOnce('rank-refresh-unexpected-new-key');
    vi.mocked(crawlerApi.refreshRankBoard).mockRejectedValue({
      response: {
        status: 409,
        data: {
          code: 409,
          message: 'RANK_REFRESH_IN_PROGRESS',
          traceId: 'trace-in-progress',
        },
      },
    });
    vi.mocked(crawlerApi.getRankStatus).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
        },
        timestamp: 1,
        traceId: 'trace-status-pending',
      },
    });

    const { wrapper } = await mountInitializedRankWorkspace();
    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="rank-operation-status"]').text()).toContain('自动检查最新快照');
    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenNthCalledWith(1, expect.objectContaining({
      idempotencyKey: 'rank-refresh-in-progress',
    }));
    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledTimes(1);
    expect(createRankRefreshIdempotencyKey).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(2500);
  });

  test('coalesces concurrent refresh clicks into one request', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    let resolveRefresh: ((value: ReturnType<typeof buildRankRefreshResponse>) => void) | null = null;
    vi.mocked(crawlerApi.refreshRankBoard).mockImplementation(
      () => new Promise((resolve) => {
        resolveRefresh = resolve;
      }),
    );

    const { wrapper } = await mountInitializedRankWorkspace();
    const refreshButton = wrapper.get('[data-testid="rank-force-refresh"]');
    refreshButton.element.dispatchEvent(new MouseEvent('click'));
    refreshButton.element.dispatchEvent(new MouseEvent('click'));
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledTimes(1);

    resolveRefresh?.(buildRankRefreshResponse());
    await flushPromises();
    wrapper.unmount();
  });

  test('shows current snapshot total and next fetch count beside the toolbar state', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 10,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          rankFetchCount: 50,
        },
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      attachTo: document.body,
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.get('[data-testid="rank-current-total"]').text()).toContain('12');
    expect(wrapper.get('[data-testid="rank-next-fetch-count"]').text()).toContain('50');
  });

  test('uses the updated fetch count when manual refresh is triggered after selection', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 10,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          rankFetchCount: 100,
        },
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });
    vi.mocked(crawlerApi.savePreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          rankFetchCount: 20,
        },
        timestamp: 1,
        traceId: 'trace-save-preference',
      },
    });
    vi.mocked(crawlerApi.refreshRankBoard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          snapshotId: 6002,
          snapshotTime: '2026-03-22T10:10:00',
          total: 20,
          reused: false,
          refreshLimited: false,
          analysisTriggered: false,
        },
        timestamp: 1,
        traceId: 'trace-refresh',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    const fetchCountSelect = wrapper.findAllComponents({ name: 'ElSelect' })[2];
    await fetchCountSelect.setValue(20);
    fetchCountSelect.vm.$emit('change', 20);
    await flushPromises();

    vi.mocked(crawlerApi.refreshRankBoard).mockClear();

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-testid="rank-next-fetch-count"]').text()).toContain('20');
    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith(expect.objectContaining({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'FORCE',
      idempotencyKey: expect.stringMatching(/^rank-refresh-/),
      rankFetchCount: 20,
    }));
  });

  test('opens detail, refreshes chapters, then navigates to analysis', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 5,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.savePreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-brain',
        },
        timestamp: 1,
        traceId: 'trace-save-preference',
      },
    });
    vi.mocked(crawlerApi.getBookDetail).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          bookId: 1001,
          platform: 'fanqie',
          bookName: 'Book 1',
          author: 'Author 1',
          intro: 'Long intro',
          bookUrl: 'https://book.test/1',
        },
        timestamp: 1,
        traceId: 'trace-detail',
      },
    });
    vi.mocked(crawlerApi.getChapters).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            bookId: 1001,
            chapterNo: 1,
            chapterTitle: 'Chapter 1',
            content: 'Old content',
            wordCount: 1234,
          },
        ],
        timestamp: 1,
        traceId: 'trace-chapters',
      },
    });
    vi.mocked(crawlerApi.refreshChapters).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          chapters: [
            {
              bookId: 1001,
              chapterNo: 1,
              chapterTitle: 'Chapter 1 Refreshed',
              content: 'New content',
              wordCount: 1357,
            },
          ],
          maxAllowedRefreshTimes: 3,
          usedRefreshTimes: 1,
          remainingRefreshTimes: 2,
          windowDays: 5,
        },
        timestamp: 1,
        traceId: 'trace-refresh-chapters',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/rank', component: RankView },
        { path: '/analysis', component: { template: '<div />' } },
      ],
    });
    await router.push('/rank');
    router.push = push as typeof router.push;

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    await wrapper.get('[data-testid="rank-detail-1001"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-testid="rank-chapters-1001"]').trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Long intro');
    expect(document.body.textContent).toContain('Chapter 1');

    const refreshButton = document.body.querySelector('[data-testid="refresh-chapters"]') as HTMLElement | null;
    expect(refreshButton).not.toBeNull();
    refreshButton?.click();
    await flushPromises();

    expect(crawlerApi.refreshChapters).toHaveBeenCalledWith({
      platform: 'fanqie',
      bookId: 1001,
      chapterCount: 3,
    });
    expect(document.body.textContent).toContain('Chapter 1 Refreshed');
    expect(document.body.textContent).toContain('剩余 2');

    vi.mocked(crawlerApi.refreshChapters).mockRejectedValueOnce({
      response: {
        data: {
          code: 504,
          message: 'chapter refresh timed out',
          traceId: 'trace-chapter-timeout',
        },
      },
    } as never);
    refreshButton?.click();
    await flushPromises();

    const chapterError = document.body.querySelector('[data-testid="chapter-error"]');
    expect(chapterError?.textContent).toContain('chapter refresh timed out');
    expect(chapterError?.textContent).toContain('traceId: trace-chapter-timeout');

    const goAnalysisButton = document.body.querySelector('[data-testid="go-analysis"]') as HTMLElement | null;
    expect(goAnalysisButton).not.toBeNull();
    goAnalysisButton?.click();

    expect(push).toHaveBeenCalledWith({
      path: '/analysis',
      query: {
        bookId: '1001',
        platform: 'fanqie',
        chapterCount: '3',
        bookName: 'Book 1',
        author: 'Author 1',
      },
    });
  });

  test('recovers timed-out chapter refreshes without requiring a browser reload', async () => {
    vi.useFakeTimers();
    const { crawlerApi, wrapper } = await mountInitializedRankWorkspace();
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => null as never);
    const oldChapters = Array.from({ length: 3 }, (_, index) => ({
      bookId: 1001,
      chapterNo: index + 1,
      chapterTitle: `Chapter ${index + 1}`,
      content: `Old content ${index + 1}`,
      wordCount: 1200,
      sourceWordCount: `Old content ${index + 1}`.length,
      crawlTime: '2026-03-22T10:00:00',
    }));
    const refreshedChapters = oldChapters.map((chapter) => ({
      ...chapter,
      chapterTitle: `${chapter.chapterTitle} Refreshed`,
      content: chapter.content.replace('Old', 'New'),
      crawlTime: '2026-03-22T10:05:00',
    }));
    const chapterStatusResponse = (chapters: typeof oldChapters, traceId: string) => ({
      data: {
        code: 200,
        message: 'success',
        data: chapters,
        timestamp: 1,
        traceId,
      },
    });
    vi.mocked(crawlerApi.getChapterStatus)
      .mockResolvedValueOnce(chapterStatusResponse(oldChapters, 'trace-chapter-baseline'))
      .mockResolvedValueOnce(chapterStatusResponse(oldChapters, 'trace-chapter-pending'))
      .mockResolvedValueOnce(chapterStatusResponse(refreshedChapters, 'trace-chapter-recovered'));
    vi.mocked(crawlerApi.refreshChapters).mockRejectedValue({
      code: 'ECONNABORTED',
      message: 'timeout of 180000ms exceeded',
    });

    await wrapper.get('[data-testid="rank-chapters-1001"]').trigger('click');
    await flushPromises();
    expect(crawlerApi.getChapters).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Chapter 1');

    const refreshButton = document.body.querySelector('[data-testid="refresh-chapters"]') as HTMLElement | null;
    refreshButton?.click();
    await flushPromises();

    await vi.advanceTimersByTimeAsync(2500);
    await flushPromises();

    expect(document.body.textContent).toContain('Chapter 1 Refreshed');
    expect(document.body.querySelector('[data-testid="chapter-error"]')).toBeNull();
    expect(successSpy).toHaveBeenCalledWith('章节后台抓取完成，已自动更新');
    wrapper.unmount();
  });

  test('uses one persisted snapshot for chapter refresh time and fingerprint baselines', async () => {
    vi.useFakeTimers();
    const { crawlerApi, wrapper } = await mountInitializedRankWorkspace();
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => null as never);
    successSpy.mockClear();
    const legacyVisibleChapters = Array.from({ length: 3 }, (_, index) => ({
      bookId: 1001,
      chapterNo: index + 1,
      chapterTitle: `Chapter ${index + 1}`,
      content: `Old content ${index + 1}`,
      wordCount: 1200,
    }));
    const persistedChapters = legacyVisibleChapters.map((chapter) => ({
      ...chapter,
      sourceWordCount: chapter.content.length,
      crawlTime: '2026-03-22T10:00:00',
    }));
    const refreshedChapters = persistedChapters.map((chapter) => ({
      ...chapter,
      chapterTitle: `${chapter.chapterTitle} Refreshed`,
      content: chapter.content.replace('Old', 'New'),
      crawlTime: '2026-03-22T10:05:00',
    }));
    const chapterStatusResponse = (chapters: typeof persistedChapters, traceId: string) => ({
      data: {
        code: 200,
        message: 'success',
        data: chapters,
        timestamp: 1,
        traceId,
      },
    });
    vi.mocked(crawlerApi.getChapterStatus)
      .mockResolvedValueOnce(chapterStatusResponse([], 'trace-chapter-empty'))
      .mockResolvedValueOnce(chapterStatusResponse(persistedChapters, 'trace-chapter-baseline'))
      .mockResolvedValueOnce(chapterStatusResponse(persistedChapters, 'trace-chapter-pending'))
      .mockResolvedValueOnce(chapterStatusResponse(refreshedChapters, 'trace-chapter-recovered'));
    vi.mocked(crawlerApi.getChapters).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: legacyVisibleChapters,
        timestamp: 1,
        traceId: 'trace-chapter-visible',
      },
    });
    vi.mocked(crawlerApi.refreshChapters).mockRejectedValue({
      code: 'ECONNABORTED',
      message: 'timeout of 180000ms exceeded',
    });

    await wrapper.get('[data-testid="rank-chapters-1001"]').trigger('click');
    await flushPromises();
    const drawer = wrapper.getComponent(ChapterPreviewDrawer);
    drawer.vm.$emit('refreshChapters');
    await flushPromises();

    expect(drawer.props('chapters')[0]?.chapterTitle).toBe('Chapter 1');
    expect(drawer.props('statusMessage')).toContain('章节仍在后台抓取');
    successSpy.mockClear();

    await vi.advanceTimersByTimeAsync(2500);
    await flushPromises();

    expect(drawer.props('chapters')[0]?.chapterTitle).toBe('Chapter 1 Refreshed');
    expect(drawer.props('statusMessage')).toBe('');
    expect(successSpy).toHaveBeenCalledWith('章节后台抓取完成，已自动更新');
    wrapper.unmount();
  });

  test('polls the current board page again so fresh snapshot data appears without manual refresh', async () => {
    vi.useFakeTimers();
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 10,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    vi.mocked(crawlerApi.getRankPage).mockClear();

    await vi.advanceTimersByTimeAsync(12000);
    await flushPromises();

    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 10,
    });
  });

  test('does not poll the rank page on mobile while keeping refresh-flow auto paging available', async () => {
    vi.useFakeTimers();
    setViewportWidth(390);
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 5,
          items: buildPageItems(1, 5),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    vi.mocked(crawlerApi.getRankPage).mockClear();

    await vi.advanceTimersByTimeAsync(12000);
    await flushPromises();

    expect(crawlerApi.getRankPage).not.toHaveBeenCalled();
    expect(crawlerApi.getRankStatus).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
    });
    expect(wrapper.find('[data-testid="rank-mobile-sentinel"]').exists()).toBe(true);
  });

  test('shows a mobile update prompt when lightweight rank status detects a newer snapshot', async () => {
    vi.useFakeTimers();
    setViewportWidth(390);
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.getRankPage)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            snapshotId: 6001,
            snapshotTime: '2026-03-22T10:00:00',
            total: 12,
            page: 1,
            pageSize: 5,
            items: buildPageItems(1, 5),
          },
          timestamp: 1,
          traceId: 'trace-page-1',
        },
      })
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            snapshotId: 7002,
            snapshotTime: '2026-03-22T10:12:00',
            total: 15,
            page: 1,
            pageSize: 5,
            items: buildPageItems(1, 5),
          },
          timestamp: 1,
          traceId: 'trace-page-2',
        },
      });
    vi.mocked(crawlerApi.getRankStatus).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 7002,
          snapshotTime: '2026-03-22T10:12:00',
          total: 15,
        },
        timestamp: 1,
        traceId: 'trace-status',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    vi.mocked(crawlerApi.getRankPage).mockClear();

    await vi.advanceTimersByTimeAsync(12000);
    await flushPromises();

    expect(wrapper.find('[data-testid="rank-mobile-update-banner"]').exists()).toBe(true);
    expect(wrapper.get('[data-testid="rank-mobile-update-banner"]').text()).toContain('检测到新的榜单快照');
    expect(wrapper.get('[data-testid="rank-mobile-update-action"]').text()).toContain('立即更新');

    await wrapper.get('[data-testid="rank-mobile-update-action"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 5,
    });
    expect(wrapper.find('[data-testid="rank-mobile-update-banner"]').exists()).toBe(false);
  });

  test('restores persisted chapter count and saves changes independently from rank fetch count', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    const { userConfigApi } = await import('@/api/config');
    vi.mocked(userConfigApi.get).mockResolvedValue({
      data: {
        data: {
          configValue: '5',
        },
      },
    } as never);
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-brain',
          rankFetchCount: 40,
        },
        timestamp: 1,
        traceId: 'trace-preference',
      },
    });
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 10,
          items: buildPageItems(),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(userConfigApi.get).toHaveBeenCalledWith('rank.chapter-count');
    expect(wrapper.findComponent({ name: 'ElSegmented' }).props('modelValue')).toBe(5);
  });

  test('uses refresh-flow pagination on mobile and auto loads the next page', async () => {
    setViewportWidth(390);
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.getRankPage)
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            snapshotId: 6001,
            snapshotTime: '2026-03-22T10:00:00',
            total: 12,
            page: 1,
            pageSize: 5,
            items: buildPageItems(1, 5),
          },
          timestamp: 1,
          traceId: 'trace-page-1',
        },
      })
      .mockResolvedValueOnce({
        data: {
          code: 200,
          message: 'success',
          data: {
            snapshotId: 6001,
            snapshotTime: '2026-03-22T10:00:00',
            total: 12,
            page: 2,
            pageSize: 5,
            items: buildPageItems(6, 5),
          },
          timestamp: 1,
          traceId: 'trace-page-2',
        },
      });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    expect(wrapper.findComponent({ name: 'ElPagination' }).exists()).toBe(false);
    expect(wrapper.find('[data-testid="rank-mobile-sentinel"]').exists()).toBe(true);

    triggerIntersection(true);
    await flushPromises();

    expect(crawlerApi.getRankPage).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 2,
      pageSize: 5,
    });
    expect(wrapper.text()).toContain('Book 10');
  });

  test('shows a floating back-to-top button in mobile refresh flow', async () => {
    setViewportWidth(390);
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: 'Male New',
            boards: [{ boardCode: 'urban-brain', boardName: 'Urban Brain' }],
          },
        ],
        timestamp: 1,
        traceId: 'trace-boards',
      },
    });
    vi.mocked(crawlerApi.getPreference).mockResolvedValue({
      data: {
        code: 404,
        message: 'not found',
        data: null,
        timestamp: 1,
        traceId: 'trace-preference',
      },
    } as never);
    vi.mocked(crawlerApi.getRankPage).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          snapshotId: 6001,
          snapshotTime: '2026-03-22T10:00:00',
          total: 12,
          page: 1,
          pageSize: 5,
          items: buildPageItems(1, 5),
        },
        timestamp: 1,
        traceId: 'trace-page',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/rank', component: RankView }],
    });
    await router.push('/rank');

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();
    expect(wrapper.find('[data-testid="rank-scroll-top"]').exists()).toBe(false);

    Object.defineProperty(window, 'scrollY', {
      configurable: true,
      writable: true,
      value: 500,
    });
    window.dispatchEvent(new Event('scroll'));
    await flushPromises();

    await wrapper.get('[data-testid="rank-scroll-top"]').trigger('click');
    expect(window.scrollTo).toHaveBeenCalledWith({
      top: 0,
      behavior: 'smooth',
    });
  });
});
