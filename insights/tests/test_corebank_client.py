"""CoreBankClient against a faked HTTP transport.

The transport is the legitimate boundary to mock: CoreBank itself is exercised for real by
CoreBankApiIntegrationTest and CoreBankTestcontainersIT on the Java side, and again by hand
against the live compose stack when this service was built (see the Phase 7 commit). What's
under test here is this client's own translation of CoreBank's responses -- the paged-vs-plain
payload shapes, and passing 401/403/404 straight through rather than re-deciding authorization.
"""

from __future__ import annotations

import httpx
import pytest
from fastapi import HTTPException

from app.corebank import CoreBankClient


def _client_with(handler) -> CoreBankClient:
    client = CoreBankClient("http://corebank.test")
    client._client = httpx.AsyncClient(
        base_url="http://corebank.test", transport=httpx.MockTransport(handler)
    )
    return client


class TestAccountNumbersFor:
    async def test_plain_list_payload(self):
        def handler(request: httpx.Request) -> httpx.Response:
            assert request.headers["authorization"] == "Bearer tok-123"
            return httpx.Response(200, json=[{"accountNumber": "100100000001"},
                                              {"accountNumber": "100100000002"}])

        client = _client_with(handler)
        assert await client.account_numbers_for("cust-1", "tok-123") == [
            "100100000001", "100100000002"
        ]

    async def test_paged_content_wrapper_payload(self):
        # CoreBank's PagedResponse envelope: {"content": [...], "page": 0, ...}.
        def handler(_request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={"content": [{"accountNumber": "100100000003"}],
                                              "page": 0, "totalPages": 1})

        client = _client_with(handler)
        assert await client.account_numbers_for("cust-1", "tok-123") == ["100100000003"]

    async def test_accounts_missing_a_number_are_skipped_not_crashed(self):
        def handler(_request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=[{"accountNumber": "100100000001"}, {"id": "no-number"}])

        client = _client_with(handler)
        assert await client.account_numbers_for("cust-1", "tok-123") == ["100100000001"]

    @pytest.mark.parametrize("status_code", [401, 403, 404])
    async def test_corebanks_own_authorization_decision_passes_through(self, status_code):
        def handler(_request: httpx.Request) -> httpx.Response:
            return httpx.Response(status_code)

        client = _client_with(handler)
        with pytest.raises(HTTPException) as excinfo:
            await client.account_numbers_for("cust-1", "tok-123")
        assert excinfo.value.status_code == status_code

    async def test_corebank_unreachable_is_503_not_500(self):
        def handler(_request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("connection refused")

        client = _client_with(handler)
        with pytest.raises(HTTPException) as excinfo:
            await client.account_numbers_for("cust-1", "tok-123")
        assert excinfo.value.status_code == 503

    async def test_unexpected_5xx_is_not_swallowed(self):
        def handler(_request: httpx.Request) -> httpx.Response:
            return httpx.Response(500)

        client = _client_with(handler)
        with pytest.raises(httpx.HTTPStatusError):
            await client.account_numbers_for("cust-1", "tok-123")
