<script setup lang="ts">
import { ArrowDown, ArrowUp, Delete, Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref, watch } from 'vue';
import { systemConfigApi } from '@/api/config';
import type {
  AiModelRegistry,
  AiModelRegistryModel,
  AiModelRegistryUpdateRequest,
  AiModelProviderProbeResult,
  AiModelProviderCapabilitiesV1,
  AiModelProtocol,
  AiPromptCacheCapabilitiesV1,
  AiPromptCacheStrategy,
  AiProviderRoutingPolicyV1,
  PromptTemplateOption,
  KnownSystemConfigOption,
  SystemConfig,
  PromptType,
} from '@/types/config';

const PASSWORD_KEYS = new Set([
  'ai.openai-compatible.api-key',
  'ai.langgraph-worker.internal-api-key',
]);

const FALLBACK_SYSTEM_CONFIG_KEYS: KnownSystemConfigOption[] = [
  { key: 'ai.provider.type', label: '兼容提供方', hint: '旧版兼容配置，模型注册表会优先决定实际请求方式。' },
  { key: 'ai.timeout.millis', label: 'AI 超时毫秒', hint: '控制单书分析等常规 AI 请求超时时间。' },
  { key: 'analysis.runtime.mode', label: '分析运行模式', hint: '控制单书分析使用 legacy 还是 LangGraph 运行链路。' },
  { key: 'ai.openai-compatible.base-url', label: '兼容接口地址', hint: '旧版兼容地址，模型注册表为空时作为回落值。' },
  { key: 'ai.openai-compatible.default-model', label: '兼容默认模型', hint: '旧版默认模型，模型注册表为空时作为回落值。' },
  { key: 'ai.openai-compatible.api-key', label: '兼容 API Key', hint: '旧版兼容密钥，留空或掩码会保留原密钥。' },
  { key: 'ai.openai-compatible.streaming-enabled', label: '兼容流式开关', hint: '旧版兼容开关，当前主要由运行链路决定流式表现。' },
  { key: 'ai.langgraph-worker.base-url', label: '工作流地址', hint: 'LangGraph worker 服务地址，留空时使用环境配置。' },
  { key: 'ai.langgraph-worker.internal-api-key', label: '工作流内部令牌', hint: '后端调用 LangGraph worker 的内部鉴权令牌。' },
  { key: 'ai.langgraph-worker.timeout-millis', label: '工作流超时毫秒', hint: '后端等待 LangGraph worker 响应的超时时间。' },
  { key: 'ai.knowledge.reasoning-mode.default', label: 'AI 问答默认推理模式', hint: 'fast 为快速模式；deep 会启用 DeepSeek 思考模式并使用 max 强度。' },
  { key: 'ai.available-models', label: '旧版模型列表', hint: '旧版逗号分隔模型列表，模型注册表保存后会同步。' },
  { key: 'analysis.reanalyze.cooldown-hours', label: '重析冷却小时', hint: '已有成功结果且输入未变化时，普通用户的重新分析冷却时间。' },
  { key: 'analysis.reanalyze.user-max-times', label: '重析次数限制', hint: '已有成功结果且输入未变化时，普通用户在冷却窗口内的次数上限。' },
  { key: 'analysis.chunk.max-input-tokens', label: '分段最大输入', hint: '单次分析允许的估算输入 Token 上限，超过后自动分段汇总。' },
  { key: 'analysis.chunk.target-input-tokens', label: '分段目标输入', hint: '分段分析时每段的目标输入 Token 大小。' },
  { key: 'analysis.chunk.parallelism', label: '分段并发数', hint: '分段分析时的最大并发段数。' },
  { key: 'auth.bootstrap-admin-phones', label: '管理员手机号', hint: '逗号分隔的管理员手机号列表，登录或刷新时自动补齐 ADMIN 角色。' },
  { key: 'crawler.default.chapter-count', label: '默认抓章数', hint: '扫榜页默认抓取的章节数量。' },
  { key: 'crawler.http.timeout-seconds', label: '抓取超时秒数', hint: '爬虫请求页面时的超时时间。' },
  { key: 'crawler.chapter.fetch-workers', label: '章节抓取并发', hint: '多章节抓取时的最大并发数。' },
  { key: 'crawler.chapter.force-refresh.user-max-times', label: '章节重抓限制', hint: '普通用户在当前窗口内的章节重抓上限。' },
  { key: 'crawler.rank.refresh-days', label: '榜单缓存天数', hint: '榜单缓存期与章节重抓统计窗口。' },
  { key: 'crawler.rank.force-cooldown-days', label: '榜单强刷冷却', hint: '普通用户强制刷新榜单后的冷却天数。' },
  { key: 'crawler.rank.force-max-times', label: '榜单强刷次数', hint: '普通用户在冷却窗口内可强制刷新榜单的次数。' },
  { key: 'crawler.book.refresh-days', label: '书籍缓存天数', hint: '书籍详情和章节信息的缓存天数。' },
  { key: 'security.audit.enabled', label: '审计日志开关', hint: '控制后台操作审计日志是否启用。' },
];

type SystemConfigFormItem = SystemConfig & {
  draftValue: string;
};

type ModelDraft = Omit<AiModelRegistryModel, 'protocol'> & {
  protocol: AiModelProtocol;
  draftApiKeyInput: string;
  draftDefaultTemperature: string;
  draftMaxTokens: string;
  draftPromptBindings: Record<PromptType, string>;
};

type ProviderCapabilityKey = Exclude<keyof AiModelProviderCapabilitiesV1, 'schemaVersion' | 'promptCache'>;

const DEFAULT_PROVIDER_ROUTING_POLICY: AiProviderRoutingPolicyV1 = {
  schemaVersion: 1,
  enabled: false,
  orderedProfileKeys: [],
  maxFailovers: 0,
  cooldownSeconds: 60,
};

const PROMPT_TYPES: Array<{ label: string; value: PromptType }> = [
  { label: '拆文模板', value: 'deconstruct' },
  { label: '结构模板', value: 'structure' },
  { label: '情节模板', value: 'plot' },
  { label: '趋势模板', value: 'theme' },
];

const REASONING_MODE_OPTIONS = [
  { label: '快速', value: 'fast' },
  { label: '深度', value: 'deep' },
];

const MODEL_PROTOCOL_OPTIONS: Array<{ label: string; value: AiModelProtocol }> = [
  { label: 'Responses API', value: 'responses' },
  { label: 'Chat Completions', value: 'chat_completions' },
  { label: 'unspecified（未指定）', value: 'unspecified' },
];

const PROVIDER_CAPABILITY_OPTIONS: Array<{ label: string; key: ProviderCapabilityKey }> = [
  { label: '流式输出', key: 'supportsStreaming' },
  { label: '工具调用', key: 'supportsTools' },
  { label: 'JSON Object', key: 'supportsJsonObject' },
  { label: '推理输出', key: 'supportsReasoning' },
  { label: 'Usage 统计', key: 'reportsUsage' },
  { label: '缓存 Usage 统计', key: 'reportsCacheUsage' },
];

const PROMPT_CACHE_STRATEGY_OPTIONS: Array<{
  label: string;
  value: AiPromptCacheStrategy | 'legacy_model_policy';
}> = [
  { label: '旧模型规则（兼容）', value: 'legacy_model_policy' },
  { label: 'GPT-5.6+ Responses', value: 'openai_gpt_5_6' },
  { label: '早期 GPT Responses', value: 'openai_legacy' },
  { label: 'DeepSeek 自动缓存', value: 'deepseek_automatic' },
  { label: '禁用 / 未声明', value: 'none' },
];

