package com.alexxavier.ledger.reconciliation;

import com.alexxavier.ledger.AbstractIntegrationTest;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.account.AccountService;
import com.alexxavier.ledger.transfer.TransferRequest;
import com.alexxavier.ledger.transfer.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationTest extends AbstractIntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    AccountService accounts;

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry registry;

    @Test
    void cleanLedgerReconciles() {
        UUID a = accounts.create(new CreateAccountRequest("rec-a", null)).id();
        UUID b = accounts.create(new CreateAccountRequest("rec-b", null)).id();
        accounts.deposit(UUID.randomUUID().toString(), a, 10_000);
        transfers.transfer(UUID.randomUUID().toString(), new TransferRequest(a, b, 4_000L));

        var report = reconciliation.reconcile();

        assertThat(report.ok()).as("report %s", report).isTrue();
        assertThat(report.globalEntrySumCents()).isZero();
        assertThat(registry.get("ledger.reconciliation.mismatches").gauge().value()).isZero();
    }

    @Test
    void detectsCachedBalanceThatDisagreesWithEntries() {
        UUID a = accounts.create(new CreateAccountRequest("rec-corrupt", null)).id();
        accounts.deposit(UUID.randomUUID().toString(), a, 1_000);
        // simulate a bug that updated the cache without writing entries
        jdbc.update("UPDATE account_balances SET balance_cents = balance_cents + 1 WHERE account_id = ?", a);
        try {
            var report = reconciliation.reconcile();

            assertThat(report.ok()).isFalse();
            assertThat(report.balanceMismatches())
                    .anySatisfy(m -> {
                        assertThat(m.accountId()).isEqualTo(a);
                        assertThat(m.cachedBalanceCents()).isEqualTo(1_001);
                        assertThat(m.ledgerSumCents()).isEqualTo(1_000);
                    });
            assertThat(registry.get("ledger.reconciliation.mismatches").gauge().value()).isPositive();
        } finally {
            jdbc.update("UPDATE account_balances SET balance_cents = balance_cents - 1 WHERE account_id = ?", a);
        }
        assertThat(reconciliation.reconcile().ok()).isTrue();
    }
}
