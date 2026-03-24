import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import BookDetailDrawer from '../BookDetailDrawer.vue';

describe('BookDetailDrawer', () => {
  test('renders long desktop detail content with a visible title block', async () => {
    const longTitle = '这是一本在桌面抽屉里也应该完整可见的超长书名，用来验证标题不会被挤压隐藏';
    const wrapper = mount(BookDetailDrawer, {
      props: {
        modelValue: true,
        traceId: 'trace-book-detail',
        detail: {
          bookId: 1001,
          platform: 'fanqie',
          bookName: longTitle,
          author: '测试作者',
          intro: '这里是完整简介内容',
          bookUrl: 'https://book.test/1001',
        },
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.get('[data-testid="rank-detail-title"]').text()).toContain(longTitle);
    expect(wrapper.get('[data-testid="rank-detail-meta"]').text()).toContain('测试作者');
    expect(wrapper.get('[data-testid="rank-detail-intro"]').text()).toContain('这里是完整简介内容');
  });
  test('emits close when tapping the drawer close button', async () => {
    const wrapper = mount(BookDetailDrawer, {
      props: {
        modelValue: true,
        detail: {
          bookId: 1001,
          platform: 'fanqie',
          bookName: 'Mobile Book',
          author: 'Author',
          intro: 'Intro',
          bookUrl: 'https://book.test/1001',
        },
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();
    await wrapper.get('[data-testid="rank-detail-close"]').trigger('click');

    expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
  });
});
