export interface RankSnapshot {
  snapshotTime: string;
  bookCount: number;
  topBookName?: string | null;
  topBookAuthor?: string | null;
}

export interface ThemeWordCloudItem {
  name: string;
  value: number;
}

export interface ThemeDistributionItem {
  theme: string;
  count: number;
  ratio?: number | null;
}

export interface HotBook {
  theme?: string | null;
  bookName: string;
  author?: string | null;
  rankNo?: number | null;
  rankLabel?: string | null;
  reason?: string | null;
}

export interface ThemeTableItem {
  theme: string;
  count: number;
  ratio?: number | null;
  trend: string;
  representativeBooks?: HotBook[] | null;
}

export interface SnapshotThemeComparison {
  snapshotTime: string;
  topTheme: string;
  topThemeRatio?: number | null;
  leadBookName?: string | null;
  change: string;
}

export interface InsightCard {
  label: string;
  value: string;
  note?: string | null;
}

export interface VisualData {
  platform: 'fanqie';
  channelCode: string;
  boardCode: string;
  boardName: string;
  sourceSnapshotCount: number;
  historyAnalysisCount: number;
  latestSnapshots: RankSnapshot[];
  boardSummary?: string | null;
  historicalWordCloud: ThemeWordCloudItem[];
  themeDistribution: ThemeDistributionItem[];
  themeTable: ThemeTableItem[];
  hotBooks: HotBook[];
  insightCards: InsightCard[];
  comparisonSummary: string | null;
  snapshotComparisons: SnapshotThemeComparison[];
  trendPreview?: string | null;
  detailContent?: string | null;
}

export interface AnalysisHistoryQuery {
  platform?: 'fanqie';
  bookId?: number;
  analysisType?: 'deconstruct' | 'structure' | 'plot' | 'theme';
  channelCode?: string;
  boardCode?: string;
  chapterCount?: number;
  modelName?: string;
  keyword?: string;
  startTime?: string;
  endTime?: string;
  page?: number;
  pageSize?: number;
}

export interface AnalysisHistorySummary {
  id: number;
  bookId: number;
  bookName?: string | null;
  analysisType: 'deconstruct' | 'structure' | 'plot' | 'theme';
  chapterCount: number;
  modelName: string;
  channelCode?: string | null;
  boardCode?: string | null;
  snapshotId?: number | null;
  tokenUsed?: number | null;
  costTime?: number | null;
  summaryPreview?: string | null;
  matchedFields?: string[];
  matchSnippets?: string[];
  matchScore?: number | null;
  createdAt: string;
}

export interface AnalysisHistoryDetail extends AnalysisHistorySummary {
  resultContent: string;
  resultJson: Record<string, unknown>;
}

export interface AnalysisHistoryPage {
  items: AnalysisHistorySummary[];
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
}

export type AnalysisHistoryItem = AnalysisHistoryDetail;
