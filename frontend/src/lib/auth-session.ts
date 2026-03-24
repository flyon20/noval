import { shallowRef } from 'vue';
import type { AuthSession, TokenResponse } from '@/types/auth';
import { buildAuthSession, buildAuthSessionFromSnapshot } from '@/utils/jwt';
import { clearTokenSnapshot, persistTokenSnapshot, readTokenSnapshot } from '@/utils/storage';

export const authSessionRef = shallowRef<AuthSession | null>(null);

export function getCurrentSession() {
  return authSessionRef.value;
}

export function getAccessToken() {
  return authSessionRef.value?.accessToken ?? readTokenSnapshot()?.accessToken ?? null;
}

export function setCurrentSession(session: AuthSession | null) {
  authSessionRef.value = session;

  if (!session) {
    clearTokenSnapshot();
    return;
  }

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
  const snapshot = readTokenSnapshot();

  if (!snapshot) {
    authSessionRef.value = null;
    return null;
  }

  try {
    const session = buildAuthSessionFromSnapshot(snapshot);

    if (!session) {
      clearTokenSnapshot();
      authSessionRef.value = null;
      return null;
    }

    authSessionRef.value = session;
    return session;
  } catch {
    clearTokenSnapshot();
    authSessionRef.value = null;
    return null;
  }
}

export function clearCurrentSession() {
  setCurrentSession(null);
}
