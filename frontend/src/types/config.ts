export type PromptType = 'deconstruct' | 'structure' | 'plot' | 'theme';

export interface PromptConfig {
  id: number;
  promptType: PromptType;
  promptName: string;
  promptContent: string;
  modelName: string;
  temperature?: number | null;
  maxTokens?: number | null;
}

export interface PromptConfigUpdateRequest {
  promptType: PromptType;
  promptName: string;
  promptContent: string;
  modelName: string;
  temperature?: number;
  maxTokens?: number;
}

export type KnownSystemConfigKey =
  | 'ai.provider.type'
  | 'ai.timeout.millis'
  | 'ai.openai-compatible.base-url'
  | 'ai.openai-compatible.default-model'
  | 'ai.openai-compatible.streaming-enabled'
  | 'crawler.default.chapter-count'
  | 'crawler.http.timeout-seconds'
  | 'crawler.chapter.fetch-workers'
  | 'crawler.chapter.force-refresh.user-max-times'
  | 'crawler.rank.refresh-days';

export interface SystemConfig {
  id: number;
  configKey: string;
  configValue: string;
  configType?: string | null;
  description?: string | null;
  editable: boolean;
}

export interface SystemConfigUpdateRequest {
  configKey: string;
  configValue: string;
  configType?: string;
  description?: string;
}