const PROMPT_CACHE_PRESETS: Record<AiPromptCacheStrategy, AiPromptCacheCapabilitiesV1> = {
  none: {
    strategy: 'none',
    mode: 'disabled',
    retention: 'provider_default',
    breakpoint: 'none',
  },
  deepseek_automatic: {
    strategy: 'deepseek_automatic',
    mode: 'provider_managed',
    retention: 'provider_default',
    breakpoint: 'none',
  },
  openai_legacy: {
    strategy: 'openai_legacy',
    mode: 'implicit',
    retention: 'provider_default',
    breakpoint: 'none',
  },
  openai_gpt_5_6: {
    strategy: 'openai_gpt_5_6',
    mode: 'implicit',
    retention: '30m',
    breakpoint: 'stable_prefix',
  },
};

const loading = ref(false);
const items = ref<SystemConfigFormItem[]>([]);
const knownConfigOptions = ref<KnownSystemConfigOption[]>(FALLBACK_SYSTEM_CONFIG_KEYS);
const errorMessage = ref('');

const registryLoading = ref(false);
const registrySaving = ref(false);
const registryError = ref('');
const providerProbeLoadingKey = ref('');
const providerProbeResults = ref<Record<string, AiModelProviderProbeResult>>({});
const savedProviderSignatures = ref<Record<string, string>>({});
const providerProbeGeneration = ref(0);
const routingCandidateToAdd = ref('');
const templateOptions = ref<Record<PromptType, PromptTemplateOption[]>>({
  deconstruct: [],
  structure: [],
  plot: [],
  theme: [],
});
const modelRegistry = ref<AiModelRegistry>({
  defaultModelKey: '',
  models: [],
  providerRoutingPolicy: { ...DEFAULT_PROVIDER_ROUTING_POLICY },
});

const providerRoutingPolicy = computed(() => {
  if (!modelRegistry.value.providerRoutingPolicy) {
    modelRegistry.value.providerRoutingPolicy = { ...DEFAULT_PROVIDER_ROUTING_POLICY };
  }
  return modelRegistry.value.providerRoutingPolicy;
});

const eligibleRoutingModels = computed(() => modelRegistry.value.models.filter((model) => (
  model.enabled
  && normalizeModelProtocol(model.protocol) !== 'unspecified'
  && Boolean(model.providerCapabilities)
  && Boolean(model.modelName.trim())
  && Boolean(model.baseUrl?.trim())
)));

const availableRoutingModels = computed(() => {
  const selected = new Set(providerRoutingPolicy.value.orderedProfileKeys);
  return eligibleRoutingModels.value.filter((model) => !selected.has(model.modelKey));
});

function normalizeProviderRoutingPolicy(
  policy: AiModelRegistry['providerRoutingPolicy'],
): AiProviderRoutingPolicyV1 {
  const orderedProfileKeys = Array.isArray(policy?.orderedProfileKeys)
    ? [...new Set(policy.orderedProfileKeys.map((key) => key.trim()).filter(Boolean))]
    : [];
  // Absent means "walk the whole chain"; an explicit value is clamped to the list length.
  const requestedFailovers = policy?.maxFailovers;
  const maxFailovers = typeof requestedFailovers === 'number' && Number.isFinite(requestedFailovers)
    ? Math.min(orderedProfileKeys.length, Math.max(0, Math.trunc(requestedFailovers)))
    : Math.max(0, orderedProfileKeys.length - 1);
  return {
    schemaVersion: 1,
    enabled: policy?.enabled === true,
    orderedProfileKeys,
    maxFailovers,
    cooldownSeconds: Math.min(3600, Math.max(30, Number(policy?.cooldownSeconds) || 60)),
  };
}

function toModelDraft(model: AiModelRegistryModel): ModelDraft {
  return {
    ...model,
    protocol: normalizeModelProtocol(model.protocol),
    providerCapabilities: normalizeProviderCapabilities(model.providerCapabilities),
    draftApiKeyInput: '',
    draftDefaultTemperature: model.defaultTemperature == null ? '' : String(model.defaultTemperature),
    draftMaxTokens: model.maxTokens == null ? '' : String(model.maxTokens),
    draftPromptBindings: normalizeBindings(model.promptBindings),
  };
}

function createEmptyModelDraft(index: number): ModelDraft {
  return {
    modelKey: `new-model-${index}`,
    displayName: `新模型 ${index}`,
    providerType: 'openai-compatible',
    protocol: 'unspecified',
    modelName: '',
    baseUrl: '',
    apiKey: '',
    apiKeyConfigured: false,
    apiKeyMasked: '',
    enabled: true,
    isDefault: false,
    promptBindings: {},
    draftApiKeyInput: '',
    defaultTemperature: 1,
    maxTokens: 8192,
    temperatureSpecJson: '{"min":0.0,"max":2.0,"step":0.1,"default":1.0}',
    draftDefaultTemperature: '1',
    draftMaxTokens: '8192',
    draftPromptBindings: {
      deconstruct: '',
      structure: '',
      plot: '',
      theme: '',
    },
  };
}

function normalizeModelProtocol(protocol?: AiModelProtocol | null): AiModelProtocol {
  if (protocol === 'responses' || protocol === 'chat_completions') {
    return protocol;
  }
  return 'unspecified';
}

function normalizeProviderCapabilities(
  capabilities?: AiModelProviderCapabilitiesV1 | null,
): AiModelProviderCapabilitiesV1 | undefined {
  if (
    capabilities?.schemaVersion !== 1 ||
    typeof capabilities.supportsStreaming !== 'boolean' ||
    typeof capabilities.supportsTools !== 'boolean' ||
    typeof capabilities.supportsJsonObject !== 'boolean' ||
    typeof capabilities.supportsReasoning !== 'boolean' ||
    typeof capabilities.reportsUsage !== 'boolean' ||
    typeof capabilities.reportsCacheUsage !== 'boolean'
  ) {
    return undefined;
  }

  const promptCache = normalizePromptCacheCapabilities(capabilities.promptCache);
  return {
    schemaVersion: 1,
    supportsStreaming: capabilities.supportsStreaming,
    supportsTools: capabilities.supportsTools,
    supportsJsonObject: capabilities.supportsJsonObject,
    supportsReasoning: capabilities.supportsReasoning,
    reportsUsage: capabilities.reportsUsage,
    reportsCacheUsage: capabilities.reportsCacheUsage,
    ...(promptCache ? { promptCache } : {}),
  };
}

function normalizePromptCacheCapabilities(
  promptCache?: AiPromptCacheCapabilitiesV1 | null,
): AiPromptCacheCapabilitiesV1 | undefined {
  if (!promptCache) {
    return undefined;
  }
  const valid = (
    promptCache.strategy === 'none'
    && promptCache.mode === 'disabled'
    && promptCache.retention === 'provider_default'
    && promptCache.breakpoint === 'none'
  ) || (
    promptCache.strategy === 'deepseek_automatic'
    && promptCache.mode === 'provider_managed'
    && promptCache.retention === 'provider_default'
    && promptCache.breakpoint === 'none'
  ) || (
    promptCache.strategy === 'openai_legacy'
    && promptCache.mode === 'implicit'
    && ['provider_default', 'in_memory', '24h'].includes(promptCache.retention)
    && promptCache.breakpoint === 'none'
  ) || (
    promptCache.strategy === 'openai_gpt_5_6'
    && ['implicit', 'explicit'].includes(promptCache.mode)
    && ['provider_default', '30m'].includes(promptCache.retention)
    && ['none', 'stable_prefix'].includes(promptCache.breakpoint)
  );
  return valid ? { ...promptCache } : undefined;
}

