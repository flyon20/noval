import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import ProjectMemoryOverview from '../ProjectMemoryOverview.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    getProjectMemoryOverview: vi.fn(),
  },
}));

describe('ProjectMemoryOverview', () => {
  test('shows durable work coverage without exposing storage internals', async () => {
    vi.mocked(knowledgeApi.getProjectMemoryOverview).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          projectId: 7,
          workId: 11,
          activeChapterCount: 10,
          chapterFrom: 1,
          chapterTo: 10,
          indexedDocumentCount: 4,
          characterStateCount: 12,
          worldRuleCount: 3,
          foreshadowingCount: 5,
          foreshadowingStatusCounts: { OPEN: 3, PAID_OFF: 2 },
          timelineEventCount: 16,
          storyNodeCount: 22,
          storyEdgeCount: 18,
          pendingExtractionCount: 2,
          longFormFactCount: 9,
          pendingLongFormFactCount: 1,
          longFormFactStatusCounts: { CONFIRMED: 8, PENDING_REVIEW: 1 },
          summaryNodeCount: 6,
          summaryCoveredChapterCount: 8,
          summaryCoverageStatus: 'PARTIAL',
          summaryNodeTypeCounts: { CHAPTER: 5, ARC: 1 },
          recognizedRecordsOnly: true,
          corpusFingerprint: 'sha256:hidden-from-author-view',
        },
      },
    } as never);

    const wrapper = mount(ProjectMemoryOverview, {
      props: { projectId: 7, workId: 11 },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(knowledgeApi.getProjectMemoryOverview).toHaveBeenCalledWith(7, 11);
    expect(wrapper.text()).toContain('作品长期记忆');
    expect(wrapper.text()).toContain('第 1-10 章');
    expect(wrapper.text()).toContain('有效章节');
    expect(wrapper.text()).toContain('已索引资料');
    expect(wrapper.text()).toContain('摘要覆盖 8/10 章');
    expect(wrapper.text()).toContain('未回收 3');
    expect(wrapper.text()).toContain('已回收 2');
    expect(wrapper.get('[data-test="project-memory-pending"]').text()).toContain('3');
    expect(wrapper.text()).not.toContain('sha256:hidden-from-author-view');
    expect(wrapper.text()).not.toContain('generation');
    expect(wrapper.text()).not.toContain('token');
  });

  test('reloads when the active work changes', async () => {
    vi.mocked(knowledgeApi.getProjectMemoryOverview).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: {
          projectId: 7,
          workId: 11,
          activeChapterCount: 0,
          indexedDocumentCount: 0,
          characterStateCount: 0,
          worldRuleCount: 0,
          foreshadowingCount: 0,
          foreshadowingStatusCounts: {},
          timelineEventCount: 0,
          storyNodeCount: 0,
          storyEdgeCount: 0,
          pendingExtractionCount: 0,
          longFormFactCount: 0,
          pendingLongFormFactCount: 0,
          longFormFactStatusCounts: {},
          summaryNodeCount: 0,
          summaryCoveredChapterCount: 0,
          summaryCoverageStatus: 'NO_CORPUS',
          summaryNodeTypeCounts: {},
          recognizedRecordsOnly: true,
        },
      },
    } as never);
    const wrapper = mount(ProjectMemoryOverview, {
      props: { projectId: 7, workId: 11 },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    await wrapper.setProps({ workId: 12 });
    await flushPromises();

    expect(knowledgeApi.getProjectMemoryOverview).toHaveBeenLastCalledWith(7, 12);
  });
});
