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

type AnalysisRunPhase =
  | 'idle'
  | 'preparing'
  | 'streaming'
  | 'fallback-blocking'
  | 'done'
  | 'error'
  | 'aborted';

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
    phase: 'idle' as AnalysisRunPhase,
    streamingText: '',
    errorMessage: '',
    traceId: '',
    isFallback: false,
    results: {
      deconstruct: null as AnalysisResult | null,
      structure: null as AnalysisResult | null,
      plot: null as AnalysisResult | null,
    },
  });

  let currentTask: AnalysisStreamTask | null = null;
  let currentRunId = 0;

  function buildPayload(forceReanalyze = false): AnalysisRequest {
    const context = resolveContext(options);

    return {
      platform: context.platform,
      bookId: context.bookId,
      chapterCount: context.chapterCount,
      ...(forceReanalyze ? { forceReanalyze: true } : {}),
    };
  }

  function abortCurrent() {
    if (!currentTask) {
      return;
    }

    currentTask.abort();
    void currentTask.result.catch(() => undefined);
    currentTask = null;
  }

  async function runAnalysis(mode: AnalysisType, runOptions: { forceReanalyze?: boolean } = {}) {
    abortCurrent();

    currentRunId += 1;
    const runId = currentRunId;
    const payload = buildPayload(Boolean(runOptions.forceReanalyze));

    state.activeMode = mode;
    state.phase = 'preparing';
    state.streamingText = '';
    state.errorMessage = '';
    state.traceId = '';
    state.isFallback = false;

    const task = normalizeTask(
      options.runMode(mode, payload, {
        onStart(event) {
          if (runId !== currentRunId) {
            return;
          }

          state.phase = 'streaming';
          state.traceId = event.traceId;
        },
        onDelta(event) {
          if (runId !== currentRunId) {
            return;
          }

          state.phase = 'streaming';
          state.streamingText += event.delta;
        },
        onDone(event) {
          if (runId !== currentRunId) {
            return;
          }

          state.results[mode] = event.data;
        },
        onError(event) {
          if (runId !== currentRunId) {
            return;
          }

          state.phase = 'error';
          state.errorMessage = event.message;
          state.traceId = event.traceId ?? state.traceId;
        },
        onFallback() {
          if (runId !== currentRunId) {
            return;
          }

          state.phase = 'fallback-blocking';
          state.isFallback = true;
        },
      }),
    );

    currentTask = task;

    try {
      const result = await task.result;

      if (runId !== currentRunId) {
        return result;
      }

      state.results[mode] = result;
      state.phase = 'done';
      state.streamingText = result.resultContent;
      state.traceId = result.traceId ?? state.traceId;
      currentTask = null;
      return result;
    } catch (error) {
      if (runId !== currentRunId) {
        throw error;
      }

      if (state.phase !== 'error') {
        state.phase = 'error';
        state.errorMessage = error instanceof Error ? error.message : '分析失败';
      }

      currentTask = null;
      throw error;
    }
  }

  async function switchMode(mode: AnalysisType) {
    const cached = state.results[mode];

    if (cached) {
      abortCurrent();
      currentRunId += 1;
      state.activeMode = mode;
      state.phase = 'done';
      state.streamingText = cached.resultContent;
      state.errorMessage = '';
      state.traceId = cached.traceId ?? state.traceId;
      state.isFallback = false;
      return cached;
    }

    return runAnalysis(mode);
  }

  function stopAnalysis() {
    abortCurrent();
    currentRunId += 1;
    state.phase = 'aborted';
  }

  async function rerunAnalysis() {
    return runAnalysis(state.activeMode, {
      forceReanalyze: true,
    });
  }

  async function copyResult() {
    const text = state.results[state.activeMode]?.resultContent ?? state.streamingText;

    if (!text) {
      return;
    }

    await options.copyText(text);
  }

  return {
    state,
    runAnalysis,
    switchMode,
    stopAnalysis,
    rerunAnalysis,
    copyResult,
  };
}
