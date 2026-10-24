package com.alexxavier.ledger.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Stores the response of every executed transfer under its Idempotency-Key.
 * Plain JDBC because we need Postgres' {@code INSERT ... ON CONFLICT DO NOTHING}.
 */
@Repository
public class IdempotencyStore {

    public record StoredResponse(String key, String requestHash, String responseBody, int statusCode) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredResponse> find(String key) {
        return jdbc.query(
                "SELECT key, request_hash, response_body::text, status_code FROM idempotency_keys WHERE key = ?",
                (rs, i) -> new StoredResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                key).stream().findFirst();
    }

    /**
     * Inserts the key unless it already exists. If another transaction inserted the same key and has not
     * committed yet, Postgres makes this statement wait for it; if that transaction commits we get 0 rows.
     *
     * @return true if this call inserted the key, false if someone else owns it
     */
    public boolean insertIfAbsent(String key, String requestHash, String responseBody, int statusCode) {
        int rows = jdbc.update("""
                INSERT INTO idempotency_keys (key, request_hash, response_body, status_code)
                VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (key) DO NOTHING
                """, key, requestHash, responseBody, statusCode);
        return rows == 1;
    }
}
