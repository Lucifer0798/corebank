-- Every suspension, reinstatement and closure of a customer, who made it, and why.
--
-- customer.status has allowed SUSPENDED and CLOSED since V1, and the application has acted on them
-- -- no new accounts, no money leaving, no KYC on a closed customer -- but nothing could ever set
-- them. A customer who had left stayed ACTIVE for good, and the only way to stop one was to freeze
-- each account in turn or record a KYC rejection nobody had made.
--
-- Append-only, as account_status_change is: no update or delete exists in the application. seq is
-- the tiebreak that keeps "newest first" meaning the one written last -- see V21.

CREATE TABLE customer_status_change (
    id                  UUID            PRIMARY KEY,
    customer_id         UUID            NOT NULL,
    from_status         VARCHAR(20)     NOT NULL,
    to_status           VARCHAR(20)     NOT NULL,
    changed_by_subject  VARCHAR(64)     NOT NULL,
    changed_by_name     VARCHAR(255),
    reason              VARCHAR(500),
    changed_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    seq                 BIGINT GENERATED ALWAYS AS IDENTITY,

    CONSTRAINT fk_customer_status_change_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_customer_status_change_from     CHECK (from_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_customer_status_change_to       CHECK (to_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    -- Suspending or closing needs a stated reason; the service refuses one without, and this is the
    -- backstop. Reinstating may give one but need not.
    CONSTRAINT ck_customer_status_change_reason   CHECK (to_status = 'ACTIVE' OR reason IS NOT NULL)
);

-- "This customer's history, newest first" -- the only way it is read.
CREATE INDEX idx_customer_status_change_customer ON customer_status_change (customer_id, changed_at, seq);
