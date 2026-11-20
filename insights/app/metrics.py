"""What Prometheus reads from this service, at /metrics.

Before this, nothing here was scraped at all: a consumer that had stopped, a database it could not
reach, or events it was skipping showed only in the log, and none of CoreBank's alerts could see
them. observability/alerts.yml has the rules that watch these.

Every series exists from start-up, at zero. A counter that first appears at 1 has no earlier
sample for ``increase()`` to compare against, so the first event would never fire its alert.
"""

from __future__ import annotations

from collections.abc import Callable

from prometheus_client import Counter, Gauge

EVENTS = Counter(
    "insights_events",
    "Events the consumer dealt with: stored, or skipped as malformed or as unusable",
    ["outcome"],
)
STORED = EVENTS.labels(outcome="stored")
MALFORMED = EVENTS.labels(outcome="malformed")
SKIPPED = EVENTS.labels(outcome="skipped")

STORE_RETRIES = Counter(
    "insights_store_retries",
    "Attempts to store an event that failed because the database was unavailable, and will be retried",
)

CONSUMER_RUNNING = Gauge(
    "insights_consumer_running",
    "1 while the Kafka consumer is consuming, 0 once it has stopped or before it has started",
)


def report_consumer(is_running: Callable[[], bool]) -> None:
    """Reads the consumer's state at each scrape, rather than keeping a copy that could go stale."""
    CONSUMER_RUNNING.set_function(lambda: 1.0 if is_running() else 0.0)
