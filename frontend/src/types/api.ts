export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
  traceId: string;
}

export interface ApiErrorPayload {
  code?: number;
  message?: string;
  traceId?: string;
}
