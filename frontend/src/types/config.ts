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
  | 'ai.timeout.millis'
  | 'crawler.default.chapter-count'
  | 'security.audit.enabled';

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
