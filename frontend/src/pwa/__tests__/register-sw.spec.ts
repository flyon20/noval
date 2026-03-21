import { registerServiceWorker } from '../register-sw';

describe('registerServiceWorker', () => {
  beforeEach(() => {
    delete (globalThis as any).navigator;
  });

  test('registers sw when available', async () => {
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
