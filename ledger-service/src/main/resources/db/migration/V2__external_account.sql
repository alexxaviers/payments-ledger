-- The "external" system account represents money entering or leaving the ledger
-- (card top-ups, bank withdrawals...). Deposits are transfers FROM this account, so
-- every cent in a customer account has a matching negative entry here and the sum of
-- all entries stays 0. It is the only account allowed to go below zero.
INSERT INTO accounts (id, owner_name, currency)
VALUES ('00000000-0000-0000-0000-000000000001', 'EXTERNAL (system)', 'USD');

INSERT INTO account_balances (account_id, balance_cents)
VALUES ('00000000-0000-0000-0000-000000000001', 0);

ALTER TABLE account_balances DROP CONSTRAINT account_balances_balance_cents_check;
ALTER TABLE account_balances ADD CONSTRAINT account_balances_non_negative
  CHECK (balance_cents >= 0 OR account_id = '00000000-0000-0000-0000-000000000001');
