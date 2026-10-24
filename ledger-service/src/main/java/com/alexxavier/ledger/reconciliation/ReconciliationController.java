package com.alexxavier.ledger.reconciliation;

import com.alexxavier.ledger.reconciliation.ReconciliationService.Report;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /** Runs a reconciliation now. 200 if the books are correct, 409 with details otherwise. */
    @PostMapping("/admin/reconcile")
    public ResponseEntity<Report> reconcile() {
        Report report = reconciliation.reconcile();
        return ResponseEntity.status(report.ok() ? 200 : 409).body(report);
    }
}
