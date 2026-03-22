<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import { systemConfigApi } from '@/api/config';
import type { KnownSystemConfigKey, SystemConfig } from '@/types/config';

const SYSTEM_CONFIG_KEYS: Array<{ key: KnownSystemConfigKey; label: string; hint: string }> = [
  {
    key: 'ai.provider.type',
    label: 'AI Provider Type',
    hint: 'Choose the preferred AI provider used by backend analysis requests.',
  },
  {
    key: 'ai.timeout.millis',
    label: 'AI Timeout (ms)',
    hint: 'Control the timeout used by backend AI calls.',
  },
  {
    key: 'ai.openai-compatible.base-url',
    label: 'OpenAI-Compatible Base URL',
    hint: 'Leave blank to use the backend application default.',
  },
  {
    key: 'ai.openai-compatible.default-model',
    label: 'OpenAI-Compatible Default Model',
    hint: 'Default model name used when prompt config does not override it.',
  },
  {
    key: 'ai.openai-compatible.streaming-enabled',
    label: 'OpenAI-Compatible Streaming',
    hint: 'Toggle whether OpenAI-compatible streaming is enabled.',
  },
  {
    key: 'crawler.default.chapter-count',
    label: 'Default Chapter Count',
    hint: 'Default chapter count shown on the rank page.',
  },
  {
    key: 'crawler.http.timeout-seconds',
    label: 'Crawler HTTP Timeout (s)',
    hint: 'Timeout used by the Python crawler when requesting Fanqie pages.',
  },
  {
    key: 'crawler.chapter.fetch-workers',
    label: 'Chapter Fetch Workers',
    hint: 'Maximum parallel workers used for multi-chapter fetching.',
  },
  {
    key: 'crawler.chapter.force-refresh.user-max-times',
    label: 'User Chapter Refresh Limit',
    hint: 'Maximum force-refresh times for normal users in the current rank cache window (capped at 20).',
  },
  {
    key: 'crawler.rank.refresh-days',
    label: 'Rank Cache Window (days)',
    hint: 'Chapter force-refresh usage is counted within this same cache window.',
  },
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
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load system config.';
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
    ElMessage.success(`${item.configKey} updated`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Failed to save system config.');
  }
}

onMounted(() => {
  void loadConfigs();
});
</script>

<template>
  <section class="system-config-page">
    <header class="system-config-page__hero">
      <p class="system-config-page__eyebrow">System Config</p>
      <h2 class="system-config-page__title">系统配置</h2>
      <p class="system-config-page__subtitle">
        当前前端固定管理 10 个系统配置 key，并对齐后端 `/api/config/system`。
      </p>
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
