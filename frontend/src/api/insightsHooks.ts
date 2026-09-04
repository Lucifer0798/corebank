import { useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "react-oidc-context";
import { insightsFetch } from "./insightsClient";
import type { CategorisePreview, RecentEntry, SpendingSummary } from "./insightsTypes";

function useInsightsApi() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  return {
    get: <T,>(path: string, params?: Record<string, string | number | undefined>) =>
      insightsFetch<T>(path, token, params),
  };
}

export function useSpendingSummary(customerId: string | undefined, since?: string, until?: string) {
  const api = useInsightsApi();
  return useQuery({
    queryKey: ["insights", "summary", customerId, since, until],
    queryFn: () =>
      api.get<SpendingSummary>(`/insights/customers/${customerId}/summary`, { since, until }),
    enabled: Boolean(customerId),
    // This service's whole point is a downstream projection with no delivery guarantee on
    // when a just-posted transaction shows up here -- a short poll surfaces that catch-up
    // without the customer needing to reload.
    refetchInterval: 30_000,
  });
}

export function useRecentEntries(customerId: string | undefined, limit = 20) {
  const api = useInsightsApi();
  return useQuery({
    queryKey: ["insights", "entries", customerId, limit],
    queryFn: () => api.get<RecentEntry[]>(`/insights/customers/${customerId}/entries`, { limit }),
    enabled: Boolean(customerId),
  });
}

/** A staff-only "try the categoriser" tool -- modelled as a mutation, not a query, since it
 * only ever runs on demand against text the user just typed, never on mount. */
export function useCategorisePreview() {
  const api = useInsightsApi();
  return useMutation({
    mutationFn: (description: string) => api.get<CategorisePreview>("/insights/categorise", { description }),
  });
}
