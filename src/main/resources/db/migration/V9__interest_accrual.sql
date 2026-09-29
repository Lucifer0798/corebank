-- Interest on savings: accrued daily, capitalised monthly.
--
-- The storage scale this whole schema uses was chosen for this feature -- NUMERIC(19,4) rather
-- than (19,2) -- and accrued_interest is the column that actually needs it. A day's interest on a
-- modest balance is a fraction of a paisa; rounded to two places daily it would round to zero, or
-- worse, round consistently in one direction and drift a real amount over a year. It accumulates
-- here at full scale and is only rounded when it becomes a posting.
ALTER TABLE account ADD COLUMN accrued_interest NUMERIC(19, 4) NOT NULL DEFAULT 0;

-- Interest is owed the moment it accrues, so it can never be negative: nothing in the system
-- takes accrued interest away, and capitalisation only ever subtracts what it has just posted.
ALTER TABLE account ADD CONSTRAINT ck_account_accrued CHECK (accrued_interest >= 0);

-- A new internal account for the other side of the posting. Interest paid to a customer is the
-- bank's expense, so its normal balance is DEBIT: capitalising credits the customer (the bank
-- owes more) and debits this (the bank has spent more), exactly as a deposit debits cash.
ALTER TABLE account DROP CONSTRAINT ck_account_type;
ALTER TABLE account ADD CONSTRAINT ck_account_type
    CHECK (account_type IN ('SAVINGS', 'CURRENT', 'CASH_GL', 'SUSPENSE_GL', 'INTEREST_EXPENSE_GL'));

INSERT INTO account (
    id, account_number, customer_id, account_class, account_type, normal_balance,
    currency, balance, overdraft_limit, status, opened_at, created_at, updated_at, version
) VALUES
('00000000-0000-0000-0000-000000000003', 'GL0000000003', NULL, 'INTERNAL', 'INTEREST_EXPENSE_GL', 'DEBIT',
 'INR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- Capitalisation is a posting like any other, and wants its own type so a statement can say what
-- the line is rather than calling a month of interest a deposit.
ALTER TABLE bank_transaction DROP CONSTRAINT ck_transaction_type;
ALTER TABLE bank_transaction ADD CONSTRAINT ck_transaction_type
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL', 'INTEREST'));

-- When each account last had interest added to it, so an accrual run can tell which accounts it
-- has already covered today and a restart mid-run cannot double-accrue.
ALTER TABLE account ADD COLUMN interest_accrued_through DATE;

-- The accrual sweep's query: savings accounts not yet accrued for the day in question.
CREATE INDEX idx_account_accrual ON account (account_type, interest_accrued_through);
