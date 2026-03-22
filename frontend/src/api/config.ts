import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  PromptConfig,
  PromptConfigUpdateRequest,
  PromptType,
  SystemConfig,
  SystemConfigUpdateRequest,
  UserConfig,
  UserConfigUpdateRequest,
} from '@/types/config';

export const promptConfigApi = {
  getByType(promptType: PromptType) {
    return httpClient.get<ApiResponse<PromptConfig>>('/api/config/prompt', {
      params: { promptType },
    });
  },
  update(payload: PromptConfigUpdateRequest) {
    return httpClient.put<ApiResponse<PromptConfig>>('/api/config/prompt', payload);
  },
};

export const systemConfigApi = {
  getByKey(configKey: string) {
    return httpClient.get<ApiResponse<SystemConfig>>('/api/config/system', {
      params: { configKey },
    });
  },
  update(payload: SystemConfigUpdateRequest) {
    return httpClient.put<ApiResponse<SystemConfig>>('/api/config/system', payload);
  },
  getAvailableModels() {
    return httpClient.get<ApiResponse<string[]>>('/api/config/system/available-models');
  },
};

export const userConfigApi = {
  get(configKey: string) {
    return httpClient.get<ApiResponse<UserConfig>>('/api/config/user', {
      params: { configKey },
    });
  },
  update(payload: UserConfigUpdateRequest) {
    return httpClient.put<ApiResponse<UserConfig>>('/api/config/user', payload);
  },
};
