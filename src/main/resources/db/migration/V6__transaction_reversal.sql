-- Reversals. `REVERSED` has been a legal value of bank_transaction.status since V1 and the
-- frontend has always had a red pill ready to render it, but nothing could ever set it: there
-- was no way to undo a posting at all. This is that path.
--
-- A reversal is its own transaction, not an edit of the original. ledger_entry is append-only
-- by design (see its javadoc), so undoing a movement means posting the mirrored legs -- same
-- accounts, same amounts, opposite directions -- and marking the original REVERSED. Both the
-- original and its correction stay on the statement, which is what an auditor needs to see:
-- that the money moved and then moved back, not that it never moved.
ALTER TABLE bank_transaction ADD COLUMN reversal_of_transaction_id UUID;

-- REVERSAL joins the type vocabulary. Recreating the constraint rather than relaxing it keeps
-- the database's opinion about legal types identical to the enum's.
ALTER TABLE bank_transaction DROP CONSTRAINT ck_transaction_type;
ALTER TABLE bank_transaction ADD CONSTRAINT ck_transaction_type
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL'));

ALTER TABLE bank_transaction ADD CONSTRAINT fk_transaction_reversal_of
    FOREIGN KEY (reversal_of_transaction_id) REFERENCES bank_transaction (id);

-- "Reverse twice" is refused in the service by checking the original's status, but that check
-- and the insert it guards are two steps: two requests arriving together could both read POSTED.
-- This is the guarantee that doesn't depend on timing -- at most one reversal may ever point at
-- a given transaction. NULLs are distinct under a unique constraint, so the ordinary postings
-- (all of which leave this column NULL) are unaffected.
ALTER TABLE bank_transaction ADD CONSTRAINT uk_transaction_reversal_of
    UNIQUE (reversal_of_transaction_id);

-- The link and the type travel together in both directions: a REVERSAL always names what it
-- reverses, and nothing else ever names anything.
ALTER TABLE bank_transaction ADD CONSTRAINT ck_transaction_reversal_link
    CHECK ((type = 'REVERSAL') = (reversal_of_transaction_id IS NOT NULL));
