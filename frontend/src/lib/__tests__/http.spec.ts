import AxiosMockAdapter from 'axios-mock-adapter';
import { authSessionRef } from '@/lib/auth-session';
import type { TokenResponse } from '@/types/auth';
import { API_BASE_URL, createHttpClient, httpClient, rawHttpClient } from '@/lib/http';
import { createJwtToken } from '@/test/helpers';

describe('http client', () => {
  beforeEach(() => {
    authSessionRef.value = null;
  });

  test('uses relative api base url by default for local dev proxying', () => {
    expect(API_BASE_URL).toBe('');
  });

  test('attaches authorization header', async () => {
    const client = createHttpClient({
      baseURL: 'http://localhost:8080',
      getAccessToken: () => 'jwt-token',
      refreshToken: vi.fn(),
      applyTokenResponse: vi.fn(),
      clearSession: vi.fn(),
    });
    const mock = new AxiosMockAdapter(client);

    mock.onGet('/secure').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer jwt-token');
      return [200, { code: 200, message: 'success', data: null, timestamp: 1, traceId: 't-1' }];
    });

    await client.get('/secure');
  });

  test('refreshes token and retries the original request once on 401', async () => {
    const refreshResponse: TokenResponse = {
      accessToken: 'new-token',
      tokenType: 'Bearer',
      expiresIn: 7200,
    };
    const refreshToken = vi.fn().mockResolvedValue(refreshResponse);
    const applyTokenResponse = vi.fn();
    const clearSession = vi.fn();
    const client = createHttpClient({
      baseURL: 'http://localhost:8080',
      getAccessToken: vi
        .fn()
        .mockReturnValueOnce('old-token')
        .mockReturnValueOnce('old-token')
        .mockReturnValueOnce('new-token')
        .mockReturnValueOnce('new-token'),
      refreshToken,
      applyTokenResponse,
      clearSession,
    });
    const mock = new AxiosMockAdapter(client);

    mock.onGet('/secure').replyOnce(401).onGet('/secure').replyOnce(200, {
      code: 200,
      message: 'success',
      data: { ok: true },
      timestamp: 1,
      traceId: 't-2',
    });

    const response = await client.get('/secure');

    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(refreshToken).toHaveBeenCalledWith();
    expect(applyTokenResponse).toHaveBeenCalledWith(refreshResponse);
    expect(clearSession).not.toHaveBeenCalled();
    expect(mock.history.get).toHaveLength(2);
    expect(response.data.data).toEqual({ ok: true });
  });

  test('attempts refresh exactly once when retried request still returns 401', async () => {
    const refreshToken = vi.fn().mockResolvedValue({
      accessToken: 'new-token',
      tokenType: 'Bearer',
      expiresIn: 7200,
    } satisfies TokenResponse);
    const client = createHttpClient({
      baseURL: 'http://localhost:8080',
      getAccessToken: vi.fn().mockReturnValue('old-token'),
      refreshToken,
      applyTokenResponse: vi.fn(),
      clearSession: vi.fn(),
    });
    const mock = new AxiosMockAdapter(client);

    mock.onGet('/secure').replyOnce(401).onGet('/secure').replyOnce(401);

    await expect(client.get('/secure')).rejects.toBeTruthy();
    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(mock.history.get).toHaveLength(2);
  });

  test('cookie refresh uses withCredentials and replays request once', async () => {
    const apiMock = new AxiosMockAdapter(httpClient);
    const rawMock = new AxiosMockAdapter(rawHttpClient);
    authSessionRef.value = {
      userId: 1,
      username: 'demo',
      roles: ['USER'],
      accessToken: createJwtToken({
        sub: 'demo',
        uid: 1,
        username: 'demo',
        roles: 'USER',
        iat: 2_100_000_000,
        exp: 2_100_007_200,
      }),
      tokenType: 'Bearer',
      expiresIn: 7200,
      expireAt: 2_100_007_200_000,
      jwtExp: 2_100_007_200,
    };

    rawMock.onPost('/api/auth/refresh').reply((config) => {
      expect(config.withCredentials).toBe(true);
      const requestData = typeof config.data === 'string' ? config.data : JSON.stringify(config.data ?? '');
      expect(requestData).not.toContain('refreshToken');
      expect(requestData).not.toContain('accessToken');
      return [
        200,
        {
          code: 200,
          message: 'success',
          data: {
            accessToken: createJwtToken({
              sub: 'demo',
              uid: 1,
              username: 'demo',
              roles: 'USER',
              iat: 2_100_000_100,
              exp: 2_100_007_300,
            }),
            tokenType: 'Bearer',
            expiresIn: 7200,
          },
          timestamp: 1,
          traceId: 't-refresh',
        },
      ];
    });

    apiMock.onGet('/secure').replyOnce(401).onGet('/secure').replyOnce(200, {
      code: 200,
      message: 'success',
      data: { ok: true },
      timestamp: 1,
      traceId: 't-secure',
    });

    const response = await httpClient.get('/secure');

    expect(rawMock.history.post).toHaveLength(1);
    expect(apiMock.history.get).toHaveLength(2);
    expect(response.data.data).toEqual({ ok: true });
  });

  test('clears session when refresh fails', async () => {
    const clearSession = vi.fn();
    const client = createHttpClient({
      baseURL: 'http://localhost:8080',
      getAccessToken: () => 'expired-token',
      refreshToken: vi.fn().mockRejectedValue(new Error('refresh failed')),
      applyTokenResponse: vi.fn(),
      clearSession,
    });
    const mock = new AxiosMockAdapter(client);

    mock.onGet('/secure').reply(401);

    await expect(client.get('/secure')).rejects.toBeTruthy();
    expect(clearSession).toHaveBeenCalledTimes(1);
  });
});
