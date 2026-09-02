"""The spending categoriser: seed data, training, and inference.

Kept in one module because the three are one concern -- the label set is the thing the training
and the inference both have to agree on, and splitting them across files is how those drift.

**The training data is synthetic and hand-written** (`SEED_EXAMPLES` below). CoreBank has no real
merchant feed: its transaction descriptions come from tellers and test fixtures. So this is an
honest small model over a plausible label set, not a model learned from real customer behaviour,
and its accuracy figure describes the seed set rather than the world. Swapping in a real labelled
export later means replacing SEED_EXAMPLES and re-running training; nothing else changes.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

import joblib
import mlflow
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import FeatureUnion, Pipeline

log = logging.getLogger(__name__)

UNCATEGORISED = "UNCATEGORISED"

# Deliberately small and readable. The word/character feature union (see _build_pipeline) lets
# this generalise past the exact wording better than the example count suggests -- but see the
# module docstring: this is a plausible seed set, not real data.
SEED_EXAMPLES: list[tuple[str, str]] = [
    # GROCERIES
    ("big bazaar weekly groceries", "GROCERIES"),
    ("dmart supermarket", "GROCERIES"),
    ("reliance fresh vegetables", "GROCERIES"),
    ("more supermarket monthly shop", "GROCERIES"),
    ("grocery store card payment", "GROCERIES"),
    ("bigbasket online grocery", "GROCERIES"),
    ("local kirana store", "GROCERIES"),
    ("spencers retail groceries", "GROCERIES"),
    # DINING
    ("swiggy food order", "DINING"),
    ("zomato dinner", "DINING"),
    ("cafe coffee day", "DINING"),
    ("restaurant bill settlement", "DINING"),
    ("dominos pizza", "DINING"),
    ("starbucks coffee", "DINING"),
    ("lunch at canteen", "DINING"),
    ("bakery purchase", "DINING"),
    # TRANSPORT
    ("uber ride", "TRANSPORT"),
    ("ola cab fare", "TRANSPORT"),
    ("indian oil petrol pump", "TRANSPORT"),
    ("metro card recharge", "TRANSPORT"),
    ("irctc train ticket", "TRANSPORT"),
    ("fastag toll recharge", "TRANSPORT"),
    ("bus pass monthly", "TRANSPORT"),
    ("hp petrol fuel", "TRANSPORT"),
    # UTILITIES
    ("electricity bill payment", "UTILITIES"),
    ("water bill municipal", "UTILITIES"),
    ("airtel broadband bill", "UTILITIES"),
    ("jio mobile recharge", "UTILITIES"),
    ("gas cylinder booking", "UTILITIES"),
    ("dth tv recharge", "UTILITIES"),
    ("postpaid mobile bill", "UTILITIES"),
    ("internet monthly charges", "UTILITIES"),
    # RENT
    ("monthly house rent", "RENT"),
    ("rent for august", "RENT"),
    ("landlord rent transfer", "RENT"),
    ("flat rental payment", "RENT"),
    ("maintenance and rent society", "RENT"),
    ("apartment rent", "RENT"),
    # HEALTHCARE
    ("apollo pharmacy medicines", "HEALTHCARE"),
    ("hospital consultation fee", "HEALTHCARE"),
    ("diagnostic lab tests", "HEALTHCARE"),
    ("medical store purchase", "HEALTHCARE"),
    ("dental clinic treatment", "HEALTHCARE"),
    ("health insurance premium", "HEALTHCARE"),
    # SHOPPING
    ("amazon order", "SHOPPING"),
    ("flipkart purchase", "SHOPPING"),
    ("myntra clothing", "SHOPPING"),
    ("electronics store tv", "SHOPPING"),
    ("furniture purchase", "SHOPPING"),
    ("shoes and apparel", "SHOPPING"),
    # ENTERTAINMENT
    ("netflix subscription", "ENTERTAINMENT"),
    ("bookmyshow movie tickets", "ENTERTAINMENT"),
    ("spotify premium", "ENTERTAINMENT"),
    ("gaming subscription", "ENTERTAINMENT"),
    ("amusement park tickets", "ENTERTAINMENT"),
    ("concert booking", "ENTERTAINMENT"),
    # TRANSFERS -- the bank's own vocabulary, which is what most real CoreBank descriptions
    # actually look like today.
    ("branch counter deposit", "TRANSFERS"),
    ("atm cash withdrawal", "TRANSFERS"),
    ("neft transfer to savings", "TRANSFERS"),
    ("imps fund transfer", "TRANSFERS"),
    ("upi payment to contact", "TRANSFERS"),
    ("salary credit", "TRANSFERS"),
    ("cash deposit at branch", "TRANSFERS"),
    ("account to account transfer", "TRANSFERS"),
]

MODEL_FILENAME = "categoriser.joblib"


def _build_pipeline() -> Pipeline:
    return Pipeline(
        [
            (
                "features",
                # Word features *and* character features, not either alone.
                #
                # Character n-grams alone were tried first, for typo tolerance, and were measurably
                # wrong: "electricity bill payment" classified as RENT, because char n-grams see
                # "ent"/"ment" shared between *paym-ent* and *r-ent* and weight that over the word
                # "electricity". Word features carry the actual signal (brand and keyword terms are
                # what identify these), while the character half keeps "swiggy ordr" working.
                FeatureUnion(
                    [
                        ("word", TfidfVectorizer(analyzer="word", ngram_range=(1, 2),
                                                 sublinear_tf=True)),
                        ("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5),
                                                 sublinear_tf=True)),
                    ],
                    # Word features dominate; the character half is a fallback for wording the
                    # word vocabulary has never seen, not an equal vote.
                    transformer_weights={"word": 1.0, "char": 0.3},
                ),
            ),
            ("clf", LogisticRegression(max_iter=1000, class_weight="balanced")),
        ]
    )


def train(model_dir: str, tracking_uri: str) -> dict[str, float]:
    """Trains, logs the run to MLflow, and writes the model where the service will load it."""
    texts = [t for t, _ in SEED_EXAMPLES]
    labels = [c for _, c in SEED_EXAMPLES]

    # Stratified so every category appears in both halves; the seed set is small enough that an
    # unlucky split would otherwise leave a class untested entirely.
    x_train, x_test, y_train, y_test = train_test_split(
        texts, labels, test_size=0.25, random_state=42, stratify=labels
    )

    # Measure on a held-out split...
    pipeline = _build_pipeline()
    pipeline.fit(x_train, y_train)
    predictions = pipeline.predict(x_test)
    metrics = {
        "accuracy": float(accuracy_score(y_test, predictions)),
        "f1_macro": float(f1_score(y_test, predictions, average="macro")),
    }

    # ...then refit on everything for the artifact actually served. With a seed set this small,
    # shipping the split-trained model throws away a quarter of the only data there is -- and it
    # showed: the split-trained model failed on "electricity bill payment", a phrase that is in
    # SEED_EXAMPLES but had landed in the test half. The metrics above still describe the
    # held-out split, so they stay an honest estimate rather than a score against training data.
    pipeline = _build_pipeline()
    pipeline.fit(texts, labels)

    mlflow.set_tracking_uri(tracking_uri)
    mlflow.set_experiment("corebank-spending-categoriser")
    with mlflow.start_run():
        mlflow.log_param("vectoriser", "tfidf-union-word1-2-w1.0+char_wb3-5-w0.3")
        mlflow.log_param("final_fit", "full-seed-set")
        mlflow.log_param("classifier", "logistic-regression")
        mlflow.log_param("seed_examples", len(SEED_EXAMPLES))
        mlflow.log_param("categories", len(set(labels)))
        mlflow.log_metrics(metrics)

    Path(model_dir).mkdir(parents=True, exist_ok=True)
    joblib.dump(pipeline, os.path.join(model_dir, MODEL_FILENAME))
    log.info("Trained categoriser: %s", metrics)
    return metrics


class Categoriser:
    """Loads the trained pipeline, training one first if none exists yet.

    Training on demand keeps a fresh clone working with nothing but `docker compose up` -- there
    is no model artifact committed to the repository and no separate build step to forget -- while
    still recording a real MLflow run for the model actually being served.
    """

    def __init__(self, model_dir: str, tracking_uri: str) -> None:
        path = Path(model_dir) / MODEL_FILENAME
        if not path.exists():
            log.info("No categoriser at %s; training one", path)
            train(model_dir, tracking_uri)
        self._pipeline: Pipeline = joblib.load(path)
        self.categories: list[str] = sorted(self._pipeline.classes_)

    def categorise(self, description: str | None) -> tuple[str, float]:
        """Returns (category, confidence).

        An empty description is UNCATEGORISED rather than a guess: CoreBank's description field is
        optional, and inventing a category for a transaction that carries no text at all would be
        a made-up number in a spending summary someone might act on.
        """
        if not description or not description.strip():
            return UNCATEGORISED, 0.0
        probabilities = self._pipeline.predict_proba([description])[0]
        best = int(probabilities.argmax())
        return str(self._pipeline.classes_[best]), float(probabilities[best])
