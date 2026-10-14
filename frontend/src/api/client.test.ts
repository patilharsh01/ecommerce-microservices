import { ApiError, configureAuth, query, request } from './client';

function respond(status: number, body: unknown, headers: Record<string, string> = {}) {
  return vi.fn().mockResolvedValue(new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  }));
}

describe('request', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    configureAuth(() => null, () => {});
  });

  it('sends the bearer token and returns data with headers', async () => {
    const fetchMock = respond(200, { ok: true }, { 'Idempotent-Replayed': 'true' });
    vi.stubGlobal('fetch', fetchMock);
    configureAuth(() => 'token-123', () => {});

    const response = await request<{ ok: boolean }>('/orders', { method: 'POST', body: { a: 1 } });

    expect(response.data).toEqual({ ok: true });
    expect(response.headers.get('Idempotent-Replayed')).toBe('true');
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/api/orders');
    expect(init.headers.Authorization).toBe('Bearer token-123');
    expect(init.headers['Content-Type']).toBe('application/json');
  });

  it('turns a problem document into an ApiError with code and field errors', async () => {
    vi.stubGlobal('fetch', respond(400, {
      status: 400, detail: 'Request validation failed', code: 'VALIDATION_FAILED',
      errors: [{ field: 'items', message: 'an order needs at least one item' }],
    }));

    const error = await request('/orders').catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 400, code: 'VALIDATION_FAILED', message: 'Request validation failed' });
    expect((error as ApiError).fieldErrors).toHaveLength(1);
    expect((error as ApiError).isTransient).toBe(false);
  });

  it('signs the user out when a token is rejected', async () => {
    vi.stubGlobal('fetch', respond(401, { status: 401, code: 'UNAUTHORIZED' }));
    const onUnauthorized = vi.fn();
    configureAuth(() => 'expired', onUnauthorized);

    await expect(request('/orders')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('reports network failures as transient', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    const error = (await request('/orders').catch((caught: unknown) => caught)) as ApiError;

    expect(error.status).toBe(0);
    expect(error.isTransient).toBe(true);
  });
});

describe('query', () => {
  it('skips empty values', () => {
    expect(query({ q: '', page: 0, scope: 'all', status: undefined })).toBe('?page=0&scope=all');
    expect(query({})).toBe('');
  });
});
