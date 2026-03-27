import { ACCESS_TOKEN_KEY, TOKEN_EXPIRE_AT_KEY, TOKEN_TYPE_KEY } from '@/constants/auth';
import type { TokenSnapshot } from '@/types/auth';

export function persistTokenSnapshot(_snapshot: TokenSnapshot) {
  clearTokenSnapshot();
}

export function readTokenSnapshot(): TokenSnapshot | null {
  clearTokenSnapshot();
  return null;
}

export function clearTokenSnapshot() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(TOKEN_TYPE_KEY);
  localStorage.removeItem(TOKEN_EXPIRE_AT_KEY);
}
