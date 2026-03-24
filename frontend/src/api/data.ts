import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { AnalysisHistoryItem, AnalysisHistoryQuery, VisualData } from '@/types/data';

export const dataApi = {
  getVisual(params: { platform: 'fanqie'; channelCode: string; boardCode: string }) {
    return httpClient.get<ApiResponse<VisualData>>('/api/data/visual', {
      params,
    });
  },
  getHistory(query: AnalysisHistoryQuery = {}) {
    return httpClient.get<ApiResponse<AnalysisHistoryItem[]>>('/api/data/history', {
      params: query,
    });
  },
};
