import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { nextTick } from 'vue';
import HistoryView from '../HistoryView.vue';
import { dataApi } from '@/api/data';

vi.mock('@/api/data', () => ({
  dataApi: {
    getHistory: vi.fn(),
    getHistoryDetail: vi.fn(),
  },
}));

const historyItems = [
  {
    id: 1,
    bookId: 1001,
    bookName: 'Book Alpha',
    analysisType: 'deconstruct',
    chapterCount: 5,
    modelName: 'deepseek-chat',
    summaryPreview: 'Analysis result A summary',
    matchSnippets: ['Lead lane urban-brain dominates the board history'],
    matchedFields: ['result_content'],
    createdAt: '2026-03-21 16:00:00',
  },
  {
    id: 2,
    bookId: 1002,
    bookName: 'Book Beta',
    analysisType: 'structure',
    chapterCount: 3,
    modelName: 'deepseek-chat',
    summaryPreview: 'Analysis result B summary',
    createdAt: '2026-03-20 12:00:00',
  },
] as const;

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function historyResponse(items = historyItems, options: { page?: number; hasNext?: boolean; total?: number } = {}) {
  return {
    data: {
      code: 200,
      message: 'success',
      data: {
        items,
        page: options.page ?? 1,
        pageSize: 20,
        total: options.total ?? items.length,
        hasNext: options.hasNext ?? false,
      },
      timestamp: 1,
      traceId: 'trace-history',
    },
  };
}

function detailResponse(id: number, resultContent: string) {
  const item = historyItems.find((historyItem) => historyItem.id === id) ?? historyItems[0];
  return {
    data: {
      code: 200,
      message: 'success',
      data: {
        ...item,
        resultContent,
        resultJson: { id },
      },
      timestamp: 1,
      traceId: `trace-history-detail-${id}`,
    },
  };
}

const componentStubs = {
  HistoryFilterBar: {
    name: 'HistoryFilterBar',
    props: ['loading', 'defaultLimit'],
    emits: ['filter'],
    template: '<div data-test="history-filter-bar"></div>',
  },
  HistoryListPanel: {
    name: 'HistoryListPanel',
    props: [
      'items',
      'loading',
      'loadingMore',
      'appendError',
      'isMobile',
      'isCompactDesktop',
      'page',
      'pageSize',
      'total',
      'hasNext',
    ],
    emits: ['select', 'pageChange', 'loadMore'],
    template: `
      <section data-test="history-list">
        <button
          v-for="item in items"
          :key="item.id"
          type="button"
          :data-test="'history-item-' + item.id"
          @click="$emit('select', item)"
        >
          <span>{{ item.bookName }}</span>
          <span>{{ item.summaryPreview }}</span>
          <span
            v-for="snippet in item.matchSnippets || []"
            :key="snippet"
            data-test="history-match-snippets"
          >{{ snippet }}</span>
        </button>
        <button
          v-if="hasNext"
          type="button"
          data-test="history-load-more"
          @click="$emit('loadMore')"
        >Load more</button>
      </section>
    `,
  },
  HistoryDetailPanel: {
    name: 'HistoryDetailPanel',
    props: ['item', 'loading', 'error'],
    template: `
      <section data-test="history-detail">
        <span v-if="loading">Loading detail</span>
        <span v-else-if="error">{{ error }}</span>
        <span v-else-if="item">{{ item.resultContent }}</span>
      </section>
    `,
  },
  ElDrawer: {
    name: 'ElDrawer',
    props: ['modelValue'],
    template: '<div v-if="modelValue" data-test="history-drawer"><slot /></div>',
  },
  ElButton: {
    name: 'ElButton',
    emits: ['click'],
    template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
  },
};

async function mountHistoryView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/history', component: HistoryView }],
  });
  await router.push('/history');

  const wrapper = mount(HistoryView, {
    global: {
      plugins: [router],
      stubs: componentStubs,
    },
  });

  await flushPromises();
  await nextTick();
  return wrapper;
}

