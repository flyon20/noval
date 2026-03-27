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

describe('auth api logout contract', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  test('logout posts to backend with credentials and without token body payload', async () => {
    const clearCurrentSession = vi.fn();
    const getAccessToken = vi.fn().mockReturnValue('access-token');
    const post = vi.fn().mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: null,
        timestamp: 1,
        traceId: 'trace-logout',
      },
    });

    vi.doMock('@/lib/auth-session', () => ({
      clearCurrentSession,
      getAccessToken,
    }));
    vi.doMock('@/lib/http', () => ({
      httpClient: {
        post,
      },
      rawHttpClient: {
        post: vi.fn(),
      },
    }));

    const { authApi } = await import('@/api/auth');

    await authApi.logout();

    expect(getAccessToken).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/auth/logout', undefined, {
      skipAuthRefresh: true,
      withCredentials: true,
    });
    expect(clearCurrentSession).not.toHaveBeenCalled();
  });

  test('login and register call auth endpoints with credentials for refresh cookie', async () => {
    const rawPost = vi.fn().mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: null,
        timestamp: 1,
        traceId: 'trace-auth',
      },
    });

    vi.doMock('@/lib/auth-session', () => ({
      clearCurrentSession: vi.fn(),
      getAccessToken: vi.fn().mockReturnValue(null),
    }));
    vi.doMock('@/lib/http', () => ({
      httpClient: {
        post: vi.fn(),
      },
      rawHttpClient: {
        post: rawPost,
      },
    }));

    const { authApi } = await import('@/api/auth');

    await authApi.login({ username: 'demo', password: 'Password123' });
    await authApi.register({ username: 'new-user', password: 'Password123' });

    expect(rawPost).toHaveBeenNthCalledWith(1, '/api/auth/login', {
      username: 'demo',
      password: 'Password123',
    }, {
      withCredentials: true,
    });
    expect(rawPost).toHaveBeenNthCalledWith(2, '/api/auth/register', {
      username: 'new-user',
      password: 'Password123',
    }, {
      withCredentials: true,
    });
  });
});
