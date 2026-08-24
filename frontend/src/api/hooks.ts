import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "react-oidc-context";
import { apiFetch, newIdempotencyKey } from "./client";
import type {
  Account,
  Balance,
  Customer,
  KycStatus,
  PagedResponse,
  StatementLine,
  Transaction,
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
