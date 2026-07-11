export type PromptType = 'deconstruct' | 'structure' | 'plot' | 'theme';

export interface PromptConfig {
  id: number;
  promptType: PromptType;
  promptName: string;
  promptContent: string;
  modelName: string;
  temperature?: number | null;
  maxTokens?: number | null;
  isDefault?: boolean | null;
  inputJsonSchema?: string | null;
  inputExampleJson?: string | null;
  outputJsonSchema?: string | null;
  outputExampleJson?: string | null;
  postProcessType?: string | null;
  parseConfigJson?: string | null;
}

export interface PromptTemplateOption {
  id: number;
  promptType: PromptType;
  promptName: string;
  modelName: string;
  isDefault?: boolean | null;
}

export interface PromptConfigUpdateRequest {
  promptType: PromptType;
  promptName: string;
  promptContent: string;
  modelName: string;
  temperature?: number;
  maxTokens?: number;
  inputJsonSchema?: string;
  inputExampleJson?: string;
  outputJsonSchema?: string;
  outputExampleJson?: string;
  postProcessType?: string;
  parseConfigJson?: string;
}

export interface AiModelRegistryModel {
  modelKey: string;
  displayName: string;
  providerType: string;
  modelName: string;
  baseUrl?: string | null;
  apiKey?: string | null;
  apiKeyConfigured?: boolean | null;
  apiKeyMasked?: string | null;
  enabled: boolean;
  isDefault: boolean;
  defaultTemperature?: number | null;
  maxTokens?: number | null;
  temperatureSpecJson?: string | null;
  promptBindings?: Partial<Record<PromptType, string>> | null;
}

export interface AiModelRegistry {
  defaultModelKey: string;
  models: AiModelRegistryModel[];
}

export interface AiModelOption {
  modelKey: string;
  displayName: string;
  providerType: string;
  isDefault?: boolean | null;
  defaultTemperature?: number | null;
  maxTokens?: number | null;
  temperatureSpecJson?: string | null;
}

export interface AiModelRegistryUpdateRequest {
  defaultModelKey: string;
  models: Array<{
    modelKey: string;
    displayName: string;
    providerType: string;
    modelName: string;
    baseUrl?: string;
    apiKey?: string;
    enabled: boolean;
    isDefault: boolean;
    defaultTemperature?: number;
    maxTokens?: number;
    temperatureSpecJson?: string;
    promptBindings?: Partial<Record<PromptType, string>>;
  }>;
}

export type KnownSystemConfigKey =
  | 'ai.provider.type'
  | 'ai.timeout.millis'
  | 'analysis.runtime.mode'
  | 'ai.openai-compatible.base-url'
  | 'ai.openai-compatible.default-model'
  | 'ai.openai-compatible.api-key'
  | 'ai.openai-compatible.streaming-enabled'
  | 'ai.langgraph-worker.base-url'
  | 'ai.langgraph-worker.internal-api-key'
  | 'ai.langgraph-worker.timeout-millis'
  | 'ai.knowledge.reasoning-mode.default'
  | 'ai.available-models'
  | 'analysis.chunk.max-input-tokens'
  | 'analysis.chunk.target-input-tokens'
  | 'analysis.chunk.parallelism'
  | 'auth.bootstrap-admin-phones'
  | 'crawler.default.chapter-count'
  | 'crawler.http.timeout-seconds'
  | 'crawler.chapter.fetch-workers'
  | 'crawler.chapter.force-refresh.user-max-times'
  | 'analysis.reanalyze.cooldown-hours'
  | 'analysis.reanalyze.user-max-times'
  | 'crawler.rank.refresh-days'
  | 'crawler.rank.force-cooldown-days'
  | 'crawler.rank.force-max-times'
  | 'crawler.book.refresh-days'
  | 'security.audit.enabled';

export interface KnownSystemConfigOption {
  key: string;
  label: string;
  hint: string;
}

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

export interface UserConfig {
  configKey: string;
  configValue: string | null;
}

export interface UserConfigUpdateRequest {
  configKey: string;
  configValue: string;
}
