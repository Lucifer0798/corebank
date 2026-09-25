import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "react-oidc-context";
import { apiFetch, newIdempotencyKey } from "./client";
import type {
  Account,
  Balance,
  Customer,
  CustomerSearchHit,
  KycStatus,
  PagedResponse,
  ScheduleFrequency,
  ScheduledTransfer,
  SearchResponse,
  StatementLine,
  Transaction,
  TransactionSearchHit,
  TransactionType,
} from "./types";

/** Binds the current access token to every call, so hooks below never touch auth directly. */
function useApi() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  return {
    get: <T,>(path: string, params?: Record<string, string | number | undefined>) =>
      apiFetch<T>(path, token, { params }),
    post: <T,>(path: string, body?: unknown, idempotencyKey?: string) =>
      apiFetch<T>(path, token, { method: "POST", body, idempotencyKey }),
    patch: <T,>(path: string, body?: unknown) =>
      apiFetch<T>(path, token, { method: "PATCH", body }),
  };
}

// ---------------------------------------------------------------------------------------------
// Customers
// ---------------------------------------------------------------------------------------------

export function useCustomers(page: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["customers", page],
    queryFn: () => api.get<PagedResponse<Customer>>("/customers", { page, size: 20 }),
  });
}

export function useCustomer(customerId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["customer", customerId],
    queryFn: () => api.get<Customer>(`/customers/${customerId}`),
    enabled: Boolean(customerId),
  });
}

/** For a CUSTOMER-role login: resolves the customer record its identity has been linked to.
 * 404s until staff complete that link -- callers should treat that as "not set up yet". */
export function useMyCustomer() {
  const api = useApi();
  return useQuery({
    queryKey: ["customer", "me"],
    queryFn: () => api.get<Customer>("/customers/me"),
    retry: false,
  });
}

export interface CreateCustomerInput {
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  dateOfBirth: string;
}

export function useCreateCustomer() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateCustomerInput) => api.post<Customer>("/customers", input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["customers"] }),
  });
}

export function useUpdateKyc(customerId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (kycStatus: KycStatus) =>
      api.patch<Customer>(`/customers/${customerId}/kyc`, { kycStatus }),
    onSuccess: (customer) => {
      queryClient.setQueryData(["customer", customerId], customer);
      queryClient.invalidateQueries({ queryKey: ["customers"] });
    },
  });
}

export function useLinkIdentity(customerId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (keycloakSubject: string) =>
      api.patch<Customer>(`/customers/${customerId}/identity`, { keycloakSubject }),
    onSuccess: (customer) => {
      queryClient.setQueryData(["customer", customerId], customer);
      queryClient.invalidateQueries({ queryKey: ["customers"] });
    },
  });
}

// ---------------------------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------------------------

export function useAccountsForCustomer(customerId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["accounts", "byCustomer", customerId],
    queryFn: () =>
      api.get<PagedResponse<Account>>(`/customers/${customerId}/accounts`, { size: 50 }),
    enabled: Boolean(customerId),
  });
}

export function useAccount(accountId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["account", accountId],
    queryFn: () => api.get<Account>(`/accounts/${accountId}`),
    enabled: Boolean(accountId),
  });
}

export function useBalance(accountId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["balance", accountId],
    queryFn: () => api.get<Balance>(`/accounts/${accountId}/balance`),
    enabled: Boolean(accountId),
    // A posting invalidates this explicitly; a short refetch interval catches anything it missed.
    refetchInterval: 15_000,
  });
}

export interface OpenAccountInput {
  customerId: string;
  accountType: "SAVINGS" | "CURRENT";
  currency?: string;
  overdraftLimit?: number;
}

export function useOpenAccount() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: OpenAccountInput) => api.post<Account>("/accounts", input),
    onSuccess: (account) =>
      queryClient.invalidateQueries({ queryKey: ["accounts", "byCustomer", account.customerId] }),
  });
}

function useAccountStatusMutation(action: "freeze" | "unfreeze" | "close") {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (accountId: string) => api.post<Account>(`/accounts/${accountId}/${action}`),
    onSuccess: (account) => {
      queryClient.setQueryData(["account", account.id], account);
      queryClient.invalidateQueries({ queryKey: ["accounts", "byCustomer", account.customerId] });
    },
  });
}

export const useFreezeAccount = () => useAccountStatusMutation("freeze");
export const useUnfreezeAccount = () => useAccountStatusMutation("unfreeze");
export const useCloseAccount = () => useAccountStatusMutation("close");

// ---------------------------------------------------------------------------------------------
// Transactions
// ---------------------------------------------------------------------------------------------

export interface AmountInput {
  amount: number;
  currency?: string;
  description?: string;
}

function invalidateAfterPosting(queryClient: ReturnType<typeof useQueryClient>, accountIds: string[]) {
  for (const id of accountIds) {
    queryClient.invalidateQueries({ queryKey: ["account", id] });
    queryClient.invalidateQueries({ queryKey: ["balance", id] });
    queryClient.invalidateQueries({ queryKey: ["statement", id] });
  }
}

export function useDeposit(accountId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: AmountInput) =>
      api.post<Transaction>(`/accounts/${accountId}/deposits`, input, newIdempotencyKey()),
    onSuccess: () => invalidateAfterPosting(queryClient, [accountId]),
  });
}

