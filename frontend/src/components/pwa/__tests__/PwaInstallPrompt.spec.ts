import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import PwaInstallPrompt from '../PwaInstallPrompt.vue';

function createInstallEvent() {
  const event = new Event('beforeinstallprompt', { cancelable: true }) as BeforeInstallPromptEvent;
  Object.defineProperties(event, {
    platforms: { value: ['web'] },
    userChoice: { value: Promise.resolve({ outcome: 'accepted', platform: 'web' }) },
    prompt: { value: vi.fn().mockResolvedValue(undefined) },
  });
  return event;
}

describe('PwaInstallPrompt', () => {
  beforeEach(() => {
    Object.defineProperty(window.navigator, 'userAgent', {
      configurable: true,
      value: 'Mozilla/5.0 Android Mobile',
    });
    Object.defineProperty(window.navigator, 'standalone', {
      configurable: true,
      value: false,
    });
    Object.defineProperty(window, 'onbeforeinstallprompt', {
      configurable: true,
      writable: true,
      value: null,
    });
    vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
      matches: query.includes('standalone'),
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 390,
    });
  });

  test('stays hidden when already running as an installed app', async () => {
    const wrapper = mount(PwaInstallPrompt, {
      global: { plugins: [ElementPlus] },
    });
    window.dispatchEvent(createInstallEvent());
    await nextTick();

    expect(wrapper.find('[data-test="pwa-install-prompt"]').exists()).toBe(false);
  });

  test('shows install prompt after beforeinstallprompt in browser mode', async () => {
    vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
    const wrapper = mount(PwaInstallPrompt, {
      global: { plugins: [ElementPlus] },
    });

    window.dispatchEvent(createInstallEvent());
    await nextTick();

    expect(wrapper.find('[data-test="pwa-install-prompt"]').exists()).toBe(true);
  });

  test('shows manual install guidance on mobile before the browser exposes an install event', async () => {
    vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));

    const wrapper = mount(PwaInstallPrompt, {
      global: { plugins: [ElementPlus] },
    });
    await nextTick();

    expect(wrapper.find('[data-test="pwa-install-prompt"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="pwa-install-manual"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="pwa-install-guide"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('添加到主屏幕');
  });

  test('shows manual mobile install guidance when browser prompt is unavailable', async () => {
    Reflect.deleteProperty(window, 'onbeforeinstallprompt');
    vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));

    const wrapper = mount(PwaInstallPrompt, {
      global: { plugins: [ElementPlus] },
    });
    await nextTick();

    expect(wrapper.find('[data-test="pwa-install-prompt"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="pwa-install-manual"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('添加到主屏幕');
    expect(wrapper.text()).not.toContain('APK');
    expect(wrapper.text()).not.toContain('安装包');
  });

  test('shows iOS share guidance when Safari cannot trigger install prompt', async () => {
    Reflect.deleteProperty(window, 'onbeforeinstallprompt');
    Object.defineProperty(window.navigator, 'userAgent', {
      configurable: true,
      value: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1',
    });
    vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));

    const wrapper = mount(PwaInstallPrompt, {
      global: { plugins: [ElementPlus] },
    });
    await nextTick();

    expect(wrapper.find('[data-test="pwa-install-manual"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('分享');
    expect(wrapper.text()).toContain('添加到主屏幕');
  });

  test('stays hidden when iOS reports standalone mode', async () => {
    Reflect.deleteProperty(window, 'onbeforeinstallprompt');
    Object.defineProperty(window.navigator, 'standalone', {
      configurable: true,
      value: true,
    });
    vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));

    const wrapper = mount(PwaInstallPrompt, {
      global: { plugins: [ElementPlus] },
    });
    await nextTick();

    expect(wrapper.find('[data-test="pwa-install-prompt"]').exists()).toBe(false);
  });
});
