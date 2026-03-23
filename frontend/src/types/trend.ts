export interface TrendRequest {
  platform: 'fanqie';
  channelCode: string;
  boardCode: string;
}

export interface TrendAnalysisResult {
  analysisType: 'theme';
  platform: 'fanqie';
  channelCode: string;
  boardCode: string;
  boardName: string;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  sourceSnapshotCount: number;
  traceId?: string;
}
