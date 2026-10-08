-- Every KYC decision, who made it, and why.
--
-- Until now a decision overwrote customer.kyc_status and left nothing else: not who made it, not
-- why, not what the status had been. Since a lapsed KYC stops money leaving the customer's
-- accounts, "who rejected this customer, and on what grounds" is a question the bank has to be
-- able to answer -- to the customer, and to an auditor.
--
-- Append-only. Nothing in the application updates or deletes a row: the repository declares no
-- such method, and the entity is immutable. History starts when this table does; decisions made
-- before it were never recorded and cannot be reconstructed.

CREATE TABLE kyc_decision (
    id                  UUID            PRIMARY KEY,
    customer_id         UUID            NOT NULL,
    from_status         VARCHAR(20)     NOT NULL,
    to_status           VARCHAR(20)     NOT NULL,
    -- The token's sub claim: stable for the life of the Keycloak user, unlike the username.
    decided_by_subject  VARCHAR(64)     NOT NULL,
    -- The username as it was at the time, for a person reading the history.
    decided_by_name     VARCHAR(255),
    reason              VARCHAR(500),
    decided_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_kyc_decision_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_kyc_decision_from CHECK (from_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_kyc_decision_to   CHECK (to_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    -- A decision that restricts the customer needs a stated reason. The service refuses one without;
    -- this is the backstop for anything that ever writes here another way.
    CONSTRAINT ck_kyc_decision_reason CHECK (to_status = 'VERIFIED' OR reason IS NOT NULL)
);

-- "This customer's decisions, newest first" -- the only way they are read.
CREATE INDEX idx_kyc_decision_customer ON kyc_decision (customer_id, decided_at);
