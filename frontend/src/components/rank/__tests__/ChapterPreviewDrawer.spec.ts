import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import ChapterPreviewDrawer from '../ChapterPreviewDrawer.vue';

describe('ChapterPreviewDrawer', () => {
  test('shows excerpt list then opens full chapter detail', async () => {
    const longContent = '第一章内容'.repeat(80);
    const wrapper = mount(ChapterPreviewDrawer, {
      props: {
        modelValue: true,
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
        chapters: [
          {
            bookId: 1001,
            chapterNo: 1,
            chapterTitle: 'Chapter 1',
            content: longContent,
            wordCount: longContent.length,
          },
        ],
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('Chapter 1');
    expect(wrapper.text()).not.toContain(longContent);

    await wrapper.get('[data-testid="chapter-item-1"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain(longContent);
    expect(wrapper.get('[data-testid="chapter-back"]').exists()).toBe(true);
  });
});
