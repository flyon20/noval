const DEFAULT_CHARS_PER_TICK = 32;
const DEFAULT_TICK_MILLIS = 24;
const DEFAULT_TARGET_DURATION_MS = 1400;

export interface StreamingPlaybackController {
  append(delta: string): void;
  flushTo(finalText: string): Promise<void>;
  flushSync(): void;
  reset(): void;
  destroy(): void;
}

export function createStreamingPlaybackController(
  applyText: (text: string) => void,
  options: { charsPerTick?: number; tickMillis?: number; targetDurationMs?: number } = {},
): StreamingPlaybackController {
  const charsPerTick = Math.max(1, options.charsPerTick ?? DEFAULT_CHARS_PER_TICK);
  const tickMillis = Math.max(1, options.tickMillis ?? DEFAULT_TICK_MILLIS);
  const targetDurationMs = Math.max(tickMillis, options.targetDurationMs ?? DEFAULT_TARGET_DURATION_MS);
  const targetTickCount = Math.max(1, Math.ceil(targetDurationMs / tickMillis));

  let fullText = '';
  let visibleLength = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let pendingFlush:
    | {
        finalText: string;
        resolve: () => void;
      }
    | null = null;

  const clearTimer = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  };

  const applyVisible = () => {
    const nextText = fullText.slice(0, visibleLength);
    applyText(nextText);
  };

  const finishPendingFlush = () => {
    if (!pendingFlush) {
      return;
    }
    const { finalText, resolve } = pendingFlush;
    pendingFlush = null;
    fullText = finalText;
    visibleLength = finalText.length;
    applyText(finalText);
    resolve();
  };

  const step = () => {
    timer = null;

    if (visibleLength < fullText.length) {
      const adaptiveCharsPerTick = Math.max(charsPerTick, Math.ceil(fullText.length / targetTickCount));
      visibleLength = Math.min(fullText.length, visibleLength + adaptiveCharsPerTick);
      applyVisible();
    }

    if (visibleLength < fullText.length) {
      timer = setTimeout(step, tickMillis);
      return;
    }

    finishPendingFlush();
  };

  const ensurePlayback = () => {
    if (!timer && visibleLength < fullText.length) {
      timer = setTimeout(step, tickMillis);
    }
  };

  return {
    append(delta: string) {
      if (!delta) {
        return;
      }

      fullText += delta;

      if (visibleLength === 0) {
        visibleLength = Math.min(fullText.length, charsPerTick);
        applyVisible();
      }

      ensurePlayback();
    },

    flushTo(finalText: string) {
      fullText = finalText;
      if (visibleLength >= fullText.length) {
        visibleLength = fullText.length;
        applyText(finalText);
        return Promise.resolve();
      }

      return new Promise<void>((resolve) => {
        pendingFlush = {
          finalText,
          resolve,
        };
        ensurePlayback();
      });
    },

    flushSync() {
      clearTimer();
      visibleLength = fullText.length;
      applyVisible();
      finishPendingFlush();
    },

    reset() {
      clearTimer();
      fullText = '';
      visibleLength = 0;
      pendingFlush = null;
      applyText('');
    },

    destroy() {
      clearTimer();
      pendingFlush = null;
    },
  };
}
