"""Token validation.

Keycloak itself isn't run for this suite -- the JWKS lookup is monkeypatched to return the test
keypair's public half, which is the point where this service actually depends on Keycloak and the
only place worth cutting the boundary. Verification (signature, issuer, expiry) all still runs
for real against a real RS256-signed token.
"""

from __future__ import annotations

import datetime as dt

import jwt as pyjwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa as rsa_mod
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials

from app import auth


class _StubSigningKey:
    def __init__(self, key) -> None:
        self.key = key


class _StubJwkClient:
    def __init__(self, public_key) -> None:
        self._public_key = public_key

    def get_signing_key_from_jwt(self, _token: str) -> _StubSigningKey:
        return _StubSigningKey(self._public_key)


@pytest.fixture(autouse=True)
def stub_jwks(rsa_keypair, monkeypatch):
    _, public_key = rsa_keypair
    monkeypatch.setattr(auth, "_jwk_client", _StubJwkClient(public_key))


def _credentials(token: str) -> HTTPAuthorizationCredentials:
    return HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)


class TestCurrentPrincipal:
    async def test_valid_token_carries_subject_and_roles(self, issue_token):
        token = issue_token(subject="abc-123", roles=["TELLER"])
        principal = await auth.current_principal(_credentials(token))
        assert principal.subject == "abc-123"
        assert principal.roles == frozenset({"TELLER"})
        assert principal.token == token

    async def test_missing_token_is_401(self):
        with pytest.raises(HTTPException) as excinfo:
            await auth.current_principal(None)
        assert excinfo.value.status_code == 401

    async def test_wrong_issuer_is_401(self, issue_token):
        # Same key, same signature validity -- only the issuer claim is wrong, which is exactly
        # what stops a token from a different realm (or environment) from being accepted here.
        token = issue_token(issuer="http://localhost:8081/realms/some-other-realm")
        with pytest.raises(HTTPException) as excinfo:
            await auth.current_principal(_credentials(token))
        assert excinfo.value.status_code == 401

    async def test_expired_token_is_401(self, issue_token):
        token = issue_token(expires_in=dt.timedelta(seconds=-1))
        with pytest.raises(HTTPException) as excinfo:
            await auth.current_principal(_credentials(token))
        assert excinfo.value.status_code == 401

    async def test_malformed_token_is_401(self):
        with pytest.raises(HTTPException) as excinfo:
            await auth.current_principal(_credentials("not-a-jwt-at-all"))
        assert excinfo.value.status_code == 401

    async def test_token_signed_by_a_different_key_is_401(self, issue_token, monkeypatch):
        # The JWKS lookup here still returns *our* stub public key, but the token was signed with
        # a key nobody's JWKS would ever hand out -- the case a stolen or forged token has to fail.
        rogue_key = rsa_mod.generate_private_key(public_exponent=65537, key_size=2048)
        now = dt.datetime.now(dt.timezone.utc)
        token = pyjwt.encode(
            {"sub": "attacker", "iss": "http://localhost:8081/realms/corebank",
             "iat": now, "exp": now + dt.timedelta(minutes=5), "realm_access": {"roles": ["ADMIN"]}},
            rogue_key, algorithm="RS256",
        )
        with pytest.raises(HTTPException) as excinfo:
            await auth.current_principal(_credentials(token))
        assert excinfo.value.status_code == 401

    async def test_no_roles_claim_is_an_empty_set_not_an_error(self, issue_token):
        token = issue_token(roles=None)
        principal = await auth.current_principal(_credentials(token))
        assert principal.roles == frozenset()
        assert not principal.is_staff


class TestIsStaff:
    def test_teller_is_staff(self):
        assert auth.Principal(subject="x", roles=frozenset({"TELLER"}), token="t").is_staff

    def test_admin_is_staff(self):
        assert auth.Principal(subject="x", roles=frozenset({"ADMIN"}), token="t").is_staff

    def test_customer_is_not_staff(self):
        assert not auth.Principal(subject="x", roles=frozenset({"CUSTOMER"}), token="t").is_staff
