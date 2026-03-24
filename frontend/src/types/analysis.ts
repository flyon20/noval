export type AnalysisType = 'deconstruct' | 'structure' | 'plot';
export type StreamAnalysisType = AnalysisType | 'theme';

export interface AnalysisRequest {
  platform: 'fanqie';
  bookId: number;
  chapterCount: number;
  forceReanalyze?: boolean;
}

export interface AnalysisResult {
  id: number;
  bookId: number;
  analysisType: AnalysisType;
  modelName: string;
  resultContent: string;
  resultJson: Record<string, unknown>;
  tokenUsed: number;
  traceId?: string;
}

export type StreamEventType = 'start' | 'delta' | 'done' | 'error';

export interface StreamStartEvent {
  event: 'start';
  traceId: string;
  analysisType: StreamAnalysisType;
}

export interface StreamDeltaEvent {
  event: 'delta';
  delta: string;
  chunkIndex?: number;
}

export interface StreamDoneEvent<T> {
  event: 'done';
  data: T;
}

export interface StreamErrorEvent {
  event: 'error';
  code: number;
  message: string;
  traceId?: string;
}
