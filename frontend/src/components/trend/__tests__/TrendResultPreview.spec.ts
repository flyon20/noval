import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import TrendResultPreview from '../TrendResultPreview.vue';

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  });
  window.dispatchEvent(new Event('resize'));
}

describe('TrendResultPreview', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  test('wires the mobile edge-swipe close affordance on the detail drawer surface', async () => {
    const source = await import('../TrendResultPreview.vue?raw');

    expect(source.default).toContain('useMobileEdgeSwipeClose');
    expect(source.default).toContain('useMobileDrawerBack');
    expect(source.default).toContain('detailDrawerSwipe.onTouchStart');
    expect(source.default).toContain('detailDrawerSwipe.onTouchEnd');
    expect(source.default).toContain('detailDrawerSwipe.onPointerStart');
    expect(source.default).toContain('detailDrawerSwipe.onPointerEnd');
  });

  test('mobile browser back closes the detail drawer at the exact mobile breakpoint', async () => {
    setViewportWidth(768);
    const wrapper = mount(TrendResultPreview, {
      attachTo: document.body,
      props: {
        phase: 'done',
        resultContent: '# Trend detail',
        resultSummary: 'Trend summary',
        comparisonSummary: 'Comparison summary',
        keyPoints: ['Point A'],
        resultMeta: {
          traceId: 'trace-trend',
          modelName: 'deepseek-chat',
        },
      },
      global: {
        plugins: [ElementPlus],
      },
    });
    await flushPromises();

    await wrapper.get('[data-test="trend-result-detail-open"]').trigger('click');
    await flushPromises();
    expect(document.body.querySelector('[data-test="trend-result-detail-close"]')).not.toBeNull();

    window.dispatchEvent(new PopStateEvent('popstate'));
    await flushPromises();

    expect(document.body.querySelector('[data-test="trend-result-detail-close"]')).toBeNull();
    wrapper.unmount();
  });
});
