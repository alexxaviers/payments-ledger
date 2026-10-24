package com.alexxavier.ledger;

import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.account.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The database itself refuses to edit or delete history, and refuses overdrafts. */
class LedgerImmutabilityTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountService accounts;

    @Test
    void entriesCannotBeUpdatedOrDeleted() {
        UUID id = accounts.create(new CreateAccountRequest("imm", null)).id();
        accounts.deposit(UUID.randomUUID().toString(), id, 500);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount_cents = 1000000 WHERE account_id = ?", id))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE account_id = ?", id))
                .hasMessageContaining("immutable");
    }

    @Test
    void databaseRejectsNegativeCustomerBalance() {
        UUID id = accounts.create(new CreateAccountRequest("neg", null)).id();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE account_balances SET balance_cents = -1 WHERE account_id = ?", id))
                .hasMessageContaining("account_balances_non_negative");
    }
}
