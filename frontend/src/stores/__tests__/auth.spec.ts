import { createPinia, setActivePinia } from 'pinia';
import { useAuthStore } from '@/stores/auth';
import { authSessionRef } from '@/lib/auth-session';
import { createJwtToken } from '@/test/helpers';

vi.mock('@/lib/auth-bootstrap', () => ({
  bootstrapAuthSession: vi.fn(),
}));

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    authSessionRef.value = null;
  });

  test('hydrates session through bootstrap and stores access token in memory', async () => {
    const { bootstrapAuthSession } = await import('@/lib/auth-bootstrap');
    const bootstrappedSession = {
      userId: 9,
      username: 'alice',
      roles: ['USER'],
      accessToken: createJwtToken({
        sub: 'alice',
        uid: 9,
        username: 'alice',
        roles: 'USER',
        iat: 2_100_000_000,
        exp: 2_100_007_200,
      }),
      tokenType: 'Bearer',
      expiresIn: 7200,
      expireAt: 2_100_007_200_000,
      jwtExp: 2_100_007_200,
    };
    vi.mocked(bootstrapAuthSession).mockImplementation(async () => {
      authSessionRef.value = bootstrappedSession;
      return bootstrappedSession;
    });

    const authStore = useAuthStore();
    const restored = await authStore.ensureAuthRestored();

    expect(bootstrapAuthSession).toHaveBeenCalledTimes(1);
    expect(restored?.username).toBe('alice');
    expect(authStore.session?.username).toBe('alice');
    expect(authStore.session?.roles).toEqual(['USER']);
    expect(authStore.isAuthenticated).toBe(true);
    expect(authStore.restoreStatus).toBe('authenticated');
  });

  test('failed bootstrap only attempts refresh once and remains logged out', async () => {
    const { bootstrapAuthSession } = await import('@/lib/auth-bootstrap');
    vi.mocked(bootstrapAuthSession).mockResolvedValue(null);

    const authStore = useAuthStore();

    await authStore.ensureAuthRestored();
    await authStore.ensureAuthRestored();

    expect(bootstrapAuthSession).toHaveBeenCalledTimes(1);
    expect(authStore.session).toBeNull();
    expect(authStore.isAuthenticated).toBe(false);
    expect(authStore.restoreStatus).toBe('logged_out');
  });
});
