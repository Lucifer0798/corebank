-- Cross-currency transfers.
--
-- Money.BASE_CURRENCY has said since Phase 1 that "multi-currency ledgers arrive with FX in a
-- later phase". Until now Account.assertCurrency refused any posting whose currency did not match
-- the account, which kept the ledger honest by keeping it monolingual.
--
-- An FX transfer is four legs, not two, and that is the whole shape of this change. The customer
-- pays in one currency and is paid in another, so there is no single pair of entries that
-- balances: each currency has to balance on its own, against a position account that stands
-- between them. What the bank is really doing is buying one currency and selling another, and the
-- position accounts are where that trade lives.

ALTER TABLE account DROP CONSTRAINT ck_account_type;
ALTER TABLE account ADD CONSTRAINT ck_account_type
    CHECK (account_type IN ('SAVINGS', 'CURRENT', 'CASH_GL', 'SUSPENSE_GL',
                            'INTEREST_EXPENSE_GL', 'FX_POSITION_GL'));

-- One position account per currency the bank deals in. Normal balance CREDIT, like the suspense
-- account: receiving a currency from a customer credits it (the bank has taken the currency on),
-- and paying one out debits it, which drives it negative to show the bank is short. Netting the
-- lot at market rates is the bank's FX position, and the spread accumulates here as it trades.
INSERT INTO account (
    id, account_number, customer_id, account_class, account_type, normal_balance,
    currency, balance, overdraft_limit, status, opened_at, created_at, updated_at, version
) VALUES
('00000000-0000-0000-0000-000000000010', 'GL0000000010', NULL, 'INTERNAL', 'FX_POSITION_GL', 'CREDIT',
 'INR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000011', 'GL0000000011', NULL, 'INTERNAL', 'FX_POSITION_GL', 'CREDIT',
 'USD', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000012', 'GL0000000012', NULL, 'INTERNAL', 'FX_POSITION_GL', 'CREDIT',
 'EUR', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-000000000013', 'GL0000000013', NULL, 'INTERNAL', 'FX_POSITION_GL', 'CREDIT',
 'GBP', 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- Rates, one row per ordered pair: 1 unit of base_currency buys mid_rate of quote_currency.
--
-- Both directions are stored explicitly rather than inverting one. Inverting looks tidy and is
-- wrong in two ways: the arithmetic of 1/rate loses precision differently in each direction, and
-- a real book does not quote the same spread either way -- the bank's appetite for buying dollars
-- is not its appetite for selling them.
CREATE TABLE fx_rate (
    id              UUID            PRIMARY KEY,
    base_currency   VARCHAR(3)      NOT NULL,
    quote_currency  VARCHAR(3)      NOT NULL,
    mid_rate        NUMERIC(19, 8)  NOT NULL,
    -- The bank's margin, in basis points, always taken against the customer. Stored apart from
    -- the mid rate so that what the market said and what the bank charged stay separable: a rate
    -- quoted net of spread cannot be audited against any published source afterwards.
    spread_bps      INTEGER         NOT NULL,
    as_of           TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT uk_fx_pair       UNIQUE (base_currency, quote_currency),
    CONSTRAINT ck_fx_rate       CHECK (mid_rate > 0),
    CONSTRAINT ck_fx_spread     CHECK (spread_bps >= 0 AND spread_bps < 10000),
    CONSTRAINT ck_fx_distinct   CHECK (base_currency <> quote_currency)
);

-- Indicative rates so a fresh install can transact. A real deployment replaces these from a feed;
-- nothing in the application assumes they are current beyond reading as_of.
INSERT INTO fx_rate (id, base_currency, quote_currency, mid_rate, spread_bps, as_of, created_at, updated_at, version) VALUES
('00000000-0000-0000-0000-0000000000a1', 'INR', 'USD', 0.01200000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-0000000000a2', 'USD', 'INR', 83.00000000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-0000000000a3', 'INR', 'EUR', 0.01110000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-0000000000a4', 'EUR', 'INR', 90.00000000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-0000000000a5', 'INR', 'GBP', 0.00950000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-0000000000a6', 'GBP', 'INR', 105.00000000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-0000000000a7', 'USD', 'EUR', 0.92000000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('00000000-0000-0000-0000-0000000000a8', 'EUR', 'USD', 1.08000000, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- The rate actually applied, recorded on the posting. Null on a same-currency transaction, which
-- is every transaction before this migration. Kept at eight decimal places because a rate is not
-- money -- rounding it to the money scale would make a large conversion irreproducible from its
-- own audit trail.
ALTER TABLE bank_transaction ADD COLUMN exchange_rate NUMERIC(19, 8);

-- The counter-currency amount, so a statement line in one currency can say what it became in the
-- other without re-deriving it from a rate that may since have moved.
ALTER TABLE bank_transaction ADD COLUMN counter_amount NUMERIC(19, 4);
ALTER TABLE bank_transaction ADD COLUMN counter_currency VARCHAR(3);

-- The three FX columns are meaningful only together.
ALTER TABLE bank_transaction ADD CONSTRAINT ck_transaction_fx CHECK (
    (exchange_rate IS NULL AND counter_amount IS NULL AND counter_currency IS NULL)
    OR (exchange_rate IS NOT NULL AND counter_amount IS NOT NULL AND counter_currency IS NOT NULL)
);
