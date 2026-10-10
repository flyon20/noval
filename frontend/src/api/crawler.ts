import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  BookDetail,
  ChapterItem,
  ChapterRefreshResult,
  CrawlerChapterRequest,
  CrawlerRankRequest,
  Platform,
  RankFetchCount,
  RankBoardCatalog,
  RankBoardStatus,
  RankBookItem,
  RankPageRequest,
  RankPageResult,
  RankRefreshResult,
  UserRankPreference,
} from '@/types/crawler';

export type RankRefreshRequest = Omit<CrawlerRankRequest, 'idempotencyKey'> & {
  idempotencyKey: string;
};

const RANK_REFRESH_TIMEOUT_MS = 120000;
const CHAPTER_FETCH_TIMEOUT_MS = 180000;

export function createRankRefreshIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    try {
      return `rank-refresh-${crypto.randomUUID()}`;
    } catch {
      // Fall through for non-secure test/webview contexts.
    }
  }
  return `rank-refresh-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export const crawlerApi = {
  getRank(payload: CrawlerRankRequest) {
    return httpClient.post<ApiResponse<RankBookItem[]>>('/api/crawler/rank', payload);
  },
  getBoards(params: { platform: Platform }) {
    return httpClient.get<ApiResponse<RankBoardCatalog[]>>('/api/crawler/boards', {
      params,
    });
  },
  getPreference(params: { platform: Platform }) {
    return httpClient.get<ApiResponse<UserRankPreference>>('/api/crawler/preference', {
      params,
    });
  },
  savePreference(payload: { platform: Platform; channelCode: string; boardCode: string; rankFetchCount: RankFetchCount }) {
    return httpClient.post<ApiResponse<UserRankPreference>>('/api/crawler/preference', payload);
  },
  refreshRankBoard(payload: RankRefreshRequest) {
    if (typeof payload.idempotencyKey !== 'string' || !payload.idempotencyKey.trim()) {
      throw new TypeError('rank refresh idempotencyKey is required');
    }
    return httpClient.post<ApiResponse<RankRefreshResult>>('/api/crawler/rank/refresh', payload, {
      timeout: RANK_REFRESH_TIMEOUT_MS,
    });
  },
  getRankPage(params: RankPageRequest) {
    return httpClient.get<ApiResponse<RankPageResult>>('/api/crawler/rank/page', {
      params,
    });
  },
  getRankStatus(params: { platform: Platform; channelCode: string; boardCode: string }) {
    return httpClient.get<ApiResponse<RankBoardStatus>>('/api/crawler/rank/status', {
      params,
    });
  },
  getBookDetail(bookId: number, platform: Platform) {
    return httpClient.get<ApiResponse<BookDetail>>(`/api/crawler/book/${bookId}`, {
      params: {
        platform,
      },
    });
  },
  getChapters(payload: CrawlerChapterRequest) {
    return httpClient.post<ApiResponse<ChapterItem[]>>('/api/crawler/chapters', payload, {
      timeout: CHAPTER_FETCH_TIMEOUT_MS,
    });
  },
  getChapterStatus(payload: CrawlerChapterRequest) {
    return httpClient.post<ApiResponse<ChapterItem[]>>('/api/crawler/chapters/status', payload);
  },
  refreshChapters(payload: CrawlerChapterRequest) {
    return httpClient.post<ApiResponse<ChapterRefreshResult>>('/api/crawler/chapters/refresh', payload, {
      timeout: CHAPTER_FETCH_TIMEOUT_MS,
    });
  },
};
