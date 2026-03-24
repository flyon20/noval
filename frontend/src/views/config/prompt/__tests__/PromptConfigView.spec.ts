import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import PromptConfigView from '../PromptConfigView.vue';

vi.mock('@/api/config', () => ({
  promptConfigApi: {
    getByType: vi.fn(),
    update: vi.fn(),
  },
  systemConfigApi: {
    getAvailableModels: vi.fn(),
  },
}));

function createPromptConfig(promptType: 'deconstruct' | 'structure' | 'plot' | 'theme') {
  return {
    id: 1,
    promptType,
    promptName: `${promptType}-default`,
    promptContent: `这是 ${promptType} 的提示词，正文占位：{{content}}`,
    modelName: 'dify',
    temperature: 0.7,
    maxTokens: 2048,
    outputJsonSchema: '{"type":"object"}',
    outputExampleJson: '{"summary":"example"}',
    postProcessType: 'json_extract',
    parseConfigJson: '{"parser":"json"}',
  };
}

describe('PromptConfigView', () => {
  test('loads default prompt config and switches prompt type', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.getByType).mockImplementation(async (promptType) => ({
      data: {
        code: 200,
        message: 'success',
        data: createPromptConfig(promptType),
        timestamp: 1,
        traceId: `trace-${promptType}`,
      },
    }));
    vi.mocked(systemConfigApi.getAvailableModels).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: ['dify', 'deepseek-chat'],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    expect(promptConfigApi.getByType).toHaveBeenCalledWith('deconstruct');
    expect(wrapper.text()).toContain('{{content}}');
    expect((wrapper.get('[data-test="prompt-post-process-type-input"]').element as HTMLInputElement).value).toBe('json_extract');

    await wrapper.get('[data-test="prompt-type-structure"]').trigger('click');
    await flushPromises();

    expect(promptConfigApi.getByType).toHaveBeenLastCalledWith('structure');
  });

  test('submits prompt config update with current form values', async () => {
    const { promptConfigApi, systemConfigApi } = await import('@/api/config');

    vi.mocked(promptConfigApi.getByType).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPromptConfig('deconstruct'),
        timestamp: 1,
        traceId: 'trace-deconstruct',
      },
    });
    vi.mocked(systemConfigApi.getAvailableModels).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: ['dify', 'dify-chat'],
        timestamp: 1,
        traceId: 'trace-models',
      },
    });
    vi.mocked(promptConfigApi.update).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: createPromptConfig('deconstruct'),
        timestamp: 1,
        traceId: 'trace-update',
      },
    });

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/config/prompt', component: PromptConfigView }],
    });
    await router.push('/config/prompt');

    const wrapper = mount(PromptConfigView, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    await flushPromises();

    await wrapper.get('[data-test="prompt-name-input"]').setValue('deconstruct-updated');
    await wrapper
      .get('[data-test="prompt-content-input"]')
      .setValue('新的提示词模板，保留正文占位：{{content}}');
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'dify-chat');
    await flushPromises();
    await wrapper.get('[data-test="prompt-temperature-input"]').setValue('0.9');
    await wrapper.get('[data-test="prompt-max-tokens-input"]').setValue('4096');
    await wrapper.get('[data-test="prompt-output-json-schema-input"]').setValue('{"type":"object","properties":{"summary":{"type":"string"}}}');
    await wrapper.get('[data-test="prompt-output-example-json-input"]').setValue('{"summary":"example"}');
    await wrapper.get('[data-test="prompt-post-process-type-input"]').setValue('json_extract');
    await wrapper.get('[data-test="prompt-parse-config-json-input"]').setValue('{"parser":"json","trimMarkdownFence":true}');
    await wrapper.get('[data-test="prompt-save-button"]').trigger('click');
    await flushPromises();

    expect(promptConfigApi.update).toHaveBeenCalledWith({
      promptType: 'deconstruct',
      promptName: 'deconstruct-updated',
      promptContent: '新的提示词模板，保留正文占位：{{content}}',
      modelName: 'dify-chat',
      temperature: 0.9,
      maxTokens: 4096,
      outputJsonSchema: '{"type":"object","properties":{"summary":{"type":"string"}}}',
      outputExampleJson: '{"summary":"example"}',
      postProcessType: 'json_extract',
      parseConfigJson: '{"parser":"json","trimMarkdownFence":true}',
    });
  });
});
