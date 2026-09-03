"""Store against a real Postgres, not a mock.

This is where the money-adjacent logic actually lives: the upsert key that makes a redelivered
Kafka message idempotent, the sign convention the summary query depends on, and the
create-database-on-missing-catalog path that lets this service share an existing CoreBank
Postgres without an init script. A mock would happily let any of the three be wrong.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from testcontainers.community.postgres import PostgresContainer

from app.store import Store


@pytest.fixture(scope="session")
def postgres_container():
    with PostgresContainer("postgres:17-alpine", username="corebank", password="corebank",
                           dbname="corebank") as container:
        yield container


def _dsn(container: PostgresContainer, dbname: str) -> str:
    # Not get_connection_url(): that returns a SQLAlchemy-style `postgresql+psycopg2://` URL,
    # and asyncpg (what store.py actually uses) wants a bare `postgresql://` DSN.
    return (
        f"postgresql://{container.username}:{container.password}"
        f"@{container.get_container_host_ip()}:{container.get_exposed_port(5432)}/{dbname}"
    )


@pytest.fixture
async def store(postgres_container):
    # A fresh, uniquely-named database per test rather than one shared database: tests run
    # concurrently would otherwise see each other's rows, and TRUNCATE-between-tests is more
    # machinery for the same result CREATE/DROP DATABASE gives for free via the code under test.
    import uuid
    name = f"insights_test_{uuid.uuid4().hex[:12]}"
    bootstrap_dsn = _dsn(postgres_container, "corebank")
    test_dsn = _dsn(postgres_container, name)

    instance = await Store.connect(test_dsn, bootstrap_dsn=bootstrap_dsn)
    yield instance
    await instance.close()


class TestConnect:
    async def test_creates_the_database_when_it_does_not_exist_yet(self, postgres_container):
        # The exact scenario store.py exists to handle: an insights database that has never been
        # created, alongside a CoreBank Postgres that has. Confirmed rather than assumed --
        # Store.connect was written to do this but had never actually been exercised against a
        # database guaranteed absent until this test.
        import uuid
        name = f"insights_fresh_{uuid.uuid4().hex[:12]}"
        bootstrap_dsn = _dsn(postgres_container, "corebank")
        test_dsn = _dsn(postgres_container, name)

        instance = await Store.connect(test_dsn, bootstrap_dsn=bootstrap_dsn)
        try:
            assert await instance.count() == 0
        finally:
            await instance.close()

    async def test_second_connect_reuses_the_now_existing_database(self, postgres_container):
        import uuid
        name = f"insights_reuse_{uuid.uuid4().hex[:12]}"
        bootstrap_dsn = _dsn(postgres_container, "corebank")
        test_dsn = _dsn(postgres_container, name)

        first = await Store.connect(test_dsn, bootstrap_dsn=bootstrap_dsn)
        await first.close()

        second = await Store.connect(test_dsn, bootstrap_dsn=bootstrap_dsn)
        try:
            assert await second.count() == 0
        finally:
            await second.close()


class TestUpsertEntry:
    async def test_a_redelivered_message_overwrites_rather_than_duplicates(self, store):
        posted_at = datetime.now(timezone.utc)
        await store.upsert_entry(
            reference="TXN-1", account_number="100100000001", direction="DEBIT",
            signed_amount="-500.00", currency="INR", description="first pass",
            category="SHOPPING", confidence=0.4, posted_at=posted_at,
        )
        # Same (reference, account_number) key, different category -- as if the categoriser's
        # model changed between the original delivery and a Kafka redelivery.
        await store.upsert_entry(
            reference="TXN-1", account_number="100100000001", direction="DEBIT",
            signed_amount="-500.00", currency="INR", description="second pass",
            category="DINING", confidence=0.9, posted_at=posted_at,
        )

        assert await store.count() == 1
        rows = await store.entries(["100100000001"], limit=10)
        assert len(rows) == 1
        assert rows[0]["category"] == "DINING"
        assert rows[0]["description"] == "second pass"

    async def test_different_accounts_on_the_same_reference_are_separate_rows(self, store):
        # A transfer: one reference, two legs on two different accounts. The primary key is
        # (reference, account_number), not reference alone, precisely so this is two rows.
        posted_at = datetime.now(timezone.utc)
        await store.upsert_entry(
            reference="TXN-2", account_number="100100000001", direction="DEBIT",
            signed_amount="-100.00", currency="INR", description="transfer",
            category="TRANSFERS", confidence=0.6, posted_at=posted_at,
        )
        await store.upsert_entry(
            reference="TXN-2", account_number="100100000002", direction="CREDIT",
            signed_amount="100.00", currency="INR", description="transfer",
            category="TRANSFERS", confidence=0.6, posted_at=posted_at,
        )
        assert await store.count() == 2


class TestSummary:
    async def test_only_negative_signed_amounts_count_as_spend(self, store):
        posted_at = datetime.now(timezone.utc)
        await store.upsert_entry(
            reference="TXN-3", account_number="A1", direction="DEBIT", signed_amount="-200.00",
            currency="INR", description="dinner", category="DINING", confidence=0.8,
            posted_at=posted_at,
        )
        await store.upsert_entry(
            reference="TXN-4", account_number="A1", direction="CREDIT", signed_amount="1000.00",
            currency="INR", description="salary credit", category="TRANSFERS", confidence=0.7,
            posted_at=posted_at,
        )

        rows = await store.summary(["A1"], since=None, until=None)
        categories = {r["category"]: r["spent"] for r in rows}
        assert categories == {"DINING": 200}
        assert "TRANSFERS" not in categories

    async def test_grouped_and_summed_per_category(self, store):
        posted_at = datetime.now(timezone.utc)
        for ref, amount in [("TXN-5", "-100.00"), ("TXN-6", "-150.00")]:
            await store.upsert_entry(
                reference=ref, account_number="A2", direction="DEBIT", signed_amount=amount,
                currency="INR", description="swiggy", category="DINING", confidence=0.8,
                posted_at=posted_at,
            )
        rows = await store.summary(["A2"], since=None, until=None)
        assert len(rows) == 1
        assert rows[0]["category"] == "DINING"
        assert rows[0]["spent"] == 250
        assert rows[0]["entries"] == 2

    async def test_since_and_until_bound_the_window(self, store):
        old = datetime(2020, 1, 1, tzinfo=timezone.utc)
        recent = datetime.now(timezone.utc)
        await store.upsert_entry(
            reference="TXN-7", account_number="A3", direction="DEBIT", signed_amount="-50.00",
            currency="INR", description="old spend", category="SHOPPING", confidence=0.5,
            posted_at=old,
        )
        await store.upsert_entry(
            reference="TXN-8", account_number="A3", direction="DEBIT", signed_amount="-75.00",
            currency="INR", description="recent spend", category="SHOPPING", confidence=0.5,
            posted_at=recent,
        )

        rows = await store.summary(["A3"], since=recent - timedelta(minutes=1), until=None)
        assert rows[0]["spent"] == 75

    async def test_accounts_not_requested_are_excluded(self, store):
        posted_at = datetime.now(timezone.utc)
        await store.upsert_entry(
            reference="TXN-9", account_number="not-mine", direction="DEBIT",
            signed_amount="-999.00", currency="INR", description="someone else's spend",
            category="SHOPPING", confidence=0.5, posted_at=posted_at,
        )
        rows = await store.summary(["A4"], since=None, until=None)
        assert rows == []


class TestEntries:
    async def test_newest_first(self, store):
        earlier = datetime.now(timezone.utc) - timedelta(hours=1)
        later = datetime.now(timezone.utc)
        await store.upsert_entry(
            reference="TXN-10", account_number="A5", direction="DEBIT", signed_amount="-10.00",
            currency="INR", description="earlier", category="SHOPPING", confidence=0.5,
            posted_at=earlier,
        )
        await store.upsert_entry(
            reference="TXN-11", account_number="A5", direction="DEBIT", signed_amount="-20.00",
            currency="INR", description="later", category="SHOPPING", confidence=0.5,
            posted_at=later,
        )
        rows = await store.entries(["A5"], limit=10)
        assert [r["description"] for r in rows] == ["later", "earlier"]

    async def test_limit_is_respected(self, store):
        posted_at = datetime.now(timezone.utc)
        for i in range(5):
            await store.upsert_entry(
                reference=f"TXN-limit-{i}", account_number="A6", direction="DEBIT",
                signed_amount="-1.00", currency="INR", description=f"spend {i}",
                category="SHOPPING", confidence=0.5, posted_at=posted_at,
            )
        rows = await store.entries(["A6"], limit=2)
        assert len(rows) == 2
