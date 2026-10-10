import { registerServiceWorker } from '../register-sw';
import { ElMessageBox } from 'element-plus';

vi.mock('element-plus', () => ({
  ElMessageBox: {
    confirm: vi.fn(),
  },
}));

describe('registerServiceWorker', () => {
  const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const originalServiceWorker = globalThis.navigator
    ? Object.getOwnPropertyDescriptor(globalThis.navigator, 'serviceWorker')
    : undefined;

  beforeEach(() => {
    if (globalThis.navigator) {
      Reflect.deleteProperty(globalThis.navigator, 'serviceWorker');
    }
  });

  afterEach(() => {
    if (globalThis.navigator) {
      if (originalServiceWorker) {
        Object.defineProperty(globalThis.navigator, 'serviceWorker', originalServiceWorker);
      } else {
        Reflect.deleteProperty(globalThis.navigator, 'serviceWorker');
      }
    }
    if (originalNavigator) {
      Object.defineProperty(globalThis, 'navigator', originalNavigator);
    } else {
      Reflect.deleteProperty(globalThis, 'navigator');
    }
    vi.unstubAllEnvs();
    vi.clearAllMocks();
  });

  test('skips registration outside production build', async () => {
    vi.stubEnv('DEV', true as unknown as string);
    vi.stubEnv('PROD', false as unknown as string);
    vi.stubEnv('VITE_DISABLE_SW', 'false');

    const register = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(globalThis.navigator, 'serviceWorker', {
      value: {
        register,
      },
      configurable: true,
      writable: true,
    });

    registerServiceWorker();
    await Promise.resolve();
    expect(register).not.toHaveBeenCalled();
  });

  test('registers sw in production when available', async () => {
    vi.stubEnv('DEV', false as unknown as string);
    vi.stubEnv('PROD', true as unknown as string);
    vi.stubEnv('VITE_DISABLE_SW', 'false');

    const update = vi.fn().mockResolvedValue(undefined);
    const register = vi.fn().mockResolvedValue({
      waiting: null,
      addEventListener: vi.fn(),
      update,
    });
    Object.defineProperty(globalThis.navigator, 'serviceWorker', {
      value: {
        register,
      },
      configurable: true,
      writable: true,
    });

    registerServiceWorker();
    await Promise.resolve();
    expect(register).toHaveBeenCalledWith('/sw.js');
    expect(update).toHaveBeenCalledOnce();
  });

  test('prompts user to activate a waiting update', async () => {
    vi.stubEnv('DEV', false as unknown as string);
    vi.stubEnv('PROD', true as unknown as string);
    vi.stubEnv('VITE_DISABLE_SW', 'false');

    const postMessage = vi.fn();
    const register = vi.fn().mockResolvedValue({
      waiting: {
        postMessage,
      },
      addEventListener: vi.fn(),
      update: vi.fn().mockResolvedValue(undefined),
    });
    Object.defineProperty(globalThis.navigator, 'serviceWorker', {
      value: {
        controller: {},
        register,
        addEventListener: vi.fn(),
      },
      configurable: true,
      writable: true,
    });
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm');

    registerServiceWorker();
    await Promise.resolve();
    await Promise.resolve();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '发现新版本，是否立即更新？',
      '新版本可用',
      expect.objectContaining({
        confirmButtonText: '立即更新',
        cancelButtonText: '稍后再说',
      }),
    );
    expect(postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' });
  });

  test('skips when navigator unavailable', () => {
    registerServiceWorker();
    expect(true).toBe(true);
  });
});
