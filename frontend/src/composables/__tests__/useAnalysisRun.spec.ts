import { nextTick } from 'vue';
import { useAnalysisRun } from '@/composables/useAnalysisRun';
import type { AnalysisResult, AnalysisType } from '@/types/analysis';

function createResult(analysisType: AnalysisType, overrides: Partial<AnalysisResult> = {}): AnalysisResult {
  return {
    id: 1,
    bookId: 1001,
    analysisType,
    modelName: 'deepseek-chat',
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
    expect(analysis.state.modes.deconstruct.phase).toBe('done');
    expect(analysis.state.modes.deconstruct.result?.resultContent).toBe('deconstruct-content');
  });

  test('runs multiple modes in parallel without aborting the earlier task', async () => {
    const deconstructAbort = vi.fn();
    const structureAbort = vi.fn();
    let resolveDeconstruct: ((value: AnalysisResult) => void) | null = null;
    let resolveStructure: ((value: AnalysisResult) => void) | null = null;

    const runner = vi
      .fn()
      .mockImplementationOnce(() => ({
        abort: deconstructAbort,
        result: new Promise<AnalysisResult>((resolve) => {
          resolveDeconstruct = resolve;
        }),
      }))
      .mockImplementationOnce(() => ({
        abort: structureAbort,
        result: new Promise<AnalysisResult>((resolve) => {
          resolveStructure = resolve;
        }),
      }));

    const analysis = useAnalysisRun({
      context: {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      runMode: runner,
      copyText: vi.fn(),
    });

    const deconstructRun = analysis.runAnalysis('deconstruct');
    await nextTick();
    const structureRun = analysis.runAnalysis('structure');
    await nextTick();

    expect(deconstructAbort).not.toHaveBeenCalled();
    expect(structureAbort).not.toHaveBeenCalled();

    resolveDeconstruct?.(createResult('deconstruct'));
    resolveStructure?.(createResult('structure'));
    await Promise.all([deconstructRun, structureRun]);

    expect(analysis.state.modes.deconstruct.phase).toBe('done');
    expect(analysis.state.modes.structure.phase).toBe('done');
    expect(analysis.state.modes.deconstruct.result?.analysisType).toBe('deconstruct');
    expect(analysis.state.modes.structure.result?.analysisType).toBe('structure');
  });

  test('stops only the requested mode and leaves other modes running', async () => {
    const deconstructAbort = vi.fn();
    const structureAbort = vi.fn();
    let resolveStructure: ((value: AnalysisResult) => void) | null = null;

    const runner = vi
      .fn()
      .mockImplementationOnce(() => ({
        abort: deconstructAbort,
        result: new Promise<AnalysisResult>(() => undefined),
      }))
      .mockImplementationOnce(() => ({
        abort: structureAbort,
        result: new Promise<AnalysisResult>((resolve) => {
          resolveStructure = resolve;
        }),
      }));

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
    const structureRun = analysis.runAnalysis('structure');
    await nextTick();

    analysis.stopAnalysis('deconstruct');

    expect(deconstructAbort).toHaveBeenCalledTimes(1);
    expect(structureAbort).not.toHaveBeenCalled();
    expect(analysis.state.modes.deconstruct.phase).toBe('aborted');

    resolveStructure?.(createResult('structure'));
    await structureRun;

    expect(analysis.state.modes.structure.phase).toBe('done');
    expect(analysis.state.modes.structure.result?.analysisType).toBe('structure');
  });

  test('reruns the requested mode with forceReanalyze=true', async () => {
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

    await analysis.rerunAnalysis('plot');

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
        delta: 'partial-output',
        chunkIndex: 0,
      });
      callbacks.onError({
        event: 'error',
        code: 500,
        message: 'stream broken',
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
    expect(analysis.state.modes.deconstruct.streamingText).toBe('partial-output');
    expect(analysis.state.modes.deconstruct.errorMessage).toBe('stream broken');
  });
});