describe('HistoryView', () => {
  beforeEach(() => {
    vi.mocked(dataApi.getHistory).mockReset();
    vi.mocked(dataApi.getHistoryDetail).mockReset();
    vi.mocked(dataApi.getHistory).mockResolvedValue(historyResponse());
    vi.mocked(dataApi.getHistoryDetail).mockImplementation(async (id: number) => (
      detailResponse(id, id === 1 ? 'Analysis result A' : 'Analysis result B')
    ));
  });

  test('loads history list with default query', async () => {
    const wrapper = await mountHistoryView();

    expect(vi.mocked(dataApi.getHistory)).toHaveBeenCalledWith({
      platform: 'fanqie',
      page: 1,
      pageSize: 20,
    });
    expect(vi.mocked(dataApi.getHistoryDetail)).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('Book Alpha');
    expect(wrapper.text()).toContain('Book Beta');
  });

  test('renders legacy array history response', async () => {
    vi.mocked(dataApi.getHistory).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: [...historyItems],
        timestamp: 1,
        traceId: 'trace-history-array',
      },
    });

    const wrapper = await mountHistoryView();

    expect(wrapper.text()).toContain('Book Alpha');
    expect(wrapper.text()).toContain('Book Beta');
  });

  test('clicking history item opens the detail drawer', async () => {
    const wrapper = await mountHistoryView();

    expect(wrapper.find('[data-test="history-drawer"]').exists()).toBe(false);

    await wrapper.find('[data-test="history-item-1"]').trigger('click');
    await flushPromises();
    await nextTick();

    expect(vi.mocked(dataApi.getHistoryDetail)).toHaveBeenCalledWith(1);
    expect(wrapper.find('[data-test="history-drawer"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="history-detail"]').text()).toContain('Analysis result A');
  });

  test('mobile browser back closes the detail drawer', async () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 390,
    });
    const wrapper = await mountHistoryView();

    await wrapper.find('[data-test="history-item-1"]').trigger('click');
    await flushPromises();
    await nextTick();
    expect(wrapper.find('[data-test="history-drawer"]').exists()).toBe(true);

    window.dispatchEvent(new PopStateEvent('popstate'));
    await flushPromises();
    await nextTick();

    expect(wrapper.find('[data-test="history-drawer"]').exists()).toBe(false);
  });

  test('ignores stale detail responses after quickly selecting another row', async () => {
    const firstDetail = deferred<Awaited<ReturnType<typeof dataApi.getHistoryDetail>>>();
    const secondDetail = deferred<Awaited<ReturnType<typeof dataApi.getHistoryDetail>>>();
    vi.mocked(dataApi.getHistoryDetail)
      .mockReturnValueOnce(firstDetail.promise)
      .mockReturnValueOnce(secondDetail.promise);

    const wrapper = await mountHistoryView();

    await wrapper.find('[data-test="history-item-1"]').trigger('click');
    await wrapper.find('[data-test="history-item-2"]').trigger('click');

    secondDetail.resolve(detailResponse(2, 'Analysis result B'));
    await flushPromises();
    await nextTick();

    expect(wrapper.find('[data-test="history-detail"]').text()).toContain('Analysis result B');

    firstDetail.resolve(detailResponse(1, 'Analysis result A'));
    await flushPromises();
    await nextTick();

    expect(wrapper.find('[data-test="history-detail"]').text()).toContain('Analysis result B');
    expect(wrapper.find('[data-test="history-detail"]').text()).not.toContain('Analysis result A');
  });

  test('uses list item detail fallback when detail endpoint fails', async () => {
    vi.mocked(dataApi.getHistory).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            ...historyItems[0],
            resultContent: 'Legacy full analysis content',
            resultJson: { legacy: true },
          },
        ],
        timestamp: 1,
        traceId: 'trace-history-array',
      },
    });
    vi.mocked(dataApi.getHistoryDetail).mockRejectedValueOnce(new Error('detail failed'));

    const wrapper = await mountHistoryView();

    await wrapper.find('[data-test="history-item-1"]').trigger('click');
    await flushPromises();
    await nextTick();

    expect(wrapper.find('[data-test="history-detail"]').text()).toContain('Legacy full analysis content');
  });

  test('filters refetch with analysisType', async () => {
    const wrapper = await mountHistoryView();

    wrapper.findComponent({ name: 'HistoryFilterBar' }).vm.$emit('filter', { analysisType: 'plot', pageSize: 20 });
    await flushPromises();
    await nextTick();

    expect(vi.mocked(dataApi.getHistory)).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      analysisType: 'plot',
      page: 1,
      pageSize: 20,
    });
  });

  test('searches history by project-aware keyword and renders match snippets', async () => {
    const wrapper = await mountHistoryView();

    wrapper.findComponent({ name: 'HistoryFilterBar' }).vm.$emit('filter', { keyword: 'urban-brain', pageSize: 20 });
    await flushPromises();
    await nextTick();

    expect(vi.mocked(dataApi.getHistory)).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      keyword: 'urban-brain',
      page: 1,
      pageSize: 20,
    });
    expect(wrapper.find('[data-test="history-match-snippets"]').text()).toContain('Lead lane urban-brain');
  });

  test('loads the next page on mobile with load more', async () => {
    const originalWidth = window.innerWidth;
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 390,
    });
    window.dispatchEvent(new Event('resize'));

    vi.mocked(dataApi.getHistory)
      .mockResolvedValueOnce(historyResponse([historyItems[0]], { total: 2, hasNext: true }))
      .mockResolvedValueOnce(historyResponse([historyItems[1]], { page: 2, total: 2, hasNext: false }));

    const wrapper = await mountHistoryView();

    await wrapper.get('[data-test="history-load-more"]').trigger('click');
    await flushPromises();
    await nextTick();

    expect(vi.mocked(dataApi.getHistory)).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      page: 2,
      pageSize: 20,
    });
    expect(wrapper.text()).toContain('Book Alpha');
    expect(wrapper.text()).toContain('Book Beta');

    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: originalWidth,
    });
  });
});
