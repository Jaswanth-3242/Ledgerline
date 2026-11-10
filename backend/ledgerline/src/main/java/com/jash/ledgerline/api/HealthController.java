package com.jash.ledgerline.api;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Liveness: the process is up. */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Readiness: the database answers AND the ledger's system account exists. 503 otherwise. */
    @GetMapping("/api/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE is_system", Integer.class);
            if (n != null && n == 1) return ResponseEntity.ok(Map.of("status", "READY"));
        } catch (RuntimeException e) {
            // fall through to 503
        }
        return ResponseEntity.status(503).body(Map.of("status", "NOT_READY"));
    }
}
