import { computed } from 'vue';
import { defineStore } from 'pinia';
import { authApi } from '@/api/auth';
import { HOME_ROUTE, LOGIN_ROUTE } from '@/constants/auth';
import {
  applyTokenResponse as applyTokenResponseToSession,
  authSessionRef,
  clearCurrentSession,
  getCurrentSession,
  restoreSessionFromStorage,
} from '@/lib/auth-session';
import type { RoleCode, TokenResponse } from '@/types/auth';

export const useAuthStore = defineStore('auth', () => {
  const session = computed(() => authSessionRef.value);
  const isAuthenticated = computed(() => !!authSessionRef.value);

  function restoreSession() {
    return restoreSessionFromStorage();
  }

  function applyTokenResponse(tokenResponse: TokenResponse) {
    return applyTokenResponseToSession(tokenResponse);
  }

  function clearSession() {
    clearCurrentSession();
  }

  function hasRole(role: RoleCode) {
    return session.value?.roles.includes(role) ?? false;
  }

  async function logout() {
    try {
      await authApi.logout();
    } finally {
      clearCurrentSession();
    }
  }

  return {
    session,
    isAuthenticated,
    restoreSession,
    applyTokenResponse,
    clearSession,
    hasRole,
    logout,
    loginRoute: LOGIN_ROUTE,
    homeRoute: HOME_ROUTE,
    getCurrentSession,
  };
});
