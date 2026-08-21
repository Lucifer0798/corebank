-- CoreBank Lite baseline schema.
-- Written in portable SQL so it runs unchanged on PostgreSQL and on H2 (PostgreSQL mode).

CREATE TABLE customer (
    id              UUID            PRIMARY KEY,
    customer_number VARCHAR(20)     NOT NULL,
    first_name      VARCHAR(60)     NOT NULL,
    last_name       VARCHAR(60)     NOT NULL,
    email           VARCHAR(160)    NOT NULL,
    phone           VARCHAR(20),
    date_of_birth   DATE            NOT NULL,
    kyc_status      VARCHAR(20)     NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_customer_number UNIQUE (customer_number),
    CONSTRAINT uk_customer_email  UNIQUE (email),
    CONSTRAINT ck_customer_kyc    CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE account (
    id              UUID            PRIMARY KEY,
    account_number  VARCHAR(20)     NOT NULL,
    customer_id     UUID,
    account_class   VARCHAR(20)     NOT NULL,
    account_type    VARCHAR(20)     NOT NULL,
    normal_balance  VARCHAR(10)     NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    balance         NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    overdraft_limit NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL,
    opened_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at       TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_account_number    UNIQUE (account_number),
    CONSTRAINT fk_account_customer  FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_account_class     CHECK (account_class IN ('CUSTOMER', 'INTERNAL')),
    CONSTRAINT ck_account_type      CHECK (account_type IN ('SAVINGS', 'CURRENT', 'CASH_GL', 'SUSPENSE_GL')),
    CONSTRAINT ck_account_normal    CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_account_status    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_account_overdraft CHECK (overdraft_limit >= 0),
    -- A customer account is always owned; an internal general-ledger account never is.
    CONSTRAINT ck_account_ownership CHECK (
        (account_class = 'CUSTOMER' AND customer_id IS NOT NULL)
        OR (account_class = 'INTERNAL' AND customer_id IS NULL)
    )
);

CREATE INDEX idx_account_customer ON account (customer_id);

CREATE TABLE bank_transaction (
    id              UUID            PRIMARY KEY,
    reference       VARCHAR(36)     NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    description     VARCHAR(255),
    idempotency_key VARCHAR(80),
    posted_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_transaction_reference UNIQUE (reference),
    CONSTRAINT ck_transaction_type      CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_transaction_status    CHECK (status IN ('POSTED', 'REVERSED')),
    CONSTRAINT ck_transaction_amount    CHECK (amount > 0)
);

CREATE INDEX idx_transaction_posted_at ON bank_transaction (posted_at);

-- Double-entry ledger: every transaction posts a balanced set of debit and credit legs.
CREATE TABLE ledger_entry (
    id              UUID            PRIMARY KEY,
    transaction_id  UUID            NOT NULL,
    account_id      UUID            NOT NULL,
    direction       VARCHAR(10)     NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    balance_after   NUMERIC(19, 4)  NOT NULL,
    sequence_no     INTEGER         NOT NULL,
    posted_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_entry_transaction FOREIGN KEY (transaction_id) REFERENCES bank_transaction (id),
    CONSTRAINT fk_entry_account     FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT uk_entry_sequence    UNIQUE (transaction_id, sequence_no),
    CONSTRAINT ck_entry_direction   CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_entry_amount      CHECK (amount > 0)
);

CREATE INDEX idx_entry_account_posted ON ledger_entry (account_id, posted_at);

CREATE TABLE app_user (
    id              UUID            PRIMARY KEY,
    username        VARCHAR(64)     NOT NULL,
    password_hash   VARCHAR(100)    NOT NULL,
    full_name       VARCHAR(120),
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    customer_id     UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT fk_app_user_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

CREATE TABLE app_user_role (
    user_id         UUID            NOT NULL,
    role            VARCHAR(20)     NOT NULL,
    CONSTRAINT ck_app_user_role CHECK (role IN ('CUSTOMER', 'TELLER', 'ADMIN')),
    CONSTRAINT uk_app_user_role UNIQUE (user_id, role),
    CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- Replay protection for money-moving POSTs. One row per (endpoint, Idempotency-Key).
CREATE TABLE idempotency_record (
    id              UUID            PRIMARY KEY,
    scope           VARCHAR(80)     NOT NULL,
    idempotency_key VARCHAR(80)     NOT NULL,
    request_hash    VARCHAR(64)     NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    response_status INTEGER,
    response_body   VARCHAR(8000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_idempotency UNIQUE (scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);
