import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import RankView from '../RankView.vue';

const push = vi.fn();

vi.mock('@/api/crawler', () => ({
  crawlerApi: {
    getBoards: vi.fn(),
    refreshRankBoard: vi.fn(),
    getRankPage: vi.fn(),
    getBookDetail: vi.fn(),
    getChapters: vi.fn(),
  },
}));

function buildPageItems() {
  return Array.from({ length: 5 }, (_, index) => ({
    bookId: 1001 + index,
    rankNo: index + 1,
    bookName: `书籍 ${index + 1}`,
    author: `作者 ${index + 1}`,
    intro: `简介 ${index + 1}`,
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
            channelName: '男频新书榜',
            boards: [
              { boardCode: 'urban-brain', boardName: '都市脑洞' },
              { boardCode: 'urban-power', boardName: '都市高武' },
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
          pageSize: 5,
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
    router.push = push as typeof router.push;

    const wrapper = mount(RankView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(crawlerApi.getBoards).toHaveBeenCalledWith({ platform: 'fanqie' });
    expect(crawlerApi.refreshRankBoard).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      refreshMode: 'AUTO',
    });
    expect(crawlerApi.getRankPage).toHaveBeenCalledWith({
      platform: 'fanqie',
      channelCode: 'male-new',
      boardCode: 'urban-brain',
      page: 1,
      pageSize: 5,
    });
    expect(wrapper.text()).toContain('都市脑洞');
    expect(wrapper.text()).toContain('书籍 1');
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
            channelName: '男频新书榜',
            boards: [{ boardCode: 'urban-brain', boardName: '都市脑洞' }],
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
          pageSize: 5,
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
      pageSize: 5,
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

  test('opens detail and chapter flow from current page item', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getBoards).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            channelCode: 'male-new',
            channelName: '男频新书榜',
            boards: [{ boardCode: 'urban-brain', boardName: '都市脑洞' }],
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
    vi.mocked(crawlerApi.getBookDetail).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          bookId: 1001,
          platform: 'fanqie',
          bookName: '书籍 1',
          author: '作者 1',
          intro: '长简介',
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
            chapterTitle: '第一章',
            content: '正文',
            wordCount: 1234,
          },
        ],
        timestamp: 1,
        traceId: 'trace-chapters',
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

    expect(wrapper.text()).toContain('长简介');
    expect(wrapper.text()).toContain('第一章');

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
