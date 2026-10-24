package com.alexxavier.ledger.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audits the whole ledger the way a finance team would, from first principles:
 * <ol>
 *   <li>every cached balance equals the sum of that account's ledger entries;</li>
 *   <li>the global sum of all entries is 0;</li>
 *   <li>every COMPLETED transaction has exactly two entries that sum to 0 (and REJECTED ones have none);</li>
 *   <li>every completed transaction has an outbox event, and no event waited longer than the allowed lag.</li>
 * </ol>
 * The number of problems found is exported as the {@code ledger_reconciliation_mismatches} gauge.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int MAX_DETAILS = 20;

    public record AccountMismatch(UUID accountId, long cachedBalanceCents, long ledgerSumCents) {
    }

    public record BadTransaction(UUID transactionId, String status, long entryCount, long entrySumCents) {
    }

    public record Report(
            OffsetDateTime checkedAt,
            long accountsChecked,
            long transactionsChecked,
            List<AccountMismatch> balanceMismatches,
            long globalEntrySumCents,
            List<BadTransaction> malformedTransactions,
            long completedTransactionsWithoutEvent,
            long outboxEventsOverdue,
            long mismatches) {

        public boolean ok() {
            return mismatches == 0;
        }
    }

    private final JdbcTemplate jdbc;
    private final Duration maxOutboxLag;
    private final AtomicLong lastMismatches = new AtomicLong(0);

    public ReconciliationService(JdbcTemplate jdbc, MeterRegistry registry,
                                 @Value("${ledger.reconciliation.max-outbox-lag:60s}") Duration maxOutboxLag) {
        this.jdbc = jdbc;
        this.maxOutboxLag = maxOutboxLag;
        Gauge.builder("ledger.reconciliation.mismatches", lastMismatches, AtomicLong::get)
                .description("Problems found by the last reconciliation run (0 = books are correct)")
                .register(registry);
    }

    /** REPEATABLE READ: all checks see one consistent snapshot even while transfers keep committing. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report reconcile() {
        long accounts = count("SELECT COUNT(*) FROM account_balances");
        long transactions = count("SELECT COUNT(*) FROM transactions");

        long balanceMismatchCount = count("""
                SELECT COUNT(*) FROM account_balances b
                LEFT JOIN (SELECT account_id, SUM(amount_cents) AS total FROM ledger_entries GROUP BY account_id) e
                  ON e.account_id = b.account_id
                WHERE b.balance_cents <> COALESCE(e.total, 0)
                """);
        List<AccountMismatch> balanceMismatches = jdbc.query("""
                SELECT b.account_id, b.balance_cents, COALESCE(e.total, 0) FROM account_balances b
                LEFT JOIN (SELECT account_id, SUM(amount_cents) AS total FROM ledger_entries GROUP BY account_id) e
                  ON e.account_id = b.account_id
                WHERE b.balance_cents <> COALESCE(e.total, 0)
                LIMIT ?
                """, (rs, i) -> new AccountMismatch(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)),
                MAX_DETAILS);

        long globalSum = count("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries");

        String malformedWhere = """
                FROM transactions t
                LEFT JOIN (SELECT transaction_id, COUNT(*) AS n, SUM(amount_cents) AS total
                           FROM ledger_entries GROUP BY transaction_id) e ON e.transaction_id = t.id
                WHERE (t.status = 'COMPLETED' AND (COALESCE(e.n, 0) <> 2 OR COALESCE(e.total, 0) <> 0))
                   OR (t.status = 'REJECTED' AND COALESCE(e.n, 0) <> 0)
                """;
        long malformedCount = count("SELECT COUNT(*) " + malformedWhere);
        List<BadTransaction> malformed = jdbc.query(
                "SELECT t.id, t.status, COALESCE(e.n, 0), COALESCE(e.total, 0) " + malformedWhere + " LIMIT ?",
                (rs, i) -> new BadTransaction(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getLong(4)), MAX_DETAILS);

        long withoutEvent = count("""
                SELECT COUNT(*) FROM transactions t
                WHERE t.status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.aggregate_id = t.id)
                """);
        long overdue = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox
                WHERE COALESCE(published_at, now()) - created_at > make_interval(secs => ?)
                """, Long.class, (double) maxOutboxLag.toMillis() / 1000.0);

        long mismatches = balanceMismatchCount + (globalSum != 0 ? 1 : 0) + malformedCount + withoutEvent + overdue;
        var report = new Report(OffsetDateTime.now(), accounts, transactions, balanceMismatches, globalSum,
                malformed, withoutEvent, overdue, mismatches);

        lastMismatches.set(mismatches);
        if (report.ok()) {
            log.info("Reconciliation OK: {} accounts, {} transactions, entries sum to 0", accounts, transactions);
        } else {
            log.error("RECONCILIATION FAILED: {} problem(s). balanceMismatches={} globalEntrySum={} "
                            + "malformedTransactions={} completedWithoutEvent={} outboxOverdue={}",
                    mismatches, balanceMismatches, globalSum, malformed, withoutEvent, overdue);
        }
        return report;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
