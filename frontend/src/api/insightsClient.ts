import type { InsightsProblem } from "./insightsTypes";

const INSIGHTS_BASE_URL = import.meta.env.VITE_INSIGHTS_API_BASE_URL ?? "http://localhost:8000/api/v1";

/**
 * A thin fetch wrapper for the spending-insights service -- deliberately not a reuse of
 * api/client.ts's apiFetch. It is a separate FastAPI application on its own origin, and its
 * error responses are FastAPI's plain `{"detail": "..."}` shape, not the Java API's RFC 7807
 * problem document, so wrapping it the same way would mean pretending to a shape it doesn't
 * return.
 */
export async function insightsFetch<T>(
  path: string,
  accessToken: string | undefined,
  params?: Record<string, string | number | undefined>,
): Promise<T> {
  const url = new URL(INSIGHTS_BASE_URL + path);
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value !== undefined) {
      url.searchParams.set(key, String(value));
    }
  }

  const headers: Record<string, string> = {};
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  const response = await fetch(url, { headers });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : undefined;

  if (!response.ok) {
    const detail = (payload as InsightsProblem | undefined)?.detail;
    throw new Error(detail ?? `Request failed with status ${response.status}`);
  }
  return payload as T;
}
