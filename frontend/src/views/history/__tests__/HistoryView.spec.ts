import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter } from 'vue-router';
import { nextTick } from 'vue';
import HistoryView from '../HistoryView.vue';
import HistoryFilterBar from '@/components/history/HistoryFilterBar.vue';
import { dataApi } from '@/api/data';

vi.mock('@/api/data', () => ({
  dataApi: {
    getHistory: vi.fn(),
  },
}));

const historyItems = [
  {
    id: 1,
    bookId: 1001,
    bookName: '绝影长河',
    analysisType: 'deconstruct',
    chapterCount: 5,
    modelName: 'dify',
    resultContent: '分析结果 A',
    resultJson: {},
    createdAt: '2026-03-21 16:00:00',
  },
  {
    id: 2,
    bookId: 1002,
    bookName: '暮海残星',
    analysisType: 'structure',
    chapterCount: 3,
    modelName: 'dify',
    resultContent: '分析结果 B',
    resultJson: {},
    createdAt: '2026-03-20 12:00:00',
  },
];

describe('HistoryView', () => {
  beforeEach(() => {
    vi.mocked(dataApi.getHistory).mockClear();
    vi.mocked(dataApi.getHistory).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: historyItems,
        timestamp: 1,
        traceId: 'trace-history',
      },
    });
  });

  test('loads history list with default query', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/history', component: HistoryView }],
    });
    await router.push('/history');

    const wrapper = mount(HistoryView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await nextTick();
    await nextTick();

    expect(vi.mocked(dataApi.getHistory)).toHaveBeenCalledWith({
      platform: 'fanqie',
      limit: 20,
    });
    expect(wrapper.text()).toContain('绝影长河');
    expect(wrapper.text()).toContain('暮海残星');
  });

  test('clicking history shows detail', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/history', component: HistoryView }],
    });
    await router.push('/history');

    const wrapper = mount(HistoryView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await nextTick();
    await nextTick();

    const listItem = wrapper.find('[data-test="history-item-1"]');
    await listItem.trigger('click');
    await nextTick();

    expect(wrapper.find('[data-test="history-detail"]').text()).toContain('分析结果 A');
  });

  test('filters refetch with analysisType', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/history', component: HistoryView }],
    });
    await router.push('/history');

    const wrapper = mount(HistoryView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await nextTick();
    await nextTick();

    const filterBar = wrapper.findComponent(HistoryFilterBar);
    filterBar.vm.$emit('filter', { analysisType: 'plot', limit: 20 });
    await nextTick();

    expect(vi.mocked(dataApi.getHistory)).toHaveBeenLastCalledWith({
      platform: 'fanqie',
      analysisType: 'plot',
      limit: 20,
    });
  });
});
