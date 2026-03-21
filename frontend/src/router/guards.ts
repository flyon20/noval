interface GuardRouteLike {
  path: string;
  meta?: {
    public?: boolean;
  };
}

export function resolveAuthRedirect(to: GuardRouteLike, isAuthenticated: boolean) {
  if (!isAuthenticated && !to.meta?.public) {
    return '/login';
  }

  if (isAuthenticated && to.path === '/login') {
    return '/rank';
  }

  return null;
}
