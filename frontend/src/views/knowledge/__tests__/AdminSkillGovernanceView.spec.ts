import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import AdminSkillGovernanceView from '../AdminSkillGovernanceView.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listSkillCandidates: vi.fn(),
    reviewSkillCandidate: vi.fn(),
  },
}));

describe('AdminSkillGovernanceView', () => {
  test('renders candidates and approves a skill', async () => {
    vi.mocked(knowledgeApi.listSkillCandidates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{ id: 1, skillId: 'webnovel-market', title: '市场扫描', status: 'PENDING', evalStatus: 'PASSED' }],
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

    expect(wrapper.text()).toContain('webnovel-market');
    await wrapper.find('[data-test="approve-skill"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.reviewSkillCandidate).toHaveBeenCalledWith(1, { decision: 'APPROVED' });
    expect(wrapper.text()).toContain('APPROVED');
  });
});
