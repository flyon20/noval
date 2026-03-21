import { nextTick } from 'vue';
import { useAnalysisRun } from '@/composables/useAnalysisRun';
import type { AnalysisResult, AnalysisType } from '@/types/analysis';

function createResult(analysisType: AnalysisType, overrides: Partial<AnalysisResult> = {}): AnalysisResult {
  return {
    id: 1,
    bookId: 1001,
    analysisType,
    modelName: 'dify',
    resultContent: `${analysisType}-content`,
    resultJson: {},
    tokenUsed: 123,
    ...overrides,
  };
}

describe('useAnalysisRun', () => {
  test('runs the default mode and stores the completed result', async () => {
    const runner = vi.fn().mockResolvedValue(createResult('deconstruct'));
    const analysis = useAnalysisRun({
      context: {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      runMode: runner,
      copyText: vi.fn(),
    });

    await analysis.runAnalysis('deconstruct');

    expect(runner).toHaveBeenCalledWith(
      'deconstruct',
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      expect.any(Object),
    );
    expect(analysis.state.phase).toBe('done');
    expect(analysis.state.results.deconstruct?.resultContent).toBe('deconstruct-content');
  });

  test('switches mode and aborts the previous task', async () => {
    const firstAbort = vi.fn();
    let firstResolve: ((value: AnalysisResult) => void) | null = null;
    const runner = vi
      .fn()
      .mockImplementationOnce(() => ({
        abort: firstAbort,
        result: new Promise<AnalysisResult>((resolve) => {
          firstResolve = resolve;
        }),
      }))
      .mockImplementationOnce(() => Promise.resolve(createResult('structure')));

    const analysis = useAnalysisRun({
      context: {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      runMode: runner,
      copyText: vi.fn(),
    });

    void analysis.runAnalysis('deconstruct');
    await nextTick();
    await analysis.switchMode('structure');
    firstResolve?.(createResult('deconstruct'));

    expect(firstAbort).toHaveBeenCalledTimes(1);
    expect(analysis.state.activeMode).toBe('structure');
    expect(analysis.state.results.structure?.analysisType).toBe('structure');
  });

  test('reruns current mode with forceReanalyze=true', async () => {
    const runner = vi.fn().mockResolvedValue(createResult('plot'));
    const analysis = useAnalysisRun({
      context: {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      runMode: runner,
      copyText: vi.fn(),
    });

    analysis.state.activeMode = 'plot';
    await analysis.rerunAnalysis();

    expect(runner).toHaveBeenCalledWith(
      'plot',
      {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
        forceReanalyze: true,
      },
      expect.any(Object),
    );
  });

  test('keeps partial text after stream interruption', async () => {
    const runner = vi.fn().mockImplementation(async (_mode, _payload, callbacks) => {
      callbacks.onStart({
        event: 'start',
        traceId: 'trace-1',
        analysisType: 'deconstruct',
      });
      callbacks.onDelta({
        event: 'delta',
        delta: '第一段',
        chunkIndex: 0,
      });
      callbacks.onError({
        event: 'error',
        code: 500,
        message: '连接中断，可重试',
        traceId: 'trace-1',
      });
      throw new Error('network error');
    });
    const analysis = useAnalysisRun({
      context: {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      runMode: runner,
      copyText: vi.fn(),
    });

    await expect(analysis.runAnalysis('deconstruct')).rejects.toThrow();
    expect(analysis.state.streamingText).toBe('第一段');
    expect(analysis.state.errorMessage).toBe('连接中断，可重试');
  });
});
