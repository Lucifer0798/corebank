"""Consumes CoreBank's posted-transaction feed and stores a categorised row per ledger leg.

Its own consumer group, so it neither competes with nor disturbs CoreBank's own listener or the
Phase 5 search indexers reading the same topic -- the pattern Phase 5 established for adding a
second consumer without touching the first.

What happens when a message cannot be stored:

- **A message that is not valid JSON** is logged and skipped. It used to be parsed by aiokafka's
  ``value_deserializer``, which raised out of the ``async for`` itself, outside any ``try``: the
  consumer task ended, quietly, and since its offset never moved past the bad message, every
  restart ended the same way. Insights stopped for good while ``/health`` said UP.
- **The database being unavailable** is waited out: the same event again after a pause of a
  second, doubling to a minute, for as long as it takes. It used to be logged and skipped like
  anything else, and with auto-commit on, that row was gone. Storing is an upsert keyed by
  (reference, account), so trying again cannot count anything twice.
- **Anything else**, an event missing a field it needs, will fail the same way every time, so it
  is logged and skipped rather than holding up everything behind it.

Offsets are committed by hand, after each message is dealt with, not on a timer: a crash while
waiting for the database then hands the same message to the next start instead of losing it.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from datetime import datetime
from decimal import Decimal

import asyncpg
from aiokafka import AIOKafkaConsumer

from . import metrics
from .model import Categoriser
from .store import Store

log = logging.getLogger(__name__)

# The database, not the event, is the problem: a lost or refused connection, a server shutting
# down or not yet accepting connections, too many connections, a socket error or a timeout
# (TimeoutError is an OSError). Every one of these clears by itself.
DATABASE_UNAVAILABLE: tuple[type[BaseException], ...] = (
    asyncpg.exceptions.PostgresConnectionError,
    asyncpg.exceptions.OperatorInterventionError,
    asyncpg.exceptions.InsufficientResourcesError,
    OSError,
)

MAX_PAUSE_SECONDS = 60


def _signed_amount(direction: str, amount: str) -> Decimal:
    """Signs a leg from the holding account's point of view.

    A customer account is a liability of the bank, so a CREDIT increases it -- money in. This is
    the same rule as CoreBank's `Account.applyEntry`, but it is applied here to *customer*
    accounts only: general-ledger accounts (GL...) have the opposite normal balance, and a
    spending summary over the bank's own cash account is meaningless, so `_handle` drops those
    legs via `_is_customer_account` before this is ever called on one.
    """
    value = Decimal(amount)
    return value if direction == "CREDIT" else -value


def _is_customer_account(account_number: str) -> bool:
    # General-ledger accounts are the bank's own books (GL0000000001 is cash); every deposit has
    # one as its contra leg. Including them would double-count every transaction and attribute
    # the bank's cash movements to a customer.
    return not account_number.upper().startswith("GL")


class TransactionConsumer:
    def __init__(self, *, bootstrap_servers: str, topic: str, group_id: str,
                 store: Store, categoriser: Categoriser,
                 sleep: Callable[[float], Awaitable[object]] = asyncio.sleep) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._topic = topic
        self._group_id = group_id
        self._store = store
        self._categoriser = categoriser
        self._sleep = sleep
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task | None = None

    @property
    def is_running(self) -> bool:
        """False once the consuming task has ended, for whatever reason: what /health reports."""
        return self._task is not None and not self._task.done()

    async def start(self) -> None:
        self._consumer = AIOKafkaConsumer(
            self._topic,
            bootstrap_servers=self._bootstrap_servers,
            group_id=self._group_id,
            auto_offset_reset="earliest",
            enable_auto_commit=False,
        )
        await self._consumer.start()
        self._task = asyncio.create_task(self._run(), name="insights-consumer")
        log.info("Consuming %s as group %s", self._topic, self._group_id)

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            except Exception:
                # Already logged by _run when it ended. Shutting down must still close the consumer.
                pass
        if self._consumer is not None:
            await self._consumer.stop()

    async def _run(self) -> None:
        assert self._consumer is not None
        try:
            async for message in self._consumer:
                await self._process(message)
                await self._consumer.commit()
        except asyncio.CancelledError:
            raise
        except Exception:
            # Kafka itself failing in a way the client does not recover from. Nothing is consumed
            # from here on, so it is logged loudly and /health turns DOWN, which restarts the pod.
            log.exception("The insights consumer has stopped; nothing more will be stored until it restarts")
            raise

    async def _process(self, message) -> None:
        try:
            event = json.loads(message.value)
        except ValueError:
            # Covers a bad UTF-8 decode too: UnicodeDecodeError is a ValueError.
            metrics.MALFORMED.inc()
            log.error("Skipped a malformed message on %s partition %s offset %s",
                      message.topic, message.partition, message.offset)
            return

        attempt = 0
        while True:
            try:
                await self._handle(event)
                metrics.STORED.inc()
                return
            except DATABASE_UNAVAILABLE as unavailable:
                metrics.STORE_RETRIES.inc()
                pause = min(MAX_PAUSE_SECONDS, 2 ** attempt)
                attempt += 1
                log.warning("Could not store %s because the database is unavailable; trying again in %ss: %r",
                            _reference_of(event), pause, unavailable)
                await self._sleep(pause)
            except Exception:
                metrics.SKIPPED.inc()
                log.exception("Skipped an event that could not be processed: %s on %s partition %s offset %s",
                              _reference_of(event), message.topic, message.partition, message.offset)
                return

    async def _handle(self, event: dict) -> None:
        reference = event["reference"]
        description = event.get("description")
        # The transaction's currency is the sender's. Each leg is in its own account's currency, which
        # on a cross-currency transfer is not that for half the legs; the fallback is for messages
        # from before legs carried one, all of which were single-currency.
        transaction_currency = event.get("currency", "INR")
        posted_at = datetime.fromisoformat(event["postedAt"].replace("Z", "+00:00"))
        category, confidence = self._categoriser.categorise(description)

        for leg in event.get("legs", []):
            account_number = leg["accountNumber"]
            if not _is_customer_account(account_number):
                continue
            await self._store.upsert_entry(
                reference=reference,
                account_number=account_number,
                direction=leg["direction"],
                signed_amount=str(_signed_amount(leg["direction"], str(leg["amount"]))),
                currency=leg.get("currency") or transaction_currency,
                description=description,
                category=category,
                confidence=confidence,
                posted_at=posted_at,
            )


def _reference_of(event) -> str:
    return event.get("reference", "an event with no reference") if isinstance(event, dict) else "a non-object event"
