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

export type HoldStatus = "ACTIVE" | "CAPTURED" | "RELEASED" | "EXPIRED" | "CAPTURE_REVERSED";

/** Mirrors com.corebank.account.dto.HoldResponse. */
export interface Hold {
  id: string;
  reference: string;
  accountId: string;
  amount: number;
  currency: string;
  description: string | null;
  status: HoldStatus;
  placedAt: string;
  expiresAt: string;
  settledAt: string | null;
  /** The posting a capture produced; null on anything that moved no money. */
  capturedTransactionReference: string | null;
  /** The reversal that undid this hold's capture, if one did. */
  captureReversalReference: string | null;
  /** Who placed it; null on holds from before this was recorded. */
  placedBy: { subject: string; name: string | null } | null;
  /** Who captured or released it, or system:hold-expiry; null while outstanding. */
  settledBy: { subject: string; name: string | null } | null;
}

/** Mirrors com.corebank.customer.dto.IdentityLinkResponse. History starts when it was introduced. */
export interface IdentityLink {
  id: string;
  /** The login this replaced; null on a first link. */
  previousSubject: string | null;
  newSubject: string;
  linkedBySubject: string;
  linkedByName: string | null;
  /** Always present on a relink. */
  reason: string | null;
  linkedAt: string;
}

/** Mirrors com.corebank.account.dto.AccountStatusChangeResponse. History starts when it was introduced. */
export interface AccountStatusChange {
  id: string;
  fromStatus: AccountStatus;
  toStatus: AccountStatus;
  changedBySubject: string;
  changedByName: string | null;
  /** Always present on a freeze or a closure. */
  reason: string | null;
  changedAt: string;
}

/** Mirrors com.corebank.customer.dto.KycDecisionResponse. History starts when it was introduced. */
export interface KycDecision {
  id: string;
  fromStatus: KycStatus;
  toStatus: KycStatus;
  /** The decider's token subject, or `system:<process>` for a non-human one. */
  decidedBySubject: string;
  decidedByName: string | null;
  /** Always present on a move away from VERIFIED. */
  reason: string | null;
  decidedAt: string;
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
  /** This leg's own currency -- its account's. On an FX transfer, not the transaction's for half the legs. */
  currency: string;
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
  /** Set only on a cross-currency transfer: the rate applied and what the amount became. */
  fx: { exchangeRate: number; counterAmount: number; counterCurrency: string } | null;
  /** Who made it: a member of staff, or `system:<job>`. Null on postings from before this was recorded. */
  initiatedBy: { subject: string; name: string | null } | null;
}

/** Mirrors com.corebank.fx.service.FxRateService.Quote. */
export interface FxQuote {
  fromCurrency: string;
  toCurrency: string;
  fromAmount: number;
  toAmount: number;
  /** After the bank's spread: what the transfer would actually use. */
  rateApplied: number;
  midRate: number;
  spreadBps: number;
  asOf: string;
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
  /** Who set it up. Null on instructions from before this was recorded. */
  createdBy: { subject: string; name: string | null } | null;
}

/** Mirrors com.corebank.schedule.dto.ScheduledTransferEventResponse. */
export interface ScheduledTransferEvent {
  id: string;
  event: "CANCELLED" | "RESUMED" | "SUSPENDED";
  /** A member of staff, or system:scheduled-transfer-runner for a suspension. */
  by: { subject: string; name: string | null };
  reason: string | null;
  occurredAt: string;
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
