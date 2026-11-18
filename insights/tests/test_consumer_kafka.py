"""The consumer against a real Kafka broker.

test_consumer.py proves what _run does with each message using a stand-in for the Kafka client.
What only a real broker shows is that this is how the real client behaves: that a message which
is not JSON reaches _run as bytes it can skip, rather than raising out of the client's own
iterator, which is what used to end the consumer for good; and that offsets really are committed,
so a restart carries on after what was handled instead of from the start.

The store and categoriser are stand-ins; what is stored is test_store.py's concern.
"""

from __future__ import annotations

import asyncio
import json
import socket
import uuid

import pytest
from aiokafka import AIOKafkaProducer
from testcontainers.core.container import DockerContainer
from testcontainers.core.waiting_utils import wait_for_logs

from app.consumer import TransactionConsumer


def _free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


@pytest.fixture(scope="module")
def bootstrap_servers():
    # The same image CoreBank's own integration test uses. Bound to the same port inside and out,
    # so the address the broker advertises is the one this test can reach.
    port = _free_port()
    container = (
        DockerContainer("apache/kafka:4.3.1")
        .with_bind_ports(port, port)
        .with_env("KAFKA_NODE_ID", "1")
        .with_env("KAFKA_PROCESS_ROLES", "broker,controller")
        .with_env("KAFKA_LISTENERS", f"PLAINTEXT://:{port},CONTROLLER://:9093")
        .with_env("KAFKA_ADVERTISED_LISTENERS", f"PLAINTEXT://localhost:{port}")
        .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
        .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
        .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .with_env("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
    )
    with container:
        wait_for_logs(container, "Kafka Server started", timeout=120)
        yield f"localhost:{port}"


class _Store:
    def __init__(self) -> None:
        self.references: list[str] = []

    async def upsert_entry(self, **row):
        self.references.append(row["reference"])


class _Categoriser:
    def categorise(self, description):
        return "SHOPPING", 0.5


def _event(reference: str) -> bytes:
    return json.dumps({
        "reference": reference, "type": "DEPOSIT", "amount": "5.00", "currency": "INR",
        "description": "coffee", "postedAt": "2026-11-18T10:00:00Z",
        "legs": [{"accountNumber": "100100000001", "direction": "CREDIT", "amount": "5.00"}],
    }).encode()


async def _publish(bootstrap_servers: str, topic: str, *values: bytes) -> None:
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap_servers)
    await producer.start()
    try:
        for value in values:
            # One partition, so the order below is the order consumed.
            await producer.send_and_wait(topic, value, partition=0)
    finally:
        await producer.stop()


async def _until(condition, seconds: float = 30) -> None:
    deadline = asyncio.get_running_loop().time() + seconds
    while not condition():
        if asyncio.get_running_loop().time() > deadline:
            raise AssertionError("timed out waiting")
        await asyncio.sleep(0.2)


def _consumer(bootstrap_servers, topic, group, store):
    return TransactionConsumer(bootstrap_servers=bootstrap_servers, topic=topic, group_id=group,
                               store=store, categoriser=_Categoriser())


async def test_a_malformed_message_does_not_stop_it(bootstrap_servers):
    topic, group = f"insights-test-{uuid.uuid4().hex}", f"insights-test-{uuid.uuid4().hex}"
    await _publish(bootstrap_servers, topic, _event("TXN-BEFORE"), b"not json {", _event("TXN-AFTER"))

    store = _Store()
    consumer = _consumer(bootstrap_servers, topic, group, store)
    await consumer.start()
    try:
        await _until(lambda: "TXN-AFTER" in store.references)
        assert store.references == ["TXN-BEFORE", "TXN-AFTER"]
        assert consumer.is_running
    finally:
        await consumer.stop()


async def test_a_restart_carries_on_after_what_was_handled(bootstrap_servers):
    topic, group = f"insights-test-{uuid.uuid4().hex}", f"insights-test-{uuid.uuid4().hex}"
    await _publish(bootstrap_servers, topic, _event("TXN-1"), b"not json {")

    first = _Store()
    consumer = _consumer(bootstrap_servers, topic, group, first)
    await consumer.start()
    try:
        await _until(lambda: "TXN-1" in first.references)
        # Long enough for the malformed message behind it to be skipped and committed too.
        await asyncio.sleep(2)
    finally:
        await consumer.stop()

    await _publish(bootstrap_servers, topic, _event("TXN-2"))
    second = _Store()
    consumer = _consumer(bootstrap_servers, topic, group, second)
    await consumer.start()
    try:
        await _until(lambda: "TXN-2" in second.references)
        # Neither TXN-1 again nor the malformed message: both were committed before the restart.
        assert second.references == ["TXN-2"]
        assert consumer.is_running
    finally:
        await consumer.stop()
