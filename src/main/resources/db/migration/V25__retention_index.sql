-- What the nightly retention job deletes idempotency records by. Without it, each of its batched
-- deletes would scan the whole table, which is the table that grows by a row per money-moving
-- request.
--
-- One index for both of its deletes: completed records by when they completed, and abandoned claims
-- (IN_PROGRESS, so completed_at IS NULL) by when they were made. outbox_event needs none: the relay's
-- idx_outbox_event_unpublished (published_at, created_at) already leads with published_at.
CREATE INDEX idx_idempotency_retention ON idempotency_record (status, completed_at, created_at);