function recommendedPromptCacheCapabilities(model: ModelDraft): AiPromptCacheCapabilitiesV1 {
  if (normalizeModelProtocol(model.protocol) !== 'responses') {
    return { ...PROMPT_CACHE_PRESETS.none };
  }
  const modelName = model.modelName.trim().toLowerCase();
  if (modelName.startsWith('deepseek-')) {
    return { ...PROMPT_CACHE_PRESETS.deepseek_automatic };
  }
  const version = /^gpt-(\d+)(?:\.(\d+))?/.exec(modelName);
  if (version) {
    const major = Number(version[1]);
    const minor = Number(version[2] ?? 0);
    return major > 5 || (major === 5 && minor >= 6)
      ? { ...PROMPT_CACHE_PRESETS.openai_gpt_5_6 }
      : { ...PROMPT_CACHE_PRESETS.openai_legacy };
  }
  return { ...PROMPT_CACHE_PRESETS.none };
}

function beginProviderCapabilities(model: ModelDraft) {
  model.providerCapabilities = {
    schemaVersion: 1,
    supportsStreaming: false,
    supportsTools: false,
    supportsJsonObject: false,
    supportsReasoning: false,
    reportsUsage: false,
    reportsCacheUsage: false,
    promptCache: recommendedPromptCacheCapabilities(model),
  };
}

function updatePromptCacheStrategy(
  model: ModelDraft,
  strategy: AiPromptCacheStrategy | 'legacy_model_policy',
) {
  if (!model.providerCapabilities) {
    return;
  }
  if (strategy === 'legacy_model_policy') {
    const { promptCache: _promptCache, ...legacy } = model.providerCapabilities;
    model.providerCapabilities = legacy;
    return;
  }
  model.providerCapabilities = {
    ...model.providerCapabilities,
    promptCache: { ...PROMPT_CACHE_PRESETS[strategy] },
  };
}

function updatePromptCacheField<K extends keyof Omit<AiPromptCacheCapabilitiesV1, 'strategy'>>(
  model: ModelDraft,
  key: K,
  value: AiPromptCacheCapabilitiesV1[K],
) {
  const promptCache = model.providerCapabilities?.promptCache;
  if (!model.providerCapabilities || !promptCache) {
    return;
  }
  model.providerCapabilities = {
    ...model.providerCapabilities,
    promptCache: { ...promptCache, [key]: value },
  };
}

function updateProviderCapability(
  model: ModelDraft,
  key: ProviderCapabilityKey,
  value: boolean,
) {
  if (!model.providerCapabilities) {
    return;
  }
  model.providerCapabilities = {
    ...model.providerCapabilities,
    [key]: value,
  };
}

function buildProviderCapabilitiesPayload(capabilities?: AiModelProviderCapabilitiesV1 | null) {
  const normalized = normalizeProviderCapabilities(capabilities);
  return normalized ? { providerCapabilities: normalized } : {};
}

function normalizeBindings(bindings?: Partial<Record<PromptType, string>> | null) {
  return {
    deconstruct: bindings?.deconstruct ?? '',
    structure: bindings?.structure ?? '',
    plot: bindings?.plot ?? '',
    theme: bindings?.theme ?? '',
  };
}

function applyModelRegistry(registry: AiModelRegistry) {
  providerProbeGeneration.value += 1;
  const drafts = registry.models.map(toModelDraft);
  modelRegistry.value = {
    defaultModelKey: registry.defaultModelKey,
    models: drafts,
    providerRoutingPolicy: normalizeProviderRoutingPolicy(registry.providerRoutingPolicy),
  };
  routingCandidateToAdd.value = '';
  savedProviderSignatures.value = Object.fromEntries(
    drafts.map((model) => [model.modelKey, providerProbeSignature(model)]),
  );
  providerProbeResults.value = {};
}

function providerProbeSignature(model: ModelDraft) {
  return JSON.stringify({
    modelKey: model.modelKey.trim(),
    providerType: model.providerType.trim(),
    protocol: normalizeModelProtocol(model.protocol),
    modelName: model.modelName.trim(),
    baseUrl: model.baseUrl?.trim() ?? '',
    enabled: model.enabled,
    providerCapabilities: normalizeProviderCapabilities(model.providerCapabilities) ?? null,
  });
}

function canProbeModelProvider(model: ModelDraft) {
  return Boolean(
    model.enabled
    && model.apiKeyConfigured
    && normalizeModelProtocol(model.protocol) !== 'unspecified'
    && model.modelName.trim()
    && model.baseUrl?.trim()
    && !model.draftApiKeyInput.trim()
    && savedProviderSignatures.value[model.modelKey] === providerProbeSignature(model)
    && !providerProbeLoadingKey.value,
  );
}

function hasUnsavedProviderProbeChanges(model: ModelDraft) {
  return Boolean(
    model.draftApiKeyInput.trim()
    || savedProviderSignatures.value[model.modelKey] !== providerProbeSignature(model),
  );
}

function providerProbeHint(model: ModelDraft) {
  if (!model.enabled) {
    return '启用并保存该模型后再验证';
  }
  if (!model.apiKeyConfigured || model.draftApiKeyInput.trim()) {
    return '保存该模型的专属 Key 后再验证';
  }
  if (normalizeModelProtocol(model.protocol) === 'unspecified') {
    return '选择并保存请求协议后再验证';
  }
  if (savedProviderSignatures.value[model.modelKey] !== providerProbeSignature(model)) {
    return '先保存当前接口、模型和能力配置';
  }
  return '验证已保存配置';
}

function providerProbeErrorLabel(errorCode?: string | null) {
  return {
    AUTHENTICATION_FAILED: '密钥认证失败',
    QUOTA_EXHAUSTED: '额度不可用',
    RATE_LIMITED: '请求受限',
    REQUEST_REJECTED: '请求不兼容',
    PROVIDER_UNAVAILABLE: '网关暂不可用',
    PROFILE_NOT_AVAILABLE: '配置已变化',
    PROFILE_INVALID: '配置不可用',
    PROFILE_INSECURE_ENDPOINT: '接口必须使用 HTTPS',
    RESPONSE_INVALID: '网关未返回有效内容',
    PROBE_BUSY: '已有连接验证进行中',
    PROBE_FAILED: '连接失败',
  }[errorCode ?? ''] ?? '连接失败';
}

function providerProbeSummary(result: AiModelProviderProbeResult) {
  if (result.status !== 'SUCCEEDED') {
    return providerProbeErrorLabel(result.errorCode);
  }
  const usage = result.usageReported ? 'Usage 已报告' : 'Usage 未报告';
  const cache = result.cacheUsageReported ? '缓存 Usage 已报告' : '缓存 Usage 未报告';
  return `${result.latencyMillis} ms · ${usage} · ${cache}`;
}

async function probeModelProvider(model: ModelDraft) {
  if (!canProbeModelProvider(model)) {
    return;
  }
  const requestedModelKey = model.modelKey;
  const requestedSignature = providerProbeSignature(model);
  const requestedGeneration = providerProbeGeneration.value;
  const requestIsCurrent = () => {
    const currentModel = modelRegistry.value.models.find(
      (candidate) => candidate.modelKey === requestedModelKey,
    );
    return Boolean(
      providerProbeGeneration.value === requestedGeneration
      && currentModel
      && savedProviderSignatures.value[requestedModelKey] === requestedSignature
      && providerProbeSignature(currentModel) === requestedSignature,
    );
  };
  providerProbeLoadingKey.value = requestedModelKey;
  try {
    const response = await systemConfigApi.probeModelProvider({ modelKey: requestedModelKey });
    const result = response.data.data;
    if (!requestIsCurrent()) {
      return;
    }
    providerProbeResults.value = {
      ...providerProbeResults.value,
      [requestedModelKey]: result,
    };
    if (result.status === 'SUCCEEDED') {
      ElMessage.success('Agent 连接验证通过');
    } else {
      ElMessage.error(providerProbeErrorLabel(result.errorCode));
    }
  } catch {
    if (requestIsCurrent()) {
      ElMessage.error('Agent 连接验证失败');
    }
  } finally {
    if (providerProbeLoadingKey.value === requestedModelKey) {
      providerProbeLoadingKey.value = '';
    }
  }
}

