package com.jash.ledgerline.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * First-admin bootstrap. On startup, if ADMIN_BOOTSTRAP_USERNAME and ADMIN_BOOTSTRAP_PASSWORD are set
 * AND no admin exists yet, create that admin. Does nothing otherwise, so it cannot be used to add admins later,
 * and the password only ever lives in an environment variable (never in code, never logged).
 */
@Component
@Order(2)
public class BootstrapAdmin implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public BootstrapAdmin(JdbcTemplate jdbc, PasswordEncoder encoder,
                          @Value("${ADMIN_BOOTSTRAP_USERNAME:}") String username,
                          @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (username.isBlank() || password.isBlank()) return;
        Long admins = jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Long.class);
        if (admins != null && admins > 0) return;
        if (password.length() < 12 || password.length() > 72) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD must be 12 to 72 characters");
        }
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES (?, ?, 'ADMIN')",
                username.toLowerCase(Locale.ROOT), encoder.encode(password));
        jdbc.update("INSERT INTO audit_log(actor_id, action, target, outcome, detail) VALUES (NULL,?,?,?,?)",
                "ADMIN_BOOTSTRAP", "user:" + username.toLowerCase(Locale.ROOT), "OK", "first admin created");
    }
}
