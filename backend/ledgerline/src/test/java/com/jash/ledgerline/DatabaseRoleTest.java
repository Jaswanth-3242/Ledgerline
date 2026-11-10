package com.jash.ledgerline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the running app cannot tamper with history even if the app code were compromised.
 * Only runs when the app connects as ledgerline_app (export DB_USER=ledgerline_app), otherwise it is skipped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.ratelimit.enabled=false")
class DatabaseRoleTest {

    @Autowired JdbcTemplate jdbc;

    boolean tablePriv(String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_table_privilege(current_user, ?, ?)", Boolean.class, "public." + table, privilege));
    }

    boolean columnPriv(String table, String column, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_column_privilege(current_user, ?, ?, ?)", Boolean.class, "public." + table, column, privilege));
    }

    @Test
    void appRoleHasLeastPrivilege() {
        String me = jdbc.queryForObject("SELECT current_user", String.class);
        assumeTrue("ledgerline_app".equals(me), "skipped: run with DB_USER=ledgerline_app to check least privilege");

        Boolean superuser = jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertFalse(Boolean.TRUE.equals(superuser));

        for (String t : List.of("ledger_entries", "transactions", "audit_log", "idempotency_keys",
                "users", "accounts", "onboarding_requests")) {
            assertFalse(tablePriv(t, "DELETE"), t + " must not allow DELETE");
            assertFalse(tablePriv(t, "TRUNCATE"), t + " must not allow TRUNCATE");
        }
        for (String t : List.of("ledger_entries", "transactions", "audit_log")) {
            assertFalse(tablePriv(t, "UPDATE"), t + " must not allow UPDATE");
            assertTrue(tablePriv(t, "INSERT"), t + " must allow INSERT");
        }
        assertTrue(columnPriv("accounts", "balance", "UPDATE"));
        assertFalse(columnPriv("accounts", "owner_id", "UPDATE"));
        assertFalse(columnPriv("users", "role", "UPDATE"));
        assertFalse(columnPriv("users", "password_hash", "UPDATE"));
        assertTrue(columnPriv("users", "status", "UPDATE"));
    }
}