function findKnownConfig(configKey: string) {
  return knownConfigOptions.value.find((config) => config.key === configKey);
}

function toKnownConfigOption(config: SystemConfig): KnownSystemConfigOption {
  const fallback = FALLBACK_SYSTEM_CONFIG_KEYS.find((item) => item.key === config.configKey);
  return {
    key: config.configKey,
    label: fallback?.label ?? config.configKey,
    hint: fallback?.hint ?? config.description ?? '暂无补充说明。',
  };
}

function isReasoningModeConfig(item: SystemConfigFormItem) {
  return item.configKey === 'ai.knowledge.reasoning-mode.default';
}

async function loadKnownConfigOptions() {
  try {
    const response = await systemConfigApi.listKnown();
    const knownOptions = (response.data.data ?? []).map(toKnownConfigOption);
    knownConfigOptions.value = knownOptions.length > 0 ? knownOptions : FALLBACK_SYSTEM_CONFIG_KEYS;
  } catch {
    knownConfigOptions.value = FALLBACK_SYSTEM_CONFIG_KEYS;
  }
}

async function loadConfigs() {
  loading.value = true;
  errorMessage.value = '';

  try {
    await loadKnownConfigOptions();
    const responses = await Promise.all(
      knownConfigOptions.value.map(async (item) => {
        const response = await systemConfigApi.getByKey(item.key);
        const config = response.data.data;
        return {
          ...config,
          draftValue: config.configValue,
        };
      }),
    );

    items.value = responses;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '系统配置加载失败。';
  } finally {
    loading.value = false;
  }
}

async function loadModelRegistry() {
  registryLoading.value = true;
  registryError.value = '';

  try {
    const response = await systemConfigApi.getModelRegistry();
    applyModelRegistry(response.data.data);
  } catch (error) {
    registryError.value = error instanceof Error ? error.message : '模型注册表加载失败。';
  } finally {
    registryLoading.value = false;
  }
}

async function loadPromptTemplates() {
  try {
    const results = await Promise.all(
      PROMPT_TYPES.map(async (item) => {
        const response = await systemConfigApi.listPromptTemplates(item.value);
        return [item.value, response.data.data ?? []] as const;
      }),
    );
    templateOptions.value = Object.fromEntries(results) as Record<PromptType, PromptTemplateOption[]>;
  } catch {
    templateOptions.value = {
      deconstruct: [],
      structure: [],
      plot: [],
      theme: [],
    };
  }
}

async function saveItem(item: SystemConfigFormItem) {
  if (!item.editable) {
    return;
  }

  try {
    const response = await systemConfigApi.update({
      configKey: item.configKey,
      configValue: item.draftValue,
      ...(item.configType ? { configType: item.configType } : {}),
      ...(item.description ? { description: item.description } : {}),
    });

    const updated = response.data.data;
    item.configValue = updated.configValue;
    item.draftValue = updated.configValue;
    item.configType = updated.configType ?? undefined;
    item.description = updated.description ?? undefined;
    item.editable = updated.editable;
    ElMessage.success(`${item.configKey} 已更新`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '系统配置保存失败。');
  }
}

function addModel() {
  const usedKeys = new Set(modelRegistry.value.models.map((model) => model.modelKey));
  let nextIndex = modelRegistry.value.models.length + 1;
  while (usedKeys.has(`new-model-${nextIndex}`)) {
    nextIndex += 1;
  }
  const nextModel = createEmptyModelDraft(nextIndex);
  if (!modelRegistry.value.defaultModelKey) {
    nextModel.isDefault = true;
    modelRegistry.value.defaultModelKey = nextModel.modelKey;
  }
  modelRegistry.value.models = [...modelRegistry.value.models, nextModel];
}

function removeModel(modelKey: string) {
  modelRegistry.value.models = modelRegistry.value.models.filter((model) => model.modelKey !== modelKey);
  providerRoutingPolicy.value.orderedProfileKeys = providerRoutingPolicy.value.orderedProfileKeys
    .filter((profileKey) => profileKey !== modelKey);
  if (modelRegistry.value.defaultModelKey === modelKey) {
    modelRegistry.value.defaultModelKey = modelRegistry.value.models[0]?.modelKey ?? '';
  }
}

function handleProviderRoutingEnabled(enabled: boolean) {
  providerRoutingPolicy.value.enabled = enabled;
  if (!enabled || providerRoutingPolicy.value.orderedProfileKeys.length > 0) {
    return;
  }
  providerRoutingPolicy.value.orderedProfileKeys = eligibleRoutingModels.value
    .map((model) => model.modelKey)
    .sort((left, right) => {
      if (left === modelRegistry.value.defaultModelKey) return -1;
      if (right === modelRegistry.value.defaultModelKey) return 1;
      return 0;
    });
  providerRoutingPolicy.value.maxFailovers = Math.max(
    0,
    providerRoutingPolicy.value.orderedProfileKeys.length - 1,
  );
}

// Removing candidates must not leave maxFailovers above the list length, which the
// backend rejects on save.
watch(
  () => providerRoutingPolicy.value.orderedProfileKeys.length,
  (length) => {
    if (providerRoutingPolicy.value.maxFailovers > length) {
      providerRoutingPolicy.value.maxFailovers = length;
    }
  },
);

function addRoutingCandidate() {
  const profileKey = routingCandidateToAdd.value.trim();
  if (!profileKey || providerRoutingPolicy.value.orderedProfileKeys.includes(profileKey)) {
    return;
  }
  providerRoutingPolicy.value.orderedProfileKeys.push(profileKey);
  routingCandidateToAdd.value = '';
}

function removeRoutingCandidate(profileKey: string) {
  providerRoutingPolicy.value.orderedProfileKeys = providerRoutingPolicy.value.orderedProfileKeys
    .filter((candidate) => candidate !== profileKey);
}

function moveRoutingCandidate(index: number, direction: -1 | 1) {
  const nextIndex = index + direction;
  const ordered = [...providerRoutingPolicy.value.orderedProfileKeys];
  if (nextIndex < 0 || nextIndex >= ordered.length) {
    return;
  }
  [ordered[index], ordered[nextIndex]] = [ordered[nextIndex]!, ordered[index]!];
  providerRoutingPolicy.value.orderedProfileKeys = ordered;
}

function routingModelLabel(profileKey: string) {
  const model = modelRegistry.value.models.find((candidate) => candidate.modelKey === profileKey);
  return model ? `${model.displayName || model.modelKey} · ${model.modelKey}` : profileKey;
}

