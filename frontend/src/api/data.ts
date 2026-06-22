import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  AnalysisHistoryDetail,
  AnalysisHistoryPage,
  AnalysisHistoryQuery,
  AnalysisHistorySummary,
  VisualData,
} from '@/types/data';

export const dataApi = {
  getVisual(params: { platform: 'fanqie'; channelCode: string; boardCode: string }) {
    return httpClient.get<ApiResponse<VisualData>>('/api/data/visual', {
      params,
    });
  },
  getHistory(query: AnalysisHistoryQuery = {}) {
    return httpClient.get<ApiResponse<AnalysisHistoryPage | AnalysisHistorySummary[]>>('/api/data/history', {
      params: query,
    });
  },
  getHistoryDetail(id: number) {
    return httpClient.get<ApiResponse<AnalysisHistoryDetail>>(`/api/data/history/${id}`);
  },
};
