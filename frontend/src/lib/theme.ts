export type AppTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'preferred-theme';
export const THEME_EVENT_NAME = 'app-theme-change';

export function applyTheme(theme: AppTheme): void {
  if (typeof document === 'undefined') {
    return;
  }

  document.documentElement.dataset.theme = theme;
  document.dispatchEvent(new CustomEvent<AppTheme>(THEME_EVENT_NAME, { detail: theme }));
}

export function getStoredTheme(): AppTheme | null {
  if (typeof window === 'undefined') {
    return null;
  }

  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') {
      return stored;
    }
  } catch {
    // ignore storage access issues
  }

  return null;
}

export function resolveSystemTheme(): AppTheme {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return 'light';
  }

  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function getCurrentTheme(): AppTheme {
  if (typeof document === 'undefined') {
    return 'light';
  }

  const current = document.documentElement.dataset.theme;
  return current === 'dark' ? 'dark' : 'light';
}

export function setStoredTheme(theme: AppTheme): void {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // ignore storage access issues
  }
}

export function toggleTheme(): AppTheme {
  const nextTheme: AppTheme = getCurrentTheme() === 'dark' ? 'light' : 'dark';
  setStoredTheme(nextTheme);
  applyTheme(nextTheme);
  return nextTheme;
}

export function bootstrapTheme(): (() => void) | undefined {
  const prefersDarkQuery = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null;

  const storedTheme = getStoredTheme();
  applyTheme(storedTheme ?? resolveSystemTheme());

  if (storedTheme || !prefersDarkQuery) {
    return undefined;
  }

  const handleSchemeChange = (event: MediaQueryListEvent | MediaQueryList) => {
    applyTheme(event.matches ? 'dark' : 'light');
  };

  if ('addEventListener' in prefersDarkQuery) {
    prefersDarkQuery.addEventListener('change', handleSchemeChange);
    return () => prefersDarkQuery.removeEventListener('change', handleSchemeChange);
  }

  prefersDarkQuery.addListener(handleSchemeChange);
  return () => prefersDarkQuery.removeListener(handleSchemeChange);
}
