import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import SystemConfigView from '../SystemConfigView.vue';

vi.mock('@/api/config', () => ({
  promptConfigApi: {
    getByType: vi.fn(),
    update: vi.fn(),
  },
  systemConfigApi: {
    getByKey: vi.fn(),
    update: vi.fn(),
    getModelRegistry: vi.fn(),
    updateModelRegistry: vi.fn(),
  },
}));

function createSystemConfig(
  configKey: string,
  configValue: string,
  overrides: Partial<{
    configType: string;
    description: string;
    editable: boolean;
  }> = {},
) {
  return {
    id: 1,
    configKey,
    configValue,
    configType: overrides.configType ?? 'string',
    description: overrides.description ?? `${configKey} description`,
    editable: overrides.editable ?? true,
  };
}

describe('SystemConfigView', () => {
  test('loads fixed system config keys on mount', async () => {
    const { systemConfigApi } = await import('@/api/config');
    const expectedKeys = [
      'ai.provider.type',
      'ai.timeout.millis',
      'ai.openai-compatible.streaming-enabled',
      'analysis.chunk.max-input-tokens',
      'analysis.chunk.target-input-tokens',
      'analysis.chunk.parallelism',
      'crawler.default.chapter-count',
      'crawler.http.timeout-seconds',
      'crawler.chapter.fetch-workers',
      'crawler.chapter.force-refresh.user-max-times',
      'crawler.rank.refresh-days',
    ];

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat',
              providerType: 'openai-compatible',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: 'registry-key',
              enabled: true,
              isDefault: true,
              defaultTemperature: 1,
              maxTokens: 8192,
              temperatureSpecJson: '{"min":0,"max":2}',
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(systemConfigApi.getModelRegistry).toHaveBeenCalledTimes(1);
    expect(systemConfigApi.getByKey).toHaveBeenCalledTimes(expectedKeys.length);
    expectedKeys.forEach((configKey) => {
      expect(systemConfigApi.getByKey).toHaveBeenCalledWith(configKey);
    });
    expect(wrapper.text()).toContain('ai.provider.type');
    expect(wrapper.text()).toContain('DeepSeek Chat');
  });

  test('updates editable system config item', async () => {
    const { systemConfigApi } = await import('@/api/config');

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, configKey === 'security.audit.enabled' ? 'true' : '1000'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat',
              providerType: 'openai-compatible',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: 'registry-key',
              enabled: true,
              isDefault: true,
              defaultTemperature: 1,
              maxTokens: 8192,
              temperatureSpecJson: '{"min":0,"max":2}',
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.update).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig('ai.timeout.millis', '2000'),
        timestamp: 1,
        traceId: 'trace-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    await wrapper.get('[data-test="system-config-value-ai.timeout.millis"]').setValue('2000');
    await wrapper.get('[data-test="system-config-save-ai.timeout.millis"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.update).toHaveBeenCalledWith({
      configKey: 'ai.timeout.millis',
      configValue: '2000',
      configType: 'string',
      description: 'ai.timeout.millis description',
    });
  });

  test('updates model registry with edited model cards', async () => {
    const { systemConfigApi } = await import('@/api/config');

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));
    vi.mocked(systemConfigApi.getModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat',
              providerType: 'openai-compatible',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: 'registry-key',
              enabled: true,
              isDefault: true,
              defaultTemperature: 1,
              maxTokens: 8192,
              temperatureSpecJson: '{"min":0,"max":2}',
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry',
      },
    });
    vi.mocked(systemConfigApi.updateModelRegistry).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          defaultModelKey: 'deepseek-chat',
          models: [
            {
              modelKey: 'deepseek-chat',
              displayName: 'DeepSeek Chat Updated',
              providerType: 'openai-compatible',
              modelName: 'deepseek-chat',
              baseUrl: 'https://api.deepseek.com/v1',
              apiKey: 'registry-key-updated',
              enabled: true,
              isDefault: true,
              defaultTemperature: 0.8,
              maxTokens: 4096,
              temperatureSpecJson: '{"min":0,"max":2,"default":0.8}',
            },
          ],
        },
        timestamp: 1,
        traceId: 'trace-registry-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/system', component: SystemConfigView }],
    });
    await router.push('/config/system');

    const wrapper = mount(SystemConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    await wrapper.get('[data-test="model-display-name-deepseek-chat"]').setValue('DeepSeek Chat Updated');
    await wrapper.get('[data-test="model-api-key-deepseek-chat"]').setValue('registry-key-updated');
    await wrapper.get('[data-test="model-default-temperature-deepseek-chat"]').setValue('0.8');
    await wrapper.get('[data-test="model-max-tokens-deepseek-chat"]').setValue('4096');
    await wrapper.get('[data-test="model-temperature-spec-deepseek-chat"]').setValue('{"min":0,"max":2,"default":0.8}');
    await wrapper.get('[data-test="model-registry-save"]').trigger('click');
    await flushPromises();

    expect(systemConfigApi.updateModelRegistry).toHaveBeenCalledWith({
      defaultModelKey: 'deepseek-chat',
      models: [
        {
          modelKey: 'deepseek-chat',
          displayName: 'DeepSeek Chat Updated',
          providerType: 'openai-compatible',
          modelName: 'deepseek-chat',
          baseUrl: 'https://api.deepseek.com/v1',
          apiKey: 'registry-key-updated',
          enabled: true,
          isDefault: true,
          defaultTemperature: 0.8,
          maxTokens: 4096,
          temperatureSpecJson: '{"min":0,"max":2,"default":0.8}',
        },
      ],
    });
  });
});
