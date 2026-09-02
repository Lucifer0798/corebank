"""Keycloak bearer-token validation, mirroring CoreBank's resource-server rules.

This endpoint serves per-customer financial data, so it authenticates exactly as the core does:
same realm, same tokens, same nested `realm_access.roles` claim. Verification is real -- RS256
signature against Keycloak's JWKS, plus issuer -- not a decode-and-trust.
"""

from __future__ import annotations

from dataclasses import dataclass

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt import PyJWKClient

from .config import settings

_bearer = HTTPBearer(auto_error=False)
_jwk_client: PyJWKClient | None = None


def _jwks() -> PyJWKClient:
    # Built lazily and cached: constructing it eagerly at import time would fetch from Keycloak
    # during startup and make this service fail to boot whenever Keycloak is merely slow, which
    # is the trap CoreBank avoids by splitting issuer-uri from jwk-set-uri.
    global _jwk_client
    if _jwk_client is None:
        _jwk_client = PyJWKClient(settings().oidc_jwks_url, cache_keys=True)
    return _jwk_client


@dataclass(frozen=True)
class Principal:
    subject: str
    roles: frozenset[str]
    token: str

    @property
    def is_staff(self) -> bool:
        return bool(self.roles & {"TELLER", "ADMIN"})


async def current_principal(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> Principal:
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token = credentials.credentials
    try:
        signing_key = _jwks().get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            issuer=settings().oidc_issuer,
            # Keycloak puts the client id in `azp`, and `aud` is frequently just "account", so
            # audience is not a useful check here; issuer plus signature is what actually binds
            # the token to this realm.
            options={"verify_aud": False},
        )
    except jwt.PyJWTError as exc:
        # Why it failed stays server-side, matching CoreBank's ProblemAuthenticationHandler.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc

    roles = frozenset(claims.get("realm_access", {}).get("roles", []))
    return Principal(subject=claims.get("sub", ""), roles=roles, token=token)
