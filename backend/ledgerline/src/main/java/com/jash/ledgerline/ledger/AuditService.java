package com.jash.ledgerline.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** REQUIRES_NEW so a denied attempt is still recorded when the caller's transaction rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long actorId, String action, String target, String outcome, String detail) {
        jdbc.update("INSERT INTO audit_log(actor_id, action, target, outcome, detail) VALUES (?,?,?,?,?)",
                actorId, action, target, outcome, detail);
    }
}
