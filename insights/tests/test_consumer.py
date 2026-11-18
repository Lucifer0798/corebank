"""Leg signing, the GL filter, and event handling.

The categoriser here is a stub, not the real trained pipeline -- categorisation correctness is
model.py's own concern (test_model.py); what matters in this file is that TransactionConsumer
calls it once per event and stores the right rows for the right legs, regardless of what it
returns.
"""

from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from decimal import Decimal

import asyncpg
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

    async def test_each_leg_is_stored_in_its_own_currency(self, consumer):
        # An INR->USD transfer: the transaction's currency is the sender's. Stamping every leg with
        # it recorded the dollar account's credit as rupees.
        transaction_consumer, store, _ = consumer
        await transaction_consumer._handle({
            "reference": "TXN-FX", "type": "TRANSFER", "amount": "10000.00", "currency": "INR",
            "description": "to USD", "postedAt": "2026-10-25T10:00:00Z",
            "legs": [
                {"accountNumber": "100100000001", "direction": "DEBIT", "amount": "10000.00",
                 "currency": "INR", "balanceAfter": "0.00"},
                {"accountNumber": "GL0000000010", "direction": "CREDIT", "amount": "10000.00",
                 "currency": "INR", "balanceAfter": "0.00"},
                {"accountNumber": "GL0000000011", "direction": "DEBIT", "amount": "119.40",
                 "currency": "USD", "balanceAfter": "0.00"},
                {"accountNumber": "100100000002", "direction": "CREDIT", "amount": "119.40",
                 "currency": "USD", "balanceAfter": "119.40"},
            ],
        })

        by_account = {row["account_number"]: row["currency"] for row in store.upserts}
        assert by_account == {"100100000001": "INR", "100100000002": "USD"}

    async def test_a_leg_without_a_currency_falls_back_to_the_transactions(self, consumer):
        # Messages from before legs carried a currency. Every one of those was single-currency --
        # cross-currency transfers were the reason the field was added -- so the transaction's own
        # currency is right for them.
        transaction_consumer, store, _ = consumer
        await transaction_consumer._handle(_deposit_event())

        assert store.upserts[0]["currency"] == "INR"


class _Message:
    def __init__(self, value: bytes, offset: int) -> None:
        self.value = value
        self.offset = offset
        self.topic = "corebank.transactions.posted"
        self.partition = 0


class _FakeKafka:
    """Stands in for AIOKafkaConsumer in _run: yields the given messages, records each commit."""

    def __init__(self, messages, journal: list) -> None:
        self._messages = messages
        self._journal = journal
        self._position = None

    def __aiter__(self):
        return self._iterate()

    async def _iterate(self):
        for message in self._messages:
            self._position = message.offset
            yield message

    async def commit(self):
        self._journal.append(("commit", self._position))

    async def stop(self):
        self._journal.append(("stopped", None))


class _JournalStore:
    """Records each stored reference in the shared journal; fails the first `failures` times."""

    def __init__(self, journal: list, failures=0, error=None) -> None:
        self._journal = journal
        self._failures = failures
        self._error = error

    async def upsert_entry(self, **row):
        if self._failures:
            self._failures -= 1
            raise self._error
        self._journal.append(("stored", row["reference"]))


def _encoded(reference: str) -> bytes:
    return json.dumps(_deposit_event(reference=reference)).encode()


class TestRun:
    """What _run does with each message: store it, skip it, or wait for the database."""

    @pytest.fixture
    def journal(self):
        return []

    @pytest.fixture
    def pauses(self):
        return []

    def _consumer(self, store, pauses):
        async def record_pause(seconds):
            pauses.append(seconds)
            # A consumer that retries something for ever would otherwise hang the test run.
            if len(pauses) > 50:
                raise AssertionError(f"still retrying after {len(pauses)} pauses")

        return TransactionConsumer(bootstrap_servers="unused:9092", topic="unused", group_id="unused",
                                   store=store, categoriser=_StubCategoriser(), sleep=record_pause)

    async def _run(self, consumer, messages, journal):
        consumer._consumer = _FakeKafka(messages, journal)
        await consumer._run()

    async def test_a_malformed_message_is_skipped_and_the_next_stored(self, journal, pauses):
        # It used to end the consuming task, and every restart ended it again on the same message.
        consumer = self._consumer(_JournalStore(journal), pauses)
        await self._run(consumer, [_Message(_encoded("TXN-A"), 0), _Message(b"not json {", 1),
                                   _Message(_encoded("TXN-B"), 2)], journal)

        assert [ref for kind, ref in journal if kind == "stored"] == ["TXN-A", "TXN-B"]
        assert [offset for kind, offset in journal if kind == "commit"] == [0, 1, 2]
        assert pauses == []

    async def test_bytes_that_are_not_even_text_are_skipped(self, journal, pauses):
        consumer = self._consumer(_JournalStore(journal), pauses)
        await self._run(consumer, [_Message(bytes([0xFF, 0xFE, 0x00]), 0), _Message(_encoded("TXN-B"), 1)], journal)

        assert [ref for kind, ref in journal if kind == "stored"] == ["TXN-B"]

    @pytest.mark.parametrize("error", [
        asyncpg.exceptions.ConnectionDoesNotExistError("connection was closed in the middle of operation"),
        asyncpg.exceptions.CannotConnectNowError("the database system is starting up"),
        asyncpg.exceptions.TooManyConnectionsError("sorry, too many clients already"),
        ConnectionRefusedError("[Errno 111] Connection refused"),
        TimeoutError(),
    ], ids=["connection-lost", "starting-up", "too-many-connections", "refused", "timeout"])
    async def test_a_database_outage_is_waited_out_not_skipped(self, journal, pauses, error):
        # It used to be logged and skipped, and with auto-commit on, the row was simply gone.
        consumer = self._consumer(_JournalStore(journal, failures=3, error=error), pauses)
        await self._run(consumer, [_Message(_encoded("TXN-A"), 0)], journal)

        assert pauses == [1, 2, 4]
        # Stored once it could be, and only then committed.
        assert journal == [("stored", "TXN-A"), ("commit", 0)]

    async def test_the_pause_doubles_and_stops_growing_at_a_minute(self, journal, pauses):
        consumer = self._consumer(_JournalStore(journal, failures=9, error=ConnectionResetError()), pauses)
        await self._run(consumer, [_Message(_encoded("TXN-A"), 0)], journal)

        assert pauses == [1, 2, 4, 8, 16, 32, 60, 60, 60]
        assert journal == [("stored", "TXN-A"), ("commit", 0)]

    async def test_an_event_missing_what_it_needs_is_skipped_not_retried(self, journal, pauses):
        # The same failure every time: waiting would hold up every event behind it for nothing.
        consumer = self._consumer(_JournalStore(journal), pauses)
        no_timestamp = json.dumps({"reference": "TXN-BAD", "legs": []}).encode()
        await self._run(consumer, [_Message(no_timestamp, 0), _Message(_encoded("TXN-B"), 1)], journal)

        assert pauses == []
        assert [ref for kind, ref in journal if kind == "stored"] == ["TXN-B"]
        assert [offset for kind, offset in journal if kind == "commit"] == [0, 1]

    async def test_stopping_after_the_consumer_died_does_not_raise(self, journal, pauses):
        consumer = self._consumer(_JournalStore(journal), pauses)

        async def died():
            raise RuntimeError("the broker went away")

        consumer._consumer = _FakeKafka([], journal)
        consumer._task = asyncio.create_task(died())
        await asyncio.sleep(0)

        assert not consumer.is_running
        await consumer.stop()
        assert ("stopped", None) in journal
