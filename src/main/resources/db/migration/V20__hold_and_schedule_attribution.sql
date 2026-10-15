-- Who placed and settled each hold, who set up each standing order, and what happened to it since.
--
-- A hold reserves a customer's money and a standing order moves it every month, but neither
-- recorded who did it. A capture's posting has said who since V17; placing the hold, releasing
-- it, or its expiring, did not. A standing order's payments are attributed to the runner, which is
-- true and says nothing about who authorised them -- and cancelling or resuming one left no trace.
-- Everything before this was never recorded and is left null.

-- A hold is placed once and settled once (captured, released or expired), so its row holds both.
ALTER TABLE account_hold ADD COLUMN placed_by_subject VARCHAR(64);
ALTER TABLE account_hold ADD COLUMN placed_by_name VARCHAR(255);
ALTER TABLE account_hold ADD COLUMN settled_by_subject VARCHAR(64);
ALTER TABLE account_hold ADD COLUMN settled_by_name VARCHAR(255);

-- Who set the standing order up: on the row, since every listing shows it.
ALTER TABLE scheduled_transfer ADD COLUMN created_by_subject VARCHAR(64);
ALTER TABLE scheduled_transfer ADD COLUMN created_by_name VARCHAR(255);

-- What happened to it afterwards. Resuming can happen any number of times, so this is a history,
-- not a column. Append-only, like the other histories. SUSPENDED is the runner's doing, recorded as
-- system:scheduled-transfer-runner.
CREATE TABLE scheduled_transfer_event (
    id                      UUID            PRIMARY KEY,
    scheduled_transfer_id   UUID            NOT NULL,
    event                   VARCHAR(20)     NOT NULL,
    actor_subject           VARCHAR(64)     NOT NULL,
    actor_name              VARCHAR(255),
    -- Optional: cancelling or resuming is usually routine, on the customer's own request.
    reason                  VARCHAR(500),
    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_schedule_event_schedule FOREIGN KEY (scheduled_transfer_id) REFERENCES scheduled_transfer (id),
    CONSTRAINT ck_schedule_event          CHECK (event IN ('CANCELLED', 'RESUMED', 'SUSPENDED'))
);

CREATE INDEX idx_schedule_event_schedule ON scheduled_transfer_event (scheduled_transfer_id, occurred_at);
