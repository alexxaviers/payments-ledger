package com.alexxavier.ledger.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Business metrics, exported at /actuator/prometheus:
 * <ul>
 *   <li>{@code ledger_transfers_total{outcome="completed|rejected|replayed"}}</li>
 *   <li>{@code ledger_transfer_duration_seconds} (histogram, for p50/p95/p99)</li>
 *   <li>{@code ledger_outbox_backlog} and {@code ledger_outbox_oldest_unpublished_seconds}</li>
 *   <li>{@code ledger_outbox_published_total}</li>
 *   <li>{@code ledger_reconciliation_mismatches} (registered by ReconciliationService)</li>
 * </ul>
 */
@Component
public class LedgerMetrics {

    private final Counter completed;
    private final Counter rejected;
    private final Counter replayed;
    private final Counter published;
    private final Timer duration;

    public LedgerMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.completed = transfers(registry, "completed");
        this.rejected = transfers(registry, "rejected");
        this.replayed = transfers(registry, "replayed");
        this.published = Counter.builder("ledger.outbox.published")
                .description("Outbox events acknowledged by Kafka").register(registry);
        this.duration = Timer.builder("ledger.transfer.duration")
                .description("Time to process POST /transfers inside the service")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);

        Gauge.builder("ledger.outbox.backlog", jdbc,
                        j -> query(j, "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL"))
                .description("Outbox events not yet published to Kafka").register(registry);
        Gauge.builder("ledger.outbox.oldest.unpublished", jdbc, j -> query(j, """
                        SELECT COALESCE(EXTRACT(EPOCH FROM now() - MIN(created_at)), 0)
                        FROM outbox WHERE published_at IS NULL"""))
                .description("Age in seconds of the oldest unpublished outbox event")
                .baseUnit("seconds").register(registry);
    }

    private static Counter transfers(MeterRegistry registry, String outcome) {
        return Counter.builder("ledger.transfers").tag("outcome", outcome)
                .description("Transfers by outcome; replayed = answered from the idempotency store")
                .register(registry);
    }

    private static double query(JdbcTemplate jdbc, String sql) {
        try {
            Number n = jdbc.queryForObject(sql, Number.class);
            return n == null ? 0 : n.doubleValue();
        } catch (Exception e) {
            return Double.NaN;   // database unreachable: report "unknown" rather than a misleading 0
        }
    }

    public Timer.Sample start() {
        return Timer.start();
    }

    public void recordTransfer(Timer.Sample sample, boolean replay, boolean wasCompleted) {
        sample.stop(duration);
        if (replay) replayed.increment();
        else if (wasCompleted) completed.increment();
        else rejected.increment();
    }

    public void recordPublished(int count) {
        published.increment(count);
    }
}
