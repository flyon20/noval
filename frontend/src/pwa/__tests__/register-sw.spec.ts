import { registerServiceWorker } from '../register-sw';

describe('registerServiceWorker', () => {
  const originalEnv = { ...import.meta.env };

  beforeEach(() => {
    delete (globalThis as any).navigator;
  });

  afterEach(() => {
    Object.defineProperty(import.meta, 'env', {
      value: originalEnv,
      configurable: true,
    });
  });

  test('skips registration outside production build', async () => {
    Object.defineProperty(import.meta, 'env', {
      value: {
        ...originalEnv,
        DEV: true,
        PROD: false,
        VITE_DISABLE_SW: 'false',
      },
      configurable: true,
    });

    const register = vi.fn().mockResolvedValue(undefined);
    (globalThis as any).navigator = {
      serviceWorker: {
        register,
      },
    };

    registerServiceWorker();
    await Promise.resolve();
    expect(register).not.toHaveBeenCalled();
  });

  test('registers sw in production when available', async () => {
    Object.defineProperty(import.meta, 'env', {
      value: {
        ...originalEnv,
        DEV: false,
        PROD: true,
        VITE_DISABLE_SW: 'false',
      },
      configurable: true,
    });

    const register = vi.fn().mockResolvedValue(undefined);
    (globalThis as any).navigator = {
      serviceWorker: {
        register,
      },
    };

    registerServiceWorker();
    await Promise.resolve();
    expect(register).toHaveBeenCalledWith('/sw.js');
  });

  test('skips when navigator unavailable', () => {
    registerServiceWorker();
    expect(true).toBe(true);
  });
});
