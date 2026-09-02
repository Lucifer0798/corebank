"""Settings, all overridable by environment variable the same way CoreBank's own are.

Defaults point at localhost so the service runs outside Docker for development; compose.yaml
and k8s/insights.yaml override them with container DNS names.
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="INSIGHTS_", extra="ignore")

    # Its own database, not a schema inside CoreBank's. Sharing a database would let a query here
    # lock a table the ledger is writing, which is exactly the coupling a downstream projection
    # is supposed to avoid -- the same reasoning that keeps OpenSearch out of the ledger.
    database_url: str = "postgresql://corebank:corebank@localhost:5432/insights"
    # Only used to CREATE DATABASE when `database_url`'s database does not exist yet; see
    # store.Store.connect for why that is done in code rather than by an init script.
    database_bootstrap_url: str = "postgresql://corebank:corebank@localhost:5432/corebank"

    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_topic: str = "corebank.transactions.posted"
    # Its own consumer group, independent of corebank-app's and corebank-search-indexer's, so
    # this service's offsets never interfere with theirs.
    kafka_group_id: str = "corebank-insights"

    # Used only to resolve which account numbers a customer holds; see corebank.py.
    corebank_api_url: str = "http://localhost:8080"

    # issuer is compared against the token's `iss` claim as a plain string and never dialled;
    # jwks_url is the one actually fetched. Split for the same reason CoreBank splits them.
    oidc_issuer: str = "http://localhost:8081/realms/corebank"
    oidc_jwks_url: str = "http://localhost:8081/realms/corebank/protocol/openid-connect/certs"

    # Where the trained categoriser and its MLflow run history live.
    #
    # SQLite rather than the `file:` store, and rather than a tracking server: MLflow 3.15 puts
    # the filesystem backend in maintenance mode and *raises* on it unless MLFLOW_ALLOW_FILE_STORE
    # is set, so `file:` now means opting into a deprecated path. A SQLite file is the backend
    # MLflow itself recommends, still needs no extra container, and `mlflow ui --backend-store-uri
    # sqlite:////var/lib/insights/mlflow.db` reads the same runs.
    model_dir: str = "/var/lib/insights/model"
    mlflow_tracking_uri: str = "sqlite:////var/lib/insights/mlflow.db"


@lru_cache
def settings() -> Settings:
    return Settings()
