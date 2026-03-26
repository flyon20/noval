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
  limit?: number;
}

export interface AnalysisHistoryItem {
  id: number;
  bookId: number;
  bookName?: string | null;
  analysisType: 'deconstruct' | 'structure' | 'plot' | 'theme';
  chapterCount: number;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  createdAt: string;
}
