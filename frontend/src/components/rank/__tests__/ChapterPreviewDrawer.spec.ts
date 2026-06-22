import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import ChapterPreviewDrawer from '../ChapterPreviewDrawer.vue';

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  });
  window.dispatchEvent(new Event('resize'));
}

describe('ChapterPreviewDrawer', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

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

  test('wires the mobile edge-swipe close affordance on the drawer surface', async () => {
    const source = await import('../ChapterPreviewDrawer.vue?raw');

    expect(source.default).toContain('useMobileEdgeSwipeClose');
    expect(source.default).toContain('useMobileDrawerBack');
    expect(source.default).toContain('drawerSwipe.onTouchStart');
    expect(source.default).toContain('drawerSwipe.onTouchEnd');
    expect(source.default).toContain('drawerSwipe.onPointerStart');
    expect(source.default).toContain('drawerSwipe.onPointerEnd');
  });

  test('uses a full-screen mobile reader and browser back returns from detail before closing', async () => {
    setViewportWidth(390);
    const longContent = 'Mobile chapter content '.repeat(80);
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

    const drawer = wrapper.findComponent({ name: 'ElDrawer' });
    expect(drawer.props('direction')).toBe('btt');
    expect(drawer.props('size')).toBe('100%');

    (document.body.querySelector('[data-testid="chapter-item-1"]') as HTMLElement)?.click();
    await flushPromises();
    expect(document.body.textContent).toContain(longContent);

    window.dispatchEvent(new PopStateEvent('popstate'));
    await flushPromises();
    expect(document.body.textContent).not.toContain(longContent);
    expect(document.body.querySelector('[data-testid="chapter-item-1"]')).not.toBeNull();

    window.dispatchEvent(new PopStateEvent('popstate'));
    await flushPromises();
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
    wrapper.unmount();
  });

  test('chapter back button consumes the mobile detail history entry', async () => {
    setViewportWidth(390);
    const pushStateSpy = vi.spyOn(window.history, 'pushState');
    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    const longContent = 'Detail content '.repeat(80);
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
    const drawerState = pushStateSpy.mock.calls[0][0];

    (document.body.querySelector('[data-testid="chapter-item-1"]') as HTMLElement)?.click();
    await flushPromises();
    expect(document.body.textContent).toContain(longContent);

    (document.body.querySelector('[data-testid="chapter-back"]') as HTMLElement)?.click();
    await flushPromises();

    expect(backSpy).toHaveBeenCalledTimes(1);

    window.dispatchEvent(new PopStateEvent('popstate', { state: drawerState }));
    await flushPromises();

    expect(document.body.textContent).not.toContain(longContent);
    expect(document.body.querySelector('[data-testid="chapter-item-1"]')).not.toBeNull();
    wrapper.unmount();
  });

  test('mobile detail close clears both synthetic history entries at the exact mobile breakpoint', async () => {
    setViewportWidth(920);
    const goSpy = vi.spyOn(window.history, 'go').mockImplementation(() => undefined);
    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    const longContent = 'Breakpoint detail content '.repeat(60);
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

    (document.body.querySelector('[data-testid="chapter-item-1"]') as HTMLElement)?.click();
    await flushPromises();
    expect(document.body.textContent).toContain(longContent);

    (document.body.querySelector('[data-testid="chapter-close"]') as HTMLElement)?.click();
    await flushPromises();

    expect(goSpy).toHaveBeenCalledWith(-2);
    expect(backSpy).not.toHaveBeenCalled();
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
    wrapper.unmount();
  });
});
