package com.alexxavier.ledger.admin;

import com.alexxavier.ledger.common.SystemAccounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Computes the ledger's invariants straight from the database. */
@Service
public class InvariantService {

    public record InvariantReport(
            long customerBalanceTotalCents,
            long externalBalanceCents,
            long sumOfAllBalancesCents,
            long sumOfAllEntriesCents,
            long negativeCustomerBalances,
            long cachedBalanceMismatches,
            long completedTransactionsWithoutTwoBalancedEntries,
            long completedTransactions,
            long rejectedTransactions,
            boolean ok) {
    }

    private final JdbcTemplate jdbc;

    public InvariantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** REPEATABLE READ gives every query below the same snapshot, so totals are consistent with each other. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvariantReport check() {
        String external = SystemAccounts.EXTERNAL.toString();
        long customerTotal = longQuery(
                "SELECT COALESCE(SUM(balance_cents), 0) FROM account_balances WHERE account_id <> ?::uuid", external);
        long externalBalance = longQuery(
                "SELECT COALESCE(SUM(balance_cents), 0) FROM account_balances WHERE account_id = ?::uuid", external);
        long balancesSum = longQuery("SELECT COALESCE(SUM(balance_cents), 0) FROM account_balances");
        long entriesSum = longQuery("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries");
        long negatives = longQuery(
                "SELECT COUNT(*) FROM account_balances WHERE balance_cents < 0 AND account_id <> ?::uuid", external);
        long mismatches = longQuery("""
                SELECT COUNT(*) FROM account_balances b
                LEFT JOIN (SELECT account_id, SUM(amount_cents) AS total FROM ledger_entries GROUP BY account_id) e
                  ON e.account_id = b.account_id
                WHERE b.balance_cents <> COALESCE(e.total, 0)
                """);
        long badTransactions = longQuery("""
                SELECT COUNT(*) FROM transactions t
                LEFT JOIN (SELECT transaction_id, COUNT(*) AS n, SUM(amount_cents) AS total
                           FROM ledger_entries GROUP BY transaction_id) e ON e.transaction_id = t.id
                WHERE t.status = 'COMPLETED' AND (COALESCE(e.n, 0) <> 2 OR COALESCE(e.total, 0) <> 0)
                """);
        long completed = longQuery("SELECT COUNT(*) FROM transactions WHERE status = 'COMPLETED'");
        long rejected = longQuery("SELECT COUNT(*) FROM transactions WHERE status = 'REJECTED'");

        boolean ok = entriesSum == 0 && balancesSum == 0 && negatives == 0 && mismatches == 0 && badTransactions == 0;
        return new InvariantReport(customerTotal, externalBalance, balancesSum, entriesSum, negatives, mismatches,
                badTransactions, completed, rejected, ok);
    }

    private long longQuery(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
