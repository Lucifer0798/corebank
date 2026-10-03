-- A hold whose capture was reversed.
--
-- A capture produces an ordinary WITHDRAWAL, and withdrawals are reversible. Reversing one returned
-- the money correctly, but the hold was never told: it stayed CAPTURED, still naming the posting
-- that had been undone. Anyone reading it saw a merchant who had been paid, when the payment had
-- been reversed. The balances were right; the record said something false.
--
-- CAPTURE_REVERSED rather than reusing RELEASED. The two leave the customer in the same place, but
-- they are different histories -- a release means no money ever moved, a reversed capture means it
-- moved and came back -- and reconciliation against the merchant needs to tell them apart.

ALTER TABLE account_hold DROP CONSTRAINT ck_hold_status;
ALTER TABLE account_hold ADD CONSTRAINT ck_hold_status
    CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED', 'CAPTURE_REVERSED'));

-- A reversed capture still names the posting it captured into -- that capture really happened --
-- so the rule "only a captured hold names a transaction" widens to include it.
ALTER TABLE account_hold DROP CONSTRAINT ck_hold_capture;
ALTER TABLE account_hold ADD CONSTRAINT ck_hold_capture
    CHECK (status IN ('CAPTURED', 'CAPTURE_REVERSED') OR captured_transaction_reference IS NULL);

-- The reversal that undid it. Set exactly when the capture was reversed, and never otherwise, so
-- "was this merchant's payment taken back" has one answer whichever column is read.
ALTER TABLE account_hold ADD COLUMN capture_reversal_reference VARCHAR(36);
ALTER TABLE account_hold ADD CONSTRAINT ck_hold_capture_reversal
    CHECK ((status = 'CAPTURE_REVERSED') = (capture_reversal_reference IS NOT NULL));

-- The lookup a reversal makes: which hold, if any, captured into this posting.
CREATE INDEX idx_hold_captured_reference ON account_hold (captured_transaction_reference);
