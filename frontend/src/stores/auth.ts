import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { authApi } from '@/api/auth';
import { HOME_ROUTE, LOGIN_ROUTE } from '@/constants/auth';
import { bootstrapAuthSession } from '@/lib/auth-bootstrap';
import {
  applyTokenResponse as applyTokenResponseToSession,
  authSessionRef,
  clearCurrentSession,
  getCurrentSession,
  restoreSessionFromStorage,
} from '@/lib/auth-session';
import type { AuthRestoreStatus, RoleCode, TokenResponse } from '@/types/auth';

export const useAuthStore = defineStore('auth', () => {
  const session = computed(() => authSessionRef.value);
  const isAuthenticated = computed(() => !!authSessionRef.value);
  const restoreStatus = ref<AuthRestoreStatus>(authSessionRef.value ? 'authenticated' : 'logged_out');
  let restorePromise: Promise<ReturnType<typeof getCurrentSession>> | null = null;

  function syncRestoreStatus() {
    restoreStatus.value = authSessionRef.value ? 'authenticated' : 'logged_out';
  }

  function restoreSession() {
    const restored = restoreSessionFromStorage();
    syncRestoreStatus();
    return restored;
  }

  async function ensureAuthRestored() {
    if (restoreStatus.value === 'authenticated') {
      return authSessionRef.value;
    }

    if (!restorePromise) {
      restoreStatus.value = 'restoring';
      restorePromise = bootstrapAuthSession().finally(() => {
        syncRestoreStatus();
        restorePromise = null;
      });
    }

    return restorePromise;
  }

  function applyTokenResponse(tokenResponse: TokenResponse) {
    const restored = applyTokenResponseToSession(tokenResponse);
    syncRestoreStatus();
    return restored;
  }

  function clearSession() {
    clearCurrentSession();
    syncRestoreStatus();
  }

  function hasRole(role: RoleCode) {
    return session.value?.roles.includes(role) ?? false;
  }

  async function logout() {
    try {
      await authApi.logout();
    } finally {
      clearCurrentSession();
      syncRestoreStatus();
    }
  }

  return {
    session,
    isAuthenticated,
    restoreStatus,
    restoreSession,
    ensureAuthRestored,
    applyTokenResponse,
    clearSession,
    hasRole,
    logout,
    loginRoute: LOGIN_ROUTE,
    homeRoute: HOME_ROUTE,
    getCurrentSession,
  };
});
