// Mirrors insights/app/schemas.py. A separate file from types.ts on purpose: this is a genuinely
// different backend (FastAPI, its own origin/port), and its wire shape is snake_case where the
// Java API's is camelCase -- keeping them apart avoids either service's convention leaking into
// the other's types.

export interface CategorySpend {
  category: string;
  spent: string;
  entries: number;
}

export interface SpendingSummary {
  customer_id: string;
  currency: string;
  total_spent: string;
  categories: CategorySpend[];
  accounts: string[];
}

export interface RecentEntry {
  reference: string;
  account_number: string;
  category: string;
  confidence: number;
  signed_amount: string;
  currency: string;
  description: string | null;
  posted_at: string;
}

export interface CategorisePreview {
  description: string;
  category: string;
  confidence: number;
}

/** FastAPI's default error body -- not an RFC 7807 problem document like the Java API's. */
export interface InsightsProblem {
  detail: string;
}
