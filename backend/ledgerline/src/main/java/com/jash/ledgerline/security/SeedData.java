package com.jash.ledgerline.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Demo users (admin, auditor, alice, bob), created only when the users table is empty.
 * Set SEED_PASSWORD to choose the demo password; set SEED_DEMO=false to skip demo data entirely.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "seed.demo", havingValue = "true", matchIfMissing = true)
public class SeedData implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String seedPassword;

    public SeedData(JdbcTemplate jdbc, PasswordEncoder encoder,
                    @Value("${SEED_PASSWORD:Demo@2026}") String seedPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(String... args) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        if (count != null && count > 0) return;

        String hash = encoder.encode(seedPassword);
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES ('admin', ?, 'ADMIN')", hash);
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES ('auditor', ?, 'AUDITOR')", hash);
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES ('alice', ?, 'HOLDER')", hash);
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES ('bob', ?, 'HOLDER')", hash);
        jdbc.update("INSERT INTO accounts(owner_id, name) SELECT id, 'Alice Savings' FROM users WHERE username = 'alice'");
        jdbc.update("INSERT INTO accounts(owner_id, name) SELECT id, 'Bob Savings' FROM users WHERE username = 'bob'");
    }
}
