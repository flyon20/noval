import { createPinia, setActivePinia } from 'pinia';
import { useAuthStore } from '@/stores/auth';
import { persistTokenSnapshot } from '@/utils/storage';
import { createJwtToken } from '@/test/helpers';

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  test('restores session from localStorage', () => {
    persistTokenSnapshot({
      accessToken: createJwtToken({
        sub: 'alice',
        uid: 9,
        username: 'alice',
        roles: 'USER',
        iat: 2_100_000_000,
        exp: 2_100_007_200,
      }),
      tokenType: 'Bearer',
      expireAt: 2_100_007_200_000,
    });

    const authStore = useAuthStore();
    authStore.restoreSession();

    expect(authStore.session?.username).toBe('alice');
    expect(authStore.session?.roles).toEqual(['USER']);
    expect(authStore.isAuthenticated).toBe(true);
  });
});
