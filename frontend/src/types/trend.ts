export interface TrendRequest {
  platform: 'fanqie';
  category?: string;
}

export interface TrendAnalysisResult {
  analysisType: 'theme';
  platform: 'fanqie';
  category?: string;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  sourceSnapshotCount: number;
  traceId?: string;
}
