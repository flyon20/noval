import type {
  AnalysisRequest,
  AnalysisResult,
  StreamDeltaEvent,
  StreamDoneEvent,
  StreamErrorEvent,
  StreamStartEvent,
} from '@/types/analysis';
import type { TokenResponse } from '@/types/auth';

export interface AnalysisStreamCallbacks<TDone = AnalysisResult> {
  onStart(event: StreamStartEvent): void;
  onDelta(event: StreamDeltaEvent): void;
  onDone(event: StreamDoneEvent<TDone>): void;
  onError(event: StreamErrorEvent): void;
  onFallback?(): void;
}

export interface AnalysisStreamTask<TDone = AnalysisResult> {
  abort(): void;
  result: Promise<TDone>;
}

interface ParsedEvent {
  event: 'start' | 'delta' | 'done' | 'error';
  payload: unknown;
}

const FALLBACK_SIGNAL = Symbol('analysis-stream-fallback');

export interface AnalysisStreamRunnerDeps<TRequest = AnalysisRequest, TDone = AnalysisResult> {
  getAccessToken(): string | null;
  refreshToken(token: string): Promise<TokenResponse>;
  applyTokenResponse(response: TokenResponse): void;
  clearSession(): void;
  fetchImpl?: typeof fetch;
  fallbackRequest(payload: TRequest): Promise<TDone>;
}

export function parseSseFrames(buffer: string, options: { flush?: boolean } = {}) {
  let normalized = buffer.replace(/\r\n/g, '\n');

  if (options.flush && normalized.trim()) {
    normalized = normalized.endsWith('\n\n') ? normalized : `${normalized}\n\n`;
  }

  const events: ParsedEvent[] = [];
  let cursor = 0;

  while (cursor < normalized.length) {
    const boundary = normalized.indexOf('\n\n', cursor);

    if (boundary === -1) {
      break;
    }

    const block = normalized.slice(cursor, boundary).trim();
    cursor = boundary + 2;

    if (!block) {
      continue;
    }

    let eventName = 'message';
    const dataLines: string[] = [];

    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      }

      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim());
      }
    }

    if (!dataLines.length) {
      continue;
    }

    try {
      events.push({
        event: eventName as ParsedEvent['event'],
        payload: JSON.parse(dataLines.join('\n')),
      });
    } catch {
      continue;
    }
  }

  return {
    events,
    rest: normalized.slice(cursor),
  };
}

function createAbortError() {
  return new Error('Analysis stream aborted');
}

function isAbortError(error: unknown) {
  return error instanceof Error && error.message === 'Analysis stream aborted';
}

function isAnalysisProgressDelta(payload: unknown) {
  if (!payload || typeof payload !== 'object') {
    return false;
  }

  const delta = 'delta' in payload ? (payload as { delta?: unknown }).delta : undefined;
  return typeof delta === 'string' && delta.startsWith('[analysis-progress]');
}

export function createAnalysisStreamRunner<TRequest = AnalysisRequest, TDone = AnalysisResult>(
  deps: AnalysisStreamRunnerDeps<TRequest, TDone>,
) {
  const fetchImpl = deps.fetchImpl ?? fetch;

  return {
    run(
      url: string,
      payload: TRequest,
      callbacks: AnalysisStreamCallbacks<TDone>,
    ): AnalysisStreamTask<TDone> {
      const controller = new AbortController();
      let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
      let settled = false;
      let rejectPromise: ((reason?: unknown) => void) | null = null;

      const finalizeReject = (reason: unknown) => {
        if (settled) {
          return;
        }

        settled = true;
        rejectPromise?.(reason);
      };

      const result = new Promise<TDone>((resolve, reject) => {
        rejectPromise = reject;

        const execute = async () => {
          let currentToken = deps.getAccessToken();

          if (!currentToken) {
            deps.clearSession();
            throw new Error('Missing access token');
          }

          let retried = false;
          let sawDelta = false;

          const consumeEvents = (items: ParsedEvent[]) => {
            for (const item of items) {
              if (controller.signal.aborted) {
                throw createAbortError();
              }

              if (item.event === 'start') {
                callbacks.onStart(item.payload as StreamStartEvent);
                continue;
              }

              if (item.event === 'delta') {
                if (isAnalysisProgressDelta(item.payload)) {
                  continue;
                }
                sawDelta = true;
                callbacks.onDelta(item.payload as StreamDeltaEvent);
                continue;
              }

              if (item.event === 'done') {
                const doneEvent = item.payload as StreamDoneEvent<TDone>;
                callbacks.onDone(doneEvent);
                return doneEvent.data;
              }

              if (item.event === 'error') {
                const errorEvent = item.payload as StreamErrorEvent;
                callbacks.onError(errorEvent);
                if (!sawDelta) {
                  callbacks.onFallback?.();
                  return FALLBACK_SIGNAL;
                }
                throw new Error(errorEvent.message);
              }
            }

            return undefined;
          };

          while (true) {
            if (controller.signal.aborted) {
              throw createAbortError();
            }

            const response = await fetchImpl(url, {
              method: 'POST',
              headers: {
                Authorization: `Bearer ${currentToken}`,
                'Content-Type': 'application/json',
                Accept: 'text/event-stream',
              },
              body: JSON.stringify(payload),
              signal: controller.signal,
            });

            if (response.status === 401 && !retried) {
              const refreshed = await deps.refreshToken(currentToken);
              deps.applyTokenResponse(refreshed);
              currentToken = refreshed.accessToken;
              retried = true;
              continue;
            }

            if (response.status === 401) {
              deps.clearSession();
              throw new Error('Unauthorized stream request');
            }

            const contentType = response.headers.get('Content-Type') ?? '';

            if (!response.ok || !contentType.includes('text/event-stream')) {
              callbacks.onFallback?.();
              return deps.fallbackRequest(payload);
            }

            if (!response.body) {
              callbacks.onFallback?.();
              return deps.fallbackRequest(payload);
            }

            reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            while (true) {
              if (controller.signal.aborted) {
                throw createAbortError();
              }

              const { done, value } = await reader.read();

              if (done) {
                buffer += decoder.decode();
                const finalParsed = parseSseFrames(buffer, { flush: true });
                buffer = finalParsed.rest;
                const finalResult = consumeEvents(finalParsed.events);

                if (finalResult === FALLBACK_SIGNAL) {
                  return deps.fallbackRequest(payload);
                }

                if (finalResult) {
                  return finalResult;
                }

                break;
              }

              buffer += decoder.decode(value, { stream: true });
              const parsed = parseSseFrames(buffer);
              buffer = parsed.rest;

              const streamedResult = consumeEvents(parsed.events);

              if (streamedResult === FALLBACK_SIGNAL) {
                return deps.fallbackRequest(payload);
              }

              if (streamedResult) {
                return streamedResult;
              }
            }

            if (!sawDelta) {
              callbacks.onFallback?.();
              return deps.fallbackRequest(payload);
            }

            throw new Error('Stream ended unexpectedly');
          }
        };

        execute()
          .then((value) => {
            if (!settled) {
              settled = true;
              resolve(value);
            }
          })
          .catch((error) => {
            if (!isAbortError(error) && !settled) {
              settled = true;
              reject(error);
            }
          });
      });

      controller.signal.addEventListener('abort', () => {
        void reader?.cancel().catch(() => undefined);
        finalizeReject(createAbortError());
      });

      return {
        abort() {
          controller.abort();
        },
        result,
      };
    },
  };
}
