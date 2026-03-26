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
  beforeEach(() => {
    vi.useRealTimers();
  });

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

  test('progressively reveals large streaming chunks instead of painting the full result immediately', async () => {
    vi.useFakeTimers();
    let completeStream: ((value: AnalysisResult) => void) | null = null;

    const runner = vi.fn().mockImplementation((_mode, _payload, callbacks) => {
      callbacks.onStart({
        event: 'start',
        traceId: 'trace-1',
        analysisType: 'deconstruct',
      });
      callbacks.onDelta({
        event: 'delta',
        delta: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.repeat(12),
        chunkIndex: 0,
      });

      return {
        abort() {},
        result: new Promise<AnalysisResult>((resolve) => {
          completeStream = resolve;
        }),
      };
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

    const runPromise = analysis.runAnalysis('deconstruct');
    await nextTick();

    const fullText = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.repeat(12);
    expect(analysis.state.modes.deconstruct.streamingText.length).toBeGreaterThan(0);
    expect(analysis.state.modes.deconstruct.streamingText.length).toBeLessThan(fullText.length);

    await vi.advanceTimersByTimeAsync(1200);
    expect(analysis.state.modes.deconstruct.streamingText).toBe(fullText);

    completeStream?.(createResult('deconstruct', { resultContent: fullText }));
    await runPromise;
    expect(analysis.state.modes.deconstruct.phase).toBe('done');
  });

  test('finishes long playback quickly enough that large analysis results do not stay stuck in streaming state', async () => {
    vi.useFakeTimers();
    let completeStream: ((value: AnalysisResult) => void) | null = null;

    const runner = vi.fn().mockImplementation((_mode, _payload, callbacks) => {
      callbacks.onStart({
        event: 'start',
        traceId: 'trace-long',
        analysisType: 'deconstruct',
      });
      callbacks.onDelta({
        event: 'delta',
        delta: 'LONG-CONTENT-SEGMENT-'.repeat(1200),
        chunkIndex: 0,
      });

      return {
        abort() {},
        result: new Promise<AnalysisResult>((resolve) => {
          completeStream = resolve;
        }),
      };
    });

    const analysis = useAnalysisRun({
      context: {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 10,
      },
      runMode: runner,
      copyText: vi.fn(),
    });

    const fullText = 'LONG-CONTENT-SEGMENT-'.repeat(1200);
    const runPromise = analysis.runAnalysis('deconstruct');
    await nextTick();
    completeStream?.(createResult('deconstruct', { resultContent: fullText }));

    await vi.advanceTimersByTimeAsync(1800);
    await runPromise;

    expect(analysis.state.modes.deconstruct.phase).toBe('done');
    expect(analysis.state.modes.deconstruct.streamingText).toBe(fullText);
  });

  test('hydrates persisted results without rerunning analysis', () => {
    const runner = vi.fn();
    const analysis = useAnalysisRun({
      context: {
        platform: 'fanqie',
        bookId: 1001,
        chapterCount: 3,
      },
      runMode: runner,
      copyText: vi.fn(),
    });

    analysis.hydrateModes({
      deconstruct: createResult('deconstruct'),
      plot: createResult('plot'),
    });

    expect(runner).not.toHaveBeenCalled();
    expect(analysis.state.modes.deconstruct.phase).toBe('done');
    expect(analysis.state.modes.deconstruct.result?.analysisType).toBe('deconstruct');
    expect(analysis.state.modes.plot.phase).toBe('done');
    expect(analysis.state.modes.plot.result?.analysisType).toBe('plot');
    expect(analysis.state.modes.structure.phase).toBe('idle');
  });
});
