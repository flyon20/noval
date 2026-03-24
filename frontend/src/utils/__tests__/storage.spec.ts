import {
  clearTokenSnapshot,
  persistTokenSnapshot,
  readTokenSnapshot,
} from '@/utils/storage';
import { ACCESS_TOKEN_KEY, TOKEN_EXPIRE_AT_KEY, TOKEN_TYPE_KEY } from '@/constants/auth';

describe('storage utils', () => {
  test('persists and reads token snapshot', () => {
    persistTokenSnapshot({
      accessToken: 'token-1',
      tokenType: 'Bearer',
      expireAt: 1_710_007_200_000,
    });

    expect(readTokenSnapshot()).toEqual({
      accessToken: 'token-1',
      tokenType: 'Bearer',
      expireAt: 1_710_007_200_000,
    });
  });

  test('clears persisted token snapshot', () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'token-1');
    localStorage.setItem(TOKEN_TYPE_KEY, 'Bearer');
    localStorage.setItem(TOKEN_EXPIRE_AT_KEY, '1710007200000');

    clearTokenSnapshot();

    expect(readTokenSnapshot()).toBeNull();
  });
});
