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

export type AiModelProtocol = 'responses' | 'chat_completions' | 'unspecified';

export type AiPromptCacheStrategy =
  | 'none'
  | 'deepseek_automatic'
  | 'openai_legacy'
  | 'openai_gpt_5_6';

export interface AiPromptCacheCapabilitiesV1 {
  strategy: AiPromptCacheStrategy;
  mode: 'disabled' | 'provider_managed' | 'implicit' | 'explicit';
  retention: 'provider_default' | '30m' | 'in_memory' | '24h';
  breakpoint: 'none' | 'stable_prefix';
}

export interface AiModelProviderCapabilitiesV1 {
  schemaVersion: 1;
  supportsStreaming: boolean;
  supportsTools: boolean;
  supportsJsonObject: boolean;
  supportsReasoning: boolean;
  reportsUsage: boolean;
  reportsCacheUsage: boolean;
  promptCache?: AiPromptCacheCapabilitiesV1;
}

export interface AiModelRegistryModel {
  modelKey: string;
  displayName: string;
  providerType: string;
  protocol?: AiModelProtocol | null;
  providerCapabilities?: AiModelProviderCapabilitiesV1 | null;
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

export interface AiProviderRoutingPolicyV1 {
  schemaVersion: 1;
  enabled: boolean;
  orderedProfileKeys: string[];
  maxFailovers: number;
  cooldownSeconds: number;
}

export interface AiModelRegistry {
  defaultModelKey: string;
  models: AiModelRegistryModel[];
  providerRoutingPolicy?: AiProviderRoutingPolicyV1 | null;
}

export interface AiModelProviderProbeRequest {
  modelKey: string;
}

export interface AiModelProviderProbeResult {
  status: 'SUCCEEDED' | 'FAILED';
  profileKey: string;
  profileVersion: string;
  endpointFingerprint?: string | null;
  model?: string | null;
  protocol?: Exclude<AiModelProtocol, 'unspecified'> | null;
  latencyMillis: number;
  usageReported: boolean;
  cacheUsageReported: boolean;
  errorCode?: string | null;
}

export interface AiModelOption {
  modelKey: string;
  displayName: string;
  providerType: string;
  isDefault?: boolean | null;
  defaultTemperature?: number | null;
  maxTokens?: number | null;
  temperatureSpecJson?: string | null;
  /** 由 worker 方言表给出；worker 不可达时为空，表示该模型不展示思考强度控件。 */
  supportsReasoning?: boolean | null;
  reasoningTiers?: string[] | null;
  /** worker 判定的供应商族，用于选择器分栏；比注册表里的 providerType 更准。 */
  providerFamily?: string | null;
}

export interface AiModelRegistryUpdateRequest {
  defaultModelKey: string;
  providerRoutingPolicy: AiProviderRoutingPolicyV1;
  models: Array<{
    modelKey: string;
    displayName: string;
    providerType: string;
    protocol: AiModelProtocol;
    providerCapabilities?: AiModelProviderCapabilitiesV1;
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
