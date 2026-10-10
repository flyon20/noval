import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus';
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import ProjectIngestPanel from '../ProjectIngestPanel.vue';
import { knowledgeApi } from '@/api/knowledge';
import type { ProjectDocumentBatch, ProjectDocumentQuestion } from '@/types/knowledge';

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    createProjectDocumentBatch: vi.fn(),
    listProjectDocumentBatches: vi.fn(),
    getProjectDocumentBatch: vi.fn(),
    listProjectDocumentQuestions: vi.fn(),
    answerProjectDocumentQuestion: vi.fn(),
    retryProjectDocumentBatch: vi.fn(),
    cancelProjectDocumentBatch: vi.fn(),
    discardProjectDocumentBatch: vi.fn(),
  },
}));

enableAutoUnmount(afterEach);

function batch(overrides: Partial<ProjectDocumentBatch> = {}): ProjectDocumentBatch {
  return {
    batchId: 31,
    projectId: 7,
    workId: 11,
    status: 'PARSING',
    statusLabel: '正在解析',
    progress: 35,
    totalFiles: 4,
    parsedFiles: 2,
    indexedFiles: 0,
    pendingQuestions: 0,
    ...overrides,
  };
}

function question(overrides: Partial<ProjectDocumentQuestion> = {}): ProjectDocumentQuestion {
  return {
    questionId: 91,
    batchId: 31,
    questionType: 'DOCUMENT_KIND',
    prompt: '请确认资料类型',
    relativePath: 'materials/notes.md',
    optionsJson: '["OUTLINE","REFERENCE"]',
    status: 'PENDING',
    ...overrides,
  };
}

function mountPanel(projectId = 7, workId = 11) {
  return mount(ProjectIngestPanel, {
    props: { projectId, workId },
    global: { plugins: [ElementPlus] },
  });
}

function file(name: string, path = '') {
  const value = new File(['content'], name, { type: name.endsWith('.zip') ? 'application/zip' : 'text/plain' });
  Object.defineProperty(value, 'webkitRelativePath', { configurable: true, value: path });
  return value;
}

