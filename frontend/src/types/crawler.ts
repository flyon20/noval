export type Platform = 'fanqie';

export type KnownRankCategory = 'male-hot-a' | 'male-hot-b' | 'male-new-a';

export type RankCategory = KnownRankCategory | (string & {});

export type UiChapterCount = 1 | 3 | 5 | 10;

export interface CrawlerRankRequest {
  platform: Platform;
  category: RankCategory;
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

export interface ChapterItem {
  bookId: number;
  chapterNo: number;
  chapterTitle: string;
  content: string;
  wordCount: number;
}
