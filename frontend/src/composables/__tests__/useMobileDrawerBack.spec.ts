import { mount } from '@vue/test-utils';
import { defineComponent, nextTick, ref } from 'vue';
import { useMobileDrawerBack } from '../useMobileDrawerBack';

const mountedWrappers: ReturnType<typeof mount>[] = [];

function setWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  });
  window.dispatchEvent(new Event('resize'));
}

function createHarness(options: {
  open?: boolean;
  detail?: boolean;
  close?: ReturnType<typeof vi.fn>;
  backDetail?: ReturnType<typeof vi.fn>;
  isMobile?: () => boolean;
  mobileWidth?: number;
} = {}) {
  const close = options.close ?? vi.fn();
  const backDetail = options.backDetail ?? vi.fn();
  const exposed = {} as {
    isOpen: ReturnType<typeof ref<boolean>>;
    hasDetail: ReturnType<typeof ref<boolean>>;
  };

  const wrapper = mount(defineComponent({
    setup() {
      const isOpen = ref(options.open ?? false);
      const hasDetail = ref(options.detail ?? false);
      exposed.isOpen = isOpen;
      exposed.hasDetail = hasDetail;
      useMobileDrawerBack({
        isOpen: () => isOpen.value,
        close,
        hasInnerDetail: () => hasDetail.value,
        backInnerDetail: () => {
          hasDetail.value = false;
          backDetail();
        },
        mobileWidth: options.mobileWidth,
        isMobile: options.isMobile,
      });
      return { isOpen };
    },
    template: '<div />',
  }));
  mountedWrappers.push(wrapper);

  return { wrapper, close, backDetail, exposed };
}

describe('useMobileDrawerBack', () => {
  let pushStateSpy: ReturnType<typeof vi.spyOn>;
  let backSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    setWidth(390);
    pushStateSpy = vi.spyOn(window.history, 'pushState');
    backSpy = vi.spyOn(window.history, 'back');
  });

  afterEach(() => {
    for (const wrapper of mountedWrappers.splice(0)) {
      wrapper.unmount();
    }
    vi.restoreAllMocks();
  });

  test('pushes history when a mobile drawer opens and closes it on popstate', async () => {
    const { close } = createHarness({ open: true });
    await nextTick();

    expect(pushStateSpy).toHaveBeenCalledTimes(1);

    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(close).toHaveBeenCalledTimes(1);
  });

  test('backs out of inner detail before closing the drawer', async () => {
    const { close, backDetail, exposed } = createHarness({ open: true, detail: true });
    await nextTick();

    window.dispatchEvent(new PopStateEvent('popstate'));

    expect(backDetail).toHaveBeenCalledTimes(1);
    expect(exposed.hasDetail.value).toBe(false);
    expect(close).not.toHaveBeenCalled();

    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(close).toHaveBeenCalledTimes(1);
  });

  test('only the topmost open drawer handles browser back', async () => {
    const firstClose = vi.fn();
    const secondClose = vi.fn();
    createHarness({ open: true, close: firstClose });
    createHarness({ open: true, close: secondClose });
    await nextTick();

    window.dispatchEvent(new PopStateEvent('popstate'));

    expect(secondClose).toHaveBeenCalledTimes(1);
    expect(firstClose).not.toHaveBeenCalled();
  });

  test('underlying drawer ignores popstate that returns to its own state', async () => {
    const firstClose = vi.fn();
    const secondClose = vi.fn();
    createHarness({ open: true, close: firstClose });
    const second = createHarness({ open: true, close: secondClose });
    await nextTick();
    const firstDrawerState = pushStateSpy.mock.calls[0][0];

    second.exposed.isOpen.value = false;
    await nextTick();
    window.dispatchEvent(new PopStateEvent('popstate', { state: firstDrawerState }));

    expect(backSpy).toHaveBeenCalledTimes(1);
    expect(firstClose).not.toHaveBeenCalled();
    expect(secondClose).not.toHaveBeenCalled();
  });

  test('inner detail handles popstate when returning to the current drawer state', async () => {
    const { close, backDetail } = createHarness({ open: true, detail: true });
    await nextTick();
    const drawerState = pushStateSpy.mock.calls[0][0];

    window.dispatchEvent(new PopStateEvent('popstate', { state: drawerState }));

    expect(backDetail).toHaveBeenCalledTimes(1);
    expect(close).not.toHaveBeenCalled();
  });

  test('model close consumes the drawer history entry without trapping browser back', async () => {
    const { close, exposed } = createHarness({ open: true });
    await nextTick();

    exposed.isOpen.value = false;
    await nextTick();

    expect(backSpy).toHaveBeenCalledTimes(1);
    expect(close).not.toHaveBeenCalled();
  });

  test('respects a caller-provided mobile predicate at the exact breakpoint', async () => {
    setWidth(920);
    createHarness({
      open: true,
      mobileWidth: 920,
      isMobile: () => window.innerWidth <= 920,
    });
    await nextTick();

    expect(pushStateSpy).toHaveBeenCalledTimes(1);
  });
});
