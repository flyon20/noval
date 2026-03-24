import { ACCESS_TOKEN_KEY, TOKEN_EXPIRE_AT_KEY, TOKEN_TYPE_KEY } from '@/constants/auth';
import type { TokenSnapshot } from '@/types/auth';

export function persistTokenSnapshot(snapshot: TokenSnapshot) {
  localStorage.setItem(ACCESS_TOKEN_KEY, snapshot.accessToken);
  localStorage.setItem(TOKEN_TYPE_KEY, snapshot.tokenType);
  localStorage.setItem(TOKEN_EXPIRE_AT_KEY, String(snapshot.expireAt));
}

export function readTokenSnapshot(): TokenSnapshot | null {
  const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
  const tokenType = localStorage.getItem(TOKEN_TYPE_KEY);
  const expireAt = localStorage.getItem(TOKEN_EXPIRE_AT_KEY);

  if (!accessToken || !tokenType || !expireAt) {
    return null;
  }

  return {
    accessToken,
    tokenType,
    expireAt: Number(expireAt),
  };
}

export function clearTokenSnapshot() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(TOKEN_TYPE_KEY);
  localStorage.removeItem(TOKEN_EXPIRE_AT_KEY);
}
