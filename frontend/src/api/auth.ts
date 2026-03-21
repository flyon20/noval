import { clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import { httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { LoginRequest, TokenResponse } from '@/types/auth';

export const authApi = {
  login(payload: LoginRequest) {
    return rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/login', payload);
  },
  async logout() {
    const token = getAccessToken();

    if (!token) {
      clearCurrentSession();
      return {
        data: {
          code: 200,
          message: 'success',
          data: null,
          timestamp: Date.now(),
          traceId: 'local-logout',
        },
      };
    }

    return httpClient.post<ApiResponse<null>>(
      '/api/auth/logout',
      { token },
      {
        skipAuthRefresh: true,
      },
    );
  },
};
