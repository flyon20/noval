import { resolveAuthRedirect } from '@/router/guards';

describe('route guards', () => {
  test('redirects unauthenticated users from protected routes', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/rank',
          meta: {},
        },
        false,
        [],
      ),
    ).toBe('/login');
  });

  test('redirects authenticated users away from login page', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/login',
          meta: {
            public: true,
          },
        },
        true,
        ['USER'],
      ),
    ).toBe('/rank');
  });

  test('allows authenticated users to access protected routes', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/rank',
          meta: {},
        },
        true,
        ['USER'],
      ),
    ).toBeNull();
  });

  test('redirects non-admin users away from admin route', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/config/system',
          meta: {
            roles: ['ADMIN'],
          },
        },
        true,
        ['USER'],
      ),
    ).toBe('/rank');
  });

  test('allows admin users to access admin route', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/config/system',
          meta: {
            roles: ['ADMIN'],
          },
        },
        true,
        ['ADMIN'],
      ),
    ).toBeNull();
  });
});
