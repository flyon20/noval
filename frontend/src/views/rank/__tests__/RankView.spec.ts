import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import RankView from '../RankView.vue';
import { userConfigApi } from '@/api/config';

const push = vi.fn();

vi.mock('@/api/crawler', () => ({
  crawlerApi: {
    getBoards: vi.fn(),
    getPreference: vi.fn(),
    savePreference: vi.fn(),
    refreshRankBoard: vi.fn(),
    getRankPage: vi.fn(),
    getBookDetail: vi.fn(),
    getChapters: vi.fn(),
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

describe('RankView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
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
    setViewportWidth(1280);
    vi.mocked(userConfigApi.get).mockResolvedValue({
      data: {
        data: {
          configValue: null,
        },
      },
    } as never);
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
    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'AUTO',
      rankFetchCount: 30,
    });
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

    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'FORCE',
      rankFetchCount: 50,
    });
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
    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'FORCE',
      rankFetchCount: 20,
    });
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

    expect(wrapper.text()).toContain('Long intro');
    expect(wrapper.text()).toContain('Chapter 1');

    await wrapper.get('[data-testid="refresh-chapters"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshChapters).toHaveBeenCalledWith({
      platform: 'fanqie',
      bookId: 1001,
      chapterCount: 3,
    });
    expect(wrapper.text()).toContain('Chapter 1 Refreshed');
    expect(wrapper.text()).toContain('剩余 2');

    await wrapper.get('[data-testid="go-analysis"]').trigger('click');

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
