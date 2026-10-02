-- Cash and interest expense, in every currency the bank deals in.
--
-- V10 made the ledger balance per currency, which was right: summed in total, a four-leg FX
-- posting balances by coincidence and would accept 1,000 rupees becoming a million dollars. But it
-- had a consequence nobody checked. Cash (V2) and interest expense (V9) had only ever existed in
-- rupees, so every posting pairing a non-rupee customer account with either of them now had one
-- currency debited and another credited, and could not balance.
--
-- The effect, from V10 until this migration:
--   * a dollar, euro or sterling account could not take a cash deposit or a cash withdrawal at
--     all -- it could only ever be funded by an FX transfer from a rupee account;
--   * interest on a non-rupee savings account accrued every day and was never paid. The runner
--     caught the failure and logged a warning each month, so "interest accrued" simply climbed.
--
-- The per-currency rule did its job here. Before it, a dollar deposit would have posted 100 dollars
-- against 100 rupees of cash and balanced, silently, which is far worse than refusing. The fix is
-- not to loosen the rule but to give each currency the internal accounts it was always missing --
-- the same shape V10 already used for the FX position accounts.

INSERT INTO account (
    id, account_number, customer_id, account_class, account_type, normal_balance,
    currency, balance, overdraft_limit, status, opened_at, created_at, updated_at, version
) VALUES
-- Cash, an asset: a deposit debits it, exactly as GL0000000001 does for rupees.
('00000000-0000-0000-0000-000000000021', 'GL0000000021', NULL, 'INTERNAL', 'CASH_GL', 'DEBIT',
 'USD', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000022', 'GL0000000022', NULL, 'INTERNAL', 'CASH_GL', 'DEBIT',
 'EUR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000023', 'GL0000000023', NULL, 'INTERNAL', 'CASH_GL', 'DEBIT',
 'GBP', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
-- Interest expense, the bank's cost of paying interest in each currency, as GL0000000003 is for
-- rupees.
('00000000-0000-0000-0000-000000000031', 'GL0000000031', NULL, 'INTERNAL', 'INTEREST_EXPENSE_GL', 'DEBIT',
 'USD', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000032', 'GL0000000032', NULL, 'INTERNAL', 'INTEREST_EXPENSE_GL', 'DEBIT',
 'EUR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000033', 'GL0000000033', NULL, 'INTERNAL', 'INTEREST_EXPENSE_GL', 'DEBIT',
 'GBP', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- An internal account of a given type in a given currency is now the lookup every posting path
-- makes, so it must be unique: two rupee cash accounts would leave the ledger choosing between
-- them arbitrarily. Partial would be the natural expression -- unique among INTERNAL accounts
-- only -- but H2 has no partial indexes, so the uniqueness is enforced in the lookup instead, by
-- AccountService refusing an ambiguous result. The index below serves that lookup either way.
CREATE INDEX idx_account_internal_lookup ON account (account_class, account_type, currency);
