# Walkthrough: how the ledger works and why

A tour of every design decision, with links to the code that implements it. Read it top to bottom once and
you should be able to explain any part of the system and defend the trade-offs.

- [1. The data model](#1-the-data-model)
- [2. A transfer, step by step](#2-a-transfer-step-by-step)
- [3. Idempotency and the concurrent-key race](#3-idempotency-and-the-concurrent-key-race)
- [4. Locking: why pessimistic, why sorted](#4-locking-why-pessimistic-why-sorted)
- [5. The outbox and effectively-once events](#5-the-outbox-and-effectively-once-events)
- [6. Reconciliation](#6-reconciliation)
- [7. Observability](#7-observability)
- [8. Kubernetes and the failure experiment](#8-kubernetes-and-the-failure-experiment)
- [9. How each guarantee is tested](#9-how-each-guarantee-is-tested)
- [10. Known limitations and what I would do next](#10-known-limitations-and-what-i-would-do-next)
- [11. Interview questions, answered](#11-interview-questions-answered)

---

## 1. The data model

Schema: [`V1__init.sql`](../ledger-service/src/main/resources/db/migration/V1__init.sql),
[`V2__external_account.sql`](../ledger-service/src/main/resources/db/migration/V2__external_account.sql),
[`V3__outbox.sql`](../ledger-service/src/main/resources/db/migration/V3__outbox.sql).

| Table | Role | Key constraint |
|---|---|---|
| `accounts` | who owns money | — |
| `ledger_entries` | **the source of truth**: one signed row per leg of every transfer | append-only (trigger rejects `UPDATE`/`DELETE`) |
| `account_balances` | cache of `SUM(ledger_entries)` per account, so reads don't scan history | `CHECK (balance_cents >= 0)` except EXTERNAL |
| `transactions` | one row per money movement, `COMPLETED` or `REJECTED` | `UNIQUE (idempotency_key)` |
| `idempotency_keys` | the stored response for every key | `PRIMARY KEY (key)` |
| `outbox` | events waiting to be published to Kafka | — |

**Why cents in a `long` (`BIGINT`)?** `0.1 + 0.2 != 0.3` in binary floating point; a ledger that drifts by a
fraction of a cent per operation is wrong after enough operations. A `long` of cents is exact, and its range
(±9.2 × 10¹⁶ dollars) is enough. `Math.addExact` / `subtractExact` in
[`AccountBalance`](../ledger-service/src/main/java/com/alexxavier/ledger/account/AccountBalance.java) turn
overflow into an exception instead of silent wrap-around.

**Why both entries and a cached balance?** Entries are the audit trail and can't be edited; balances make
"what's my balance" and the overdraft check O(1). The risk of a cache is drift, so they are updated in the same
database transaction and the [reconciliation job](#6-reconciliation) continuously proves they agree.

**Why an EXTERNAL account?** If a deposit just incremented a balance, money would appear from nowhere and the
ledger would no longer sum to zero. Instead a deposit is a transfer *from* EXTERNAL, whose balance goes
negative by exactly the amount of money that entered the system. It is the only row the `CHECK` allows below
zero (V2 migration), and [`TransferService`](../ledger-service/src/main/java/com/alexxavier/ledger/transfer/TransferService.java)
refuses to let customers use it in transfers.

**Defense in depth.** The application checks for overdrafts, and the database `CHECK` would still refuse one
if the code had a bug. The application never updates entries, and the database trigger would refuse it anyway.
[`LedgerImmutabilityTest`](../ledger-service/src/test/java/com/alexxavier/ledger/LedgerImmutabilityTest.java)
proves the database-side guards work.

## 2. A transfer, step by step

Code: [`TransferController`](../ledger-service/src/main/java/com/alexxavier/ledger/transfer/TransferController.java) →
[`TransferService`](../ledger-service/src/main/java/com/alexxavier/ledger/transfer/TransferService.java) →
[`TransferExecutor`](../ledger-service/src/main/java/com/alexxavier/ledger/transfer/TransferExecutor.java).

1. **Validate** (outside any transaction): key present and ≤ 255 chars, amount > 0, different accounts, no
   system accounts. These are `400`s and are *not* stored under the key, because the request never executed.
2. **Open one database transaction** (`@Transactional` on `TransferExecutor.execute`).
3. **Seen this key?** Return the stored response, or `422` if the body hash differs.
4. **Lock both balance rows** in sorted-id order (`SELECT ... FOR UPDATE`). Unknown account → `404`.
5. **Business rule:** not enough money → record a `REJECTED` transaction, respond `422 INSUFFICIENT_FUNDS`.
6. **Otherwise write:** a `COMPLETED` transaction, the `-amount` and `+amount` entries, both cached balances,
   and a `TransferCompleted` event in the outbox.
7. **Flush** so constraint violations surface now rather than at commit.
8. **Store the response** under the idempotency key (`INSERT ... ON CONFLICT DO NOTHING`).
9. **Commit.** Everything from step 3 to 8 is atomic: there is no state in which money moved but the key isn't
   recorded, or the key is recorded but the event is missing.

Why are rejections stored and replayed too? A client that retries a rejected transfer after topping up its
account must get the *same* answer for the *same* key, otherwise the key no longer means "this one request".
A new attempt needs a new key. `TransferApiTest.rejectionIsAlsoReplayed` pins this down.

The service is split into `TransferService` (not transactional) and `TransferExecutor` (transactional)
because Spring's `@Transactional` works through a proxy: the race handling in step 3 of the next section must
run *after* the failed transaction has rolled back, so it has to live outside it.

## 3. Idempotency and the concurrent-key race

The easy case: a client retries after a timeout, the key is found in step 3, the stored response is returned
with `Idempotent-Replayed: true`, and no money moves.

**The hard case** is two requests with the *same brand-new key* arriving at the same moment:

```
request A                                 request B
---------                                 ---------
SELECT key → not found                    SELECT key → not found
lock rows, write transfer                 lock rows → WAITS for A's row locks
INSERT idempotency key
COMMIT ───────────────────────────────►   gets the locks, writes the transfer again,
                                          INSERT transactions(idempotency_key) → UNIQUE violation
                                          ROLLBACK (its entries, balances, outbox event all vanish)
                                          TransferService catches it, reads A's stored response,
                                          returns it
```

Both callers get the same `transactionId`, and money moved exactly once. Two database constraints make this
safe without any application-level locking: `UNIQUE (transactions.idempotency_key)` and
`PRIMARY KEY (idempotency_keys.key)`. Whichever one B hits first, the result is a rollback plus a replay. If
the requests target *different* accounts (same key, different body), B doesn't wait on A's row locks, but its
insert of the same key into the unique index waits for A's transaction to finish, so the outcome is the same.
The replay then sees a different body hash and answers `422`.

If the integrity violation is *not* about the key (the replay finds nothing stored), it's a genuine error and
is rethrown rather than swallowed.

Proof: [`IdempotencyConcurrencyTest`](../ledger-service/src/test/java/com/alexxavier/ledger/transfer/IdempotencyConcurrencyTest.java)
releases 20 threads with a `CountDownLatch` and asserts one transaction, two entries, one outbox event, one
non-replayed response and 20 identical `transactionId`s, repeated five times. A second test sends the same key
with 20 different bodies: exactly one executes and 19 get `422`.

## 4. Locking: why pessimistic, why sorted

**The bug locking prevents (lost update):** two transfers from the same account both read balance = 100, both
debit 80, both write 20. 160 left the account but only 80 was deducted.

**Pessimistic (`SELECT ... FOR UPDATE`) vs optimistic (`@Version` + retry):**

| | Pessimistic (chosen) | Optimistic |
|---|---|---|
| Under contention | requests queue on the row lock and each succeeds once | conflicts abort and must retry; under heavy contention most attempts are wasted |
| Code | straight-line, no retry loop | retry loop around the whole transaction, plus idempotency interplay |
| Cost when uncontended | one extra lock per row (cheap in Postgres) | none |

Payments have hot accounts (a merchant, a payroll account), and every transfer touches two rows, so conflicts
are normal, not rare. Waiting is cheaper and simpler than retrying. The cost is that a transaction holds locks
for its duration, so transactions are kept short: no network calls (Kafka publishing happens later, via the
outbox).

**Why sorted order prevents deadlocks:** if A→B locks A then B while B→A locks B then A, each can end up
holding one lock and waiting for the other forever. Postgres detects this after `deadlock_timeout` (1 s) and
kills one transaction, so you get latency spikes and errors. If *everyone* locks the smaller id first, the
cycle can't form: both transfers try to lock `min(A, B)` first, so one simply waits behind the other.
`TransferExecutor.lockInOrder` sorts with `UUID.compareTo`; the specific order doesn't matter, only that every
code path uses the same one (deposits go through the same method).

`TransferConcurrencyTest` runs 1,000 transfers in both directions across only 10 accounts from 50 threads and
asserts that none of them threw, which includes no deadlock errors.

**What it costs:** that test is deliberately the worst case, with 50 threads fighting over 10 rows, and it
takes about 20–32 s for 1,000 transfers in CI (≈30–50/s), because almost every transfer waits behind another.
The k6 load test spreads the same kind of traffic over 50 accounts and sustains about 700/s on the same
hardware. Hot rows, not CPU, set the ceiling. That's the honest answer to "how does it scale?", and §10 lists
what I'd do about it.

## 5. The outbox and effectively-once events

**The dual-write problem:** after a transfer we want to publish `TransferCompleted` to Kafka. Doing it in the
request is wrong either way round:

- write DB, then publish → a crash in between commits the transfer but loses the event;
- publish, then write DB → a crash or rollback sends an event for a transfer that never happened;
- publishing inside the transaction holds row locks during a network call, and the commit can still fail
  after the publish.

**The outbox pattern:** [`OutboxWriter`](../ledger-service/src/main/java/com/alexxavier/ledger/outbox/OutboxWriter.java)
inserts the event into the `outbox` table *inside the transfer's transaction*
(`Propagation.MANDATORY` makes it impossible to call outside one). The transfer and the event now commit or
roll back together. The idempotency test shows that a losing racer's event disappears with its rollback.

[`OutboxPublisher`](../ledger-service/src/main/java/com/alexxavier/ledger/outbox/OutboxPublisher.java) runs
every 200 ms in every replica:

1. `SELECT ... WHERE published_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED`. `SKIP LOCKED`
   lets three replicas publish in parallel without picking the same rows or waiting for each other.
2. Send every row to Kafka (key = transaction id, `acks=all`, idempotent producer) and **wait for every ack**.
3. `UPDATE outbox SET published_at = now()` and commit.

**Be precise: this is at-least-once, not exactly-once.** If the process dies after Kafka acked but before the
commit, the rows are still unpublished and will be sent again. So the consumer must be idempotent:
[`NotificationProcessor`](../notifications-service/src/main/java/com/alexxavier/ledger/notifications/NotificationProcessor.java)
does `INSERT INTO processed_events (event_id) ... ON CONFLICT DO NOTHING` and the side effect (notification
rows) **in one database transaction**; 0 rows inserted means "already done, skip". Kafka offsets are committed
only after that transaction commits (`ack-mode: record`), so a consumer crash causes a redelivery, which is
skipped.

> At-least-once delivery plus an idempotent consumer gives you effectively-once processing.

The experiment measures this directly: the consumer's `deliveries` table counts every delivery, so
`duplicateDeliveriesSkipped` shows how many redeliveries actually happened and were absorbed.

Ordering: events are keyed by transaction id, so there is no cross-transfer ordering guarantee, and the
consumer doesn't need one. If a consumer needed per-account order, I'd key by account id.

## 6. Reconciliation

[`ReconciliationService`](../ledger-service/src/main/java/com/alexxavier/ledger/reconciliation/ReconciliationService.java)
recomputes everything from first principles every 30 s (and on demand via `POST /admin/reconcile`):

1. every `account_balances.balance_cents` equals `SUM(ledger_entries.amount_cents)` for that account;
2. the global sum of entries is 0;
3. every `COMPLETED` transaction has exactly two entries summing to 0, and every `REJECTED` one has none;
4. every completed transaction has an outbox event, and no event waited longer than 60 s to be published.

It runs in a `REPEATABLE READ` transaction so all queries see one snapshot even while transfers commit.
Otherwise a transfer committing between two queries could produce a false alarm. The result is exported as
`ledger_reconciliation_mismatches` and alerts if it's ever above 0. `ReconciliationTest` corrupts a balance on
purpose and checks it is caught, with the account id and both numbers in the report.

Why audit if the code is correct? Because "the code is correct" is a belief, and reconciliation is
evidence. It also catches what the code can't control: manual SQL fixes, bad migrations, restored backups.

## 7. Observability

[`LedgerMetrics`](../ledger-service/src/main/java/com/alexxavier/ledger/metrics/LedgerMetrics.java) exports
(via `/actuator/prometheus`):

| Metric | Why it matters |
|---|---|
| `ledger_transfers_total{outcome}` | throughput, rejection rate, and how often clients retry (`replayed`) |
| `ledger_transfer_duration_seconds` (histogram) | p50/p95/p99 with `histogram_quantile`, aggregatable across replicas |
| `ledger_outbox_backlog`, `ledger_outbox_oldest_unpublished_seconds` | is Kafka keeping up? |
| `ledger_outbox_published_total` | publisher throughput |
| `ledger_reconciliation_mismatches` | are the books correct *right now*? |
| `notifications_events_total{result}` | consumer throughput and redeliveries skipped |

Histograms rather than client-side percentiles, because percentiles can't be averaged across three pods but
histogram buckets can be summed. The backlog gauges return `NaN` (unknown) instead of `0` when the database is
unreachable, so a dashboard doesn't show "all good" during an outage.

Alerts ([`alerts.yml`](../observability/prometheus/alerts.yml)): any reconciliation mismatch (critical),
outbox backlog > 1,000 or oldest event > 60 s, 5xx rate > 1%. The dashboard is
[`ledger.json`](../observability/grafana/dashboards/ledger.json).

## 8. Kubernetes and the failure experiment

[`k8s/`](../k8s): 3 ledger replicas behind a NodePort Service, a notifications Deployment, Postgres and Kafka
StatefulSets, ConfigMaps and a Secret. Probe choices:

- **startupProbe** gives the JVM and Flyway up to 2 minutes before liveness applies, so a slow start isn't
  mistaken for a hang.
- **readiness** includes the database: a pod that can't reach Postgres stops receiving traffic but isn't
  restarted.
- **liveness** does *not* include the database, otherwise a Postgres blip would restart every pod at once.
- A **preStop** sleep plus `server.shutdown: graceful` let kube-proxy stop routing to a terminating pod before
  it stops accepting connections.

Flyway runs on every replica at startup; it takes a Postgres advisory lock, so concurrent starts are safe.

**The experiment** ([`failure-experiment.sh`](../scripts/failure-experiment.sh)): 10,000 transfers, 10%
deliberately re-sent with the same key, while a ledger pod is crashed every 5 seconds, 50 times. Each crash is
a `SIGKILL` of the JVM from the node, so there is no graceful shutdown: in-flight HTTP requests get connection
resets and in-flight database transactions are aborted by Postgres when the connection drops. The pod is then
deleted so the Deployment replaces it immediately. Every 10th crash also kills the consumer.

The client behaves like a good payment client: on any failure it retries **the same request with the same
key**. This is the scenario idempotency exists for. A request may have committed before the crash with
only the response lost, and the retry must return that result instead of moving money again.

[`verify_experiment.py`](../scripts/verify_experiment.py) then compares what the client saw with what the
ledger recorded:

- **lost** = the client was told "completed" but the ledger has no such transaction (or the client gave up);
- **executed twice** = the ledger recorded more transactions than the client's logical transfers, or a
  re-send got a different answer;
- plus balance mismatches, the entry sum, reconciliation, and completed transfers without a delivered event.

Results are in the [README](../README.md#failure-injection-experiment).

## 9. How each guarantee is tested

| Guarantee | Test |
|---|---|
| Money is conserved under concurrency | `TransferConcurrencyTest`, k6 load test + `/admin/invariants` |
| No deadlocks | `TransferConcurrencyTest` (opposite-direction transfers on 10 hot accounts) |
| A retried request moves money once | `TransferApiTest`, `IdempotencyConcurrencyTest`, failure experiment |
| Key reuse with a different body is refused | `TransferApiTest`, `IdempotencyConcurrencyTest` |
| History can't be edited; no overdrafts even with buggy code | `LedgerImmutabilityTest` |
| Transfer and event are atomic | `OutboxIntegrationTest`, `IdempotencyConcurrencyTest` (one outbox row per transaction) |
| Events reach Kafka | `OutboxIntegrationTest` (real Kafka via Testcontainers) |
| Redeliveries are processed once | `IdempotentConsumerTest` (the same event delivered 3×) |
| Corruption is detected | `ReconciliationTest` |
| Metrics are exported | `MetricsEndpointTest` |
| It all survives crashes | failure experiment on kind |

## 10. Known limitations and what I would do next

- **Hot accounts serialize.** Every transfer from one account waits on one row lock. For a very hot account
  (a large merchant) I'd split it into N sub-accounts ("sharded balances") or batch its credits.
- **Single currency per transfer.** Currency is stored per account but transfers don't convert or check that
  both sides match. A real system would enforce matching currencies or post FX legs explicitly.
- **Idempotency keys are kept forever.** In production they would expire (e.g. after 24–72 h) with a
  scheduled cleanup, and keys would be scoped per client/API key.
- **One Postgres.** Throughput and availability are bounded by a single primary. Next steps are a replica for
  reads and reconciliation, then partitioning accounts across databases (at which point cross-shard transfers
  need a saga or two-phase posting).
- **Deposits are a test-only endpoint** with no authentication. There is no authn/authz at all; a real API would
  sit behind an API gateway with per-client credentials and rate limits.
- **Single-node Kafka and Postgres in k8s** are fine for a local cluster, not for production (use Strimzi with
  3 brokers and `min.insync.replicas=2`, and a managed or operator-run Postgres).

## 11. Interview questions, answered

**"How do you prevent double charges?"** Every transfer carries a client-generated Idempotency-Key. The key,
a hash of the request and the response are stored in the same database transaction as the transfer, so a
retry gets the stored answer and moves no money. The subtle case is two requests with the same new key at the
same instant: both pass the "seen it?" check, but unique constraints on the key mean only one can commit; the
other rolls back completely and returns the winner's response. A different body with the same key is a client
bug and gets a 422.

**"What happens if two transfers hit the same account at once?"** Both rows are locked with
`SELECT ... FOR UPDATE`, so the second transfer waits until the first commits and then sees the updated
balance; no lost updates. Locks are always taken in sorted-id order so A→B and B→A can't deadlock. I chose
pessimistic over optimistic locking because contention on payment accounts is normal, and retries under
contention waste more work than waiting.

**"How do you keep the database and Kafka in sync?"** I don't write to both in the request. The event goes into
an outbox table in the same transaction as the transfer, and a publisher ships it to Kafka afterwards and marks
it sent. That's at-least-once: a crash after sending but before marking resends it. So the consumer records
each event id in the same transaction as its side effect and skips ids it has seen. At-least-once delivery plus
an idempotent consumer gives effectively-once processing.

**"How do you know your system is correct?"** Four layers. Database constraints make illegal states
unrepresentable (no negative balances, no edited entries, no duplicate keys). Tests against a real Postgres prove
correctness under concurrency and races. A reconciliation job continuously recomputes every balance from the
entries and alerts on any mismatch. And a failure experiment crashes pods 50 times during 10,000 transfers and
verifies nothing was lost, duplicated or mismatched.

**"Why not use the database's SERIALIZABLE isolation instead of locks?"** It would also be correct, but
serialization failures must be retried by the application, which under contention behaves like optimistic
locking. Explicit row locks make the contention visible and the behavior predictable.

**"What breaks first at 10× load?"** Hot-account row locks, then the single Postgres primary's write
throughput (every transfer is several writes plus a commit). See §10.
