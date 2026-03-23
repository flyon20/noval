import { reactive } from 'vue';
import type {
  AnalysisRequest,
  AnalysisResult,
  AnalysisType,
  StreamDeltaEvent,
  StreamDoneEvent,
  StreamErrorEvent,
  StreamStartEvent,
} from '@/types/analysis';
import type { AnalysisStreamTask } from '@/lib/analysis-stream';

type AnalysisRunModeFn = (
  mode: AnalysisType,
  payload: AnalysisRequest,
  callbacks: {
    onStart(event: StreamStartEvent): void;
    onDelta(event: StreamDeltaEvent): void;
    onDone(event: StreamDoneEvent<AnalysisResult>): void;
    onError(event: StreamErrorEvent): void;
    onFallback(): void;
  },
) => Promise<AnalysisResult> | AnalysisStreamTask;

interface UseAnalysisRunOptions {
  context:
    | {
        platform: 'fanqie';
        bookId: number;
        chapterCount: number;
      }
    | (() => {
        platform: 'fanqie';
        bookId: number;
        chapterCount: number;
      });
  runMode: AnalysisRunModeFn;
  copyText(text: string): void | Promise<void>;
}

export type AnalysisRunPhase =
  | 'idle'
  | 'preparing'
  | 'streaming'
  | 'fallback-blocking'
  | 'done'
  | 'error'
  | 'aborted';

interface AnalysisModeState {
  phase: AnalysisRunPhase;
  streamingText: string;
  errorMessage: string;
  traceId: string;
  isFallback: boolean;
  result: AnalysisResult | null;
}

const ANALYSIS_MODES: AnalysisType[] = ['deconstruct', 'structure', 'plot'];

function createModeState(): AnalysisModeState {
  return {
    phase: 'idle',
    streamingText: '',
    errorMessage: '',
    traceId: '',
    isFallback: false,
    result: null,
  };
}

function normalizeTask(taskOrPromise: Promise<AnalysisResult> | AnalysisStreamTask): AnalysisStreamTask {
  if ('result' in taskOrPromise) {
    return taskOrPromise;
  }

  return {
    abort() {},
    result: taskOrPromise,
  };
}

function resolveContext(options: UseAnalysisRunOptions) {
  return typeof options.context === 'function' ? options.context() : options.context;
}

export function useAnalysisRun(options: UseAnalysisRunOptions) {
  const state = reactive({
    activeMode: 'deconstruct' as AnalysisType,
    modes: {
      deconstruct: createModeState(),
      structure: createModeState(),
      plot: createModeState(),
    } as Record<AnalysisType, AnalysisModeState>,
  });

  const currentTasks: Record<AnalysisType, AnalysisStreamTask | null> = {
    deconstruct: null,
    structure: null,
    plot: null,
  };
  const currentRunIds: Record<AnalysisType, number> = {
    deconstruct: 0,
    structure: 0,
    plot: 0,
  };

  function buildPayload(forceReanalyze = false): AnalysisRequest {
    const context = resolveContext(options);

    return {
      platform: context.platform,
      bookId: context.bookId,
      chapterCount: context.chapterCount,
      ...(forceReanalyze ? { forceReanalyze: true } : {}),
    };
  }

  function resetModeState(mode: AnalysisType, phase: AnalysisRunPhase = 'idle') {
    const modeState = state.modes[mode];
    modeState.phase = phase;
    modeState.streamingText = '';
    modeState.errorMessage = '';
    modeState.traceId = '';
    modeState.isFallback = false;
    modeState.result = null;
  }

  function abortMode(mode: AnalysisType) {
    const task = currentTasks[mode];
    if (!task) {
      return;
    }

    task.abort();
    void task.result.catch(() => undefined);
    currentTasks[mode] = null;
  }

  async function runAnalysis(mode: AnalysisType, runOptions: { forceReanalyze?: boolean } = {}) {
    abortMode(mode);

    currentRunIds[mode] += 1;
    const runId = currentRunIds[mode];
    const payload = buildPayload(Boolean(runOptions.forceReanalyze));
    const modeState = state.modes[mode];

    state.activeMode = mode;
    resetModeState(mode, 'preparing');

    const task = normalizeTask(
      options.runMode(mode, payload, {
        onStart(event) {
          if (runId !== currentRunIds[mode]) {
            return;
          }

          modeState.phase = 'streaming';
          modeState.traceId = event.traceId;
        },
        onDelta(event) {
          if (runId !== currentRunIds[mode]) {
            return;
          }

          modeState.phase = 'streaming';
          modeState.streamingText += event.delta;
        },
        onDone(event) {
          if (runId !== currentRunIds[mode]) {
            return;
          }

          modeState.result = event.data;
        },
        onError(event) {
          if (runId !== currentRunIds[mode]) {
            return;
          }

          modeState.phase = 'error';
          modeState.errorMessage = event.message;
          modeState.traceId = event.traceId ?? modeState.traceId;
        },
        onFallback() {
          if (runId !== currentRunIds[mode]) {
            return;
          }

          modeState.phase = 'fallback-blocking';
          modeState.isFallback = true;
        },
      }),
    );

    currentTasks[mode] = task;

    try {
      const result = await task.result;

      if (runId !== currentRunIds[mode]) {
        return result;
      }

      modeState.result = result;
      modeState.phase = 'done';
      modeState.streamingText = result.resultContent;
      modeState.traceId = result.traceId ?? modeState.traceId;
      currentTasks[mode] = null;
      return result;
    } catch (error) {
      if (runId !== currentRunIds[mode]) {
        throw error;
      }

      if (modeState.phase !== 'error') {
        modeState.phase = 'error';
        modeState.errorMessage = error instanceof Error ? error.message : 'Analysis failed';
      }

      currentTasks[mode] = null;
      throw error;
    }
  }

  async function runAllAnalyses(runOptions: { forceReanalyze?: boolean } = {}) {
    return Promise.allSettled(ANALYSIS_MODES.map((mode) => runAnalysis(mode, runOptions)));
  }

  function stopAnalysis(mode: AnalysisType) {
    abortMode(mode);
    currentRunIds[mode] += 1;
    state.modes[mode].phase = 'aborted';
  }

  function stopAllAnalyses() {
    for (const mode of ANALYSIS_MODES) {
      stopAnalysis(mode);
    }
  }

  function resetAllAnalyses() {
    for (const mode of ANALYSIS_MODES) {
      abortMode(mode);
      currentRunIds[mode] += 1;
      resetModeState(mode);
    }
  }

  async function rerunAnalysis(mode: AnalysisType) {
    return runAnalysis(mode, {
      forceReanalyze: true,
    });
  }

  async function copyResult(mode: AnalysisType) {
    const modeState = state.modes[mode];
    const text = modeState.result?.resultContent ?? modeState.streamingText;

    if (!text) {
      return;
    }

    state.activeMode = mode;
    await options.copyText(text);
  }

  return {
    state,
    runAnalysis,
    runAllAnalyses,
    stopAnalysis,
    stopAllAnalyses,
    resetAllAnalyses,
    rerunAnalysis,
    copyResult,
  };
}
