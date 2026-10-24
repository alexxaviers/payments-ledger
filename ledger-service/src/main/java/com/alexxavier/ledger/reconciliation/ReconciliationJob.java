package com.alexxavier.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the audit on a schedule. Read-only, so running it on every replica is harmless. */
@Component
@ConditionalOnProperty(name = "ledger.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliation;

    public ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(initialDelayString = "${ledger.reconciliation.initial-delay:10s}",
            fixedDelayString = "${ledger.reconciliation.interval:30s}")
    public void run() {
        try {
            reconciliation.reconcile();
        } catch (Exception e) {
            log.warn("Reconciliation run failed: {}", e.toString());
        }
    }
}