function validateProviderRoutingPolicy() {
  const policy = providerRoutingPolicy.value;
  if (!policy.enabled) {
    return null;
  }
  if (policy.orderedProfileKeys.length < 2) {
    return '开启故障转移前，至少加入一个主 Profile 和一个备用 Profile。';
  }
  const modelsByKey = new Map(modelRegistry.value.models.map((model) => [model.modelKey, model]));
  const profiles = policy.orderedProfileKeys.map((profileKey) => modelsByKey.get(profileKey));
  if (profiles.some((profile) => !profile?.enabled)) {
    return '路由候选必须全部存在且处于启用状态。';
  }
  if (profiles.some((profile) => (
    !profile
    || normalizeModelProtocol(profile.protocol) === 'unspecified'
    || !profile.providerCapabilities
    || !profile.modelName.trim()
    || !profile.baseUrl?.trim()
  ))) {
    return '路由候选必须配置接口、模型、显式协议和 Provider 能力。';
  }
  const [primary] = profiles as ModelDraft[];
  const compatibility = JSON.stringify({
    providerType: primary.providerType.trim().toLowerCase(),
    protocol: normalizeModelProtocol(primary.protocol),
    capabilities: normalizeProviderCapabilities(primary.providerCapabilities),
  });
  const incompatible = (profiles as ModelDraft[]).some((profile) => JSON.stringify({
    providerType: profile.providerType.trim().toLowerCase(),
    protocol: normalizeModelProtocol(profile.protocol),
    capabilities: normalizeProviderCapabilities(profile.providerCapabilities),
  }) !== compatibility);
  return incompatible ? '主备 Profile 的提供方类型、协议和能力声明必须一致。' : null;
}

function markDefaultModel(modelKey: string) {
  modelRegistry.value.defaultModelKey = modelKey;
  modelRegistry.value.models = modelRegistry.value.models.map((model) => ({
    ...model,
    isDefault: model.modelKey === modelKey,
  }));
}

function getPromptTemplateOptions(promptType: PromptType) {
  return templateOptions.value[promptType] ?? [];
}

function buildRegistryPayload(): AiModelRegistryUpdateRequest {
  return {
    defaultModelKey: modelRegistry.value.defaultModelKey,
    providerRoutingPolicy: {
      ...providerRoutingPolicy.value,
      orderedProfileKeys: [...providerRoutingPolicy.value.orderedProfileKeys],
    },
    models: modelRegistry.value.models.map((model) => ({
      modelKey: model.modelKey.trim(),
      displayName: model.displayName.trim(),
      providerType: model.providerType.trim() || 'openai-compatible',
      protocol: normalizeModelProtocol(model.protocol),
      ...buildProviderCapabilitiesPayload(model.providerCapabilities),
      modelName: model.modelName.trim(),
      ...(model.baseUrl?.trim() ? { baseUrl: model.baseUrl.trim() } : {}),
      ...(model.draftApiKeyInput.trim() ? { apiKey: model.draftApiKeyInput.trim() } : {}),
      enabled: model.enabled,
      isDefault: modelRegistry.value.defaultModelKey === model.modelKey,
      ...(model.draftDefaultTemperature !== '' ? { defaultTemperature: Number(model.draftDefaultTemperature) } : {}),
      ...(model.draftMaxTokens !== '' ? { maxTokens: Number(model.draftMaxTokens) } : {}),
      ...(model.temperatureSpecJson?.trim() ? { temperatureSpecJson: model.temperatureSpecJson.trim() } : {}),
      promptBindings: {
        deconstruct: model.draftPromptBindings.deconstruct || undefined,
        structure: model.draftPromptBindings.structure || undefined,
        plot: model.draftPromptBindings.plot || undefined,
        theme: model.draftPromptBindings.theme || undefined,
      },
    })),
  };
}

async function saveModelRegistry() {
  const routingError = validateProviderRoutingPolicy();
  if (routingError) {
    ElMessage.error(routingError);
    return;
  }
  registrySaving.value = true;

  try {
    const response = await systemConfigApi.updateModelRegistry(buildRegistryPayload());
    applyModelRegistry(response.data.data);
    ElMessage.success('模型注册表已更新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模型注册表保存失败。');
  } finally {
    registrySaving.value = false;
  }
}

onMounted(() => {
  void Promise.all([loadConfigs(), loadPromptTemplates(), loadModelRegistry()]);
});
</script>

