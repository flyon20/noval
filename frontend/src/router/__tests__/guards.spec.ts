import { resolveAuthRedirect } from '@/router/guards';

describe('route guards', () => {
  test('waits while auth state is restoring on protected routes', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/rank',
          meta: {},
        },
        'restoring',
        [],
      ),
    ).toBeNull();
  });

  test('redirects logged out users from protected routes', () => {
    expect(
      resolveAuthRedirect(
        {
          path: '/rank',
          meta: {},
        },
        'logged_out',
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
        'authenticated',
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
        'authenticated',
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
        'authenticated',
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
        'authenticated',
        ['ADMIN'],
      ),
    ).toBeNull();
  });
});
