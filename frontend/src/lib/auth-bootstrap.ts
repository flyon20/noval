import { rawHttpClient } from '@/lib/http';
import { applyTokenResponse, clearCurrentSession, getCurrentSession } from '@/lib/auth-session';
import type { ApiResponse } from '@/types/api';
import type { TokenResponse } from '@/types/auth';

interface BootstrapOptions {
  refresh?: () => Promise<TokenResponse>;
}

let bootstrapPromise: Promise<ReturnType<typeof getCurrentSession>> | null = null;

async function refreshWithCookie() {
  const response = await rawHttpClient.post<ApiResponse<TokenResponse>>('/api/auth/refresh', null, {
    withCredentials: true,
  });

  return response.data.data;
}

export async function bootstrapAuthSession(options: BootstrapOptions = {}) {
  if (getCurrentSession()) {
    return getCurrentSession();
  }

  if (bootstrapPromise) {
    return bootstrapPromise;
  }

  const refresh = options.refresh ?? refreshWithCookie;

  bootstrapPromise = (async () => {
    try {
      const tokenResponse = await refresh();
      return applyTokenResponse(tokenResponse);
    } catch {
      clearCurrentSession();
      return null;
    } finally {
      bootstrapPromise = null;
    }
  })();

  return bootstrapPromise;
}
