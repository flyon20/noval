import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { applyTokenResponse, clearCurrentSession, getAccessToken } from '@/lib/auth-session';
import type { ApiResponse } from '@/types/api';
import type { TokenResponse } from '@/types/auth';

type RequestConfig = InternalAxiosRequestConfig & {
  skipAuth?: boolean;
  skipAuthRefresh?: boolean;
  _retry?: boolean;
};

export interface HttpClientAuthAdapter {
  baseURL: string;
  getAccessToken(): string | null;
  refreshToken(token: string): Promise<TokenResponse>;
  applyTokenResponse(response: TokenResponse): void;
  clearSession(): void;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
export { API_BASE_URL };

export const rawHttpClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
});

async function refreshWithRawClient(token: string) {
  const response = await rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/refresh', { token });
  return response.data.data;
}

export function createHttpClient(adapter: HttpClientAuthAdapter): AxiosInstance {
  const client = axios.create({
    baseURL: adapter.baseURL,
    timeout: 15000,
  });

  let refreshPromise: Promise<TokenResponse> | null = null;

  client.interceptors.request.use((config) => {
    const requestConfig = config as RequestConfig;
    const token = adapter.getAccessToken();

    if (!requestConfig.skipAuth && token) {
      requestConfig.headers = requestConfig.headers ?? {};
      requestConfig.headers.Authorization = `Bearer ${token}`;
    }

    return requestConfig;
  });

  client.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const response = error.response;
      const requestConfig = error.config as RequestConfig | undefined;

      if (!response || !requestConfig || requestConfig.skipAuthRefresh || requestConfig._retry || response.status !== 401) {
        throw error;
      }

      const currentToken = adapter.getAccessToken();

      if (!currentToken) {
        adapter.clearSession();
        throw error;
      }

      requestConfig._retry = true;

      try {
        if (!refreshPromise) {
          refreshPromise = adapter.refreshToken(currentToken);
        }

        const tokenResponse = await refreshPromise;
        adapter.applyTokenResponse(tokenResponse);

        return client.request(requestConfig);
      } catch (refreshError) {
        adapter.clearSession();
        throw refreshError;
      } finally {
        refreshPromise = null;
      }
    },
  );

  return client;
}

export const httpClient = createHttpClient({
  baseURL: API_BASE_URL,
  getAccessToken,
  refreshToken: refreshWithRawClient,
  applyTokenResponse,
  clearSession: clearCurrentSession,
});