<template>
  <section class="system-config-page">
    <header class="system-config-page__hero">
      <p class="system-config-page__eyebrow">配置中心</p>
      <h2 class="system-config-page__title">系统配置</h2>
    </header>

    <section v-loading="registryLoading" class="system-config-page__registry">
      <div class="system-config-page__registry-head">
        <div>
          <p class="system-config-page__section-eyebrow">Agent 第三方网关</p>
          <h3 class="system-config-page__section-title">Agent 模型与密钥</h3>
          <p class="system-config-page__section-copy">
            NewAPI、Sub2API 与 OpenAI-compatible Responses 模型配置。
          </p>
        </div>

        <div class="system-config-page__registry-actions">
          <el-button plain data-test="model-registry-add" @click="addModel">
            新增模型
          </el-button>
          <el-button
            type="primary"
            :loading="registrySaving"
            data-test="model-registry-save"
            @click="saveModelRegistry"
          >
            保存模型注册表
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="registryError"
        :closable="false"
        show-icon
        type="error"
        title="模型注册表加载失败"
        :description="registryError"
      />

      <section class="system-config-page__provider-routing" data-test="provider-routing-panel">
        <div class="system-config-page__provider-routing-head">
          <div>
            <div class="system-config-page__provider-routing-title-row">
              <h4 class="system-config-page__provider-routing-title">路由与故障转移</h4>
              <el-tag
                :type="!providerRoutingPolicy.enabled
                  ? 'info'
                  : providerRoutingPolicy.maxFailovers === 0 ? 'warning' : 'success'"
                effect="plain"
                data-test="provider-routing-status"
              >
                {{ !providerRoutingPolicy.enabled
                  ? '已关闭'
                  : providerRoutingPolicy.maxFailovers === 0 ? '已配置，未切换' : '已开启' }}
              </el-tag>
            </div>
            <p class="system-config-page__provider-routing-copy">
              {{ providerRoutingPolicy.maxFailovers === 0
                ? '候选配置已保存；最大切换次数为 0，运行时继续使用单 Profile 路径。'
                : `按顺序使用已保存 Profile，越靠前优先级越高；单次请求最多切换 ${providerRoutingPolicy.maxFailovers} 次。凭证失效（401/403）、额度不足（402/429）、模型缺失（404）立即换 key；连接错误、超时与 5xx 先在同一 key 重试，重试预算耗尽后再换。已开始输出的流式回答不再切换。` }}
            </p>
          </div>
          <el-switch
            :model-value="providerRoutingPolicy.enabled"
            active-text="开启"
            inactive-text="关闭"
            data-test="provider-routing-enabled"
            @update:model-value="handleProviderRoutingEnabled"
          />
        </div>

        <div v-if="providerRoutingPolicy.enabled" class="system-config-page__provider-routing-body">
          <div class="system-config-page__provider-routing-settings">
            <el-form-item label="最多切换次数">
              <el-input-number
                v-model="providerRoutingPolicy.maxFailovers"
                :min="0"
                :max="providerRoutingPolicy.orderedProfileKeys.length"
                :step="1"
                controls-position="right"
                data-test="provider-routing-max-failovers"
              />
            </el-form-item>
            <el-form-item label="熔断冷却（秒）">
              <el-input-number
                v-model="providerRoutingPolicy.cooldownSeconds"
                :min="30"
                :max="3600"
                :step="30"
                controls-position="right"
                data-test="provider-routing-cooldown-seconds"
              />
            </el-form-item>
          </div>

          <div class="system-config-page__provider-routing-list" aria-label="Provider 路由优先级">
            <div
              v-for="(profileKey, index) in providerRoutingPolicy.orderedProfileKeys"
              :key="profileKey"
              class="system-config-page__provider-routing-row"
              :data-test="`provider-routing-row-${profileKey}`"
            >
              <div class="system-config-page__provider-routing-identity">
                <el-tag size="small" effect="plain" :type="index === 0 ? 'success' : 'info'">
                  {{ index === 0 ? '主 Profile' : `备用 ${index}` }}
                </el-tag>
                <span>{{ routingModelLabel(profileKey) }}</span>
              </div>
              <div class="system-config-page__provider-routing-actions">
                <el-tooltip content="上移优先级" placement="top">
                  <el-button
                    circle
                    :icon="ArrowUp"
                    :disabled="index === 0"
                    :aria-label="`上移 ${profileKey}`"
                    :data-test="`provider-routing-up-${profileKey}`"
                    @click="moveRoutingCandidate(index, -1)"
                  />
                </el-tooltip>
                <el-tooltip content="下移优先级" placement="top">
                  <el-button
                    circle
                    :icon="ArrowDown"
                    :disabled="index === providerRoutingPolicy.orderedProfileKeys.length - 1"
                    :aria-label="`下移 ${profileKey}`"
                    :data-test="`provider-routing-down-${profileKey}`"
                    @click="moveRoutingCandidate(index, 1)"
                  />
                </el-tooltip>
                <el-tooltip content="移出路由" placement="top">
                  <el-button
                    circle
                    type="danger"
                    plain
                    :icon="Delete"
                    :aria-label="`移出 ${profileKey}`"
                    :data-test="`provider-routing-remove-${profileKey}`"
                    @click="removeRoutingCandidate(profileKey)"
                  />
                </el-tooltip>
              </div>
            </div>
          </div>

          <div class="system-config-page__provider-routing-add">
            <el-select
              v-model="routingCandidateToAdd"
              placeholder="选择已启用且能力已声明的 Profile"
              data-test="provider-routing-candidate-select"
            >
              <el-option
                v-for="model in availableRoutingModels"
                :key="model.modelKey"
                :label="routingModelLabel(model.modelKey)"
                :value="model.modelKey"
              />
            </el-select>
            <el-tooltip content="加入备用路由" placement="top">
              <span>
                <el-button
                  circle
                  type="primary"
                  :icon="Plus"
                  :disabled="!routingCandidateToAdd"
                  aria-label="加入备用路由"
                  data-test="provider-routing-add-candidate"
                  @click="addRoutingCandidate"
                />
              </span>
            </el-tooltip>
          </div>

          <p
            v-if="providerRoutingPolicy.orderedProfileKeys.length < 2"
            class="system-config-page__provider-routing-warning"
            role="alert"
          >
            至少需要一个主 Profile 和一个备用 Profile 才能保存启用状态。
          </p>
        </div>
      </section>

      <div class="system-config-page__registry-list">
        <article
          v-for="model in modelRegistry.models"
          :key="model.modelKey"
          class="system-config-page__model-card"
        >
          <div class="system-config-page__model-card-head">
            <div>
              <p class="system-config-page__model-key">{{ model.displayName || model.modelKey || '未命名模型' }}</p>
              <p class="system-config-page__model-subkey">{{ model.modelKey || '未设置 modelKey' }}</p>
              <p class="system-config-page__model-copy">当前模型条目会直接影响用户侧下拉和 AI 运行时请求。</p>
            </div>

            <div class="system-config-page__model-flags">
              <el-switch
                v-model="model.enabled"
                active-text="启用"
                inactive-text="停用"
              />
              <el-radio
                :model-value="modelRegistry.defaultModelKey"
                :label="model.modelKey"
                @change="markDefaultModel(model.modelKey)"
              >
                默认
              </el-radio>
              <el-button
                text
                type="danger"
                @click="removeModel(model.modelKey)"
              >
                删除
              </el-button>
            </div>
          </div>

          <section class="system-config-page__capabilities">
            <div class="system-config-page__capabilities-head">
              <div class="system-config-page__capabilities-heading">
                <h4 class="system-config-page__capabilities-title">Provider 能力</h4>
                <el-tag
                  :type="model.providerCapabilities ? 'info' : 'warning'"
                  effect="plain"
                  :data-test="`model-capabilities-status-${model.modelKey}`"
                >
                  {{ model.providerCapabilities ? '已声明 v1' : '未验证' }}
                </el-tag>
              </div>
              <el-button
                v-if="!model.providerCapabilities"
                plain
                size="small"
                :data-test="`model-capabilities-configure-${model.modelKey}`"
                @click="beginProviderCapabilities(model)"
              >
                配置能力
              </el-button>
            </div>

            <div v-if="model.providerCapabilities" class="system-config-page__capability-grid">
              <div
                v-for="capability in PROVIDER_CAPABILITY_OPTIONS"
                :key="`${model.modelKey}-${capability.key}`"
                class="system-config-page__capability-item"
              >
                <span class="system-config-page__capability-label">{{ capability.label }}</span>
                <el-switch
                  :model-value="model.providerCapabilities?.[capability.key] ?? false"
                  active-text="支持"
                  inactive-text="不支持"
                  :data-test="`model-capability-${capability.key}-${model.modelKey}`"
                  @update:model-value="updateProviderCapability(model, capability.key, $event)"
                />
              </div>
            </div>

            <div v-if="model.providerCapabilities" class="system-config-page__prompt-cache">
              <el-form-item label="Responses 缓存策略">
                <el-select
                  :model-value="model.providerCapabilities.promptCache?.strategy ?? 'legacy_model_policy'"
                  :disabled="normalizeModelProtocol(model.protocol) !== 'responses'"
                  :data-test="`model-prompt-cache-strategy-${model.modelKey}`"
                  @update:model-value="updatePromptCacheStrategy(model, $event)"
                >
                  <el-option
                    v-for="option in PROMPT_CACHE_STRATEGY_OPTIONS"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                  />
                </el-select>
              </el-form-item>

              <template v-if="model.providerCapabilities.promptCache?.strategy === 'openai_gpt_5_6'">
                <el-form-item label="缓存模式">
                  <el-select
                    :model-value="model.providerCapabilities.promptCache.mode"
                    :data-test="`model-prompt-cache-mode-${model.modelKey}`"
                    @update:model-value="updatePromptCacheField(model, 'mode', $event)"
                  >
                    <el-option label="implicit（多轮默认）" value="implicit" />
                    <el-option label="explicit（只写显式边界）" value="explicit" />
                  </el-select>
                </el-form-item>
                <el-form-item label="缓存 TTL">
                  <el-select
                    :model-value="model.providerCapabilities.promptCache.retention"
                    :data-test="`model-prompt-cache-retention-${model.modelKey}`"
                    @update:model-value="updatePromptCacheField(model, 'retention', $event)"
                  >
                    <el-option label="Provider 默认" value="provider_default" />
                    <el-option label="30 分钟" value="30m" />
                  </el-select>
                </el-form-item>
                <el-form-item label="显式边界">
                  <el-select
                    :model-value="model.providerCapabilities.promptCache.breakpoint"
                    :data-test="`model-prompt-cache-breakpoint-${model.modelKey}`"
                    @update:model-value="updatePromptCacheField(model, 'breakpoint', $event)"
                  >
                    <el-option label="不添加" value="none" />
                    <el-option label="稳定 developer 前缀后" value="stable_prefix" />
                  </el-select>
                </el-form-item>
              </template>

              <el-form-item
                v-else-if="model.providerCapabilities.promptCache?.strategy === 'openai_legacy'"
                label="缓存保留"
              >
                <el-select
                  :model-value="model.providerCapabilities.promptCache.retention"
                  :data-test="`model-prompt-cache-retention-${model.modelKey}`"
                  @update:model-value="updatePromptCacheField(model, 'retention', $event)"
                >
                  <el-option label="Provider 默认" value="provider_default" />
                  <el-option label="内存" value="in_memory" />
                  <el-option label="24 小时" value="24h" />
                </el-select>
              </el-form-item>

              <p class="system-config-page__prompt-cache-hint">
                先选择 Responses API；策略仅作用于当前模型，实际命中以 Provider Usage 的缓存 token 为准。
              </p>
            </div>

            <div class="system-config-page__provider-probe">
              <div>
                <p class="system-config-page__provider-probe-title">Agent 连接验证</p>
                <div
                  v-if="providerProbeResults[model.modelKey] && !hasUnsavedProviderProbeChanges(model)"
                  class="system-config-page__provider-probe-result"
                >
                  <el-tag
                    :type="providerProbeResults[model.modelKey]?.status === 'SUCCEEDED' ? 'success' : 'danger'"
                    effect="plain"
                    :data-test="`model-provider-probe-status-${model.modelKey}`"
                  >
                    {{ providerProbeResults[model.modelKey]?.status === 'SUCCEEDED' ? '连接可用' : '连接失败' }}
                  </el-tag>
                  <span>{{ providerProbeSummary(providerProbeResults[model.modelKey]!) }}</span>
                </div>
                <p v-else class="system-config-page__provider-probe-empty">
                  {{ hasUnsavedProviderProbeChanges(model) ? '配置已修改，保存后重新验证' : '尚未验证已保存配置' }}
                </p>
              </div>
              <el-tooltip
                :content="providerProbeHint(model)"
                placement="top"
              >
                <span>
                  <el-button
                    plain
                    size="small"
                    :disabled="!canProbeModelProvider(model)"
                    :loading="providerProbeLoadingKey === model.modelKey"
                    :data-test="`model-provider-probe-${model.modelKey}`"
                    @click="probeModelProvider(model)"
                  >
                    测试 Agent 连接
                  </el-button>
                </span>
              </el-tooltip>
            </div>
          </section>

          <div class="system-config-page__model-grid">
            <el-form-item label="模型 Key">
              <el-input
                v-model="model.modelKey"
                :data-test="`model-key-${model.modelKey}`"
                placeholder="例如 deepseek-chat"
              />
              <div
                v-if="model.apiKeyConfigured"
                :data-test="`model-api-key-status-${model.modelKey}`"
                class="system-config-page__field-hint"
              >
                当前状态：{{ model.apiKeyMasked || '已配置' }}，留空则保留原 key
              </div>
            </el-form-item>

            <el-form-item label="显示名称">
              <el-input
                v-model="model.displayName"
                :data-test="`model-display-name-${model.modelKey}`"
                placeholder="例如 DeepSeek Chat"
              />
            </el-form-item>

            <el-form-item label="提供方类型">
              <el-input
                v-model="model.providerType"
                :data-test="`model-provider-type-${model.modelKey}`"
                placeholder="例如 openai-compatible"
              />
            </el-form-item>

            <el-form-item label="请求协议">
              <el-select
                v-model="model.protocol"
                :data-test="`model-protocol-${model.modelKey}`"
                placeholder="选择请求协议"
                style="width: 100%"
              >
                <el-option
                  v-for="protocolOption in MODEL_PROTOCOL_OPTIONS"
                  :key="protocolOption.value"
                  :label="protocolOption.label"
                  :value="protocolOption.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="实际模型名">
              <el-input
                v-model="model.modelName"
                :data-test="`model-name-${model.modelKey}`"
                placeholder="例如 deepseek-chat"
              />
            </el-form-item>

            <el-form-item label="接口地址">
              <el-input
                v-model="model.baseUrl"
                :data-test="`model-base-url-${model.modelKey}`"
                placeholder="例如 https://api.deepseek.com/v1"
              />
            </el-form-item>

            <el-form-item label="接口密钥">
              <el-input
                v-model="model.draftApiKeyInput"
                :data-test="`model-api-key-${model.modelKey}`"
                show-password
                type="password"
                placeholder="填写该模型专属 key"
              />
            </el-form-item>

            <el-form-item label="默认温度">
              <el-input
                v-model="model.draftDefaultTemperature"
                :data-test="`model-default-temperature-${model.modelKey}`"
                type="number"
                min="0"
                max="2"
                step="0.1"
                placeholder="例如 1.0"
              />
            </el-form-item>

            <el-form-item label="最大输出 Token">
              <el-input
                v-model="model.draftMaxTokens"
                :data-test="`model-max-tokens-${model.modelKey}`"
                type="number"
                min="1"
                placeholder="例如 8192"
              />
            </el-form-item>
