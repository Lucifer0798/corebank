-- The order every history entry was written in, so "newest first" means what it says.
--
-- Each history read newest first by its timestamp alone. Two entries can share one -- a resume and
-- a cancel a moment apart, on a clock that ticks no finer than the database keeps -- and then their
-- order was the database's choice: on H2, oldest first; on PostgreSQL, whatever the plan gave. Staff
-- reading a standing order's history could see it cancelled before it was resumed.
--
-- seq is the tiebreak: every read orders by (timestamp desc, seq desc). It is an identity column,
-- so the database numbers each row as it is inserted, and GENERATED ALWAYS so nothing can write
-- one of its own. Rows already here are numbered as PostgreSQL rewrites the table, in physical
-- order; these tables are only ever inserted into, so that is as near to write order as survives.
--
-- The indexes these reads use gain seq as a last column, so the tiebreak costs no extra sort.

ALTER TABLE kyc_decision ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
DROP INDEX idx_kyc_decision_customer;
CREATE INDEX idx_kyc_decision_customer ON kyc_decision (customer_id, decided_at, seq);

ALTER TABLE account_status_change ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
DROP INDEX idx_status_change_account;
CREATE INDEX idx_status_change_account ON account_status_change (account_id, changed_at, seq);

ALTER TABLE identity_link ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
DROP INDEX idx_identity_link_customer;
CREATE INDEX idx_identity_link_customer ON identity_link (customer_id, linked_at, seq);

ALTER TABLE scheduled_transfer_event ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
DROP INDEX idx_schedule_event_schedule;
CREATE INDEX idx_schedule_event_schedule ON scheduled_transfer_event (scheduled_transfer_id, occurred_at, seq);
