import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  BookDetail,
  ChapterItem,
  ChapterRefreshResult,
  CrawlerChapterRequest,
  CrawlerRankRequest,
  Platform,
  RankBoardCatalog,
  RankBookItem,
  RankPageRequest,
  RankPageResult,
  RankRefreshResult,
  UserRankPreference,
} from '@/types/crawler';

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
  savePreference(payload: { platform: Platform; channelCode: string; boardCode: string }) {
    return httpClient.post<ApiResponse<UserRankPreference>>('/api/crawler/preference', payload);
  },
  refreshRankBoard(payload: CrawlerRankRequest) {
    return httpClient.post<ApiResponse<RankRefreshResult>>('/api/crawler/rank/refresh', payload);
  },
  getRankPage(params: RankPageRequest) {
    return httpClient.get<ApiResponse<RankPageResult>>('/api/crawler/rank/page', {
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
    return httpClient.post<ApiResponse<ChapterItem[]>>('/api/crawler/chapters', payload);
  },
  refreshChapters(payload: CrawlerChapterRequest) {
    return httpClient.post<ApiResponse<ChapterRefreshResult>>('/api/crawler/chapters/refresh', payload);
  },
};
