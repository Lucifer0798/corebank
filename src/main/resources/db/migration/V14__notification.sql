-- Customer notifications: one per customer account a posting touched, telling its owner what moved.
--
-- The first real consumer of corebank.transactions.posted. Until now TransactionEventLogger only
-- printed each message, which proved the round trip and nothing else -- and in particular never had
-- to care that Kafka delivers at least once. A notification does: a customer told "500 debited"
-- twice for one withdrawal has been told something false.

CREATE TABLE notification (
    id                      UUID            PRIMARY KEY,
    customer_id             UUID            NOT NULL,
    account_id              UUID            NOT NULL,
    transaction_reference   VARCHAR(36)     NOT NULL,
    -- POSTED or REVERSED. Part of the key below, because the same reference legitimately produces
    -- two notifications: one when it is posted, and one if it is later reversed.
    transaction_status      VARCHAR(20)     NOT NULL,
    -- The leg's direction on this account, which is what decides "debited" against "credited".
    direction               VARCHAR(10)     NOT NULL,
    amount                  NUMERIC(19, 4)  NOT NULL,
    -- The account's own currency, not the transaction's. They differ on the receiving side of an FX
    -- transfer, and a customer told their dollar account received rupees has been told nonsense.
    currency                VARCHAR(3)      NOT NULL,
    message                 VARCHAR(255)    NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_notification_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_notification_account  FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_notification_status   CHECK (transaction_status IN ('POSTED', 'REVERSED')),
    CONSTRAINT ck_notification_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_notification_amount   CHECK (amount > 0),

    -- The delivery guarantee, enforced where it cannot be argued with. Kafka delivers at least once
    -- and the outbox relay retries, so the same message can arrive more than once; this is what
    -- makes the second arrival a no-op rather than a second alert. The consumer checks first, and
    -- this is the backstop for anything that check misses.
    CONSTRAINT uk_notification_once UNIQUE (transaction_reference, account_id, transaction_status)
);

-- "My notifications, newest first" -- the only way they are read.
CREATE INDEX idx_notification_customer ON notification (customer_id, created_at);
