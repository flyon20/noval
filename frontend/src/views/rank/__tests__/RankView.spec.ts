import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import RankView from '../RankView.vue';

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

function buildPageItems() {
  return Array.from({ length: 5 }, (_, index) => ({
    bookId: 1001 + index,
    rankNo: index + 1,
    bookName: `Book ${index + 1}`,
    author: `Author ${index + 1}`,
    intro: `Intro ${index + 1} `.repeat(20),
    bookUrl: `https://book.test/${index + 1}`,
    platform: 'fanqie' as const,
    category: 'male-new:urban-brain',
  }));
}

describe('RankView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
    vi.clearAllMocks();
  });

  test('loads board catalog then auto refreshes first board and fetches first page', async () => {
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
          reused: true,
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
        code: 200,
        message: 'success',
        data: {
          userId: 2,
          platform: 'fanqie',
          channelCode: 'male-new',
          boardCode: 'urban-power',
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
    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-power',
      refreshMode: 'AUTO',
    });
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
        },
        timestamp: 1,
        traceId: 'trace-save-preference',
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
    vi.mocked(crawlerApi.refreshRankBoard).mockClear();
    vi.mocked(crawlerApi.getRankPage).mockClear();

    await wrapper.get('[data-testid="rank-force-refresh"]').trigger('click');
    await flushPromises();

    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'FORCE',
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
          reused: true,
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
      },
    });
  });
});
