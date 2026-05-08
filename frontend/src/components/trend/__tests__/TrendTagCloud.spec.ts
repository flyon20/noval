import { mount } from '@vue/test-utils';
import TrendTagCloud from '../TrendTagCloud.vue';

describe('TrendTagCloud', () => {
  test('renders larger words with larger font sizes', () => {
    const wrapper = mount(TrendTagCloud, {
      props: {
        items: [
          { name: '都市脑洞', value: 24 },
          { name: '系统流', value: 8 },
        ],
      },
    });

    const words = wrapper.findAll('text.trend-tag-cloud__tag');
    expect(words).toHaveLength(2);

    const firstFontSize = Number(words[0].attributes('font-size'));
    const secondFontSize = Number(words[1].attributes('font-size'));

    expect(firstFontSize).toBeGreaterThan(secondFontSize);
    expect(wrapper.text()).toContain('都市脑洞');
    expect(wrapper.text()).toContain('系统流');
  });
});
