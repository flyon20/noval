import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  BookDetail,
  ChapterItem,
  CrawlerChapterRequest,
  CrawlerRankRequest,
  Platform,
  RankBoardCatalog,
  RankBookItem,
  RankPageRequest,
  RankPageResult,
  RankRefreshResult,
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
};
