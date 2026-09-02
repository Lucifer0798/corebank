"""Consumes CoreBank's posted-transaction feed and stores a categorised row per ledger leg.

Its own consumer group, so it neither competes with nor disturbs CoreBank's own listener or the
Phase 5 search indexers reading the same topic -- the pattern Phase 5 established for adding a
second consumer without touching the first.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime
from decimal import Decimal

from aiokafka import AIOKafkaConsumer

from .model import Categoriser
from .store import Store

log = logging.getLogger(__name__)


def _signed_amount(direction: str, amount: str, account_number: str) -> Decimal:
    """Signs a leg from the holding account's point of view.

    A customer account is a liability of the bank, so a CREDIT increases it -- money in. This is
    the same rule as CoreBank's `Account.applyEntry`, but it is applied here to *customer*
    accounts only: general-ledger accounts (GL...) have the opposite normal balance, and a
    spending summary over the bank's own cash account is meaningless, so those legs are dropped
    by the caller rather than mis-signed here.
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
                 store: Store, categoriser: Categoriser) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._topic = topic
        self._group_id = group_id
        self._store = store
        self._categoriser = categoriser
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        self._consumer = AIOKafkaConsumer(
            self._topic,
            bootstrap_servers=self._bootstrap_servers,
            group_id=self._group_id,
            auto_offset_reset="earliest",
            enable_auto_commit=True,
            value_deserializer=lambda v: json.loads(v.decode("utf-8")),
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
        if self._consumer is not None:
            await self._consumer.stop()

    async def _run(self) -> None:
        assert self._consumer is not None
        try:
            async for message in self._consumer:
                try:
                    await self._handle(message.value)
                except Exception:
                    # One malformed or unexpected event must not kill the consumer and stall the
                    # projection; it is logged and skipped, and the topic can be replayed to
                    # rebuild. Same fire-and-forget posture as the search indexers.
                    log.exception("Skipping an event that could not be processed")
        except asyncio.CancelledError:
            raise

    async def _handle(self, event: dict) -> None:
        reference = event["reference"]
        description = event.get("description")
        currency = event.get("currency", "INR")
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
                signed_amount=str(_signed_amount(leg["direction"], str(leg["amount"]), account_number)),
                currency=currency,
                description=description,
                category=category,
                confidence=confidence,
                posted_at=posted_at,
            )
