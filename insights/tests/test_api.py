"""The FastAPI routes themselves: request/response shaping and the authorization each one applies.

`main.state` is populated directly with fakes rather than running the real `lifespan` (which
would dial real Kafka/Postgres/CoreBank) -- the same boundary-mocking rule as
test_corebank_client.py, drawn around this service's own external dependencies rather than its
own logic. `current_principal` is swapped via FastAPI's own `dependency_overrides`, which is the
supported way to control auth in a route test without a real Keycloak token.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from app import main
from app.auth import Principal, current_principal


class _FakeStore:
    def __init__(self, summary_rows=None, entry_rows=None, entries_indexed=0):
        self._summary_rows = summary_rows or []
        self._entry_rows = entry_rows or []
        self.entries_indexed = entries_indexed
        self.summary_calls: list[tuple] = []

    async def count(self):
        return self.entries_indexed

    async def summary(self, account_numbers, since, until):
        self.summary_calls.append((account_numbers, since, until))
        return self._summary_rows

    async def entries(self, account_numbers, limit):
        return self._entry_rows[:limit]


class _FakeCategoriser:
    categories = ["DINING", "TRANSFERS", "SHOPPING"]

    def categorise(self, description):
        return ("DINING", 0.42) if description else ("UNCATEGORISED", 0.0)


class _FakeCoreBank:
    def __init__(self, accounts=None, error=None):
        self._accounts = accounts if accounts is not None else ["100100000001"]
        self._error = error
        self.calls: list[tuple] = []

    async def account_numbers_for(self, customer_id, token):
        self.calls.append((customer_id, token))
        if self._error is not None:
            raise self._error
        return self._accounts


@pytest.fixture
def wire_state():
    """Populates main.state with fakes and restores it afterwards."""
    original = dict(main.state)

    def _wire(*, store=None, categoriser=None, corebank=None):
        main.state["store"] = store or _FakeStore()
        main.state["categoriser"] = categoriser or _FakeCategoriser()
        main.state["corebank"] = corebank or _FakeCoreBank()

    yield _wire
    main.state.clear()
    main.state.update(original)


@pytest.fixture
def client():
    return TestClient(main.app)


@pytest.fixture
def as_teller():
    principal = Principal(subject="teller-sub", roles=frozenset({"TELLER"}), token="teller-token")
    main.app.dependency_overrides[current_principal] = lambda: principal
    yield principal
    main.app.dependency_overrides.pop(current_principal, None)


@pytest.fixture
def as_customer():
    principal = Principal(subject="cust-sub", roles=frozenset({"CUSTOMER"}), token="cust-token")
    main.app.dependency_overrides[current_principal] = lambda: principal
    yield principal
    main.app.dependency_overrides.pop(current_principal, None)


class TestCors:
    def test_allows_the_configured_frontend_origin(self, client, wire_state):
        # Starlette's CORSMiddleware only adds Access-Control-Allow-Origin to a request that
        # actually carries an Origin header -- a plain same-process TestClient GET without one
        # would pass either way, so this has to send it explicitly to prove the grant is real.
        wire_state()
        response = client.get("/health", headers={"Origin": "http://localhost:5173"})
        assert response.headers["access-control-allow-origin"] == "http://localhost:5173"

    def test_refuses_an_origin_not_on_the_allowlist(self, client, wire_state):
        wire_state()
        response = client.get("/health", headers={"Origin": "http://evil.example"})
        assert "access-control-allow-origin" not in response.headers


class TestHealth:
    def test_is_unauthenticated_and_reports_categories(self, client, wire_state):
        wire_state(categoriser=_FakeCategoriser(), store=_FakeStore(entries_indexed=342))
        response = client.get("/health")
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "UP"
        assert body["categories"] == ["DINING", "TRANSFERS", "SHOPPING"]
        assert body["entries_indexed"] == 342


class TestCustomerSummary:
    def test_requires_authentication(self, client, wire_state):
        # HTTPBearer is constructed with auto_error=False specifically so current_principal can
        # raise its own 401 with its own body, rather than the library's default 403 -- so this
        # has to come back 401, not 403.
        wire_state()
        response = client.get("/api/v1/insights/customers/cust-1/summary")
        assert response.status_code == 401

    def test_no_accounts_returns_a_zero_summary_without_querying_the_store(
        self, client, wire_state, as_teller
    ):
        store = _FakeStore()
        wire_state(store=store, corebank=_FakeCoreBank(accounts=[]))
        response = client.get(
            "/api/v1/insights/customers/cust-1/summary",
            headers={"Authorization": "Bearer x"},
        )
        assert response.status_code == 200
        body = response.json()
        assert body["total_spent"] == "0.00"
        assert body["categories"] == []
        assert store.summary_calls == []  # Never reached the aggregate query.

    def test_aggregates_and_totals_the_categories(self, client, wire_state, as_teller):
        store = _FakeStore(summary_rows=[
            {"category": "RENT", "spent": 18000, "entries": 1},
            {"category": "DINING", "spent": 850, "entries": 1},
        ])
        wire_state(store=store, corebank=_FakeCoreBank(accounts=["100100000001"]))

        response = client.get(
            "/api/v1/insights/customers/cust-1/summary",
            headers={"Authorization": "Bearer x"},
        )
        assert response.status_code == 200
        body = response.json()
        assert body["total_spent"] == "18850.00"
        assert body["accounts"] == ["100100000001"]
        assert {c["category"]: c["spent"] for c in body["categories"]} == {
            "RENT": "18000.00", "DINING": "850.00"
        }

    def test_forwards_the_callers_own_token_to_corebank(self, client, wire_state, as_teller):
        # as_teller overrides current_principal entirely, so the request's own Authorization
        # header is never parsed here -- what has to be checked is that the route hands CoreBank
        # principal.token (the value the real dependency would have extracted), not some other
        # value it invented or dropped.
        corebank = _FakeCoreBank()
        wire_state(corebank=corebank)
        client.get(
            "/api/v1/insights/customers/cust-1/summary",
            headers={"Authorization": "Bearer irrelevant-because-of-the-override"},
        )
        assert corebank.calls == [("cust-1", as_teller.token)]

    def test_corebanks_403_is_not_swallowed(self, client, wire_state, as_customer):
        from fastapi import HTTPException
        wire_state(corebank=_FakeCoreBank(error=HTTPException(status_code=403, detail="forbidden")))
        response = client.get(
            "/api/v1/insights/customers/someone-elses-customer-id/summary",
            headers={"Authorization": "Bearer x"},
        )
        assert response.status_code == 403


class TestCustomerEntries:
    def test_maps_rows_into_the_response_shape(self, client, wire_state, as_teller):
        wire_state(
            corebank=_FakeCoreBank(accounts=["A1"]),
            store=_FakeStore(entry_rows=[{
                "reference": "TXN-1", "account_number": "A1", "category": "DINING",
                "confidence": 0.876543, "signed_amount": -500, "currency": "INR",
                "description": "swiggy", "posted_at": datetime(2026, 9, 2, tzinfo=timezone.utc),
            }]),
        )
        response = client.get(
            "/api/v1/insights/customers/cust-1/entries",
            headers={"Authorization": "Bearer x"},
        )
        assert response.status_code == 200
        [entry] = response.json()
        assert entry["reference"] == "TXN-1"
        assert entry["signed_amount"] == "-500.00"
        assert entry["confidence"] == 0.8765  # Rounded to 4 places, not truncated to the raw float.

    def test_no_accounts_returns_an_empty_list(self, client, wire_state, as_teller):
        wire_state(corebank=_FakeCoreBank(accounts=[]))
        response = client.get(
            "/api/v1/insights/customers/cust-1/entries",
            headers={"Authorization": "Bearer x"},
        )
        assert response.status_code == 200
        assert response.json() == []


class TestCategorisePreview:
    def test_staff_can_use_it(self, client, wire_state, as_teller):
        wire_state()
        response = client.get(
            "/api/v1/insights/categorise",
            params={"description": "zomato dinner"},
            headers={"Authorization": "Bearer x"},
        )
        assert response.status_code == 200
        assert response.json()["category"] == "DINING"

    def test_a_customer_role_is_refused(self, client, wire_state, as_customer):
        wire_state()
        response = client.get(
            "/api/v1/insights/categorise",
            params={"description": "zomato dinner"},
            headers={"Authorization": "Bearer x"},
        )
        assert response.status_code == 403

    def test_never_reaches_corebank(self, client, wire_state, as_teller):
        # It categorises arbitrary text, not a customer's own data -- CoreBank should not even be
        # asked.
        corebank = _FakeCoreBank()
        wire_state(corebank=corebank)
        client.get(
            "/api/v1/insights/categorise",
            params={"description": "zomato dinner"},
            headers={"Authorization": "Bearer x"},
        )
        assert corebank.calls == []
