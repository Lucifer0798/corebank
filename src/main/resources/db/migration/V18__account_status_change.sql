-- Every freeze, unfreeze and closure, who made it, and why.
--
-- None of it was recorded. The account row was overwritten in place: a closure kept closed_at, a
-- freeze kept nothing -- not even when, since updated_at moves on with the next change. A freeze
-- stops all money movement, in as well as out, and is usually a response to a fraud report or a
-- legal order; "who froze this, when, and on what grounds" has to have an answer.
--
-- Append-only, as kyc_decision is: no update or delete exists in the application. History starts
-- here; earlier changes were never recorded.

CREATE TABLE account_status_change (
    id                  UUID            PRIMARY KEY,
    account_id          UUID            NOT NULL,
    from_status         VARCHAR(20)     NOT NULL,
    to_status           VARCHAR(20)     NOT NULL,
    changed_by_subject  VARCHAR(64)     NOT NULL,
    changed_by_name     VARCHAR(255),
    reason              VARCHAR(500),
    changed_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_status_change_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_status_change_from    CHECK (from_status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_status_change_to      CHECK (to_status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    -- Freezing or closing needs a stated reason; the service refuses one without, and this is the
    -- backstop. Returning an account to service may give one but need not.
    CONSTRAINT ck_status_change_reason  CHECK (to_status = 'ACTIVE' OR reason IS NOT NULL)
);

-- "This account's history, newest first" -- the only way it is read.
CREATE INDEX idx_status_change_account ON account_status_change (account_id, changed_at);
