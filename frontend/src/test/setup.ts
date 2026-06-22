import { beforeEach, vi } from 'vitest';
import { clearCurrentSession } from '@/lib/auth-session';

beforeEach(() => {
  localStorage.clear();
  clearCurrentSession();
  vi.restoreAllMocks();
});

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

Object.defineProperty(window, 'visualViewport', {
  configurable: true,
  writable: true,
  value: {
    width: 390,
    height: 780,
    offsetTop: 0,
    offsetLeft: 0,
    scale: 1,
    onresize: null,
    onscroll: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  },
});
