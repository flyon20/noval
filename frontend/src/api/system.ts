import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { Platform } from '@/types/crawler';
import type { LoginBootstrapResult } from '@/types/system';

export const systemApi = {
  loginBootstrap(params: { platform: Platform }) {
    return httpClient.post<ApiResponse<LoginBootstrapResult>>('/api/system/login-bootstrap', null, {
      params,
    });
  },
};
