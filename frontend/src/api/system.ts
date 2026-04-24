import { httpClient, rawHttpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { Platform } from '@/types/crawler';
import type { AuthPublicConfig, LoginBootstrapResult } from '@/types/system';

export const systemApi = {
  getAuthPublicConfig() {
    return rawHttpClient.get<ApiResponse<AuthPublicConfig>>('/api/system/auth-public-config');
  },
  loginBootstrap(params: { platform: Platform }) {
    return httpClient.post<ApiResponse<LoginBootstrapResult>>('/api/system/login-bootstrap', null, {
      params,
    });
  },
};
