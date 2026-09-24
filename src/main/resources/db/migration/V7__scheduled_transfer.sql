-- Standing instructions: move an amount between two accounts on a schedule.
--
-- This is the first thing in the system that posts without a request behind it, which is what
-- most of the columns below are for. next_run_on is the claim the runner polls; occurrence_index
-- counts occurrences consumed so that every due date is derived from starts_on rather than from
-- the previous date (a month repeatedly added to the last run drifts a rent payment off the 31st
-- and never brings it back); and consecutive_failures is what eventually suspends an instruction
-- nobody can honour instead of retrying it forever.
CREATE TABLE scheduled_transfer (
    id                      UUID            PRIMARY KEY,
    source_account_id       UUID            NOT NULL,
    destination_account_id  UUID            NOT NULL,
    amount                  NUMERIC(19, 4)  NOT NULL,
    currency                VARCHAR(3)      NOT NULL,
    description             VARCHAR(255),
    frequency               VARCHAR(20)     NOT NULL,
    starts_on               DATE            NOT NULL,
    ends_on                 DATE,
    status                  VARCHAR(20)     NOT NULL,
    next_run_on             DATE,
    occurrence_index        INTEGER         NOT NULL DEFAULT 0,
    runs_completed          INTEGER         NOT NULL DEFAULT 0,
    consecutive_failures    INTEGER         NOT NULL DEFAULT 0,
    last_run_on             DATE,
    last_error              VARCHAR(500),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_schedule_source      FOREIGN KEY (source_account_id) REFERENCES account (id),
    CONSTRAINT fk_schedule_destination FOREIGN KEY (destination_account_id) REFERENCES account (id),
    CONSTRAINT ck_schedule_frequency   CHECK (frequency IN ('ONCE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_schedule_status      CHECK (status IN ('ACTIVE', 'SUSPENDED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_schedule_amount      CHECK (amount > 0),
    CONSTRAINT ck_schedule_accounts    CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_schedule_window      CHECK (ends_on IS NULL OR ends_on >= starts_on),

    -- An ACTIVE mandate is one with a date still to come; a stopped one has none. Keeping the two
    -- in step here means the runner's "WHERE status = 'ACTIVE' AND next_run_on <= ?" can never
    -- pick up something that has been cancelled, however the row got written.
    CONSTRAINT ck_schedule_next_run    CHECK ((status = 'ACTIVE') = (next_run_on IS NOT NULL))
);

-- The runner's only query: active mandates that have come due, oldest first. Leading with status
-- rather than making the index partial on it -- a partial index would suit PostgreSQL better,
-- since cancelled and completed rows accumulate forever and none is ever due, but H2 (which the
-- test suite runs these same migrations against) has no partial indexes, and one schema that
-- works on both beats two that drift.
CREATE INDEX idx_scheduled_transfer_due ON scheduled_transfer (status, next_run_on);

-- Serves "what is scheduled against this account", which lists both directions.
CREATE INDEX idx_scheduled_transfer_source ON scheduled_transfer (source_account_id);
CREATE INDEX idx_scheduled_transfer_destination ON scheduled_transfer (destination_account_id);
