import AxiosMockAdapter from 'axios-mock-adapter';
import type { TokenResponse } from '@/types/auth';
import { createHttpClient } from '@/lib/http';

describe('http client', () => {
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

    expect(refreshToken).toHaveBeenCalledWith('old-token');
    expect(applyTokenResponse).toHaveBeenCalledWith(refreshResponse);
    expect(clearSession).not.toHaveBeenCalled();
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
