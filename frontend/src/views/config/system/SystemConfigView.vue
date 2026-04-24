<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { systemConfigApi } from '@/api/config';
import type {
  AiModelRegistry,
  AiModelRegistryModel,
  AiModelRegistryUpdateRequest,
  PromptTemplateOption,
  KnownSystemConfigKey,
  SystemConfig,
  PromptType,
} from '@/types/config';

const PASSWORD_KEYS = new Set(['ai.openai-compatible.api-key']);

const SYSTEM_CONFIG_KEYS: Array<{ key: KnownSystemConfigKey; label: string; hint: string }> = [
  { key: 'ai.provider.type', label: 'AI Provider Type', hint: '选择后端分析优先使用的 AI 提供方。' },
  { key: 'ai.timeout.millis', label: 'AI Timeout (ms)', hint: '控制 AI 请求超时。' },
  { key: 'ai.openai-compatible.streaming-enabled', label: 'OpenAI-Compatible Streaming', hint: '控制是否启用流式调用。' },
  { key: 'analysis.chunk.max-input-tokens', label: 'Analysis Chunk Max Tokens', hint: '单次分析允许的估算输入 Token 上限；超过后会自动切换为分段汇总。' },
  { key: 'analysis.chunk.target-input-tokens', label: 'Analysis Chunk Target Tokens', hint: '分段分析时每段的目标输入 Token 大小；数值越小分段越多。' },
  { key: 'analysis.chunk.parallelism', label: 'Analysis Chunk Parallelism', hint: '分段分析时的最大并发段数，在降低总耗时与避免请求过多之间取平衡。' },
  { key: 'auth.bootstrap-admin-phones', label: 'Admin Phones', hint: '逗号分隔的管理员手机号列表，登录/注册/刷新时会自动补齐 ADMIN 角色。' },
  { key: 'crawler.default.chapter-count', label: 'Default Chapter Count', hint: '扫榜页默认抓章数量。' },
  { key: 'crawler.http.timeout-seconds', label: 'Crawler HTTP Timeout (s)', hint: 'Python crawler 请求页面时的超时。' },
  { key: 'crawler.chapter.fetch-workers', label: 'Chapter Fetch Workers', hint: '多章节抓取时的最大并发数。' },
  { key: 'crawler.chapter.force-refresh.user-max-times', label: 'User Chapter Refresh Limit', hint: '普通用户在当前窗口内的章节重抓上限。' },
  { key: 'crawler.rank.refresh-days', label: 'Rank Cache Window (days)', hint: '榜单缓存期与章节重抓统计窗口。' },
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

async function loadConfigs() {
  loading.value = true;
  errorMessage.value = '';

  try {
    const responses = await Promise.all(
      SYSTEM_CONFIG_KEYS.map(async (item) => {
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
  const results = await Promise.all(
    PROMPT_TYPES.map(async (item) => {
      const response = await systemConfigApi.listPromptTemplates(item.value);
      return [item.value, response.data.data ?? []] as const;
    }),
  );
  templateOptions.value = Object.fromEntries(results) as Record<PromptType, PromptTemplateOption[]>;
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
      <p class="system-config-page__eyebrow">Current Page</p>
      <h2 class="system-config-page__title">系统配置</h2>
    </header>

    <section v-loading="registryLoading" class="system-config-page__registry">
      <div class="system-config-page__registry-head">
        <div>
          <p class="system-config-page__section-eyebrow">Model Registry</p>
          <h3 class="system-config-page__section-title">统一模型请求配置</h3>
          <p class="system-config-page__section-copy">
            所有分析共用这一套模型注册表。用户侧只需要选择模型，实际的 `baseUrl`、`apiKey`、温度默认值和温度 JSON 约束都从这里下发。
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

            <el-form-item label="Provider Type">
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

            <el-form-item label="Base URL">
              <el-input
                v-model="model.baseUrl"
                :data-test="`model-base-url-${model.modelKey}`"
                placeholder="例如 https://api.deepseek.com/v1"
              />
            </el-form-item>

            <el-form-item label="API Key">
              <el-input
                v-model="model.draftApiKeyInput"
                :data-test="`model-api-key-${model.modelKey}`"
                show-password
                type="password"
                placeholder="填写该模型专属 key"
              />
            </el-form-item>

            <el-form-item label="默认 Temperature">
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

            <el-form-item label="Max Tokens">
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

          <el-form-item label="Temperature JSON 约束">
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
          <p class="system-config-page__section-eyebrow">Runtime Config</p>
          <h3 class="system-config-page__section-title">运行参数</h3>
          <p class="system-config-page__section-copy">这些配置继续负责超时、分段分析和抓取策略，不与模型注册表重复。</p>
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
                SYSTEM_CONFIG_KEYS.find((config) => config.key === item.configKey)?.hint ??
                item.description ??
                'No extra description.'
              }}
            </p>
          </div>
          <span class="system-config-page__badge">
            {{ item.editable ? 'Editable' : 'Read Only' }}
          </span>
        </div>

        <div class="system-config-page__field">
          <label class="system-config-page__label">
            {{
              SYSTEM_CONFIG_KEYS.find((config) => config.key === item.configKey)?.label ??
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
          <span v-if="item.description">说明：{{ item.description }}</span>
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
