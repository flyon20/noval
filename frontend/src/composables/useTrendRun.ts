import { reactive } from 'vue';
import type { AnalysisStreamTask } from '@/lib/analysis-stream';
import type {
  StreamDeltaEvent,
  StreamDoneEvent,
  StreamErrorEvent,
  StreamStartEvent,
} from '@/types/analysis';
import type { TrendAnalysisResult, TrendRequest } from '@/types/trend';

type TrendRunFn = (
  payload: TrendRequest,
  callbacks: {
    onStart(event: StreamStartEvent): void;
    onDelta(event: StreamDeltaEvent): void;
    onDone(event: StreamDoneEvent<TrendAnalysisResult>): void;
    onError(event: StreamErrorEvent): void;
    onFallback(): void;
  },
) => Promise<TrendAnalysisResult> | AnalysisStreamTask<TrendAnalysisResult>;

type TrendRunPhase =
  | 'idle'
  | 'preparing'
  | 'streaming'
  | 'fallback-blocking'
  | 'done'
  | 'error'
  | 'aborted';

interface UseTrendRunOptions {
  runTrend: TrendRunFn;
  copyText(text: string): void | Promise<void>;
  platform?: 'fanqie';
  initialCategory?: string;
}

function normalizeTask(
  taskOrPromise: Promise<TrendAnalysisResult> | AnalysisStreamTask<TrendAnalysisResult>,
): AnalysisStreamTask<TrendAnalysisResult> {
  if ('result' in taskOrPromise) {
    return taskOrPromise;
  }

  return {
    abort() {},
    result: taskOrPromise,
  };
}

export function useTrendRun(options: UseTrendRunOptions) {
  const state = reactive({
    platform: options.platform ?? ('fanqie' as const),
    category: options.initialCategory ?? 'male-hot-a',
    phase: 'idle' as TrendRunPhase,
    streamingText: '',
    errorMessage: '',
    traceId: '',
    isFallback: false,
    result: null as TrendAnalysisResult | null,
  });

  let currentTask: AnalysisStreamTask<TrendAnalysisResult> | null = null;
  let currentRunId = 0;

  function buildPayload(category = state.category): TrendRequest {
    return {
      platform: state.platform,
      category,
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

  async function runTrend(category = state.category) {
    abortCurrent();

    currentRunId += 1;
    const runId = currentRunId;

    state.category = category;
    state.phase = 'preparing';
    state.streamingText = '';
    state.errorMessage = '';
    state.traceId = '';
    state.isFallback = false;

    const task = normalizeTask(
      options.runTrend(buildPayload(category), {
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

          state.result = event.data;
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

      state.result = result;
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
        state.errorMessage = error instanceof Error ? error.message : '趋势分析失败';
      }

      currentTask = null;
      throw error;
    }
  }

  function stopTrend() {
    abortCurrent();
    currentRunId += 1;
    state.phase = 'aborted';
  }

  function rerunTrend() {
    return runTrend(state.category);
  }

  async function copyResult() {
    const text = state.result?.resultContent ?? state.streamingText;

    if (!text) {
      return;
    }

    await options.copyText(text);
  }

  return {
    state,
    runTrend,
    stopTrend,
    rerunTrend,
    copyResult,
  };
}
