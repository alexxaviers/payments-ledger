package com.alexxavier.ledger.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Counts used to verify "every event delivered, none processed twice". */
@RestController
public class StatsController {

    public record Stats(long processedEvents, long deliveries, long duplicateDeliveriesSkipped, long notifications) {
    }

    private final JdbcTemplate jdbc;

    public StatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/stats")
    public Stats stats() {
        long processed = count("SELECT COUNT(*) FROM processed_events");
        long deliveries = count("SELECT COUNT(*) FROM deliveries");
        long notifications = count("SELECT COUNT(*) FROM notifications");
        return new Stats(processed, deliveries, deliveries - processed, notifications);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
