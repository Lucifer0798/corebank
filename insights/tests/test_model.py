"""Tests for the two pieces with real logic in them: the categoriser and leg signing.

The API and consumer are covered end to end against real Kafka/Postgres/Keycloak in
CoreBankTestcontainersIT's counterpart script rather than mocked here -- the same split the Java
side already makes between unit tests and the real-infrastructure suite.
"""

import tempfile
from decimal import Decimal

import pytest

from app.consumer import _is_customer_account, _signed_amount
from app.model import UNCATEGORISED, Categoriser


@pytest.fixture(scope="module")
def categoriser() -> Categoriser:
    # Trains into a throwaway directory so the test never depends on, or clobbers, a model the
    # running service is using.
    with tempfile.TemporaryDirectory() as tmp:
        # SQLite, matching the service: MLflow 3.15 refuses the `file:` store by default.
        yield Categoriser(model_dir=tmp, tracking_uri=f"sqlite:///{tmp}/mlflow.db")


class TestCategoriser:
    @pytest.mark.parametrize(
        "description,expected",
        [
            ("swiggy dinner order", "DINING"),
            ("uber ride to office", "TRANSPORT"),
            ("electricity bill payment", "UTILITIES"),
            ("monthly house rent", "RENT"),
            ("apollo pharmacy medicines", "HEALTHCARE"),
            ("branch counter deposit", "TRANSFERS"),
        ],
    )
    def test_recognises_clear_descriptions(self, categoriser, description, expected):
        category, confidence = categoriser.categorise(description)
        assert category == expected
        assert 0.0 < confidence <= 1.0

    def test_generalises_past_the_exact_seed_wording(self, categoriser):
        # Not in SEED_EXAMPLES verbatim. Character n-grams are chosen precisely so that near
        # wording and typos still land, which is what makes the model more than a lookup table.
        category, _ = categoriser.categorise("swiggy ordr late night")
        assert category == "DINING"

    def test_blank_description_is_not_guessed(self, categoriser):
        # A spending summary someone might act on should not contain invented categories.
        assert categoriser.categorise(None) == (UNCATEGORISED, 0.0)
        assert categoriser.categorise("   ") == (UNCATEGORISED, 0.0)

    def test_every_seed_category_is_learnable(self, categoriser):
        assert "TRANSFERS" in categoriser.categories
        assert len(categoriser.categories) >= 8


class TestLegSigning:
    def test_credit_increases_a_customer_account(self):
        # A customer account is a liability of the bank, so money in is a CREDIT -- the same rule
        # as CoreBank's Account.applyEntry.
        assert _signed_amount("CREDIT", "500.00", "100100000001") == Decimal("500.00")

    def test_debit_reduces_a_customer_account(self):
        assert _signed_amount("DEBIT", "500.00", "100100000001") == Decimal("-500.00")

    def test_general_ledger_legs_are_not_customer_spending(self):
        # Every deposit has a GL contra leg; counting it would double-count the transaction and
        # attribute the bank's own cash movements to a customer.
        assert not _is_customer_account("GL0000000001")
        assert _is_customer_account("100100000001")
