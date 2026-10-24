package com.alexxavier.ledger.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One money movement. A COMPLETED transaction owns exactly two ledger entries; a REJECTED one owns none. */
@Entity
@Table(name = "transactions")
public class LedgerTransaction implements Persistable<UUID> {

    public enum Status { COMPLETED, REJECTED }

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Transient
    private boolean isNew = true;

    protected LedgerTransaction() {
    }

    public LedgerTransaction(UUID id, String idempotencyKey, Status status) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.createdAt = OffsetDateTime.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Status getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }
}
