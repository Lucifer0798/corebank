"""The categoriser: does it actually learn the seed categories, and does it degrade sensibly.

Leg signing and the GL filter live in test_consumer.py; the store's SQL lives in test_store.py
(against a real Postgres); the routes live in test_api.py; token validation lives in
test_auth.py; the CoreBank HTTP client lives in test_corebank_client.py.
"""

import tempfile

import pytest

from app.model import UNCATEGORISED, Categoriser


@pytest.fixture(scope="module")
def categoriser() -> Categoriser:
    # Trains into a throwaway directory so the test never depends on, or clobbers, a model the
    # running service is using.
    #
    # ignore_cleanup_errors=True: on Windows, MLflow's SQLite backend can still hold mlflow.db
    # open when the directory is torn down, which turns into a PermissionError from
    # TemporaryDirectory's own cleanup rather than a real assertion failure. The service itself
    # only ever runs in the Linux container, where this does not happen; this is purely about
    # running the suite on a Windows host without a spurious failure.
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
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
