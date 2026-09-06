import { knowledgeStatusLabel, knowledgeUserStatusLabel } from '@/utils/knowledgeDisplay';

describe('knowledge failure labels', () => {
  test('distinguishes retrieval outages from missing evidence', () => {
    expect(knowledgeStatusLabel('error')).toBe('处理失败');
    expect(knowledgeUserStatusLabel('retrieval_failed')).toBe('检索服务异常');
    expect(knowledgeStatusLabel('insufficient_evidence')).toBe('证据不足');
  });
});
