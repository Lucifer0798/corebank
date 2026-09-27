-- Authorisation holds: money promised to a merchant but not yet moved.
--
-- Until now available_balance was balance + overdraft, which is the figure a bank can only quote
-- honestly if nothing is ever pending. A card authorised at hotel check-in reserved nothing, so
-- the same money could be spent again before the hotel captured it -- and the posting finally
-- refused would be the one the bank had already guaranteed.
--
-- A hold is deliberately not a ledger entry. Nothing has happened to the bank's position yet, so
-- posting one would record a movement that never occurred. Only a capture becomes a transaction.
ALTER TABLE account ADD COLUMN held_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

-- Denormalised rather than summed from account_hold on every read. Every path that moves money
-- already row-locks the account, so maintaining the running total here is atomic for free;
-- summing instead would put a second query in the hot posting path and leave a window where a
-- hold placed between the two statements is missed.
ALTER TABLE account ADD CONSTRAINT ck_account_held_amount CHECK (held_amount >= 0);

CREATE TABLE account_hold (
    id              UUID            PRIMARY KEY,
    reference       VARCHAR(36)     NOT NULL,
    account_id      UUID            NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    description     VARCHAR(255),
    status          VARCHAR(20)     NOT NULL,
    placed_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at      TIMESTAMP WITH TIME ZONE,
    captured_transaction_reference VARCHAR(36),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT uk_hold_reference  UNIQUE (reference),
    CONSTRAINT fk_hold_account    FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_hold_status     CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_hold_amount     CHECK (amount > 0),
    CONSTRAINT ck_hold_window     CHECK (expires_at > placed_at),

    -- An outstanding hold has not been settled and produced no posting; a settled one has a time.
    -- Keeping these in step means "is this hold still reserving money" has one answer, whichever
    -- column you read.
    CONSTRAINT ck_hold_settled    CHECK ((status = 'ACTIVE') = (settled_at IS NULL)),
    -- Only a capture moves money, so only a capture may name a transaction.
    CONSTRAINT ck_hold_capture    CHECK (status = 'CAPTURED' OR captured_transaction_reference IS NULL)
);

-- The expiry sweep's query: outstanding holds that are past their time. Status leads for the
-- same reason it does on scheduled_transfer -- H2, which runs these migrations in the test
-- suite, has no partial indexes.
CREATE INDEX idx_hold_expiry ON account_hold (status, expires_at);

-- "What is outstanding against this account", which the balance breakdown and reconciliation
-- both want.
CREATE INDEX idx_hold_account ON account_hold (account_id, status);
