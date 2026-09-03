"""Shared fixtures.

Two kinds of test double appear in this suite, deliberately different: CoreBank and Keycloak are
real external systems this service talks to over HTTP, so faking their *edge* (an HTTP transport,
a JWKS lookup) is a legitimate boundary to mock. Postgres is not treated that way -- store.py's
SQL is this service's own logic, so test_store.py runs it against a real container instead.
"""

from __future__ import annotations

import datetime as dt

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa


@pytest.fixture(scope="session")
def rsa_keypair():
    """A throwaway RSA keypair, standing in for Keycloak's realm signing key for this test run."""
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return private_key, private_key.public_key()


@pytest.fixture
def issue_token(rsa_keypair):
    """Signs a token shaped like a real Keycloak access token: nested realm_access.roles, RS256."""
    private_key, _ = rsa_keypair

    def _issue(*, subject: str = "test-subject", roles: list[str] | None = None,
               issuer: str = "http://localhost:8081/realms/corebank",
               expires_in: dt.timedelta = dt.timedelta(minutes=5)) -> str:
        now = dt.datetime.now(dt.timezone.utc)
        claims = {
            "sub": subject,
            "iss": issuer,
            "iat": now,
            "exp": now + expires_in,
            "realm_access": {"roles": roles or []},
        }
        return jwt.encode(claims, private_key, algorithm="RS256")

    return _issue
