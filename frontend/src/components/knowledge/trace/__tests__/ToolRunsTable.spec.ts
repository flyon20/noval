import ElementPlus from 'element-plus';
import { flushPromises } from '@vue/test-utils';
import { mount } from '@vue/test-utils';
import ToolRunsTable from '../ToolRunsTable.vue';

describe('ToolRunsTable', () => {
  test('renders compact input and output summaries for tool observability', async () => {
    const wrapper = mount(ToolRunsTable, {
      props: {
        toolRunsJson: JSON.stringify([
          {
            name: 'knowledge.vector_search',
            status: 'succeeded',
            toolset: 'knowledge',
            input: { query: '都市脑洞 趋势', limit: 5 },
            output: {
              retrievalBackend: 'qdrant',
              items: [{ title: '样本 A' }, { title: '样本 B' }],
            },
            resultCount: 2,
          },
        ]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('knowledge.vector_search');
    expect(wrapper.text()).toContain('查询: 都市脑洞 趋势');
    expect(wrapper.text()).toContain('检索后端: qdrant');
    expect(wrapper.text()).toContain('结果项: 2');
    expect(wrapper.text()).toContain('成功');
    expect(wrapper.text()).toContain('2 条结果');
    expect(wrapper.text()).not.toContain('succeeded');
    expect(wrapper.text()).not.toContain('Input');
    expect(wrapper.text()).not.toContain('Output');
  });

  test('uses readable run cards instead of a wide horizontal table', async () => {
    const wrapper = mount(ToolRunsTable, {
      props: {
        toolRunsJson: JSON.stringify([
          {
            name: 'rank.lookup',
            status: 'succeeded',
            toolset: 'rank',
            input: { query: 'market trend', limit: 10, taskType: 'market_scan' },
            output: { ranks: [{}, {}, {}], books: [{}, {}] },
            resultCount: 10,
          },
          {
            name: 'knowledge.vector_search',
            status: 'succeeded',
            toolset: 'knowledge',
            input: { query: 'outline signals', limit: 10 },
            output: { retrievalBackend: 'qdrant', items: [{}] },
            resultCount: 1,
          },
        ]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.find('.el-table').exists()).toBe(false);
    expect(wrapper.find('.tool-runs-table__list').exists()).toBe(true);
    expect(wrapper.findAll('[data-test="tool-run-card"]')).toHaveLength(2);
    expect(wrapper.text()).toContain('rank.lookup');
    expect(wrapper.text()).toContain('榜单条目: 3');
  });
});
