package com.jash.ledgerline.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** Registration, onboarding workflow and admin user management. Every state change is audited. */
@Service
public class UserService {

    public record UserView(long id, String username, String role, String status, String createdAt) {}
    public record OnboardingView(long id, long userId, String username, String status, String createdAt) {}

    private record Pending(long userId, String status, String username) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    private void audit(Long actorId, String action, String target, String detail) {
        jdbc.update("INSERT INTO audit_log(actor_id, action, target, outcome, detail) VALUES (?,?,?,?,?)",
                actorId, action, target, "OK", detail);
    }

    /** Public sign-up. The role is ALWAYS HOLDER; the caller can never choose it. */
    @Transactional
    public long register(String username, String password) {
        String name = username.toLowerCase(Locale.ROOT);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO users(username, password_hash, role) VALUES (?,?,'HOLDER') "
                        + "ON CONFLICT (username) DO NOTHING RETURNING id",
                Long.class, name, encoder.encode(password));
        if (ids.isEmpty()) throw new ConflictException("Username already taken");
        audit(ids.get(0), "USER_REGISTER", "user:" + ids.get(0), "self-registration");
        return ids.get(0);
    }

    // ------------------------------------------------------------------ onboarding (holder side)

    @Transactional
    public long requestOnboarding(long userId) {
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO onboarding_requests(user_id) VALUES (?) ON CONFLICT DO NOTHING RETURNING id",
                Long.class, userId);
        if (ids.isEmpty()) throw new ConflictException("You already have a pending onboarding request");
        audit(userId, "ONBOARDING_REQUEST", "request:" + ids.get(0), "account requested");
        return ids.get(0);
    }

    public List<OnboardingView> myOnboarding(long userId) {
        return jdbc.query(
                "SELECT r.id, r.user_id, u.username, r.status, r.created_at FROM onboarding_requests r "
                        + "JOIN users u ON u.id = r.user_id WHERE r.user_id = ? ORDER BY r.id DESC",
                (rs, n) -> new OnboardingView(rs.getLong("id"), rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString()),
                userId);
    }

    // ------------------------------------------------------------------ admin side

    public List<OnboardingView> pendingOnboarding() {
        return jdbc.query(
                "SELECT r.id, r.user_id, u.username, r.status, r.created_at FROM onboarding_requests r "
                        + "JOIN users u ON u.id = r.user_id WHERE r.status = 'PENDING' ORDER BY r.id",
                (rs, n) -> new OnboardingView(rs.getLong("id"), rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString()));
    }

    /** Approve opens an account for the user; reject just closes the request. A decided request cannot be re-decided. */
    @Transactional
    public void decide(long adminId, long requestId, boolean approve) {
        List<Pending> rows = jdbc.query(
                "SELECT r.user_id, r.status, u.username FROM onboarding_requests r "
                        + "JOIN users u ON u.id = r.user_id WHERE r.id = ? FOR UPDATE OF r",
                (rs, n) -> new Pending(rs.getLong("user_id"), rs.getString("status"), rs.getString("username")),
                requestId);
        if (rows.isEmpty()) throw new LedgerService.InvalidRequestException("Onboarding request not found");
        Pending p = rows.get(0);
        if (!p.status().equals("PENDING")) throw new ConflictException("Request was already decided");

        jdbc.update("UPDATE onboarding_requests SET status = ?, decided_by = ?, decided_at = now() WHERE id = ?",
                approve ? "APPROVED" : "REJECTED", adminId, requestId);
        if (approve) {
            jdbc.update("INSERT INTO accounts(owner_id, name) VALUES (?, ?)", p.userId(), p.username() + " Account");
        }
        audit(adminId, approve ? "ONBOARDING_APPROVED" : "ONBOARDING_REJECTED", "user:" + p.userId(),
                "request:" + requestId);
    }

    public List<UserView> listUsers() {
        return jdbc.query("SELECT id, username, role, status, created_at FROM users ORDER BY id",
                (rs, n) -> new UserView(rs.getLong("id"), rs.getString("username"), rs.getString("role"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString()));
    }

    @Transactional
    public void setStatus(long adminId, long userId, String status) {
        if (adminId == userId) throw new LedgerService.InvalidRequestException("You cannot change your own status");
        int n = jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, userId);
        if (n == 0) throw new LedgerService.InvalidRequestException("User not found");
        audit(adminId, "USER_STATUS", "user:" + userId, status);
    }
}
