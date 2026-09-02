"""The one place this service reads from CoreBank.

The Kafka feed carries `accountNumber` on each leg but no account id and no customer id (see
`TransactionPostedEvent.Leg`), so a customer's spending cannot be assembled from the topic alone.
Rather than ask CoreBank to widen its published event -- which would change the core to suit a
downstream consumer, the opposite of what a read-only projection should do -- this resolves the
customer's own account numbers from the existing REST API at query time.

The caller's bearer token is forwarded rather than a service account being used, so CoreBank
applies exactly the authorization it always would: a CUSTOMER token can only resolve its own
accounts, and this service never sees data the caller could not have fetched itself.
"""

from __future__ import annotations

import httpx
from fastapi import HTTPException, status


class CoreBankClient:
    def __init__(self, base_url: str) -> None:
        self._client = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=5.0)

    async def close(self) -> None:
        await self._client.aclose()

    async def account_numbers_for(self, customer_id: str, bearer_token: str) -> list[str]:
        try:
            response = await self._client.get(
                f"/api/v1/customers/{customer_id}/accounts",
                headers={"Authorization": f"Bearer {bearer_token}"},
            )
        except httpx.HTTPError as exc:
            # CoreBank being unreachable makes this endpoint unanswerable, but it is not this
            # service's fault and not a 500 on its part -- 503 says "try again", which is true.
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="CoreBank is unreachable",
            ) from exc

        if response.status_code in (401, 403, 404):
            # Passed straight through: CoreBank has already decided this caller may not see this
            # customer, and re-deciding that here would be a second, drifting copy of the rule.
            raise HTTPException(status_code=response.status_code, detail=response.reason_phrase)
        response.raise_for_status()

        payload = response.json()
        accounts = payload.get("content", payload) if isinstance(payload, dict) else payload
        return [a["accountNumber"] for a in accounts if a.get("accountNumber")]
