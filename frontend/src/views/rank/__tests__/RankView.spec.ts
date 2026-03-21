import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import RankView from '../RankView.vue';

const push = vi.fn();

vi.mock('@/api/crawler', () => ({
  crawlerApi: {
    getRank: vi.fn(),
    getBookDetail: vi.fn(),
    getChapters: vi.fn(),
  },
}));

describe('RankView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    push.mockReset();
  });

  test('loads rank list with default category male-hot-a', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getRank).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            bookId: 1001,
            rankNo: 1,
            bookName: '第一本书',
            author: '作者A',
            intro: '简介',
            bookUrl: 'https://book.test/1',
            platform: 'fanqie',
            category: 'male-hot-a',
          },
        ],
        timestamp: 1,
        traceId: 'trace-rank',
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

    expect(crawlerApi.getRank).toHaveBeenCalledWith({
      platform: 'fanqie',
      category: 'male-hot-a',
    });
    expect(wrapper.text()).toContain('第一本书');
  });

  test('opens detail and chapter flow', async () => {
    const { crawlerApi } = await import('@/api/crawler');
    vi.mocked(crawlerApi.getRank).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            bookId: 1001,
            rankNo: 1,
            bookName: '第一本书',
            author: '作者A',
            intro: '简介',
            bookUrl: 'https://book.test/1',
            platform: 'fanqie',
            category: 'male-hot-a',
          },
        ],
        timestamp: 1,
        traceId: 'trace-rank',
      },
    });
    vi.mocked(crawlerApi.getBookDetail).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          bookId: 1001,
          platform: 'fanqie',
          bookName: '第一本书',
          author: '作者A',
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
