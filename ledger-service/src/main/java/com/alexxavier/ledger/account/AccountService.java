package com.alexxavier.ledger.account;

import com.alexxavier.ledger.account.AccountDtos.AccountResponse;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.transfer.TransferService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final TransferService transferService;

    public AccountService(AccountRepository accounts, AccountBalanceRepository balances,
                          TransferService transferService) {
        this.accounts = accounts;
        this.balances = balances;
        this.transferService = transferService;
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
     * the customer gets +amount, EXTERNAL gets -amount. Goes through the same locking and idempotency
     * path as a normal transfer.
     */
    public AccountResponse deposit(String idempotencyKey, UUID accountId, long amountCents) {
        transferService.deposit(idempotencyKey, accountId, amountCents);
        return get(accountId);
    }
}
