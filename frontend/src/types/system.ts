import type { RankRefreshResult } from '@/types/crawler';

export interface LoginBootstrapResult {
  results: RankRefreshResult[];
}

export interface AuthPublicConfig {
  turnstileEnabled: boolean;
  turnstileSiteKey?: string | null;
}
