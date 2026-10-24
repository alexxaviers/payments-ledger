package com.alexxavier.ledger.account;

import com.alexxavier.ledger.account.AccountDtos.AccountResponse;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.common.SystemAccounts;
import com.alexxavier.ledger.transfer.LedgerEntry;
import com.alexxavier.ledger.transfer.LedgerEntryRepository;
import com.alexxavier.ledger.transfer.LedgerTransaction;
import com.alexxavier.ledger.transfer.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;

    public AccountService(AccountRepository accounts, AccountBalanceRepository balances,
                          LedgerTransactionRepository transactions, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.balances = balances;
        this.transactions = transactions;
        this.entries = entries;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        String currency = request.currency() == null ? "USD" : request.currency();
        Account account = accounts.save(new Account(UUID.randomUUID(), request.ownerName().trim(), currency));
        balances.save(new AccountBalance(account.getId()));
        return AccountResponse.of(account, 0);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        long balance = balances.findById(id).map(AccountBalance::getBalanceCents).orElse(0L);
        return AccountResponse.of(account, balance);
    }

    /**
     * Test-only top-up, modelled as a transfer from the EXTERNAL system account so the books balance:
     * the customer gets +amount, EXTERNAL gets -amount.
     */
    @Transactional
    public AccountResponse deposit(UUID accountId, long amountCents) {
        // every deposit locks EXTERNAL first, then the target: one fixed order, so no deadlocks
        AccountBalance external = balances.lockById(SystemAccounts.EXTERNAL).orElseThrow();
        AccountBalance target = balances.lockById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        LedgerTransaction tx = transactions.save(new LedgerTransaction(
                UUID.randomUUID(), "deposit:" + UUID.randomUUID(), LedgerTransaction.Status.COMPLETED));
        entries.save(new LedgerEntry(tx.getId(), SystemAccounts.EXTERNAL, -amountCents));
        entries.save(new LedgerEntry(tx.getId(), accountId, amountCents));
        external.debit(amountCents);
        target.credit(amountCents);

        return get(accountId);
    }
}
