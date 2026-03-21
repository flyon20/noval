export interface ChartItem {
  name: string;
  value: number;
}

export interface DailyCount {
  date: string;
  value: number;
}

export interface RankSnapshot {
  category: string;
  crawlTime: string;
  bookCount: number;
}

export interface ThemeWordCloudItem {
  name: string;
  value: number;
}

export interface ThemeTableItem {
  theme: string;
  count: number;
  trend: string;
}

export interface SnapshotThemeComparison {
  snapshotTime: string;
  topTheme: string;
  change: string;
}

export interface VisualData {
  analysisTypeDistribution: ChartItem[];
  analysisDailyTrend: DailyCount[];
  rankCategoryDistribution: ChartItem[];
  latestSnapshots: RankSnapshot[];
  wordCloud: ThemeWordCloudItem[];
  themeTable: ThemeTableItem[];
  comparisonSummary: string | null;
  snapshotComparisons: SnapshotThemeComparison[];
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
