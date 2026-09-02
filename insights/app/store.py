"""Postgres storage for the categorised ledger entries this service derives.

Schema is created on startup rather than by a migration tool. That matches how Phase 5's
OpenSearch indices are created and is defensible for the same reason: this table is a downstream
projection that can be rebuilt by replaying the topic, not a system of record whose history has
to be preserved across schema changes.
"""

from __future__ import annotations

import asyncpg

# One row per (transaction, account) leg, not per transaction: a transfer touches two accounts and
# means opposite things to each, so a per-transaction row could not answer "what did this account
# spend" without re-deriving the sign every time.
#
# The primary key is (reference, account_number) so a redelivered Kafka message overwrites rather
# than double-counts -- the same at-least-once concern the search indexers handle by keying their
# documents on the reference.
SCHEMA = """
CREATE TABLE IF NOT EXISTS categorised_entry (
    reference       TEXT           NOT NULL,
    account_number  TEXT           NOT NULL,
    direction       TEXT           NOT NULL,
    -- Signed from this account's point of view: negative means money left it.
    signed_amount   NUMERIC(19,4)  NOT NULL,
    currency        TEXT           NOT NULL,
    description     TEXT,
    category        TEXT           NOT NULL,
    confidence      REAL           NOT NULL,
    posted_at       TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (reference, account_number)
);
CREATE INDEX IF NOT EXISTS categorised_entry_account_posted
    ON categorised_entry (account_number, posted_at DESC);
"""


class Store:
    def __init__(self, pool: asyncpg.Pool) -> None:
        self._pool = pool

    @classmethod
    async def connect(cls, dsn: str, *, bootstrap_dsn: str | None = None) -> "Store":
        """Connects, creating the database first if it does not exist yet.

        The obvious alternative -- an init script in the Postgres image -- only runs when the data
        volume is first created, so anyone with an existing CoreBank volume (which is everyone who
        ran an earlier phase) would have had to `docker compose down -v` and lose their data to
        pick this up. Creating it here costs a few lines and removes that footgun entirely.
        """
        try:
            pool = await asyncpg.create_pool(dsn, min_size=1, max_size=8)
        except asyncpg.InvalidCatalogNameError:
            if bootstrap_dsn is None:
                raise
            await cls._create_database(dsn, bootstrap_dsn)
            pool = await asyncpg.create_pool(dsn, min_size=1, max_size=8)

        async with pool.acquire() as connection:
            await connection.execute(SCHEMA)
        return cls(pool)

    @staticmethod
    async def _create_database(dsn: str, bootstrap_dsn: str) -> None:
        # CREATE DATABASE cannot run inside a transaction or be parameterised, and the name comes
        # from our own configuration rather than user input, so quoting it directly is safe here.
        name = dsn.rsplit("/", 1)[-1].split("?")[0]
        connection = await asyncpg.connect(bootstrap_dsn)
        try:
            await connection.execute(f'CREATE DATABASE "{name}"')
        except asyncpg.DuplicateDatabaseError:
            pass  # Another replica won the race; either way it exists now.
        finally:
            await connection.close()

    async def close(self) -> None:
        await self._pool.close()

    async def upsert_entry(
        self,
        *,
        reference: str,
        account_number: str,
        direction: str,
        signed_amount: str,
        currency: str,
        description: str | None,
        category: str,
        confidence: float,
        posted_at,
    ) -> None:
        await self._pool.execute(
            """
            INSERT INTO categorised_entry (reference, account_number, direction, signed_amount,
                                           currency, description, category, confidence, posted_at)
            VALUES ($1, $2, $3, $4::numeric, $5, $6, $7, $8, $9)
            ON CONFLICT (reference, account_number) DO UPDATE SET
                direction     = EXCLUDED.direction,
                signed_amount = EXCLUDED.signed_amount,
                currency      = EXCLUDED.currency,
                description   = EXCLUDED.description,
                category      = EXCLUDED.category,
                confidence    = EXCLUDED.confidence,
                posted_at     = EXCLUDED.posted_at
            """,
            reference, account_number, direction, signed_amount, currency,
            description, category, confidence, posted_at,
        )

    async def summary(self, account_numbers: list[str], since, until) -> list[asyncpg.Record]:
        """Spending by category over the given accounts.

        Only outgoing legs count: a spending summary that included salary credits and incoming
        transfers as "spend" would be actively misleading. `spent` is returned positive because a
        summary reads better that way, even though the stored amounts are negative.
        """
        return await self._pool.fetch(
            """
            SELECT category,
                   SUM(-signed_amount) AS spent,
                   COUNT(*)            AS entries
              FROM categorised_entry
             WHERE account_number = ANY($1::text[])
               AND signed_amount < 0
               AND ($2::timestamptz IS NULL OR posted_at >= $2)
               AND ($3::timestamptz IS NULL OR posted_at <= $3)
             GROUP BY category
             ORDER BY spent DESC
            """,
            account_numbers, since, until,
        )

    async def entries(self, account_numbers: list[str], limit: int) -> list[asyncpg.Record]:
        return await self._pool.fetch(
            """
            SELECT reference, account_number, category, confidence, signed_amount,
                   currency, description, posted_at
              FROM categorised_entry
             WHERE account_number = ANY($1::text[])
             ORDER BY posted_at DESC
             LIMIT $2
            """,
            account_numbers, limit,
        )

    async def count(self) -> int:
        return await self._pool.fetchval("SELECT COUNT(*) FROM categorised_entry")
