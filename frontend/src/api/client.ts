import type { ProblemDetail } from "./types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

/** Thrown for every non-2xx response, carrying the backend's RFC 7807 problem document intact. */
export class ApiError extends Error {
  readonly problem: ProblemDetail;
  readonly status: number;

  constructor(problem: ProblemDetail, status: number) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.name = "ApiError";
    this.problem = problem;
    this.status = status;
  }
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  /** Required by the backend on every money-moving POST; generate a fresh one per user action. */
  idempotencyKey?: string;
  params?: Record<string, string | number | undefined>;
}

/**
 * A thin fetch wrapper: attaches the bearer token, serialises the body, and turns any non-2xx
 * response into an {@link ApiError} carrying the backend's problem document rather than a bare
 * fetch Response. Built as a plain function taking the token explicitly -- rather than reading
 * it from context internally -- so it works equally from a React Query hook or a one-off call.
 */
export async function apiFetch<T>(
  path: string,
  accessToken: string | undefined,
  options: RequestOptions = {},
): Promise<T> {
  const url = new URL(API_BASE_URL + path);
  for (const [key, value] of Object.entries(options.params ?? {})) {
    if (value !== undefined) {
      url.searchParams.set(key, String(value));
    }
  }

  const headers: Record<string, string> = {};
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (options.idempotencyKey) {
    headers["Idempotency-Key"] = options.idempotencyKey;
  }

  const response = await fetch(url, {
    method: options.method ?? "GET",
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload = text ? JSON.parse(text) : undefined;

  if (!response.ok) {
    throw new ApiError(payload as ProblemDetail, response.status);
  }
  return payload as T;
}

/** A per-user-action idempotency key. Callers should generate exactly one and reuse it on retry. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
