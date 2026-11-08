package com.jash.ledgerline.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    /** 1 crore rupees in paise. Caps amounts so sums can never overflow a BIGINT. */
    static final long MAX_AMOUNT = 1_000_000_000L;

    // --- exceptions: mapped to 404 / 422 / 409 / 400 in ApiExceptionHandler ---
    public static class NotFoundException extends RuntimeException {
        public NotFoundException() { super("Account not found"); }
    }
    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException() { super("Insufficient funds"); }
    }
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() { super("Idempotency-Key reused with a different request"); }
    }
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String m) { super(m); }
    }

    record Leg(long accountId, long amount) {}
    record Acct(long id, Long ownerId, long balance, boolean system) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public LedgerService(JdbcTemplate jdbc, AuditService audit, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------- public API
    // actorId = authenticated user id from the SecurityContext, NEVER from the request body.
    // No @Transactional here on purpose: run() controls exactly where the transaction starts and ends.

    public long deposit(long actorId, String idemKey, long accountId, long amount) {
        return run("DEPOSIT", actorId, "acct:" + accountId, () -> {
            validateAmount(amount);
            String hash = hash("DEPOSIT", accountId, 0, amount);
            return idempotent(actorId, idemKey, hash, () ->
                    post("DEPOSIT", actorId, accountId,
                            List.of(new Leg(accountId, amount), new Leg(systemAccountId(), -amount))));
        });
    }

    public long withdraw(long actorId, String idemKey, long accountId, long amount) {
        return run("WITHDRAWAL", actorId, "acct:" + accountId, () -> {
            validateAmount(amount);
            String hash = hash("WITHDRAWAL", accountId, 0, amount);
            return idempotent(actorId, idemKey, hash, () ->
                    post("WITHDRAWAL", actorId, accountId,
                            List.of(new Leg(accountId, -amount), new Leg(systemAccountId(), amount))));
        });
    }

    public long transfer(long actorId, String idemKey, long fromId, long toId, long amount) {
        return run("TRANSFER", actorId, "acct:" + fromId + "->" + toId, () -> {
            validateAmount(amount);
            if (fromId == toId) throw new InvalidRequestException("Cannot transfer to the same account");
            String hash = hash("TRANSFER", fromId, toId, amount);
            return idempotent(actorId, idemKey, hash, () ->
                    post("TRANSFER", actorId, fromId,
                            List.of(new Leg(fromId, -amount), new Leg(toId, amount))));
        });
    }

    // ------------------------------------------------------------------ internals

    private void validateAmount(long amount) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new InvalidRequestException("Amount must be between 1 and " + MAX_AMOUNT + " paise");
        }
    }

    /**
     * One DB transaction per request.
     *  - success: the OK audit row commits together with the ledger rows (all or nothing).
     *  - failure: the transaction rolls back FIRST and releases its connection, and only then the
     *    DENIED row is written. (Writing it from inside the failing transaction needed a second
     *    connection while still holding the first, which can exhaust the pool under load.)
     */
    private long run(String action, long actorId, String target, Supplier<Long> body) {
        try {
            Long txnId = tx.execute(status -> {
                long id = body.get();
                jdbc.update("INSERT INTO audit_log(actor_id, action, target, outcome, detail) VALUES (?,?,?,?,?)",
                        actorId, action, target, "OK", "txn:" + id);
                return id;
            });
            return txnId;
        } catch (RuntimeException e) {
            audit.log(actorId, action, target, "DENIED", e.getClass().getSimpleName());
            throw e; // if this audit write fails the request fails too (fail closed)
        }
    }

    /**
     * Same key + same request  -> return the original txn id, no second posting.
     * Same key + different body -> 409.
     * Two concurrent requests with the same key: the second INSERT blocks on the
     * primary key until the first commits, then falls into the "already exists" branch.
     */
    private long idempotent(long actorId, String key, String requestHash, Supplier<Long> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new InvalidRequestException("Idempotency-Key header is required (max 100 chars)");
        }
        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys(user_id, key, request_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                actorId, key, requestHash);
        if (inserted == 0) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT request_hash, txn_id FROM idempotency_keys WHERE user_id = ? AND key = ?",
                    actorId, key);
            if (!requestHash.equals(row.get("request_hash"))) throw new IdempotencyConflictException();
            return ((Number) row.get("txn_id")).longValue();
        }
        long txnId = action.get();
        jdbc.update("UPDATE idempotency_keys SET txn_id = ? WHERE user_id = ? AND key = ?", txnId, actorId, key);
        return txnId;
    }

    /**
     * The one place money moves.
     * 1. lock every touched account in ascending id order (no deadlocks on A->B vs B->A)
     * 2. check ownership of the source account AFTER locking
     * 3. check balances AFTER locking (no check-then-act race)
     * 4. write the transaction + signed entries, update balances
     */
    private long post(String type, long actorId, long mustOwnAccountId, List<Leg> legs) {
        if (legs.stream().mapToLong(Leg::amount).sum() != 0) {
            throw new IllegalStateException("Legs do not balance"); // programming error, never user input
        }
        List<Long> ids = legs.stream().map(Leg::accountId).distinct().sorted().toList();
        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        List<Acct> locked = jdbc.query(
                "SELECT id, owner_id, balance, is_system FROM accounts WHERE id IN (" + placeholders
                        + ") ORDER BY id FOR UPDATE",
                (rs, n) -> new Acct(rs.getLong("id"), (Long) rs.getObject("owner_id"),
                        rs.getLong("balance"), rs.getBoolean("is_system")),
                ids.toArray());
        if (locked.size() != ids.size()) throw new NotFoundException();

        Map<Long, Acct> byId = new HashMap<>();
        locked.forEach(a -> byId.put(a.id(), a));

        // Ownership: not-yours looks exactly like does-not-exist (no account-id probing).
        Acct source = byId.get(mustOwnAccountId);
        if (source.system() || source.ownerId() == null || source.ownerId() != actorId) {
            throw new NotFoundException();
        }
        // A transfer destination may not be the system account.
        for (Leg leg : legs) {
            if (type.equals("TRANSFER") && byId.get(leg.accountId()).system()) throw new NotFoundException();
        }
        for (Leg leg : legs) {
            Acct a = byId.get(leg.accountId());
            if (!a.system() && a.balance() + leg.amount() < 0) throw new InsufficientFundsException();
        }

        long txnId = jdbc.queryForObject(
                "INSERT INTO transactions(type, created_by) VALUES (?,?) RETURNING id",
                Long.class, type, actorId);
        for (Leg leg : legs) {
            jdbc.update("INSERT INTO ledger_entries(txn_id, account_id, amount) VALUES (?,?,?)",
                    txnId, leg.accountId(), leg.amount());
            jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", leg.amount(), leg.accountId());
        }
        return txnId;
    }

    private long systemAccountId() {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE is_system", Long.class);
    }

    static String hash(String type, long from, long to, long amount) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((type + "|" + from + "|" + to + "|" + amount).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
