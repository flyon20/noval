import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import AdminMemoryView from '../AdminMemoryView.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listMemories: vi.fn(),
    listMemoryCandidates: vi.fn(),
    approveMemoryCandidate: vi.fn(),
    rejectMemoryCandidate: vi.fn(),
    deleteMemory: vi.fn(),
  },
}));

describe('AdminMemoryView', () => {
  test('lists memories, filters, reviews candidates and deletes memory', async () => {
    vi.mocked(knowledgeApi.listMemories).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            id: 10,
            userId: 7,
            projectId: 900,
            scope: 'project',
            memoryType: 'fact',
            content: 'three terminal setting',
            status: 'confirmed',
            sourceTraceId: 'trace-1',
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.listMemoryCandidates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            id: 20,
            userId: 7,
            projectId: 900,
            scope: 'user',
            memoryType: 'preference',
            content: 'likes fast starts',
            status: 'candidate',
            sourceTraceId: 'trace-2',
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.approveMemoryCandidate).mockResolvedValue({
      data: { code: 200, message: 'success', data: { id: 30, content: 'likes fast starts', status: 'confirmed' } },
    } as never);
    vi.mocked(knowledgeApi.rejectMemoryCandidate).mockResolvedValue({
      data: { code: 200, message: 'success', data: { id: 20, content: 'likes fast starts', status: 'rejected' } },
    } as never);
    vi.mocked(knowledgeApi.deleteMemory).mockResolvedValue({
      data: { code: 200, message: 'success', data: null },
    } as never);

    const wrapper = mount(AdminMemoryView, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(knowledgeApi.listMemories).toHaveBeenCalledWith({ limit: 100 });
    expect(knowledgeApi.listMemoryCandidates).toHaveBeenCalledWith({ limit: 100 });
    expect(wrapper.text()).toContain('Agent 记忆');
    expect(wrapper.text()).toContain('已确认记忆');
    expect(wrapper.text()).toContain('记忆候选');
    expect(wrapper.text()).toContain('three terminal setting');
    expect(wrapper.text()).toContain('likes fast starts');
    expect(wrapper.text()).toContain('trace-1');

    await wrapper.find('[data-test="memory-user-filter"]').setValue('7');
    await wrapper.find('[data-test="memory-project-filter"]').setValue('900');
    await wrapper.find('[data-test="memory-search"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.listMemories).toHaveBeenLastCalledWith({ userId: 7, projectId: 900, limit: 100 });
    await wrapper.find('[data-test="approve-memory-candidate"]').trigger('click');
    await flushPromises();
    expect(knowledgeApi.approveMemoryCandidate).toHaveBeenCalledWith(20);

    await wrapper.find('[data-test="reject-memory-candidate"]').trigger('click');
    await flushPromises();
    expect(knowledgeApi.rejectMemoryCandidate).toHaveBeenCalledWith(20);

    await wrapper.find('[data-test="delete-memory"]').trigger('click');
    await flushPromises();
    expect(knowledgeApi.deleteMemory).toHaveBeenCalledWith(10);
  });
});
