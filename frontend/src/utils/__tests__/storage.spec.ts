import {
  clearTokenSnapshot,
  persistTokenSnapshot,
  readTokenSnapshot,
} from '@/utils/storage';
import { ACCESS_TOKEN_KEY, TOKEN_EXPIRE_AT_KEY, TOKEN_TYPE_KEY } from '@/constants/auth';

describe('storage utils', () => {
  test('persistTokenSnapshot no longer stores access tokens in localStorage', () => {
    persistTokenSnapshot({
      accessToken: 'token-1',
      tokenType: 'Bearer',
      expireAt: 1_710_007_200_000,
    });

    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(TOKEN_TYPE_KEY)).toBeNull();
    expect(localStorage.getItem(TOKEN_EXPIRE_AT_KEY)).toBeNull();
    expect(readTokenSnapshot()).toBeNull();
  });

  test('clears persisted token snapshot', () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'token-1');
    localStorage.setItem(TOKEN_TYPE_KEY, 'Bearer');
    localStorage.setItem(TOKEN_EXPIRE_AT_KEY, '1710007200000');

    clearTokenSnapshot();

    expect(readTokenSnapshot()).toBeNull();
  });
});
