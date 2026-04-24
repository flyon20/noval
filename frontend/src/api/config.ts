import { httpClient } from '@/lib/http';
import type { ApiResponse } from '@/types/api';
import type {
  AiModelOption,
  AiModelRegistry,
  AiModelRegistryUpdateRequest,
  PromptConfig,
  PromptTemplateOption,
  PromptConfigUpdateRequest,
  PromptType,
  SystemConfig,
  SystemConfigUpdateRequest,
  UserConfig,
  UserConfigUpdateRequest,
} from '@/types/config';

export const promptConfigApi = {
  getByType(promptType: PromptType, promptName?: string) {
    return httpClient.get<ApiResponse<PromptConfig>>('/api/config/prompt', {
      params: {
        promptType,
        ...(promptName ? { promptName } : {}),
      },
    });
  },
  listTemplates(promptType: PromptType) {
    return httpClient.get<ApiResponse<PromptTemplateOption[]>>('/api/config/prompt/templates', {
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
  getModelRegistry() {
    return httpClient.get<ApiResponse<AiModelRegistry>>('/api/config/system/model-registry');
  },
  updateModelRegistry(payload: AiModelRegistryUpdateRequest) {
    return httpClient.put<ApiResponse<AiModelRegistry>>('/api/config/system/model-registry', payload);
  },
  getModelOptions() {
    return httpClient.get<ApiResponse<AiModelOption[]>>('/api/config/system/model-options');
  },
  getAvailableModels() {
    return httpClient.get<ApiResponse<string[]>>('/api/config/system/available-models');
  },
  listPromptTemplates(promptType: PromptType) {
    return httpClient.get<ApiResponse<PromptTemplateOption[]>>('/api/config/prompt/templates', {
      params: { promptType },
    });
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
