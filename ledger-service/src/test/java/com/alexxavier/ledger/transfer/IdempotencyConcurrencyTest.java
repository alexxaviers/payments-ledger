package com.alexxavier.ledger.transfer;

import com.alexxavier.ledger.AbstractIntegrationTest;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.account.AccountService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires the same request with the same brand-new Idempotency-Key from 20 threads released at the same
 * instant. Exactly one transfer may happen; every caller must get the same answer.
 */
class IdempotencyConcurrencyTest extends AbstractIntegrationTest {

    static final int PARALLEL = 20;

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    @RepeatedTest(5)
    void sameKeyInParallelMovesMoneyExactlyOnce() throws Exception {
        UUID from = newAccount(10_000);
        UUID to = newAccount(0);
        String key = UUID.randomUUID().toString();
        var request = new TransferRequest(from, to, 700L);

        List<TransferResult> results = runInParallel(() -> transfers.transfer(key, request));

        assertThat(count("SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id
                WHERE t.idempotency_key = ?""", key)).isEqualTo(2);
        assertThat(accounts.get(from).balanceCents()).isEqualTo(9_300);
        assertThat(accounts.get(to).balanceCents()).isEqualTo(700);

        UUID txId = results.get(0).body().transactionId();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.body().transactionId()).isEqualTo(txId);
            assertThat(r.httpStatus()).isEqualTo(201);
        });
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodiesInParallelExecutesOnlyOne() throws Exception {
        UUID from = newAccount(10_000);
        UUID to = newAccount(0);
        String key = UUID.randomUUID().toString();

        List<Callable<TransferResult>> calls = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            long amount = 100 + i;   // every body is different
            calls.add(() -> transfers.transfer(key, new TransferRequest(from, to, amount)));
        }
        List<Object> outcomes = runEach(calls);

        long successes = outcomes.stream().filter(o -> o instanceof TransferResult).count();
        long conflicts = outcomes.stream().filter(o -> o instanceof IdempotencyConflictException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(PARALLEL - 1);
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(accounts.get(from).balanceCents() + accounts.get(to).balanceCents()).isEqualTo(10_000);
    }

    private UUID newAccount(long balance) {
        UUID id = accounts.create(new CreateAccountRequest("idem", null)).id();
        if (balance > 0) {
            accounts.deposit(UUID.randomUUID().toString(), id, balance);
        }
        return id;
    }

    private List<TransferResult> runInParallel(Callable<TransferResult> call) throws Exception {
        List<Callable<TransferResult>> calls = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) calls.add(call);
        List<TransferResult> results = new ArrayList<>();
        for (Object o : runEach(calls)) {
            if (o instanceof Throwable t) throw new AssertionError("call failed", t);
            results.add((TransferResult) o);
        }
        return results;
    }

    /** Releases all calls at once via a latch; returns each call's result or the exception it threw. */
    private List<Object> runEach(List<Callable<TransferResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();
        for (Callable<TransferResult> c : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return c.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, SECONDS)).isTrue();
        List<Object> out = new ArrayList<>();
        for (Future<TransferResult> f : futures) {
            try {
                out.add(f.get());
            } catch (ExecutionException e) {
                out.add(e.getCause());
            }
        }
        return out;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
