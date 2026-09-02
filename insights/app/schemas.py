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
    spent: str = Field(description="Positive: how much left the account in this category")
    entries: int


class SpendingSummary(BaseModel):
    customer_id: str
    currency: str
    total_spent: str
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
