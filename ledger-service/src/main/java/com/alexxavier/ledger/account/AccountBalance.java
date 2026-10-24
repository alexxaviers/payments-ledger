package com.alexxavier.ledger.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * Cached balance of one account. It is always updated in the same database transaction as the
 * ledger entries that justify it, and the reconciliation job checks that it equals the sum of those entries.
 */
@Entity
@Table(name = "account_balances")
public class AccountBalance implements Persistable<UUID> {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Transient
    private boolean isNew = true;

    protected AccountBalance() {
    }

    public AccountBalance(UUID accountId) {
        this.accountId = accountId;
        this.balanceCents = 0;
    }

    @Override
    public UUID getId() {
        return accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public void debit(long amountCents) {
        balanceCents = Math.subtractExact(balanceCents, amountCents);
    }

    public void credit(long amountCents) {
        balanceCents = Math.addExact(balanceCents, amountCents);
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
