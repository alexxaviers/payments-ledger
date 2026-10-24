package com.alexxavier.ledger.transfer;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                     @Valid @RequestBody TransferRequest request) {
        return toResponse(service.transfer(idempotencyKey, request));
    }

    static ResponseEntity<TransferResponse> toResponse(TransferResult result) {
        return ResponseEntity.status(result.httpStatus())
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.body());
    }
}
