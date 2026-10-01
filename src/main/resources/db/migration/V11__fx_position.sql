-- Valuing the FX position.
--
-- V10 gave the bank position accounts and nothing that reads them. They accumulate whatever the
-- trading does and no part of the system ever asks what they are worth, which is a bank that
-- cannot state its own currency exposure.
--
-- Two accounts are needed, both in the reporting currency, and that is not a style choice: the
-- per-currency balance rule means a revaluation posting can only balance if both its legs are in
-- the same currency. So the foreign positions are never touched by a revaluation. They stay
-- denominated in their own money, and what gets posted is the reporting-currency view of holding
-- them -- which is exactly what a revaluation is.

ALTER TABLE account DROP CONSTRAINT ck_account_type;
ALTER TABLE account ADD CONSTRAINT ck_account_type
    CHECK (account_type IN ('SAVINGS', 'CURRENT', 'CASH_GL', 'SUSPENSE_GL',
                            'INTEREST_EXPENSE_GL', 'FX_POSITION_GL',
                            'FX_REVALUATION_GL', 'FX_GAIN_LOSS_GL'));

INSERT INTO account (
    id, account_number, customer_id, account_class, account_type, normal_balance,
    currency, balance, overdraft_limit, status, opened_at, created_at, updated_at, version
) VALUES
-- Carries the current mark: the reporting-currency value of the whole FX book. Asset-like, so a
-- rising position is a DEBIT. Its balance is the mark, which makes "did the mark get applied
-- correctly" a question with a checkable answer rather than a matter of trust.
('00000000-0000-0000-0000-000000000014', 'GL0000000014', NULL, 'INTERNAL', 'FX_REVALUATION_GL', 'DEBIT',
 'INR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
-- Where the change in the mark is recognised. Income-like: a gain is a CREDIT.
('00000000-0000-0000-0000-000000000015', 'GL0000000015', NULL, 'INTERNAL', 'FX_GAIN_LOSS_GL', 'CREDIT',
 'INR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- Revaluation is the first posting in this ledger that moves no customer money at all -- the bank
-- restating what it already holds rather than anything changing hands. Its own type, so a reader
-- of the ledger is never left inferring that from the accounts involved.
ALTER TABLE bank_transaction DROP CONSTRAINT ck_transaction_type;
ALTER TABLE bank_transaction ADD CONSTRAINT ck_transaction_type
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL', 'INTEREST', 'FX_REVALUATION'));
