import { shallowRef } from 'vue';
import type { AuthSession, TokenResponse } from '@/types/auth';
import { buildAuthSession } from '@/utils/jwt';
import { clearTokenSnapshot, persistTokenSnapshot } from '@/utils/storage';

export const authSessionRef = shallowRef<AuthSession | null>(null);

export function getCurrentSession() {
  return authSessionRef.value;
}

export function getAccessToken() {
  return authSessionRef.value?.accessToken ?? null;
}

export function setCurrentSession(session: AuthSession | null) {
  authSessionRef.value = session;

  if (!session) {
    clearTokenSnapshot();
    return;
  }

  // Kept for compatibility, but storage no longer persists auth tokens.
  persistTokenSnapshot({
    accessToken: session.accessToken,
    tokenType: session.tokenType,
    expireAt: session.expireAt,
  });
}

export function applyTokenResponse(response: TokenResponse) {
  const session = buildAuthSession(response);
  setCurrentSession(session);

  return session;
}

export function restoreSessionFromStorage() {
  clearTokenSnapshot();
  authSessionRef.value = null;
  return null;
}

export function clearCurrentSession() {
  setCurrentSession(null);
}