async function choose(wrapper: VueWrapper, selector: string, files: File[]) {
  const input = wrapper.get(`${selector} input`);
  Object.defineProperty(input.element, 'files', { configurable: true, value: files });
  await input.trigger('change');
  await flushPromises();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

describe('ProjectIngestPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.listProjectDocumentQuestions).mockResolvedValue({
      data: { code: 200, message: 'success', data: [] },
    } as never);
    vi.mocked(knowledgeApi.getProjectDocumentBatch).mockResolvedValue({
      data: { code: 200, message: 'success', data: batch() },
    } as never);
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  test('submits multiple files and preserves directory relative paths in one batch', async () => {
    const created = batch({ status: 'STORED', progress: 5 });
    vi.mocked(knowledgeApi.createProjectDocumentBatch).mockResolvedValue({
      data: { code: 200, message: 'success', data: created },
    } as never);
    const wrapper = mountPanel();
    await flushPromises();
    const files = [file('one.md', 'novel/one.md'), file('two.txt', 'novel/chapters/two.txt')];

    await choose(wrapper, '[data-test="ingest-directory-input"]', files);
    await wrapper.getComponent('[data-test="ingest-kind"]').vm.$emit('update:modelValue', 'NOVEL_TEXT');
    await wrapper.get('[data-test="ingest-submit"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.createProjectDocumentBatch).toHaveBeenCalledWith(
      7,
      11,
      files,
      ['novel/one.md', 'novel/chapters/two.txt'],
      'NOVEL_TEXT',
      expect.any(String),
    );
    expect(wrapper.emitted('submitted')?.[0]?.[0]).toMatchObject({ batchId: 31 });
  });

  test('accepts ZIP without reading or decoding it in the browser', async () => {
    const archive = file('novel.zip');
    const arrayBuffer = vi.fn();
    Object.defineProperty(archive, 'arrayBuffer', { configurable: true, value: arrayBuffer });
    const wrapper = mountPanel();
    await flushPromises();

    await choose(wrapper, '[data-test="ingest-file-input"]', [archive]);

    expect(wrapper.get('[data-test="ingest-selection"]').text()).toContain('novel.zip');
    expect(arrayBuffer).not.toHaveBeenCalled();
  });

  test('restores aggregate batch progress and emits ready once', async () => {
    const ready = batch({ status: 'READY', statusLabel: '可用于 AI', progress: 100, parsedFiles: 4, indexedFiles: 4 });
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [ready] },
    } as never);
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.get('[data-test="ingest-active-batch"]').text()).toContain('4/4 已解析');
    expect(wrapper.emitted('ready')).toHaveLength(1);
    await wrapper.get('[data-test="ingest-refresh"]').trigger('click');
    await flushPromises();
    expect(wrapper.emitted('ready')).toHaveLength(1);
  });

  test('polls active batches every two seconds only while visible', async () => {
    vi.useFakeTimers();
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [batch()] },
    } as never);
    mountPanel();
    await flushPromises();
    expect(knowledgeApi.listProjectDocumentBatches).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(2_000);
    await flushPromises();
    expect(knowledgeApi.listProjectDocumentBatches).toHaveBeenCalledTimes(2);

    visibility.mockReturnValue('hidden');
    await vi.advanceTimersByTimeAsync(4_000);
    expect(knowledgeApi.listProjectDocumentBatches).toHaveBeenCalledTimes(2);

    visibility.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await flushPromises();
    expect(knowledgeApi.listProjectDocumentBatches).toHaveBeenCalledTimes(3);
  });

  test('ignores a late batch response after project switch', async () => {
    const oldResponse = deferred<unknown>();
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockImplementation((projectId) => (
      projectId === 7
        ? oldResponse.promise as never
        : Promise.resolve({ data: { code: 200, message: 'success', data: [batch({ batchId: 99, projectId: 9, workId: 12 })] } }) as never
    ));
    const wrapper = mountPanel();
    await wrapper.setProps({ projectId: 9, workId: 12 });
    await flushPromises();
    expect(wrapper.get('[data-test="ingest-active-batch"]').text()).toContain('#99');

    oldResponse.resolve({ data: { code: 200, message: 'success', data: [batch({ batchId: 31 })] } });
    await flushPromises();

    expect(wrapper.get('[data-test="ingest-active-batch"]').text()).toContain('#99');
    expect(wrapper.text()).not.toContain('#31');
  });

  test('shows pending classification questions and submits the selected answer', async () => {
    const waiting = batch({ status: 'WAITING_CONFIRMATION', pendingQuestions: 1, progress: 70 });
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [waiting] },
    } as never);
    vi.mocked(knowledgeApi.listProjectDocumentQuestions).mockResolvedValue({
      data: { code: 200, message: 'success', data: [question()] },
    } as never);
    vi.mocked(knowledgeApi.answerProjectDocumentQuestion).mockResolvedValue({
      data: { code: 200, message: 'success', data: question({ status: 'RESOLVED', answerJson: 'OUTLINE' }) },
    } as never);
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.get('[data-test="ingest-question-91"]').text()).toContain('materials/notes.md');
    await wrapper.get('[data-test="ingest-question-91"] button').trigger('click');
    await flushPromises();

    expect(knowledgeApi.answerProjectDocumentQuestion).toHaveBeenCalledWith(7, 31, 91, 'OUTLINE');
  });

  test('does not load or show stale questions for a cancelled batch', async () => {
    const cancelled = batch({ status: 'CANCELLED', pendingQuestions: 8, progress: 70 });
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [cancelled] },
    } as never);
    const wrapper = mountPanel();
    await flushPromises();

    expect(knowledgeApi.listProjectDocumentQuestions).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="ingest-active-batch"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="ingest-questions"]').exists()).toBe(false);
    expect(wrapper.get('[data-test="ingest-history"]').attributes()).toHaveProperty('open');
    expect(wrapper.get('[data-test="ingest-discard-31"]').text()).toContain('删除记录');
  });

  test('confirms and discards a cancelled historical batch', async () => {
    const cancelled = batch({ status: 'CANCELLED', pendingQuestions: 8, progress: 70 });
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [cancelled] },
    } as never);
    vi.mocked(knowledgeApi.discardProjectDocumentBatch).mockResolvedValue({
      data: { code: 200, message: 'success', data: undefined },
    } as never);
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-test="ingest-discard-31"]').trigger('click');
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalled();
    expect(knowledgeApi.discardProjectDocumentBatch).toHaveBeenCalledWith(7, 31);
    expect(wrapper.find('[data-test="ingest-history"]').exists()).toBe(false);
    expect(ElMessage.success).toHaveBeenCalledWith('误传批次已删除');
  });

  test('replaces a generic confirmation server error with actionable feedback', async () => {
    const waiting = batch({ status: 'WAITING_CONFIRMATION', pendingQuestions: 1, progress: 70 });
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [waiting] },
    } as never);
    vi.mocked(knowledgeApi.listProjectDocumentQuestions).mockResolvedValue({
      data: { code: 200, message: 'success', data: [question()] },
    } as never);
    vi.mocked(knowledgeApi.answerProjectDocumentQuestion).mockRejectedValue({
      response: { status: 500, data: { message: 'internal server error' } },
    });
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-test="ingest-question-91"] button').trigger('click');
    await flushPromises();

    const error = wrapper.get('[data-test="ingest-error"]').text();
    expect(error).toContain('服务暂时不可用');
    expect(error).not.toContain('internal server error');
  });

  test('retries a failed batch without re-uploading files', async () => {
    const failed = batch({ status: 'RETRYABLE_FAILED', errorSummary: '向量服务超时' });
    vi.mocked(knowledgeApi.listProjectDocumentBatches).mockResolvedValue({
      data: { code: 200, message: 'success', data: [failed] },
    } as never);
    vi.mocked(knowledgeApi.retryProjectDocumentBatch).mockResolvedValue({
      data: { code: 200, message: 'success', data: batch({ status: 'PARSED_PENDING_INDEX', progress: 80 }) },
    } as never);
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-test="ingest-retry"]').trigger('click');
    await flushPromises();

    expect(knowledgeApi.retryProjectDocumentBatch).toHaveBeenCalledWith(7, 31);
    expect(knowledgeApi.createProjectDocumentBatch).not.toHaveBeenCalled();
  });

  test('rejects unsupported files locally without parsing valid content', async () => {
    const wrapper = mountPanel();
    await flushPromises();

    await choose(wrapper, '[data-test="ingest-file-input"]', [file('cover.pdf')]);

    expect(wrapper.get('[data-test="ingest-error"]').text()).toContain('格式不受支持');
    expect(knowledgeApi.createProjectDocumentBatch).not.toHaveBeenCalled();
  });
});
