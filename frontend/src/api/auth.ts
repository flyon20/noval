import { clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import { httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  LoginRequest,
  PasswordResetRequest,
  RegisterRequest,
  SmsLoginRequest,
  SmsSendRequest,
  SmsSendResponse,
  TokenResponse,
} from '@/types/auth';

type PasswordLoginRequestPayload = {
  phone: string;
  password: string;
  deviceLabel?: string;
};

export const authApi = {
  login(payload: LoginRequest | PasswordLoginRequestPayload) {
    return rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/login/password', payload, {
      withCredentials: true,
    });
  },
  loginWithSms(payload: SmsLoginRequest) {
    return rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/login/sms', payload, {
      withCredentials: true,
    });
  },
  register(payload: RegisterRequest) {
    return rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/register', payload, {
      withCredentials: true,
    });
  },
  sendSmsCode(payload: SmsSendRequest) {
    return rawHttpClient.post<ApiResponse<SmsSendResponse>>('/api/auth/sms/send', payload, {
      withCredentials: true,
    });
  },
  resetPassword(payload: PasswordResetRequest) {
    return rawHttpClient.post<ApiResponse<null>>('/api/auth/password/reset', payload, {
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
