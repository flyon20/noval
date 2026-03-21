import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  BookDetail,
  ChapterItem,
  CrawlerChapterRequest,
  CrawlerRankRequest,
  Platform,
  RankBookItem,
} from '@/types/crawler';

export const crawlerApi = {
  getRank(payload: CrawlerRankRequest) {
    return httpClient.post<ApiResponse<RankBookItem[]>>('/api/crawler/rank', payload);
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
