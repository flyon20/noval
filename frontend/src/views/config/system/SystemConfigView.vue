<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import { systemConfigApi } from '@/api/config';
import type {
  AiModelRegistry,
  AiModelRegistryModel,
  AiModelRegistryUpdateRequest,
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

type ModelDraft = AiModelRegistryModel & {
  draftApiKeyInput: string;
  draftDefaultTemperature: string;
  draftMaxTokens: string;
  draftPromptBindings: Record<PromptType, string>;
};

const PROMPT_TYPES: Array<{ label: string; value: PromptType }> = [
  { label: '拆文模板', value: 'deconstruct' },
  { label: '结构模板', value: 'structure' },
  { label: '情节模板', value: 'plot' },
  { label: '趋势模板', value: 'theme' },
];

const loading = ref(false);
const items = ref<SystemConfigFormItem[]>([]);
const knownConfigOptions = ref<KnownSystemConfigOption[]>(FALLBACK_SYSTEM_CONFIG_KEYS);
const errorMessage = ref('');

const registryLoading = ref(false);
const registrySaving = ref(false);
const registryError = ref('');
const templateOptions = ref<Record<PromptType, PromptTemplateOption[]>>({
  deconstruct: [],
  structure: [],
  plot: [],
  theme: [],
});
const modelRegistry = ref<AiModelRegistry>({
  defaultModelKey: '',
  models: [],
});

function toModelDraft(model: AiModelRegistryModel): ModelDraft {
  return {
    ...model,
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

function normalizeBindings(bindings?: Partial<Record<PromptType, string>> | null) {
  return {
    deconstruct: bindings?.deconstruct ?? '',
    structure: bindings?.structure ?? '',
    plot: bindings?.plot ?? '',
    theme: bindings?.theme ?? '',
  };
}

function applyModelRegistry(registry: AiModelRegistry) {
  modelRegistry.value = {
    defaultModelKey: registry.defaultModelKey,
    models: registry.models.map(toModelDraft),
  };
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
  const nextIndex = modelRegistry.value.models.length + 1;
  const nextModel = createEmptyModelDraft(nextIndex);
  if (!modelRegistry.value.defaultModelKey) {
    nextModel.isDefault = true;
    modelRegistry.value.defaultModelKey = nextModel.modelKey;
  }
  modelRegistry.value.models = [...modelRegistry.value.models, nextModel];
}

function removeModel(modelKey: string) {
  modelRegistry.value.models = modelRegistry.value.models.filter((model) => model.modelKey !== modelKey);
  if (modelRegistry.value.defaultModelKey === modelKey) {
    modelRegistry.value.defaultModelKey = modelRegistry.value.models[0]?.modelKey ?? '';
  }
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
    models: modelRegistry.value.models.map((model) => ({
      modelKey: model.modelKey.trim(),
      displayName: model.displayName.trim(),
      providerType: model.providerType.trim() || 'openai-compatible',
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
          <p class="system-config-page__section-eyebrow">模型注册表</p>
          <h3 class="system-config-page__section-title">统一模型请求配置</h3>
          <p class="system-config-page__section-copy">
            配置模型、接口地址、密钥、默认参数和提示词模板绑定。
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
            v-model="item.draftValue"
            :disabled="!item.editable"
            :type="PASSWORD_KEYS.has(item.configKey) ? 'password' : 'text'"
            :show-password="PASSWORD_KEYS.has(item.configKey)"
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

.system-config-page__model-card,
.system-config-page__card {
  display: grid;
  gap: 0.9rem;
  padding: 1rem;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.03);
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
}
</style>
