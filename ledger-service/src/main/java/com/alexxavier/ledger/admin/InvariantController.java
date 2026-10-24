package com.alexxavier.ledger.admin;

import com.alexxavier.ledger.admin.InvariantService.InvariantReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvariantController {

    private final InvariantService invariants;

    public InvariantController(InvariantService invariants) {
        this.invariants = invariants;
    }

    /** 200 when every invariant holds, 409 otherwise, so scripts can simply check the status code. */
    @GetMapping("/admin/invariants")
    public ResponseEntity<InvariantReport> check() {
        InvariantReport report = invariants.check();
        return ResponseEntity.status(report.ok() ? 200 : 409).body(report);
    }
}
