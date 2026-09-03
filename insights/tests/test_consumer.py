"""Leg signing, the GL filter, and event handling.

The categoriser here is a stub, not the real trained pipeline -- categorisation correctness is
model.py's own concern (test_model.py); what matters in this file is that TransactionConsumer
calls it once per event and stores the right rows for the right legs, regardless of what it
returns.
"""

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

import pytest

from app.consumer import TransactionConsumer, _is_customer_account, _signed_amount


class _StubCategoriser:
    def __init__(self, category: str = "SHOPPING", confidence: float = 0.5) -> None:
        self.calls: list[str | None] = []
        self._category = category
        self._confidence = confidence

    def categorise(self, description):
        self.calls.append(description)
        return self._category, self._confidence


class _FakeStore:
    def __init__(self) -> None:
        self.upserts: list[dict] = []

    async def upsert_entry(self, **kwargs):
        self.upserts.append(kwargs)


def _deposit_event(*, reference="TXN-1", amount="500.00", description="swiggy dinner",
                    customer_account="100100000001", cash_account="GL0000000001"):
    return {
        "reference": reference,
        "type": "DEPOSIT",
        "amount": amount,
        "currency": "INR",
        "description": description,
        "postedAt": "2026-09-02T10:00:00Z",
        "legs": [
            {"accountNumber": cash_account, "direction": "DEBIT", "amount": amount,
             "balanceAfter": "0.00"},
            {"accountNumber": customer_account, "direction": "CREDIT", "amount": amount,
             "balanceAfter": amount},
        ],
    }


class TestSignedAmount:
    def test_credit_is_positive(self):
        assert _signed_amount("CREDIT", "500.00") == Decimal("500.00")

    def test_debit_is_negative(self):
        assert _signed_amount("DEBIT", "500.00") == Decimal("-500.00")


class TestIsCustomerAccount:
    def test_gl_prefixed_account_is_not_a_customer_account(self):
        assert not _is_customer_account("GL0000000001")

    def test_gl_prefix_check_is_case_insensitive(self):
        # CoreBank's own account numbers are upper-case, but nothing guarantees an event's JSON
        # was produced that way -- the check should not depend on it.
        assert not _is_customer_account("gl0000000001")

    def test_ordinary_account_number_is_a_customer_account(self):
        assert _is_customer_account("100100000001")


class TestHandle:
    @pytest.fixture
    def consumer(self):
        store = _FakeStore()
        categoriser = _StubCategoriser()
        return TransactionConsumer(
            bootstrap_servers="unused:9092", topic="unused", group_id="unused",
            store=store, categoriser=categoriser,
        ), store, categoriser

    async def test_only_the_customer_leg_is_stored(self, consumer):
        transaction_consumer, store, _ = consumer
        await transaction_consumer._handle(_deposit_event())

        assert len(store.upserts) == 1
        row = store.upserts[0]
        assert row["account_number"] == "100100000001"
        assert row["direction"] == "CREDIT"
        assert row["signed_amount"] == "500.00"

    async def test_gl_leg_is_dropped_not_double_counted(self, consumer):
        transaction_consumer, store, _ = consumer
        await transaction_consumer._handle(_deposit_event())

        assert not any(u["account_number"].startswith("GL") for u in store.upserts)

    async def test_categoriser_is_called_once_per_event_not_once_per_leg(self, consumer):
        # A transfer has two customer-facing legs on two different accounts but is one posting
        # with one description; categorising it twice would be wasted work, not wrong data, but
        # it's still worth pinning.
        transaction_consumer, store, categoriser = consumer
        transfer = {
            "reference": "TXN-2", "type": "TRANSFER", "amount": "100.00", "currency": "INR",
            "description": "upi payment to contact", "postedAt": "2026-09-02T10:00:00Z",
            "legs": [
                {"accountNumber": "100100000001", "direction": "DEBIT", "amount": "100.00",
                 "balanceAfter": "400.00"},
                {"accountNumber": "100100000002", "direction": "CREDIT", "amount": "100.00",
                 "balanceAfter": "100.00"},
            ],
        }
        await transaction_consumer._handle(transfer)

        assert categoriser.calls == ["upi payment to contact"]
        assert len(store.upserts) == 2
        by_account = {u["account_number"]: u for u in store.upserts}
        assert by_account["100100000001"]["signed_amount"] == "-100.00"
        assert by_account["100100000002"]["signed_amount"] == "100.00"

    async def test_category_and_confidence_are_stored_on_every_leg(self, consumer):
        transaction_consumer, store, categoriser = consumer
        categoriser._category, categoriser._confidence = "DINING", 0.87
        await transaction_consumer._handle(_deposit_event())

        assert store.upserts[0]["category"] == "DINING"
        assert store.upserts[0]["confidence"] == 0.87

    async def test_posted_at_is_parsed_from_the_zulu_timestamp(self, consumer):
        transaction_consumer, store, _ = consumer
        await transaction_consumer._handle(_deposit_event())

        assert store.upserts[0]["posted_at"] == datetime(2026, 9, 2, 10, 0, 0, tzinfo=timezone.utc)

    async def test_missing_description_still_stores_and_categorises(self, consumer):
        transaction_consumer, store, categoriser = consumer
        event = _deposit_event(description=None)
        await transaction_consumer._handle(event)

        assert categoriser.calls == [None]
        assert store.upserts[0]["description"] is None
