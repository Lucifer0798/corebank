// Mirrors the response records in com.corebank.*.dto. Kept as one file since the backend's
// OpenAPI document is the real contract; this is a thin, hand-written projection of it.

export type KycStatus = "PENDING" | "VERIFIED" | "REJECTED";
export type CustomerStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";
export type AccountType = "SAVINGS" | "CURRENT";
export type AccountStatus = "ACTIVE" | "FROZEN" | "CLOSED";
export type EntryDirection = "DEBIT" | "CREDIT";
// Mirrors com.corebank.transaction.domain.TransactionType. INTEREST (#35) and FX_REVALUATION
// (#37) were missing for a while: statements already showed INTEREST lines, and once system
// postings started reaching Kafka they could appear in search too.
export type TransactionType =
  | "DEPOSIT" | "WITHDRAWAL" | "TRANSFER" | "REVERSAL" | "INTEREST" | "FX_REVALUATION";

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
  /** Reserved by outstanding authorisation holds; already subtracted from availableBalance. */
  heldAmount: number;
  overdraftLimit: number;
  /** Interest earned but not yet paid; not part of the balance until capitalised. */
  accruedInterest: number;
  status: AccountStatus;
  openedAt: string;
  closedAt: string | null;
}

export interface Balance {
  accountNumber: string;
  currency: string;
  balance: number;
  availableBalance: number;
  heldAmount: number;
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
  /** Set only on a REVERSAL: the reference of the posting it undoes. */
  reversalOf: string | null;
  legs: TransactionLeg[];
}

export type ScheduleFrequency = "ONCE" | "DAILY" | "WEEKLY" | "MONTHLY";
export type ScheduleStatus = "ACTIVE" | "SUSPENDED" | "COMPLETED" | "CANCELLED";

export interface ScheduledTransfer {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  currency: string;
  description: string | null;
  frequency: ScheduleFrequency;
  /** Calendar dates (YYYY-MM-DD), not instants -- see formatCalendarDate. */
  startsOn: string;
  endsOn: string | null;
  status: ScheduleStatus;
  /** Null once nothing further is due. */
  nextRunOn: string | null;
  runsCompleted: number;
  consecutiveFailures: number;
  lastRunOn: string | null;
  lastError: string | null;
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

/**
 * Mirrors com.corebank.notification.dto.NotificationResponse. One per customer account a posting
 * touched, written by the Kafka consumer -- so a fresh posting can take a moment to appear here.
 */
export type NotificationKind =
  | "TRANSACTION" | "SCHEDULED_TRANSFER_FAILED" | "SCHEDULED_TRANSFER_SUSPENDED";

export interface Notification {
  id: string;
  /** TRANSACTION carries the posting fields; the other two carry the schedule fields instead. */
  kind: NotificationKind;
  accountId: string;
  transactionReference: string | null;
  /** REVERSED rows are a second notification about the same posting, not a replacement. */
  transactionStatus: "POSTED" | "REVERSED" | null;
  direction: EntryDirection | null;
  scheduledTransferId: string | null;
  /** The date a missed standing-instruction payment was due -- a calendar date, no time. */
  dueOn: string | null;
  amount: number;
  currency: string;
  message: string;
  createdAt: string;
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

// -------------------------------------------------------------------------------------------
// Search (OpenSearch-backed, bank-wide -- see com.corebank.search.dto)
// -------------------------------------------------------------------------------------------

/** Not PagedResponse: OpenSearch results never come from a Spring Data Page. */
export interface SearchResponse<T> {
  hits: T[];
  totalHits: number;
  page: number;
  size: number;
}

export interface TransactionSearchHit {
  reference: string;
  type: TransactionType;
  /**
   * Null on a document indexed before status existed -- read it as POSTED, the same rule the
   * indexer applies. A replay from the admin endpoint fills it in.
   */
  status: "POSTED" | "REVERSED" | null;
  amount: number;
  currency: string;
  description: string | null;
  postedAt: string;
  accountNumbers: string[];
}

export interface CustomerSearchHit {
  id: string;
  customerNumber: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string | null;
  kycStatus: KycStatus;
  status: CustomerStatus;
}
