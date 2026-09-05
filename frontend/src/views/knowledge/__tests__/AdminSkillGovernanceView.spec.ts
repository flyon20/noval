import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import AdminSkillGovernanceView from '../AdminSkillGovernanceView.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    getSkillDashboard: vi.fn(),
    reviewSkillCandidate: vi.fn(),
    publishSkillCandidate: vi.fn(),
    disableSkillCandidate: vi.fn(),
    rollbackSkillCandidate: vi.fn(),
    createSkillCandidate: vi.fn(),
  },
}));

describe('AdminSkillGovernanceView', () => {
  test('renders runtime skills, paged candidates and approves a skill', async () => {
    vi.mocked(knowledgeApi.getSkillDashboard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runtimeSkills: [
            {
              skillId: 'webnovel-market-scan',
              version: '1.0.0',
              description: '读取当前榜单证据后再综合判断',
              intents: ['market_scan'],
              triggers: ['榜单', '趋势'],
              requestedCapabilities: ['market.read'],
            },
          ],
          candidates: {
            page: 1,
            pageSize: 20,
            total: 1,
            hasNext: false,
            items: [{ id: 1, skillId: 'webnovel-market', title: '市场扫描', status: 'PENDING', evalStatus: 'PASSED' }],
          },
        },
      },
    } as never);
    vi.mocked(knowledgeApi.reviewSkillCandidate).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { id: 1, skillId: 'webnovel-market', title: '市场扫描', status: 'APPROVED', evalStatus: 'PASSED' },
      },
    } as never);

    const wrapper = mount(AdminSkillGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(knowledgeApi.getSkillDashboard).toHaveBeenCalledWith({ page: 1, pageSize: 20 });
    expect(wrapper.text()).toContain('Agent 技能');
    expect(wrapper.text()).toContain('上传技能');
    expect(wrapper.text()).toContain('技能候选');
    expect(wrapper.text()).toContain('webnovel-market-scan');
    expect(wrapper.text()).toContain('读取当前榜单证据后再综合判断');
    expect(wrapper.text()).toContain('请求能力（非授权）');
    expect(wrapper.text()).toContain('market.read');
    expect(wrapper.text()).toContain('榜单市场分析');
    expect(wrapper.text()).toContain('webnovel-market');
    await wrapper.find('[data-test="approve-skill"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.reviewSkillCandidate).toHaveBeenCalledWith(1, { decision: 'APPROVED' });
    expect(wrapper.text()).toContain('已通过');
    expect(wrapper.text()).not.toContain('APPROVED');
  });

  test('shows runtime skills even when there are no candidates', async () => {
    vi.mocked(knowledgeApi.getSkillDashboard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runtimeSkills: [{ skillId: 'webnovel-outline', version: '1.0.0', intents: ['outline_building'], triggers: [] }],
          candidates: { page: 1, pageSize: 20, total: 0, hasNext: false, items: [] },
        },
      },
    } as never);

    const wrapper = mount(AdminSkillGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('webnovel-outline');
    expect(wrapper.text()).toContain('暂无待审核候选');
  });

  test('renders structured eval gate metrics for candidates', async () => {
    vi.mocked(knowledgeApi.getSkillDashboard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runtimeSkills: [],
          candidates: {
            page: 1,
            pageSize: 20,
            total: 1,
            hasNext: false,
            items: [
              {
                id: 7,
                skillId: 'market-gate',
                title: 'Market Gate',
                status: 'PENDING',
                evalStatus: 'PASSED',
                evalResult: {
                  requiredToolPassRate: 1,
                  evidencePassRate: 0.96,
                  faithfulnessPassRate: 0.95,
                },
              },
            ],
          },
        },
      },
    } as never);

    const wrapper = mount(AdminSkillGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('工具 100%');
    expect(wrapper.text()).toContain('证据 96%');
    expect(wrapper.text()).toContain('忠实度 95%');
  });

  test('renders flat backend eval gate metrics for candidates', async () => {
    vi.mocked(knowledgeApi.getSkillDashboard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runtimeSkills: [],
          candidates: {
            page: 1,
            pageSize: 20,
            total: 1,
            hasNext: false,
            items: [
              {
                id: 8,
                skillId: 'flat-gate',
                title: 'Flat Gate',
                status: 'PENDING',
                evalStatus: 'PASSED',
                requiredToolPassRate: 1,
                evidencePassRate: 0.91,
                faithfulnessPassRate: 0.92,
              },
            ],
          },
        },
      },
    } as never);

    const wrapper = mount(AdminSkillGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('工具 100%');
    expect(wrapper.text()).toContain('证据 91%');
    expect(wrapper.text()).toContain('忠实度 92%');
  });

  test('creates a manual uploaded skill candidate and refreshes the dashboard', async () => {
    vi.mocked(knowledgeApi.getSkillDashboard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runtimeSkills: [],
          candidates: { page: 1, pageSize: 20, total: 0, hasNext: false, items: [] },
        },
      },
    } as never);
    vi.mocked(knowledgeApi.createSkillCandidate).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 12,
          skillId: 'webnovel-hook-upload',
          title: '开篇钩子强化',
          status: 'PENDING',
          evalStatus: 'PASSED',
        },
      },
    } as never);

    const wrapper = mount(AdminSkillGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    const callsAfterInitialLoad = vi.mocked(knowledgeApi.getSkillDashboard).mock.calls.length;

    await wrapper.find('[data-test="skill-upload-id"]').setValue('webnovel-hook-upload');
    await wrapper.find('[data-test="skill-upload-title"]').setValue('开篇钩子强化');
    await wrapper.find('[data-test="skill-upload-content"]').setValue('# Skill\n用于强化前三章钩子。');
    await wrapper.find('[data-test="skill-upload-eval-json"]').setValue(
      '{"requiredToolPassRate":1,"evidencePassRate":0.96,"faithfulnessPassRate":0.95}',
    );
    await wrapper.find('[data-test="skill-upload-submit"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.createSkillCandidate).toHaveBeenCalledWith({
      skillId: 'webnovel-hook-upload',
      title: '开篇钩子强化',
      content: '# Skill\n用于强化前三章钩子。',
      evalResultJson: '{"requiredToolPassRate":1,"evidencePassRate":0.96,"faithfulnessPassRate":0.95}',
    });
    expect(knowledgeApi.getSkillDashboard).toHaveBeenCalledTimes(callsAfterInitialLoad + 1);
    expect(wrapper.text()).toContain('技能候选已创建');
  });

  test('imports a markdown skill file into the governed candidate form', async () => {
    vi.mocked(knowledgeApi.getSkillDashboard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runtimeSkills: [],
          candidates: { page: 1, pageSize: 20, total: 0, hasNext: false, items: [] },
        },
      },
    } as never);
    vi.mocked(knowledgeApi.createSkillCandidate).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 13,
          skillId: 'webnovel-opening-hook',
          title: 'Webnovel Opening Hook',
          status: 'PENDING',
          evalStatus: 'PENDING',
        },
      },
    } as never);

    const wrapper = mount(AdminSkillGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    const fileInput = wrapper.get<HTMLInputElement>('[data-test="skill-md-file"]');
    const file = new File([
      '---\nname: webnovel-opening-hook\ndescription: 强化前三章开篇钩子\n---\n# Instructions\n\nTrigger: opening_strategy',
    ], 'ignored-filename.md', {
      type: 'text/markdown',
    });
    Object.defineProperty(fileInput.element, 'files', {
      configurable: true,
      value: [file],
    });
    await fileInput.trigger('change');
    await flushPromises();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flushPromises();

    expect((wrapper.get<HTMLInputElement>('[data-test="skill-upload-id"]').element).value)
      .toBe('webnovel-opening-hook');
    expect((wrapper.get<HTMLInputElement>('[data-test="skill-upload-title"]').element).value)
      .toBe('webnovel-opening-hook');
    expect((wrapper.get<HTMLTextAreaElement>('[data-test="skill-upload-content"]').element).value)
      .toContain('Trigger: opening_strategy');

    await wrapper.find('[data-test="skill-upload-submit"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.createSkillCandidate).toHaveBeenCalledWith(expect.objectContaining({
      skillId: 'webnovel-opening-hook',
      title: 'webnovel-opening-hook',
      content: expect.stringContaining('description: 强化前三章开篇钩子'),
    }));
  });

  test('publishes an approved skill candidate from the governance table', async () => {
    vi.mocked(knowledgeApi.getSkillDashboard).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          runtimeSkills: [],
          candidates: {
            page: 1,
            pageSize: 20,
            total: 1,
            hasNext: false,
            items: [
              {
                id: 9,
                skillId: 'publishable-skill',
                title: 'Publishable Skill',
                status: 'APPROVED',
                evalStatus: 'PASSED',
                requiredToolPassRate: 1,
                evidencePassRate: 0.95,
                faithfulnessPassRate: 0.95,
              },
            ],
          },
        },
      },
    } as never);
    vi.mocked(knowledgeApi.publishSkillCandidate).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          id: 9,
          skillId: 'publishable-skill',
          title: 'Publishable Skill',
          status: 'PUBLISHED',
          evalStatus: 'PASSED',
          requiredToolPassRate: 1,
          evidencePassRate: 0.95,
          faithfulnessPassRate: 0.95,
        },
      },
    } as never);

    const wrapper = mount(AdminSkillGovernanceView, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.find('[data-test="publish-skill"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.publishSkillCandidate).toHaveBeenCalledWith(9);
    expect(wrapper.text()).toContain('已发布');
    expect(wrapper.text()).not.toContain('PUBLISHED');
  });
});
