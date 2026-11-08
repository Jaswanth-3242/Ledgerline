package com.jash.ledgerline.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Proves the books balance: all entries sum to zero and every account balance equals its entries. */
@RestController
@RequestMapping("/api/reconcile")
public class ReconcileController {

    public record Report(boolean ok, long entriesTotal, List<Long> mismatchedAccountIds) {}

    private final JdbcTemplate jdbc;

    public ReconcileController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Report run() {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", Long.class);
        List<Long> bad = jdbc.queryForList(
                "SELECT a.id FROM accounts a "
                        + "LEFT JOIN (SELECT account_id, SUM(amount) AS s FROM ledger_entries GROUP BY account_id) e "
                        + "ON e.account_id = a.id WHERE a.balance <> COALESCE(e.s, 0) ORDER BY a.id",
                Long.class);
        long t = total == null ? 0 : total;
        return new Report(t == 0 && bad.isEmpty(), t, bad);
    }
}
