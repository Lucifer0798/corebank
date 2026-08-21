-- Internal general-ledger accounts.
-- Cash is an asset, so its normal balance is DEBIT: a customer deposit debits cash
-- (the bank holds more money) and credits the customer's account (the bank owes more).
INSERT INTO account (
    id, account_number, customer_id, account_class, account_type, normal_balance,
    currency, balance, overdraft_limit, status, opened_at, created_at, updated_at, version
) VALUES
('00000000-0000-0000-0000-000000000001', 'GL0000000001', NULL, 'INTERNAL', 'CASH_GL', 'DEBIT',
 'INR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000002', 'GL0000000002', NULL, 'INTERNAL', 'SUSPENSE_GL', 'CREDIT',
 'INR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
