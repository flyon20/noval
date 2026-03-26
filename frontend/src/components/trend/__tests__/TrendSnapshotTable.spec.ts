import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import TrendSnapshotTable from '../TrendSnapshotTable.vue';

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  });
  window.dispatchEvent(new Event('resize'));
}

const snapshots = [
  {
    snapshotTime: '2026-03-20 11:30:00',
    bookCount: 20,
    topBookName: '脑洞之王',
    topBookAuthor: '作者甲',
  },
];

describe('TrendSnapshotTable', () => {
  test('renders desktop table when viewport is wide', async () => {
    setViewportWidth(1280);
    const wrapper = mount(TrendSnapshotTable, {
      props: {
        snapshots,
        sampleCount: 1,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.find('[data-test="trend-snapshot-mobile-list"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ElTable' }).exists()).toBe(true);
  });

  test('renders mobile cards instead of a table on narrow screens', async () => {
    setViewportWidth(390);
    const wrapper = mount(TrendSnapshotTable, {
      props: {
        snapshots,
        sampleCount: 1,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.find('[data-test="trend-snapshot-mobile-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="trend-snapshot-mobile-item-0"]').text()).toContain('脑洞之王');
    expect(wrapper.findComponent({ name: 'ElTable' }).exists()).toBe(false);
  });
});
