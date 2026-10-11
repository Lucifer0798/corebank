-- Every time a sign-in identity was linked to a customer, who did it, and what it replaced.
--
-- Linking decides which Keycloak login can read a customer's accounts. It used to be recorded only
-- as customer.keycloak_subject, overwritten in place -- and the link could be overwritten: a second
-- link replaced the first without a word, pointing the customer's record at someone else's login
-- and leaving the real customer's own login resolving to nothing. Nothing said who had done it.
--
-- Now a link is set once by staff, and replacing it is a separate, admin-only act that needs a
-- reason. Both are kept here. Append-only, as the other history tables are; history starts here.

CREATE TABLE identity_link (
    id                  UUID            PRIMARY KEY,
    customer_id         UUID            NOT NULL,
    -- Null on a first link; the login it replaced on a relink.
    previous_subject    VARCHAR(64),
    new_subject         VARCHAR(64)     NOT NULL,
    linked_by_subject   VARCHAR(64)     NOT NULL,
    linked_by_name      VARCHAR(255),
    reason              VARCHAR(500),
    linked_at           TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_identity_link_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    -- Replacing a link needs a stated reason; the service refuses one without, this is the backstop.
    CONSTRAINT ck_identity_link_reason   CHECK (previous_subject IS NULL OR reason IS NOT NULL)
);

CREATE INDEX idx_identity_link_customer ON identity_link (customer_id, linked_at);
