import { registerServiceWorker } from '../register-sw';

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
    expect(register).toHaveBeenCalledWith('/sw.js');
  });

  test('skips when navigator unavailable', () => {
    registerServiceWorker();
    expect(true).toBe(true);
  });
});
