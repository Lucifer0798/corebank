// Mirrors the response records in com.corebank.*.dto. Kept as one file since the backend's
// OpenAPI document is the real contract; this is a thin, hand-written projection of it.

export type KycStatus = "PENDING" | "VERIFIED" | "REJECTED";
export type CustomerStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";
export type AccountType = "SAVINGS" | "CURRENT";
export type AccountStatus = "ACTIVE" | "FROZEN" | "CLOSED";
export type EntryDirection = "DEBIT" | "CREDIT";
export type TransactionType = "DEPOSIT" | "WITHDRAWAL" | "TRANSFER";

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface Customer {
  id: string;
  customerNumber: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string | null;
  dateOfBirth: string;
  kycStatus: KycStatus;
  status: CustomerStatus;
  identityLinked: boolean;
  createdAt: string;
}

export interface Account {
  id: string;
  accountNumber: string;
  customerId: string | null;
  accountType: AccountType;
  currency: string;
  balance: number;
  availableBalance: number;
  overdraftLimit: number;
  status: AccountStatus;
  openedAt: string;
  closedAt: string | null;
}

export interface Balance {
  accountNumber: string;
  currency: string;
  balance: number;
  availableBalance: number;
  asOf: string;
}

export interface TransactionLeg {
  accountId: string;
  accountNumber: string;
  direction: EntryDirection;
  amount: number;
  balanceAfter: number;
}

export interface Transaction {
  id: string;
  reference: string;
  type: TransactionType;
  status: "POSTED" | "REVERSED";
  amount: number;
  currency: string;
  description: string | null;
  postedAt: string;
  legs: TransactionLeg[];
}

export interface StatementLine {
  entryId: string;
  reference: string;
  type: TransactionType;
  direction: EntryDirection;
  signedAmount: number;
  balanceAfter: number;
  description: string | null;
  postedAt: string;
}

/** The shape every CoreBank error response takes -- an RFC 7807 problem document. */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  timestamp: string;
  errors?: Record<string, string>;
}
