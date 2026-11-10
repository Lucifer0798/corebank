package com.corebank.outbox.repository;

import java.time.Instant;

/** How many events are still waiting to reach Kafka, and when the oldest of them was written. */
public record OutboxBacklog(long waiting, Instant oldest) {
}
