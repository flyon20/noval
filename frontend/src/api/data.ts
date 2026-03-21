import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type { AnalysisHistoryItem, AnalysisHistoryQuery, VisualData } from '@/types/data';

export const dataApi = {
  getVisual(platform?: 'fanqie') {
    return httpClient.get<ApiResponse<VisualData>>('/api/data/visual', {
      params: {
        ...(platform ? { platform } : {}),
      },
    });
  },
  getHistory(query: AnalysisHistoryQuery = {}) {
    return httpClient.get<ApiResponse<AnalysisHistoryItem[]>>('/api/data/history', {
      params: query,
    });
  },
};
