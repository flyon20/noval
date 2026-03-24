export type Platform = 'fanqie';

export type RefreshMode = 'AUTO' | 'FORCE';

export type UiChapterCount = 1 | 3 | 5 | 10;
export type RankFetchCount = 10 | 20 | 30 | 40 | 50 | 60 | 70 | 80 | 90 | 100;

export interface RankBoardOption {
  boardCode: string;
  boardName: string;
}

export interface RankBoardCatalog {
  channelCode: string;
  channelName: string;
  boards: RankBoardOption[];
}

export interface UserRankPreference {
  userId: number;
  platform: Platform;
  channelCode: string;
  boardCode: string;
  rankFetchCount: RankFetchCount;
}

export interface CrawlerRankRequest {
  platform: Platform;
  category?: string;
  channelCode?: string;
  boardCode?: string;
  refreshMode?: RefreshMode;
  forceReason?: string;
  rankFetchCount?: RankFetchCount;
}

export interface RankBookItem {
  bookId: number;
  rankNo: number;
  bookName: string;
  author: string;
  intro: string;
  bookUrl: string;
  platform: Platform;
  category: string;
}

export interface RankRefreshResult {
  channelCode?: string;
  boardCode?: string;
  snapshotId: number;
  snapshotTime?: string;
  total: number;
  reused: boolean;
  refreshLimited: boolean;
  analysisTriggered: boolean;
}

export interface RankPageRequest {
  platform: Platform;
  channelCode: string;
  boardCode: string;
  page: number;
  pageSize: number;
}

export interface RankPageResult {
  snapshotId: number;
  snapshotTime?: string;
  total: number;
  page: number;
  pageSize: number;
  items: RankBookItem[];
}

export interface BookDetail {
  bookId: number;
  platform: Platform;
  bookName: string;
  author: string;
  intro: string;
  bookUrl: string;
}

export interface CrawlerChapterRequest {
  platform: Platform;
  bookId: number;
  chapterCount: number;
}

export interface ChapterRefreshResult {
  chapters: ChapterItem[];
  maxAllowedRefreshTimes: number;
  usedRefreshTimes: number;
  remainingRefreshTimes: number;
  windowDays: number;
}

export interface ChapterItem {
  bookId: number;
  chapterNo: number;
  chapterTitle: string;
  content: string;
  wordCount: number;
}
