package com.alexxavier.ledger.transfer;

import com.alexxavier.ledger.AbstractIntegrationTest;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.account.AccountService;
import com.alexxavier.ledger.admin.InvariantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hammers the real Postgres (Testcontainers) with concurrent transfers in both directions between a small
 * set of accounts, so the same rows are contended constantly. If locking were wrong we would see lost
 * updates (money created or destroyed), overdrafts, or deadlock errors.
 */
class TransferConcurrencyTest extends AbstractIntegrationTest {

    static final int ACCOUNTS = 10;
    static final long OPENING_BALANCE = 100_000;   // $1,000.00 each -> $10,000.00 total
    static final int TRANSFERS = 1_000;
    static final int THREADS = 50;

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accounts;

    @Autowired
    InvariantService invariants;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void thousandConcurrentTransfersConserveMoney() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID id = accounts.create(new CreateAccountRequest("acct-" + i, null)).id();
            accounts.deposit(UUID.randomUUID().toString(), id, OPENING_BALANCE);
            ids.add(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<TransferResult>> futures = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            futures.add(pool.submit(() -> transfers.transfer(UUID.randomUUID().toString(), randomTransfer(ids))));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(120, SECONDS)).isTrue();

        int completed = 0;
        int rejected = 0;
        for (Future<TransferResult> f : futures) {
            TransferResult r = f.get();   // throws if any transfer failed with an exception (e.g. deadlock)
            if (r.body().status() == LedgerTransaction.Status.COMPLETED) completed++;
            else rejected++;
        }

        assertThat(completed + rejected).isEqualTo(TRANSFERS);
        assertThat(completed).isPositive();
        assertThat(totalBalance(ids)).isEqualTo(ACCOUNTS * OPENING_BALANCE);   // still $10,000 in cents
        assertThat(sumOfEntries(ids)).isEqualTo(ACCOUNTS * OPENING_BALANCE);   // entries agree with balances
        assertThat(anyNegativeBalance(ids)).isFalse();

        var report = invariants.check();
        assertThat(report.sumOfAllEntriesCents()).isZero();                    // double-entry invariant
        assertThat(report.cachedBalanceMismatches()).isZero();
        assertThat(report.completedTransactionsWithoutTwoBalancedEntries()).isZero();
        assertThat(report.ok()).isTrue();
    }

    private static TransferRequest randomTransfer(List<UUID> ids) {
        var rnd = ThreadLocalRandom.current();
        int a = rnd.nextInt(ids.size());
        int b = (a + 1 + rnd.nextInt(ids.size() - 1)) % ids.size();   // always a different account
        long amount = 1 + rnd.nextLong(40_000);                       // $0.01 .. $400.00 -> some get rejected
        return new TransferRequest(ids.get(a), ids.get(b), amount);
    }

    private long totalBalance(List<UUID> ids) {
        return sum("SELECT COALESCE(SUM(balance_cents),0) FROM account_balances WHERE account_id = ANY(?::uuid[])", ids);
    }

    private long sumOfEntries(List<UUID> ids) {
        return sum("SELECT COALESCE(SUM(amount_cents),0) FROM ledger_entries WHERE account_id = ANY(?::uuid[])", ids);
    }

    private boolean anyNegativeBalance(List<UUID> ids) {
        return sum("SELECT COUNT(*) FROM account_balances WHERE balance_cents < 0 AND account_id = ANY(?::uuid[])",
                ids) > 0;
    }

    private long sum(String sql, List<UUID> ids) {
        String array = ids.stream().map(UUID::toString).reduce((x, y) -> x + "," + y).map(s -> "{" + s + "}").orElse("{}");
        return jdbc.queryForObject(sql, Long.class, array);
    }
}
