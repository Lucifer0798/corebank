import { ApiError } from "../api/client";

/** Renders any caught error, unwrapping an ApiError to the backend's own problem detail message. */
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) {
    return null;
  }
  const message = error instanceof ApiError ? error.problem.detail : (error as Error).message;
  return <div className="error-banner">{message ?? "Something went wrong."}</div>;
}

export function fieldErrors(error: unknown): Record<string, string> | undefined {
  return error instanceof ApiError ? error.problem.errors : undefined;
}
