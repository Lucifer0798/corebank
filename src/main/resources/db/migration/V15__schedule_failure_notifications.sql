-- Telling a customer when a standing instruction did not pay, and keeping the reason it failed
-- in a form that can be shown to them.
--
-- Until now a notification was always about a posting, and a refused scheduled transfer posts
-- nothing -- so the one event a customer most needs to hear about ("your rent did not go out")
-- was the one the system could not express. V14's columns that describe a posting become
-- optional, and a notification says which kind it is.

ALTER TABLE notification ADD COLUMN kind VARCHAR(40) NOT NULL DEFAULT 'TRANSACTION';
ALTER TABLE notification ADD COLUMN scheduled_transfer_id UUID;
ALTER TABLE notification ADD COLUMN due_on DATE;

ALTER TABLE notification ALTER COLUMN transaction_reference DROP NOT NULL;
ALTER TABLE notification ALTER COLUMN transaction_status DROP NOT NULL;
ALTER TABLE notification ALTER COLUMN direction DROP NOT NULL;

ALTER TABLE notification ADD CONSTRAINT fk_notification_schedule
    FOREIGN KEY (scheduled_transfer_id) REFERENCES scheduled_transfer (id);

ALTER TABLE notification ADD CONSTRAINT ck_notification_kind
    CHECK (kind IN ('TRANSACTION', 'SCHEDULED_TRANSFER_FAILED', 'SCHEDULED_TRANSFER_SUSPENDED'));

-- Each kind carries exactly the fields that describe it. Without this, relaxing the NOT NULLs
-- above would also have allowed a transaction notification with no transaction.
ALTER TABLE notification ADD CONSTRAINT ck_notification_shape CHECK (
    (kind = 'TRANSACTION'
        AND transaction_reference IS NOT NULL AND transaction_status IS NOT NULL AND direction IS NOT NULL
        AND scheduled_transfer_id IS NULL AND due_on IS NULL)
    OR
    (kind <> 'TRANSACTION'
        AND scheduled_transfer_id IS NOT NULL AND due_on IS NOT NULL
        AND transaction_reference IS NULL AND transaction_status IS NULL AND direction IS NULL));

-- One notification per missed occurrence. The runner's own guard already lets an occurrence be
-- recorded as failed only once, and the notification is written in that same transaction; this is
-- the backstop, as uk_notification_once is for postings. NULLs never collide, so transaction
-- notifications are unaffected.
ALTER TABLE notification ADD CONSTRAINT uk_notification_schedule_once UNIQUE (scheduled_transfer_id, due_on);

-- The stable code of the last refusal (INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, ...). last_error
-- holds the exception's message, which names full account numbers and the payer's available
-- balance -- fine in a log, wrong on a screen the payee can read. What is shown is derived from
-- this code instead. Null for failures recorded before it existed, which read as "could not be
-- processed".
ALTER TABLE scheduled_transfer ADD COLUMN last_error_code VARCHAR(50);