export function useWithdraw(accountId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: AmountInput) =>
      api.post<Transaction>(`/accounts/${accountId}/withdrawals`, input, newIdempotencyKey()),
    onSuccess: () => invalidateAfterPosting(queryClient, [accountId]),
  });
}

export interface TransferInput {
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  currency?: string;
  description?: string;
}

export function useTransfer() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TransferInput) =>
      api.post<Transaction>("/transfers", input, newIdempotencyKey()),
    onSuccess: (_, input) =>
      invalidateAfterPosting(queryClient, [input.sourceAccountId, input.destinationAccountId]),
  });
}

export function useStatement(accountId: string | undefined, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["statement", accountId, page],
    queryFn: () =>
      api.get<PagedResponse<StatementLine>>(`/accounts/${accountId}/transactions`, {
        page,
        size: 20,
      }),
    enabled: Boolean(accountId),
  });
}

export function useTransaction(reference: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ["transaction", reference],
    queryFn: () => api.get<Transaction>(`/transactions/${reference}`),
    enabled: Boolean(reference),
  });
}

export interface ReversalInput {
  /** Required by the backend, and not merely as paperwork -- it becomes the reversal's description. */
  reason: string;
}

/**
 * Undoes a posting. ADMIN only; a TELLER token gets the backend's own 403.
 *
 * Two caches go stale at once, which is the whole reason this does not just invalidate by
 * reference: the *original* is now REVERSED, and every account the mirrored legs touched has a
 * new balance. The reversal's own legs name exactly those accounts -- including the GL cash leg,
 * whose keys simply aren't cached -- so they are the right thing to invalidate over rather than
 * an account id guessed at the call site.
 */
export function useReverseTransaction(reference: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ReversalInput) =>
      api.post<Transaction>(`/transactions/${reference}/reversal`, input, newIdempotencyKey()),
    onSuccess: (reversal) => {
      queryClient.invalidateQueries({ queryKey: ["transaction", reference] });
      queryClient.setQueryData(["transaction", reversal.reference], reversal);
      invalidateAfterPosting(queryClient, reversal.legs.map((leg) => leg.accountId));
      // Search is fed asynchronously off Kafka, so this only clears what was already fetched --
      // a result list can still lag the ledger by however long the outbox relay takes.
      queryClient.invalidateQueries({ queryKey: ["search", "transactions"] });
    },
  });
}

// ---------------------------------------------------------------------------------------------
// Scheduled transfers -- standing instructions, posted by a background runner. The list is
// account-scoped and guarded by the same ownership rule as the statement, so a customer reads
// their own; creating and cancelling are staff operations.
// ---------------------------------------------------------------------------------------------

export function useScheduledTransfers(accountId: string | undefined, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["scheduledTransfers", accountId, page],
    queryFn: () =>
      api.get<PagedResponse<ScheduledTransfer>>(`/accounts/${accountId}/scheduled-transfers`, {
        page,
        size: 20,
      }),
    enabled: Boolean(accountId),
  });
}

export interface ScheduledTransferInput {
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  currency?: string;
  description?: string;
  frequency: ScheduleFrequency;
  startsOn: string;
  endsOn?: string;
}

/**
 * No Idempotency-Key, and the endpoint wants none: a duplicate request here leaves a second
 * visible mandate that can be cancelled rather than moving money twice. The occurrences it goes
 * on to post are each idempotent under a key the backend derives.
 */
export function useCreateScheduledTransfer() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ScheduledTransferInput) =>
      api.post<ScheduledTransfer>("/scheduled-transfers", input),
    onSuccess: (schedule) => invalidateSchedules(queryClient, schedule),
  });
}

export function useCancelScheduledTransfer() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (scheduleId: string) =>
      api.post<ScheduledTransfer>(`/scheduled-transfers/${scheduleId}/cancel`),
    onSuccess: (schedule) => invalidateSchedules(queryClient, schedule),
  });
}

/**
 * A mandate is listed against both accounts it names, so both lists go stale together -- the page
 * showing only one of them would otherwise keep displaying a cancelled instruction as live.
 */
function invalidateSchedules(
  queryClient: ReturnType<typeof useQueryClient>,
  schedule: ScheduledTransfer,
) {
  for (const id of [schedule.sourceAccountId, schedule.destinationAccountId]) {
    queryClient.invalidateQueries({ queryKey: ["scheduledTransfers", id] });
  }
}

// ---------------------------------------------------------------------------------------------
// Search -- bank-wide, cross-account (OpenSearch-backed). Staff only; see SearchController.
// ---------------------------------------------------------------------------------------------

export interface TransactionSearchFilters {
  q?: string;
  type?: TransactionType;
  minAmount?: number;
  maxAmount?: number;
  from?: string;
  to?: string;
}

export function useTransactionSearch(filters: TransactionSearchFilters, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["search", "transactions", filters, page],
    queryFn: () =>
      api.get<SearchResponse<TransactionSearchHit>>("/search/transactions", {
        ...filters,
        page,
        size: 20,
      }),
  });
}

export function useCustomerSearch(q: string, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: ["search", "customers", q, page],
    queryFn: () => api.get<SearchResponse<CustomerSearchHit>>("/search/customers", { q, page, size: 20 }),
  });
}
