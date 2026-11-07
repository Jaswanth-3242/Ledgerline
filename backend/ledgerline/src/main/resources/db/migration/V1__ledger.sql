-- Put in backend/ledgerline/src/main/resources/db/migration/V1__ledger.sql
-- Money is BIGINT minor units (paise). Never floats.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL CHECK (role IN ('ADMIN', 'HOLDER', 'AUDITOR')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id         BIGSERIAL PRIMARY KEY,
    owner_id   BIGINT REFERENCES users (id),          -- NULL for the system account
    name       VARCHAR(100) NOT NULL,
    is_system  BOOLEAN      NOT NULL DEFAULT false,
    balance    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Backstop: even if app code is wrong, a customer balance can never go negative.
    CONSTRAINT balance_non_negative CHECK (is_system OR balance >= 0),
    CONSTRAINT owner_rule CHECK ((is_system AND owner_id IS NULL) OR (NOT is_system AND owner_id IS NOT NULL))
);

CREATE TABLE transactions (
    id         BIGSERIAL PRIMARY KEY,
    type       VARCHAR(12) NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    created_by BIGINT      NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id         BIGSERIAL PRIMARY KEY,
    txn_id     BIGINT      NOT NULL REFERENCES transactions (id),
    account_id BIGINT      NOT NULL REFERENCES accounts (id),
    amount     BIGINT      NOT NULL CHECK (amount <> 0),   -- signed: + credit, - debit
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries (account_id, id);
CREATE INDEX idx_entries_txn ON ledger_entries (txn_id);

CREATE TABLE idempotency_keys (
    user_id      BIGINT       NOT NULL REFERENCES users (id),
    key          VARCHAR(100) NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    txn_id       BIGINT REFERENCES transactions (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    actor_id   BIGINT,
    action     VARCHAR(30)  NOT NULL,
    target     VARCHAR(100),
    outcome    VARCHAR(10)  NOT NULL,
    detail     VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every transaction's entries must sum to zero (checked at COMMIT time).
CREATE FUNCTION check_txn_balanced() RETURNS trigger AS $$
BEGIN
    IF (SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE txn_id = NEW.txn_id) <> 0 THEN
        RAISE EXCEPTION 'transaction % is unbalanced', NEW.txn_id;
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_txn_balanced();

-- Append-only: no UPDATE / DELETE / TRUNCATE on history tables.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER transactions_immutable BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_log_immutable BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- The counter-party for deposits/withdrawals (money entering/leaving the system).
INSERT INTO accounts (owner_id, name, is_system) VALUES (NULL, 'SYSTEM CASH', true);
