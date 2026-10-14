import type { Problem } from './types';

/** An API call that came back with a problem document (or no usable response at all). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(status: number, problem: Partial<Problem>) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = problem.code;
    this.fieldErrors = problem.errors ?? [];
  }

  /** Worth retrying with the same idempotency key: the outcome is unknown, not a refusal. */
  get isTransient(): boolean {
    return this.status === 0 || this.status === 429 || this.status >= 502;
  }
}

type TokenSource = () => string | null;

let tokenSource: TokenSource = () => null;
let onUnauthorized: () => void = () => {};

/** Wired up by the auth context so the client never imports React state directly. */
export function configureAuth(source: TokenSource, unauthorizedHandler: () => void) {
  tokenSource = source;
  onUnauthorized = unauthorizedHandler;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface ApiResponse<T> {
  data: T;
  headers: Headers;
  status: number;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const token = tokenSource();
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new ApiError(0, { detail: 'Could not reach the server. Check your connection and try again.' });
  }

  if (response.status === 401 && token) {
    onUnauthorized();
  }
  if (!response.ok) {
    throw new ApiError(response.status, await readProblem(response));
  }
  const data = response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  return { data, headers: response.headers, status: response.status };
}

export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  return (await request<T>(path, { signal })).data;
}

async function readProblem(response: Response): Promise<Partial<Problem>> {
  try {
    const body = (await response.json()) as Partial<Problem> & { error?: string };
    return { ...body, detail: body.detail ?? body.error };
  } catch {
    return { title: response.statusText };
  }
}

/** Builds a query string, skipping empty values. */
export function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}