</div>

          <div class="system-config-page__model-grid">
            <el-form-item
              v-for="promptType in PROMPT_TYPES"
              :key="`${model.modelKey}-${promptType.value}`"
              :label="promptType.label"
            >
              <el-select
                v-model="model.draftPromptBindings[promptType.value]"
                :data-test="`model-prompt-binding-${promptType.value}-${model.modelKey}`"
                clearable
                filterable
                placeholder="选择模板名称"
                style="width: 100%"
              >
                <el-option
                  v-for="template in getPromptTemplateOptions(promptType.value)"
                  :key="`${promptType.value}-${template.promptName}`"
                  :label="`${template.promptName} (${template.modelName})`"
                  :value="template.promptName"
                />
              </el-select>
            </el-form-item>
          </div>

          <el-form-item label="温度 JSON 约束">
            <el-input
              v-model="model.temperatureSpecJson"
              :autosize="{ minRows: 3, maxRows: 6 }"
              :data-test="`model-temperature-spec-${model.modelKey}`"
              type="textarea"
              placeholder='例如 {"min":0,"max":2,"step":0.1,"default":1.0}'
            />
          </el-form-item>
        </article>
      </div>
    </section>

    <el-alert
      v-if="errorMessage"
      :closable="false"
      show-icon
      type="error"
      title="系统配置加载失败"
      :description="errorMessage"
    />

    <section v-loading="loading" class="system-config-page__list">
      <div class="system-config-page__runtime-head">
        <div>
          <p class="system-config-page__section-eyebrow">运行参数</p>
          <h3 class="system-config-page__section-title">运行参数</h3>
          <p class="system-config-page__section-copy">配置超时、分段分析、抓取缓存和用户频控。</p>
        </div>
      </div>

      <article
        v-for="item in items"
        :key="item.configKey"
        class="system-config-page__card"
      >
        <div class="system-config-page__card-header">
          <div>
            <h3 class="system-config-page__card-title">{{ item.configKey }}</h3>
            <p class="system-config-page__card-subtitle">
              {{
                findKnownConfig(item.configKey)?.hint ??
                item.description ??
                '暂无补充说明。'
              }}
            </p>
          </div>
          <span class="system-config-page__badge">
            {{ item.editable ? '可编辑' : '只读' }}
          </span>
        </div>

        <div class="system-config-page__field">
          <label class="system-config-page__label">
            {{
              findKnownConfig(item.configKey)?.label ??
              item.configKey
            }}
          </label>
          <el-input
            v-if="!isReasoningModeConfig(item)"
            v-model="item.draftValue"
            :disabled="!item.editable"
            :type="PASSWORD_KEYS.has(item.configKey) ? 'password' : 'text'"
            :show-password="PASSWORD_KEYS.has(item.configKey)"
            :data-test="`system-config-value-${item.configKey}`"
          />
          <el-segmented
            v-else
            v-model="item.draftValue"
            :disabled="!item.editable"
            :options="REASONING_MODE_OPTIONS"
            :data-test="`system-config-value-${item.configKey}`"
          />
        </div>

        <div class="system-config-page__meta">
          <span v-if="item.configType">类型：{{ item.configType }}</span>
        </div>

        <div class="system-config-page__actions">
          <el-button
            type="primary"
            :disabled="!item.editable"
            :data-test="`system-config-save-${item.configKey}`"
            @click="saveItem(item)"
          >
            保存
          </el-button>
        </div>
      </article>
    </section>
  </section>
