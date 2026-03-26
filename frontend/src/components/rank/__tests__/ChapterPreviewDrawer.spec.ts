import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import ChapterPreviewDrawer from '../ChapterPreviewDrawer.vue';

describe('ChapterPreviewDrawer', () => {
  test('shows excerpt list then opens full chapter detail', async () => {
    const longContent = '第一章内容'.repeat(80);
    const wrapper = mount(ChapterPreviewDrawer, {
      attachTo: document.body,
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

    expect(document.body.textContent).toContain('Chapter 1');
    expect(document.body.textContent).not.toContain(longContent);

    (document.body.querySelector('[data-testid="chapter-item-1"]') as HTMLElement)?.click();
    await flushPromises();

    expect(document.body.textContent).toContain(longContent);
    expect(document.body.querySelector('[data-testid="chapter-back"]')).not.toBeNull();
    expect(document.body.textContent).not.toContain('分析参数');
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('appendToBody')).toBe(true);
    wrapper.unmount();
  });

  test('uses refresh / analysis / close action order in the primary toolbar', async () => {
    const wrapper = mount(ChapterPreviewDrawer, {
      attachTo: document.body,
      props: {
        modelValue: true,
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
        chapters: [],
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();

    const buttons = Array.from(document.body.querySelectorAll('.chapter-drawer__actions--primary .el-button'))
      .map((item) => item.textContent?.trim() ?? '');
    expect(buttons).toEqual(['重新抓取章节', '进入分析页', '关闭']);
    wrapper.unmount();
  });

  test('emits close when tapping the drawer close button', async () => {
    const wrapper = mount(ChapterPreviewDrawer, {
      attachTo: document.body,
      props: {
        modelValue: true,
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
        chapters: [],
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();
    (document.body.querySelector('[data-testid="chapter-close"]') as HTMLElement)?.click();

    expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
    wrapper.unmount();
  });
});
