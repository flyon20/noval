<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';
import { promptConfigApi, systemConfigApi } from '@/api/config';
import type { PromptConfig, PromptType } from '@/types/config';

const PROMPT_TYPES: Array<{ label: string; value: PromptType }> = [
  { label: '拆文分析', value: 'deconstruct' },
  { label: '结构分析', value: 'structure' },
  { label: '情节分析', value: 'plot' },
  { label: '趋势分析', value: 'theme' },
];

const activeType = ref<PromptType>('deconstruct');
const loading = ref(false);
const saving = ref(false);
const traceId = ref('');
const errorMessage = ref('');

const formState = reactive({
  promptName: '',
  promptContent: '',
  modelName: '',
  temperature: '',
  maxTokens: '',
  outputJsonSchema: '',
  outputExampleJson: '',
  postProcessType: '',
  parseConfigJson: '',
});

function applyPromptConfig(config: PromptConfig) {
  formState.promptName = config.promptName;
  formState.promptContent = config.promptContent;
  formState.modelName = config.modelName;
  formState.temperature = config.temperature == null ? '' : String(config.temperature);
  formState.maxTokens = config.maxTokens == null ? '' : String(config.maxTokens);
  formState.outputJsonSchema = config.outputJsonSchema ?? '';
  formState.outputExampleJson = config.outputExampleJson ?? '';
  formState.postProcessType = config.postProcessType ?? '';
  formState.parseConfigJson = config.parseConfigJson ?? '';
}

async function loadPromptConfig(promptType = activeType.value) {
  loading.value = true;
  errorMessage.value = '';

  try {
    const response = await promptConfigApi.getByType(promptType);
    activeType.value = promptType;
    traceId.value = response.data.traceId;
    applyPromptConfig(response.data.data);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '提示词配置加载失败。';
  } finally {
    loading.value = false;
  }
}

function buildUpdatePayload() {
  return {
    promptType: activeType.value,
    promptName: formState.promptName.trim(),
    promptContent: formState.promptContent,
    modelName: formState.modelName.trim(),
    ...(formState.temperature !== '' ? { temperature: Number(formState.temperature) } : {}),
    ...(formState.maxTokens !== '' ? { maxTokens: Number(formState.maxTokens) } : {}),
    ...(formState.outputJsonSchema.trim() ? { outputJsonSchema: formState.outputJsonSchema.trim() } : {}),
    ...(formState.outputExampleJson.trim() ? { outputExampleJson: formState.outputExampleJson.trim() } : {}),
    ...(formState.postProcessType.trim() ? { postProcessType: formState.postProcessType.trim() } : {}),
    ...(formState.parseConfigJson.trim() ? { parseConfigJson: formState.parseConfigJson.trim() } : {}),
  };
}

function validatePayload() {
  if (!formState.promptContent.includes('{{content}}')) {
    throw new Error('提示词必须包含 {{content}} 占位符');
  }

  if (!formState.promptName.trim()) {
    throw new Error('提示词名称不能为空');
  }

  if (!formState.modelName.trim()) {
    throw new Error('模型名称不能为空');
  }
}

async function handleSave() {
  try {
    validatePayload();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '表单校验失败');
    return;
  }

  saving.value = true;

  try {
    const response = await promptConfigApi.update(buildUpdatePayload());
    traceId.value = response.data.traceId;
    applyPromptConfig(response.data.data);
    ElMessage.success('提示词配置已保存');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提示词配置保存失败');
  } finally {
    saving.value = false;
  }
}

const availableModels = ref<string[]>([]);

onMounted(() => {
  void loadPromptConfig();
  void systemConfigApi.getAvailableModels().then((res) => {
    const models = res.data.data ?? [];
    availableModels.value = models.includes('dify') ? models : ['dify', ...models];
  }).catch(() => {
    availableModels.value = ['dify'];
  });
});
</script>