</template>

<style scoped lang="scss">
.system-config-page {
  display: grid;
  gap: 1rem;
}

.system-config-page__hero,
.system-config-page__registry,
.system-config-page__list {
  border: 1px solid color-mix(in srgb, var(--color-border) 82%, transparent);
  border-radius: 1.25rem;
  background:
    linear-gradient(155deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.08)),
    color-mix(in srgb, var(--color-surface) 90%, transparent);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(1.08);
  -webkit-backdrop-filter: blur(18px) saturate(1.08);
}

.system-config-page__hero,
.system-config-page__registry,
.system-config-page__list {
  padding: 1.2rem;
}

.system-config-page__eyebrow,
.system-config-page__title,
.system-config-page__subtitle,
.system-config-page__section-eyebrow,
.system-config-page__section-title,
.system-config-page__section-copy {
  margin: 0;
}

.system-config-page__eyebrow,
.system-config-page__section-eyebrow {
  color: var(--color-text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.system-config-page__title {
  margin-top: 0.2rem;
  font-size: 1.5rem;
}

.system-config-page__subtitle,
.system-config-page__section-copy {
  margin-top: 0.35rem;
  color: var(--color-text-muted);
  line-height: 1.7;
}

.system-config-page__registry,
.system-config-page__list {
  display: grid;
  gap: 1rem;
}

.system-config-page__registry-head,
.system-config-page__runtime-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}

.system-config-page__registry-actions {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.system-config-page__registry-list {
  display: grid;
  gap: 1rem;
}

.system-config-page__provider-routing {
  display: grid;
  gap: 1rem;
  padding: 1rem 0;
  border-top: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
}

.system-config-page__provider-routing-head,
.system-config-page__provider-routing-title-row,
.system-config-page__provider-routing-row,
.system-config-page__provider-routing-identity,
.system-config-page__provider-routing-actions,
.system-config-page__provider-routing-add {
  display: flex;
  align-items: center;
}

.system-config-page__provider-routing-head,
.system-config-page__provider-routing-row {
  justify-content: space-between;
  gap: 1rem;
}

.system-config-page__provider-routing-title-row,
.system-config-page__provider-routing-identity,
.system-config-page__provider-routing-actions,
.system-config-page__provider-routing-add {
  gap: 0.75rem;
}

.system-config-page__provider-routing-title,
.system-config-page__provider-routing-copy,
.system-config-page__provider-routing-warning {
  margin: 0;
}

.system-config-page__provider-routing-title {
  font-size: 0.95rem;
}

.system-config-page__provider-routing-copy,
.system-config-page__provider-routing-warning {
  margin-top: 0.35rem;
  color: var(--color-text-muted);
  line-height: 1.6;
}

.system-config-page__provider-routing-body,
.system-config-page__provider-routing-list {
  display: grid;
  gap: 0.75rem;
}

.system-config-page__provider-routing-settings {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 1rem;
}

.system-config-page__provider-routing-row {
  min-height: 3rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 68%, transparent);
}

.system-config-page__provider-routing-identity {
  min-width: 0;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.system-config-page__provider-routing-actions .el-button,
.system-config-page__provider-routing-add .el-button {
  width: 2.75rem;
  min-width: 2.75rem;
  height: 2.75rem;
}

.system-config-page__provider-routing-add .el-select {
  width: min(100%, 30rem);
}

.system-config-page__provider-routing-warning {
  color: var(--el-color-warning-dark-2);
}

.system-config-page__model-card,
.system-config-page__card {
  display: grid;
  gap: 0.9rem;
  padding: 1rem;
  border-radius: 1rem;
  background: color-mix(in srgb, var(--color-primary-soft) 32%, transparent);
}

.system-config-page__model-card-head,
.system-config-page__card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.system-config-page__model-key,
.system-config-page__model-subkey,
.system-config-page__model-copy,
.system-config-page__card-title,
.system-config-page__card-subtitle {
  margin: 0;
}

.system-config-page__model-key,
.system-config-page__card-title {
  font-size: 1rem;
  font-weight: 700;
}

.system-config-page__model-copy,
.system-config-page__card-subtitle {
  margin-top: 0.3rem;
  color: var(--color-text-muted);
  line-height: 1.6;
}

.system-config-page__model-subkey {
  margin-top: 0.2rem;
  color: var(--color-text-muted);
  font-size: 0.82rem;
  letter-spacing: 0.05em;
}

.system-config-page__model-flags {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}

.system-config-page__model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 1rem;
}

.system-config-page__capabilities {
  display: grid;
  gap: 0.75rem;
  padding-top: 0.9rem;
  border-top: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
}

.system-config-page__capabilities-head,
.system-config-page__capabilities-heading,
.system-config-page__capability-item {
  display: flex;
  align-items: center;
}

.system-config-page__capabilities-head {
  justify-content: space-between;
  gap: 1rem;
}

.system-config-page__capabilities-heading {
  gap: 0.75rem;
  flex-wrap: wrap;
}

.system-config-page__capabilities-title {
  margin: 0;
  font-size: 0.95rem;
}

.system-config-page__capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem 1rem;
}

.system-config-page__capability-item {
  min-height: 2.75rem;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 68%, transparent);
}

.system-config-page__capability-label {
  color: var(--color-text);
  font-size: 0.9rem;
  font-weight: 600;
}

.system-config-page__prompt-cache {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 1rem;
  padding-top: 0.75rem;
  border-top: 1px solid color-mix(in srgb, var(--color-border) 68%, transparent);
}

.system-config-page__prompt-cache-hint {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.82rem;
  line-height: 1.6;
}

.system-config-page__provider-probe {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding-top: 0.75rem;
  border-top: 1px solid color-mix(in srgb, var(--color-border) 68%, transparent);
}

.system-config-page__provider-probe-title,
.system-config-page__provider-probe-empty {
  margin: 0;
}

.system-config-page__provider-probe-title {
  font-size: 0.9rem;
  font-weight: 700;
}

.system-config-page__provider-probe-empty,
.system-config-page__provider-probe-result {
  margin-top: 0.3rem;
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.system-config-page__provider-probe-result {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  flex-wrap: wrap;
}

.system-config-page__badge {
  padding: 0.35rem 0.75rem;
  border-radius: 999px;
  background: rgba(185, 104, 31, 0.12);
  color: var(--color-text);
  font-size: 0.82rem;
  white-space: nowrap;
}

.system-config-page__field {
  display: grid;
  gap: 0.4rem;
}

.system-config-page__label {
  font-weight: 600;
}

.system-config-page__meta {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
  color: var(--color-text-muted);
  font-size: 0.88rem;
}

.system-config-page__actions {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 760px) {
  .system-config-page__registry-head,
  .system-config-page__runtime-head,
  .system-config-page__model-card-head,
  .system-config-page__card-header {
    display: grid;
  }

  .system-config-page__model-grid {
    grid-template-columns: 1fr;
  }

  .system-config-page__provider-routing-head,
  .system-config-page__provider-routing-row,
  .system-config-page__provider-routing-add {
    align-items: stretch;
    flex-direction: column;
  }

  .system-config-page__provider-routing-settings {
    grid-template-columns: 1fr;
  }

  .system-config-page__provider-routing-actions {
    align-self: flex-end;
  }

  .system-config-page__provider-routing-add .el-select {
    width: 100%;
  }

  .system-config-page__capability-grid {
    grid-template-columns: 1fr;
  }

  .system-config-page__prompt-cache {
    grid-template-columns: 1fr;
  }

  .system-config-page__provider-probe {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
