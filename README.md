# payments-ledger

A backend service that moves money between accounts and never gets a balance wrong, even with thousands of concurrent requests, retried requests, or servers crashing mid-transfer.

**Stack:** Java 21 · Spring Boot 3 · PostgreSQL · Flyway · Testcontainers · k6

## Golden rules

1. Money is stored as a `long` count of cents, never as a `double`. Floating point loses fractions of a cent.
2. Ledger entries are never edited or deleted. Corrections are new entries, the way accountants do it.
3. Invariant: the sum of all ledger entries is always 0. Money moves; it is never created.

## Running locally

```bash
docker compose up -d      # starts Postgres on :5432
./mvnw -pl ledger-service spring-boot:run
```

Tests use Testcontainers, so Docker must be running:

```bash
./mvnw verify
```
