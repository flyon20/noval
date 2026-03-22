<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import { systemConfigApi } from '@/api/config';
import type { KnownSystemConfigKey, SystemConfig } from '@/types/config';

const PASSWORD_KEYS = new Set(['ai.openai-compatible.api-key']);
const COMMA_LIST_KEYS = new Set(['ai.available-models']);

const SYSTEM_CONFIG_KEYS: Array<{ key: KnownSystemConfigKey; label: string; hint: string }> = [
  { key: 'ai.provider.type', label: 'AI Provider Type', hint: '选择后端分析优先使用的 AI 提供方。' },
  { key: 'ai.timeout.millis', label: 'AI Timeout (ms)', hint: '控制 AI 请求超时。' },
  { key: 'ai.openai-compatible.api-key', label: 'OpenAI-Compatible API Key', hint: 'API Key，保存后加密存储。' },
  { key: 'ai.openai-compatible.base-url', label: 'OpenAI-Compatible Base URL', hint: '留空则使用后端默认地址。' },
  { key: 'ai.openai-compatible.default-model', label: 'OpenAI-Compatible Default Model', hint: '默认模型名称。' },
  { key: 'ai.openai-compatible.streaming-enabled', label: 'OpenAI-Compatible Streaming', hint: '控制是否启用流式调用。' },
  { key: 'ai.available-models', label: 'Available Models', hint: '可供用户选择的模型列表，多个模型用英文逗号分隔。' },
  { key: 'analysis.chunk.max-input-tokens', label: 'Analysis Chunk Max Tokens', hint: '单次分析允许的估算输入 Token 上限；超过后会自动切换为 LangChain4j 分段汇总。推荐值：6000。' },
  { key: 'analysis.chunk.target-input-tokens', label: 'Analysis Chunk Target Tokens', hint: '分段分析时每段的目标输入 Token 大小；数值越小分段越多。推荐值：3500。' },
  { key: 'crawler.default.chapter-count', label: 'Default Chapter Count', hint: '扫榜页默认抓章数量。' },
  { key: 'crawler.http.timeout-seconds', label: 'Crawler HTTP Timeout (s)', hint: 'Python crawler 请求页面时的超时。' },
  { key: 'crawler.chapter.fetch-workers', label: 'Chapter Fetch Workers', hint: '多章节抓取时的最大并发数。' },
  { key: 'crawler.chapter.force-refresh.user-max-times', label: 'User Chapter Refresh Limit', hint: '普通用户在当前窗口内的章节重抓上限。' },
  { key: 'crawler.rank.refresh-days', label: 'Rank Cache Window (days)', hint: '榜单缓存期与章节重抓统计窗口。' },
];

type SystemConfigFormItem = SystemConfig & {
  draftValue: string;
};

const loading = ref(false);
const items = ref<SystemConfigFormItem[]>([]);
const errorMessage = ref('');

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

onMounted(() => {
  void loadConfigs();
});
</script>

<template>
  <section class="system-config-page">
    <header class="system-config-page__hero">
      <p class="system-config-page__eyebrow">Current Page</p>
      <h2 class="system-config-page__title">系统配置</h2>
      <p class="system-config-page__subtitle">管理系统参数与运行限制。</p>
    </header>

    <el-alert
      v-if="errorMessage"
      :closable="false"
      show-icon
      type="error"
      title="系统配置加载失败"
      :description="errorMessage"
    />

    <section v-loading="loading" class="system-config-page__list">
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
          <p v-if="COMMA_LIST_KEYS.has(item.configKey)" style="margin: 0.3rem 0 0; font-size: 0.82rem; color: var(--color-text-muted);">多个模型用英文逗号分隔，例如：deepseek-chat,gpt-4o</p>
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
.system-config-page__list {
  border: 1px solid var(--color-border);
  border-radius: 1.25rem;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: var(--shadow-soft);
}

.system-config-page__hero {
  padding: 1.2rem;
}

.system-config-page__eyebrow,
.system-config-page__title,
.system-config-page__subtitle {
  margin: 0;
}

.system-config-page__eyebrow {
  color: var(--color-text-muted);
  font-size: 0.8rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.system-config-page__title {
  margin-top: 0.2rem;
  font-size: 1.5rem;
}

.system-config-page__subtitle {
  margin-top: 0.35rem;
  color: var(--color-text-muted);
  line-height: 1.7;
}

.system-config-page__list {
  display: grid;
  gap: 1rem;
  padding: 1rem;
}

.system-config-page__card {
  display: grid;
  gap: 0.9rem;
  padding: 1rem;
  border-radius: 1rem;
  background: rgba(35, 65, 58, 0.03);
}

.system-config-page__card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.system-config-page__card-title,
.system-config-page__card-subtitle {
  margin: 0;
}

.system-config-page__card-title {
  font-size: 1rem;
}

.system-config-page__card-subtitle {
  margin-top: 0.3rem;
  color: var(--color-text-muted);
  line-height: 1.6;
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
  .system-config-page__card-header {
    display: grid;
  }
}
</style>
