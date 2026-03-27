import { clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import { httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { LoginRequest, RegisterRequest, TokenResponse } from '@/types/auth';

type LoginRequestPayload = {
  username: string;
  password: string;
  deviceLabel?: string;
};

export const authApi = {
  login(payload: LoginRequest | LoginRequestPayload) {
    return rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/login', payload, {
      withCredentials: true,
    });
  },
  register(payload: RegisterRequest) {
    return rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/register', payload, {
      withCredentials: true,
    });
  },
  refresh() {
    return rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/refresh', undefined, {
      withCredentials: true,
    });
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
      undefined,
      {
        skipAuthRefresh: true,
        withCredentials: true,
      },
    );
  },
};
