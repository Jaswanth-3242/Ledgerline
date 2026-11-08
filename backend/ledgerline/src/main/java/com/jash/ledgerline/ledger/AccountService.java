package com.jash.ledgerline.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Account creation and read-only views. Every read re-checks ownership. */
@Service
public class AccountService {

    public record AccountView(long id, String name, long balance) {}
    public record EntryView(long entryId, long transactionId, String type, long amount, String createdAt) {}
    public record AuditView(long id, Long actorId, String action, String target, String outcome,
                            String detail, String createdAt) {}

    private record Row(long id, long ownerId, String name, long balance) {}

    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AccountView create(long adminId, long ownerId, String name) {
        List<String> roles = jdbc.queryForList("SELECT role FROM users WHERE id = ?", String.class, ownerId);
        if (roles.isEmpty() || !roles.get(0).equals("HOLDER")) {
            throw new LedgerService.InvalidRequestException("Owner must be an existing account holder");
        }
        String clean = name.trim();
        long id = jdbc.queryForObject(
                "INSERT INTO accounts(owner_id, name) VALUES (?,?) RETURNING id", Long.class, ownerId, clean);
        jdbc.update("INSERT INTO audit_log(actor_id, action, target, outcome, detail) VALUES (?,?,?,?,?)",
                adminId, "ACCOUNT_CREATE", "acct:" + id, "OK", "owner:" + ownerId);
        return new AccountView(id, clean, 0);
    }

    /** Holders see only their own accounts. Admin and auditor see all customer accounts. */
    public List<AccountView> list(long actorId, boolean holder) {
        if (holder) {
            return jdbc.query("SELECT id, name, balance FROM accounts WHERE NOT is_system AND owner_id = ? ORDER BY id",
                    (rs, n) -> new AccountView(rs.getLong("id"), rs.getString("name"), rs.getLong("balance")),
                    actorId);
        }
        return jdbc.query("SELECT id, name, balance FROM accounts WHERE NOT is_system ORDER BY id",
                (rs, n) -> new AccountView(rs.getLong("id"), rs.getString("name"), rs.getLong("balance")));
    }

    /** Someone else's account looks exactly like a missing one (404). */
    public AccountView get(long actorId, boolean holder, long accountId) {
        List<Row> found = jdbc.query(
                "SELECT id, owner_id, name, balance FROM accounts WHERE id = ? AND NOT is_system",
                (rs, n) -> new Row(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"), rs.getLong("balance")),
                accountId);
        if (found.isEmpty()) throw new LedgerService.NotFoundException();
        Row r = found.get(0);
        if (holder && r.ownerId() != actorId) throw new LedgerService.NotFoundException();
        return new AccountView(r.id(), r.name(), r.balance());
    }

    public List<EntryView> statement(long actorId, boolean holder, long accountId) {
        get(actorId, holder, accountId); // access check first
        return jdbc.query(
                "SELECT e.id, e.txn_id, t.type, e.amount, e.created_at FROM ledger_entries e "
                        + "JOIN transactions t ON t.id = e.txn_id WHERE e.account_id = ? ORDER BY e.id DESC LIMIT 200",
                (rs, n) -> new EntryView(rs.getLong("id"), rs.getLong("txn_id"), rs.getString("type"),
                        rs.getLong("amount"), rs.getTimestamp("created_at").toInstant().toString()),
                accountId);
    }

    public List<AuditView> recentAudit() {
        return jdbc.query(
                "SELECT id, actor_id, action, target, outcome, detail, created_at FROM audit_log ORDER BY id DESC LIMIT 200",
                (rs, n) -> new AuditView(rs.getLong("id"), (Long) rs.getObject("actor_id"), rs.getString("action"),
                        rs.getString("target"), rs.getString("outcome"), rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant().toString()));
    }
}
