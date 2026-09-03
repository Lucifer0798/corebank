-- Transactional outbox. Before this, a posted transaction or customer change was published to
-- Kafka directly from an AFTER_COMMIT listener, fire-and-forget: if the broker was unreachable at
-- that exact moment, the event was gone permanently, and every downstream consumer (search,
-- spending insights) silently developed a hole with no way to notice or repair it.
--
-- Now the event row is written in the SAME transaction as the ledger/customer write it describes
-- -- see OutboxEventWriter, a plain @EventListener (not @TransactionalEventListener), so it
-- commits or rolls back atomically with the business change. A separate poller (OutboxRelay)
-- delivers rows to Kafka afterwards and can retry indefinitely, because the fact that the event
-- needs sending is now durable, not held only in a Kafka client's in-flight request.
CREATE TABLE outbox_event (
    id              UUID            PRIMARY KEY,
    aggregate_type  VARCHAR(30)     NOT NULL,
    topic           VARCHAR(100)    NOT NULL,
    event_key       VARCHAR(80)     NOT NULL,
    payload         VARCHAR(4000)   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE,
    attempts        INTEGER         NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    CONSTRAINT ck_outbox_aggregate CHECK (aggregate_type IN ('TRANSACTION', 'CUSTOMER'))
);

-- The relay's own query is "oldest unpublished rows first"; this is the index that serves it.
CREATE INDEX idx_outbox_event_unpublished ON outbox_event (published_at, created_at);
