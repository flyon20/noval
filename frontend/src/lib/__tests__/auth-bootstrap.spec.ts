import { afterEach, describe, expect, test, vi } from 'vitest';
import { ACCESS_TOKEN_KEY, TOKEN_EXPIRE_AT_KEY, TOKEN_TYPE_KEY } from '@/constants/auth';
import { bootstrapAuthSession } from '@/lib/auth-bootstrap';
import { clearCurrentSession, getCurrentSession } from '@/lib/auth-session';
import { createJwtToken } from '@/test/helpers';
import type { TokenResponse } from '@/types/auth';

describe('auth bootstrap', () => {
  afterEach(() => {
    clearCurrentSession();
    localStorage.clear();
  });

  test('calls refresh once when memory token is empty', async () => {
    const refresh = vi.fn<() => Promise<TokenResponse>>().mockResolvedValue({
      accessToken: createJwtToken({
        sub: 'alice',
        uid: 9,
        username: 'alice',
        roles: 'USER',
        iat: 2_100_000_000,
        exp: 2_100_000_900,
      }),
      tokenType: 'Bearer',
      expiresIn: 900,
    });

    await bootstrapAuthSession({ refresh });

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  test('stores only refreshed access token in memory', async () => {
    const accessToken = createJwtToken({
      sub: 'alice',
      uid: 9,
      username: 'alice',
      roles: 'USER',
      iat: 2_100_000_000,
      exp: 2_100_000_900,
    });

    await bootstrapAuthSession({
      refresh: vi.fn<() => Promise<TokenResponse>>().mockResolvedValue({
        accessToken,
        tokenType: 'Bearer',
        expiresIn: 900,
      }),
    });

    expect(getCurrentSession()?.accessToken).toBe(accessToken);
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(TOKEN_TYPE_KEY)).toBeNull();
    expect(localStorage.getItem(TOKEN_EXPIRE_AT_KEY)).toBeNull();
  });

  test('keeps app logged out when refresh fails', async () => {
    await bootstrapAuthSession({
      refresh: vi.fn<() => Promise<TokenResponse>>().mockRejectedValue(new Error('refresh failed')),
    });

    expect(getCurrentSession()).toBeNull();
  });
});
