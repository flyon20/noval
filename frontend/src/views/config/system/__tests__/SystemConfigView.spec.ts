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

    vi.mocked(systemConfigApi.getByKey).mockImplementation(async (configKey) => ({
      data: {
        code: 200,
        message: 'success',
        data: createSystemConfig(configKey, 'demo-value'),
        timestamp: 1,
        traceId: `trace-${configKey}`,
      },
    }));

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

    expect(systemConfigApi.getByKey).toHaveBeenCalledWith('ai.timeout.millis');
    expect(systemConfigApi.getByKey).toHaveBeenCalledWith('crawler.default.chapter-count');
    expect(systemConfigApi.getByKey).toHaveBeenCalledWith('security.audit.enabled');
    expect(wrapper.text()).toContain('ai.timeout.millis');
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
});
