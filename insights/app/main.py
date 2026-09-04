"""Spending insights API.

A read-only projection of CoreBank's transaction feed. Nothing here writes to the core: the only
call in that direction resolves which accounts a customer holds (see corebank.py), and it carries
the caller's own token so CoreBank's authorization applies unchanged. A total outage of this
service cannot affect money movement, by construction -- it is downstream of a commit that has
already happened.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import Depends, FastAPI, HTTPException, Query, status
from fastapi.middleware.cors import CORSMiddleware

from .auth import Principal, current_principal
from .config import settings
from .consumer import TransactionConsumer
from .corebank import CoreBankClient
from .model import Categoriser
from .schemas import (
    CategorisePreview,
    CategorySpend,
    HealthResponse,
    RecentEntry,
    SpendingSummary,
)
from .store import Store

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s : %(message)s")
log = logging.getLogger("insights")

state: dict[str, object] = {}


@asynccontextmanager
async def lifespan(_: FastAPI):
    config = settings()
    # Training happens here on a cold start if no model exists yet, so a fresh `docker compose up`
    # needs no separate build step. See model.Categoriser.
    categoriser = Categoriser(config.model_dir, config.mlflow_tracking_uri)
    store = await Store.connect(config.database_url, bootstrap_dsn=config.database_bootstrap_url)
    corebank = CoreBankClient(config.corebank_api_url)
    consumer = TransactionConsumer(
        bootstrap_servers=config.kafka_bootstrap_servers,
        topic=config.kafka_topic,
        group_id=config.kafka_group_id,
        store=store,
        categoriser=categoriser,
    )
    await consumer.start()
    state.update(categoriser=categoriser, store=store, corebank=corebank, consumer=consumer)
    try:
        yield
    finally:
        await consumer.stop()
        await corebank.close()
        await store.close()


app = FastAPI(
    title="CoreBank Spending Insights",
    description="Categorised spending derived from CoreBank's transaction feed. Read-only.",
    version="0.1.0",
    lifespan=lifespan,
)

# Every route here is a GET behind a bearer token, never a cookie, so allow_credentials stays
# false -- same posture as CoreBank's own CorsConfigurationSource in SecurityConfig.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[origin.strip() for origin in settings().allowed_origins.split(",")],
    allow_methods=["GET"],
    allow_headers=["Authorization"],
    allow_credentials=False,
)


def _store() -> Store:
    return state["store"]  # type: ignore[return-value]


def _categoriser() -> Categoriser:
    return state["categoriser"]  # type: ignore[return-value]


def _corebank() -> CoreBankClient:
    return state["corebank"]  # type: ignore[return-value]


@app.get("/health", response_model=HealthResponse, tags=["ops"])
async def health() -> HealthResponse:
    """Unauthenticated, like CoreBank's own /actuator/health -- a container probe has no token."""
    return HealthResponse(
        status="UP",
        categories=_categoriser().categories,
        entries_indexed=await _store().count(),
    )


@app.get("/api/v1/insights/customers/{customer_id}/summary",
         response_model=SpendingSummary, tags=["insights"])
async def customer_summary(
    customer_id: str,
    since: datetime | None = Query(None, description="Inclusive lower bound, ISO-8601"),
    until: datetime | None = Query(None, description="Inclusive upper bound, ISO-8601"),
    principal: Principal = Depends(current_principal),
) -> SpendingSummary:
    """Spending by category for one customer.

    Ownership is not re-decided here. Resolving the customer's accounts goes through CoreBank with
    the caller's own token, so a CUSTOMER token that does not own this customer gets CoreBank's own
    403/404 and never reaches the aggregate -- one authorization rule, enforced where it lives.
    """
    account_numbers = await _corebank().account_numbers_for(customer_id, principal.token)
    if not account_numbers:
        return SpendingSummary(customer_id=customer_id, currency="INR", total_spent="0.00",
                               categories=[], accounts=[])

    rows = await _store().summary(account_numbers, since, until)
    categories = [
        CategorySpend(category=r["category"], spent=f"{r['spent']:.2f}", entries=r["entries"])
        for r in rows
    ]
    total = sum((r["spent"] for r in rows), start=0)
    return SpendingSummary(
        customer_id=customer_id,
        currency="INR",
        total_spent=f"{total:.2f}",
        categories=categories,
        accounts=account_numbers,
    )


@app.get("/api/v1/insights/customers/{customer_id}/entries",
         response_model=list[RecentEntry], tags=["insights"])
async def customer_entries(
    customer_id: str,
    limit: int = Query(20, ge=1, le=200),
    principal: Principal = Depends(current_principal),
) -> list[RecentEntry]:
    """The categorised entries behind the summary, newest first."""
    account_numbers = await _corebank().account_numbers_for(customer_id, principal.token)
    if not account_numbers:
        return []
    rows = await _store().entries(account_numbers, limit)
    return [
        RecentEntry(
            reference=r["reference"],
            account_number=r["account_number"],
            category=r["category"],
            confidence=round(r["confidence"], 4),
            signed_amount=f"{r['signed_amount']:.2f}",
            currency=r["currency"],
            description=r["description"],
            posted_at=r["posted_at"],
        )
        for r in rows
    ]


@app.get("/api/v1/insights/categorise", response_model=CategorisePreview, tags=["insights"])
async def categorise_preview(
    description: str = Query(..., min_length=1, max_length=255),
    principal: Principal = Depends(current_principal),
) -> CategorisePreview:
    """Runs the categoriser over an arbitrary description.

    Staff-only: it is a window onto the model itself rather than onto a customer's data, useful
    for seeing why something landed where it did without going through the ledger.
    """
    if not principal.is_staff:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN,
                            detail="You are not allowed to perform this action")
    category, confidence = _categoriser().categorise(description)
    return CategorisePreview(description=description, category=category,
                             confidence=round(confidence, 4))