<template>
  <section class="prompt-config-page">
    <header class="prompt-config-page__hero">
      <div>
        <p class="prompt-config-page__eyebrow">Current Page</p>
        <h2 class="prompt-config-page__title">提示词配置</h2>
        <p class="prompt-config-page__subtitle">管理提示词内容与模型参数。</p>
      </div>
      <div class="prompt-config-page__meta">
        <span>当前类型：{{ activeType }}</span>
        <span v-if="traceId">traceId：{{ traceId }}</span>
      </div>
    </header>

    <section class="prompt-config-page__types">
      <button
        v-for="item in PROMPT_TYPES"
        :key="item.value"
        class="prompt-config-page__type"
        :class="{ 'is-active': item.value === activeType }"
        type="button"
        :data-test="`prompt-type-${item.value}`"
        @click="loadPromptConfig(item.value)"
      >
        {{ item.label }}
      </button>
    </section>

    <el-alert
      v-if="errorMessage"
      :closable="false"
      show-icon
      type="error"
      title="提示词配置加载失败"
      :description="errorMessage"
    />

    <el-form
      v-loading="loading"
      class="prompt-config-page__form"
      label-position="top"
    >
      <div class="prompt-config-page__grid">
        <el-form-item label="提示词名称">
          <el-input
            v-model="formState.promptName"
            data-test="prompt-name-input"
            placeholder="请输入提示词名称"
          />
        </el-form-item>

        <el-form-item label="模型名称">
          <el-select
            v-model="formState.modelName"
            allow-create
            filterable
            default-first-option
            data-test="prompt-model-input"
            placeholder="选择或输入模型名称"
            style="width: 100%"
          >
            <el-option
              v-for="model in availableModels"
              :key="model"
              :label="model"
              :value="model"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="Temperature">
          <el-input
            v-model="formState.temperature"
            data-test="prompt-temperature-input"
            type="number"
            min="0"
            max="2"
            step="0.1"
            placeholder="例如 0.7"
          />
        </el-form-item>

        <el-form-item label="Max Tokens">
          <el-input
            v-model="formState.maxTokens"
            data-test="prompt-max-tokens-input"
            type="number"
            min="1"
            placeholder="例如 2048"
          />
        </el-form-item>
      </div>

      <el-form-item label="提示词内容">
        <el-input
          v-model="formState.promptContent"
          :autosize="{ minRows: 14, maxRows: 20 }"
          data-test="prompt-content-input"
          type="textarea"
          placeholder="请保留 {{content}} 占位符"
        />
      </el-form-item>

      <div class="prompt-config-page__hint">
        保存前必须保留 <code v-pre>{{content}}</code> 占位符。
      </div>

      <div class="prompt-config-page__grid">
        <el-form-item label="Output JSON Schema">
          <el-input
            v-model="formState.outputJsonSchema"
            :autosize="{ minRows: 6, maxRows: 10 }"
            data-test="prompt-output-json-schema-input"
            type="textarea"
            placeholder='例如 {"type":"object","properties":{"summary":{"type":"string"}}}'
          />
        </el-form-item>

        <el-form-item label="Output Example JSON">
          <el-input
            v-model="formState.outputExampleJson"
            :autosize="{ minRows: 6, maxRows: 10 }"
            data-test="prompt-output-example-json-input"
            type="textarea"
            placeholder='例如 {"summary":"example"}'
          />
        </el-form-item>
      </div>

      <div class="prompt-config-page__grid">
        <el-form-item label="Post Process Type">
          <el-input
            v-model="formState.postProcessType"
            data-test="prompt-post-process-type-input"
            placeholder="例如 json_extract"
          />
        </el-form-item>

        <el-form-item label="Parse Config JSON">
          <el-input
            v-model="formState.parseConfigJson"
            :autosize="{ minRows: 6, maxRows: 10 }"
            data-test="prompt-parse-config-json-input"
            type="textarea"
            placeholder='例如 {"parser":"json","trimMarkdownFence":true}'
          />
        </el-form-item>
      </div>

      <div class="prompt-config-page__preview">
        <p class="prompt-config-page__preview-title">当前模板预览</p>
        <pre class="prompt-config-page__preview-body">{{ formState.promptContent }}</pre>
      </div>

      <div class="prompt-config-page__actions">
        <el-button
          type="primary"
          :loading="saving"
          data-test="prompt-save-button"
          @click="handleSave"
        >
          保存提示词
        </el-button>
      </div>
    </el-form>
  </section>
</template>

<style scoped lang="scss">
.prompt-config-page {
  display: grid;
  gap: 1rem;
}

.prompt-config-page__hero,
.prompt-config-page__types,
.prompt-config-page__form {
  border: 1px solid var(--color-border);
  border-radius: 1.25rem;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: var(--shadow-soft);
}

.prompt-config-page__hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 1.2rem;
}

.prompt-config-page__eyebrow,
.prompt-config-page__title,
.prompt-config-page__subtitle {
  margin: 0;
}

.prompt-config-page__eyebrow {
  color: var(--color-text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.prompt-config-page__title {
  margin-top: 0.2rem;
  font-size: 1.5rem;
}

.prompt-config-page__subtitle {
  margin-top: 0.35rem;
  color: var(--color-text-muted);
  line-height: 1.7;
}

.prompt-config-page__meta {
  display: grid;
  gap: 0.35rem;
  color: var(--color-text-muted);
  font-size: 0.88rem;
  text-align: right;
}

.prompt-config-page__types {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  padding: 0.9rem 1rem;
}

.prompt-config-page__type {
  min-height: 44px;
  padding: 0.7rem 1rem;
  border: 1px solid rgba(35, 65, 58, 0.14);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  font: inherit;
  cursor: pointer;
}

.prompt-config-page__type.is-active {
  background: rgba(185, 104, 31, 0.12);
  border-color: rgba(185, 104, 31, 0.4);
}

.prompt-config-page__form {
  padding: 1rem;
}

.prompt-config-page__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

.prompt-config-page__hint {
  padding: 0.85rem 1rem;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.05);
  color: var(--color-text-muted);
  line-height: 1.7;
}

.prompt-config-page__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 1rem;
}

.prompt-config-page__preview {
  margin-top: 1rem;
  padding: 1rem;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.05);
}

.prompt-config-page__preview-title,
.prompt-config-page__preview-body {
  margin: 0;
}

.prompt-config-page__preview-title {
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.prompt-config-page__preview-body {
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-muted);
  line-height: 1.7;
}

@media (max-width: 760px) {
  .prompt-config-page__hero {
    display: grid;
  }

  .prompt-config-page__meta {
    text-align: left;
  }

  .prompt-config-page__grid {
    grid-template-columns: 1fr;
  }
}
</style>
