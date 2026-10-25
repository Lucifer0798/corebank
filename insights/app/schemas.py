"""Response models.

Money is a string here for the same reason it is in corebank.proto: these values come from
NUMERIC(19,4) columns, and rendering them as JSON floats would reintroduce the binary
floating-point error the ledger is built to avoid.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field


class CategorySpend(BaseModel):
    category: str
    currency: str
    spent: str = Field(description="Positive: how much left the accounts in this category, in this currency")
    entries: int


class CurrencyTotal(BaseModel):
    currency: str
    spent: str


class SpendingSummary(BaseModel):
    """One total per currency, never a single figure: a customer can hold accounts in several, and
    a single total used to add them together and label the result INR."""

    customer_id: str
    totals: list[CurrencyTotal] = Field(description="Spend per currency, in currency-code order")
    categories: list[CategorySpend]
    accounts: list[str] = Field(description="Account numbers the summary was built from")


class RecentEntry(BaseModel):
    reference: str
    account_number: str
    category: str
    confidence: float
    signed_amount: str = Field(description="Negative when the entry reduced this account")
    currency: str
    description: str | None
    posted_at: datetime


class CategorisePreview(BaseModel):
    description: str
    category: str
    confidence: float


class HealthResponse(BaseModel):
    status: str
    categories: list[str]
    entries_indexed: int
