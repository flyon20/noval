import ElementPlus, { ElMessageBox } from 'element-plus';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import ProjectExtractionReview from '../ProjectExtractionReview.vue';
import { knowledgeApi } from '@/api/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listExtractionCandidates: vi.fn(),
    reviewExtractionCandidate: vi.fn(),
  },
}));

enableAutoUnmount(afterEach);

function mountReview() {
  return mount(ProjectExtractionReview, {
    props: { projectId: 7, workId: 11 },
    global: { plugins: [ElementPlus] },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

describe('ProjectExtractionReview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    vi.mocked(knowledgeApi.listExtractionCandidates).mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: [{
          candidateId: 21,
          projectId: 7,
          workId: 11,
          entityType: 'CHARACTER',
          payloadJson: '{"name":"林舟"}',
          evidenceRefsJson: 'chapter:3',
          confidence: 0.91,
          status: 'PENDING',
          generationId: 8,
        }],
      },
    } as never);
    vi.mocked(knowledgeApi.reviewExtractionCandidate).mockImplementation((_projectId, _candidateId, payload) => Promise.resolve({
      data: {
        code: 200,
        message: 'success',
        data: {
          candidateId: 21,
          projectId: 7,
          workId: 11,
          entityType: 'CHARACTER',
          payloadJson: '{"name":"林舟"}',
          status: payload.decision,
        },
      },
    }) as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  test('requires confirmation before accepting a candidate', async () => {
    const wrapper = mountReview();
    await flushPromises();

    expect(wrapper.get('[data-test="extraction-candidate-21"]').text()).toContain('待审核');
    expect(wrapper.get('[data-test="extraction-payload"]').text()).toContain('林舟');
    await wrapper.get('[data-test="extraction-confirm"]').trigger('click');
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalled();
    expect(knowledgeApi.reviewExtractionCandidate).toHaveBeenCalledWith(
      7,
      21,
      expect.objectContaining({ decision: 'CONFIRMED' }),
    );
    expect(wrapper.get('[data-test="extraction-candidate-21"]').text()).toContain('已确认');
  });

  test.each([
    ['extraction-edit-confirm', 'SUPERSEDED'],
    ['extraction-reject', 'REJECTED'],
  ])('submits explicit review decisions after confirmation', async (selector, decision) => {
    const wrapper = mountReview();
    await flushPromises();

    await wrapper.get(`[data-test="${selector}"]`).trigger('click');
    await flushPromises();

    expect(knowledgeApi.reviewExtractionCandidate).toHaveBeenCalledWith(
      7,
      21,
      expect.objectContaining({ decision }),
    );
  });

  test('reviews different candidates independently while requests are in flight', async () => {
    const firstReview = deferred<unknown>();
    const secondReview = deferred<unknown>();
    vi.mocked(knowledgeApi.listExtractionCandidates).mockResolvedValueOnce({
      data: {
        code: 200,
        message: 'success',
        data: [
          {
            candidateId: 21,
            projectId: 7,
            workId: 11,
            entityType: 'CHARACTER',
            payloadJson: '{"name":"林舟"}',
            status: 'PENDING',
          },
          {
            candidateId: 22,
            projectId: 7,
            workId: 11,
            entityType: 'SETTING',
            payloadJson: '{"name":"云港"}',
            status: 'PENDING',
          },
        ],
      },
    } as never);
    vi.mocked(knowledgeApi.reviewExtractionCandidate).mockImplementation((_projectId, candidateId) => (
      candidateId === 21 ? firstReview.promise : secondReview.promise
    ) as never);
    const wrapper = mountReview();
    await flushPromises();

    await wrapper
      .get('[data-test="extraction-candidate-21"] [data-test="extraction-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(knowledgeApi.reviewExtractionCandidate).toHaveBeenCalledTimes(1);
    expect(
      wrapper.get('[data-test="extraction-candidate-21"] [data-test="extraction-edit-payload"]').attributes('disabled'),
    ).toBeDefined();
    expect(
      wrapper.get('[data-test="extraction-candidate-22"] [data-test="extraction-edit-payload"]').attributes('disabled'),
    ).toBeUndefined();

    await wrapper
      .get('[data-test="extraction-candidate-22"] [data-test="extraction-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(knowledgeApi.reviewExtractionCandidate).toHaveBeenCalledTimes(2);
    expect(knowledgeApi.reviewExtractionCandidate).toHaveBeenNthCalledWith(
      2,
      7,
      22,
      expect.objectContaining({ decision: 'CONFIRMED' }),
    );

    secondReview.resolve({
      data: {
        code: 200,
        message: 'success',
        data: {
          candidateId: 22,
          projectId: 7,
          workId: 11,
          entityType: 'SETTING',
          payloadJson: '{"name":"云港"}',
          status: 'CONFIRMED',
        },
      },
    });
    await flushPromises();

    expect(wrapper.get('[data-test="extraction-candidate-22"]').text()).toContain('已确认');
    expect(wrapper.get('[data-test="extraction-candidate-21"]').text()).toContain('待审核');

    firstReview.resolve({
      data: {
        code: 200,
        message: 'success',
        data: {
          candidateId: 21,
          projectId: 7,
          workId: 11,
          entityType: 'CHARACTER',
          payloadJson: '{"name":"林舟"}',
          status: 'CONFIRMED',
        },
      },
    });
    await flushPromises();

    expect(wrapper.get('[data-test="extraction-candidate-21"]').text()).toContain('已确认');
  });

  test('does not submit when the confirmation is cancelled', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce(new Error('cancel'));
    const wrapper = mountReview();
    await flushPromises();

    await wrapper.get('[data-test="extraction-reject"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.reviewExtractionCandidate).not.toHaveBeenCalled();
  });

  test('navigates to the candidate evidence chapter', async () => {
    const wrapper = mountReview();
    await flushPromises();

    await wrapper.get('[data-test="extraction-evidence-link"]').trigger('click');

    expect(wrapper.emitted('evidenceNavigate')?.[0]).toEqual([3]);
  });

  test('keeps review errors local', async () => {
    vi.mocked(knowledgeApi.reviewExtractionCandidate).mockRejectedValue(new Error('boom'));
    const wrapper = mountReview();
    await flushPromises();
    await wrapper.get('[data-test="extraction-reject"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-test="extraction-review-error"]').text()).toContain('审核提交失败');
  });

  test('does not submit a confirmed action after the scope changes', async () => {
    const confirmation = deferred<unknown>();
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(confirmation.promise as never);
    const wrapper = mountReview();
    await flushPromises();

    await wrapper.get('[data-test="extraction-confirm"]').trigger('click');
    await wrapper.setProps({ projectId: 9, workId: 12 });
    confirmation.resolve('confirm');
    await flushPromises();

    expect(knowledgeApi.reviewExtractionCandidate).not.toHaveBeenCalled();
  });

  test('ignores a late review error after the scope changes', async () => {
    const reviewResult = deferred<unknown>();
    vi.mocked(knowledgeApi.reviewExtractionCandidate).mockReturnValueOnce(reviewResult.promise as never);
    const wrapper = mountReview();
    await flushPromises();

    await wrapper.get('[data-test="extraction-reject"]').trigger('click');
    await flushPromises();
    await wrapper.setProps({ projectId: 9, workId: 12 });
    reviewResult.reject(new Error('late review failure'));
    await flushPromises();

    expect(wrapper.find('[data-test="extraction-error"]').exists()).toBe(false);
  });
});
