CREATE TABLE accounts (
  id          UUID PRIMARY KEY,
  owner_name  TEXT NOT NULL,
  currency    CHAR(3) NOT NULL DEFAULT 'USD',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- cached balance, updated in the same transaction as entries
CREATE TABLE account_balances (
  account_id    UUID PRIMARY KEY REFERENCES accounts(id),
  balance_cents BIGINT NOT NULL DEFAULT 0 CHECK (balance_cents >= 0)
);

CREATE TABLE transactions (
  id              UUID PRIMARY KEY,
  idempotency_key TEXT UNIQUE NOT NULL,
  status          TEXT NOT NULL,          -- COMPLETED | REJECTED
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- immutable double-entry rows: one negative, one positive per transfer
CREATE TABLE ledger_entries (
  id             BIGSERIAL PRIMARY KEY,
  transaction_id UUID NOT NULL REFERENCES transactions(id),
  account_id     UUID NOT NULL REFERENCES accounts(id),
  amount_cents   BIGINT NOT NULL,         -- negative = debit, positive = credit
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id);
CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);

CREATE TABLE idempotency_keys (
  key           TEXT PRIMARY KEY,
  request_hash  TEXT NOT NULL,
  response_body JSONB,
  status_code   INT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Entries are append-only. Enforce it in the database, not just in code.
CREATE FUNCTION forbid_ledger_entry_change() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'ledger_entries are immutable: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_immutable
  BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION forbid_ledger_entry_change();
