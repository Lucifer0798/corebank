"""The alert rules and this service agree on what its metrics are called.

The same check CoreBank's AlertRulesTest makes for the Java side. promtool proves each rule fires on
the series it is given, but takes the series' names on trust, and prometheus_client adds suffixes
on the way out (a Counter named insights_events is exported as insights_events_total). A rule that
names a metric this service never exports is an alert that can never fire, and never says so.
"""

from __future__ import annotations

import re
from pathlib import Path

import yaml
from prometheus_client import REGISTRY, generate_latest

from app import metrics  # noqa: F401 -- registers this service's metrics

OBSERVABILITY = Path(__file__).resolve().parents[2] / "observability"
RULES = OBSERVABILITY / "alerts.yml"
METRIC = re.compile(r"\binsights_[a-z_]+")


def _rules() -> list[dict]:
    document = yaml.safe_load(RULES.read_text(encoding="utf-8"))
    return [rule for group in document["groups"] for rule in group["rules"]]


def test_every_metric_a_rule_names_is_exported():
    named = {name for rule in _rules() for name in METRIC.findall(rule["expr"])}
    assert named, "the rules name some of this service's metrics"

    exported = {line.split("{")[0].split(" ")[0]
                for line in generate_latest(REGISTRY).decode().splitlines()
                if line and not line.startswith("#")}

    assert named <= exported, f"named in alerts.yml but not exported: {sorted(named - exported)}"


def test_prometheus_scrapes_this_service():
    # Without the scrape job every rule above watches series that never arrive: up{job="insights"}
    # is absent rather than 0, so even the "not consuming" alert would stay quiet.
    config = yaml.safe_load((OBSERVABILITY / "prometheus.yml").read_text(encoding="utf-8"))
    jobs = {job["job_name"]: job for job in config["scrape_configs"]}
    assert "insights" in jobs
    job = jobs["insights"]
    assert job["metrics_path"] == "/metrics"
    assert [target for group in job["static_configs"] for target in group["targets"]] == ["insights:8000"]

