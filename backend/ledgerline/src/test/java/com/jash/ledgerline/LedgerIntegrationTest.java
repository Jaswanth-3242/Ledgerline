package com.jash.ledgerline;

import com.jash.ledgerline.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attacks our own system. Each test creates its own fresh users/accounts, so tests never depend on
 * each other and nothing needs cleaning up (the ledger is append-only anyway).
 * Run with:  export DB_PASSWORD=...  then  ./mvnw test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerIntegrationTest {

    static final String PW = "Test@1234";

    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Value("${local.server.port}") int port;

    // ------------------------------------------------------------------ helpers

    long newUser(String role) {
        String name = role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject(
                "INSERT INTO users(username, password_hash, role) VALUES (?,?,?) RETURNING id",
                Long.class, name, encoder.encode(PW), role);
    }

    String usernameOf(long userId) {
        return jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, userId);
    }

    long newAccount(long ownerId) {
        return jdbc.queryForObject(
                "INSERT INTO accounts(owner_id, name) VALUES (?, 'test') RETURNING id", Long.class, ownerId);
    }

    long fundedAccount(long ownerId, long amount) {
        long acct = newAccount(ownerId);
        ledger.deposit(ownerId, key(), acct, amount);
        return acct;
    }

    long balance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    int status(String method, String path, String username, String body, String idemKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (username != null) {
            String cred = Base64.getEncoder().encodeToString((username + ":" + PW).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + cred);
        }
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        b.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest req = b.method(method, pub).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // ------------------------------------------------------- money integrity / concurrency

    /** THREATS #3: 20 threads race to withdraw 1000 from an account holding 5000. Exactly 5 may win. */
    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        long owner = newUser("HOLDER");
        long acct = fundedAccount(owner, 5000);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    ledger.withdraw(owner, key(), acct, 1000);
                    return true;
                } catch (LedgerService.InsufficientFundsException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get(60, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertEquals(5, ok);
        assertEquals(0, balance(acct));
    }

    /** THREATS #1: the same key sent 10 times in parallel posts exactly one transaction. */
    @Test
    void sameKeyPostsOnlyOnce() throws Exception {
        long owner = newUser("HOLDER");
        long acct = newAccount(owner);
        String k = key();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> ledger.deposit(owner, k, acct, 700)));
        }
        Set<Long> ids = new HashSet<>();
        for (Future<Long> f : futures) ids.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, ids.size());
        assertEquals(700, balance(acct));
    }

    /** THREATS #2: same key, different body is refused and nothing extra is posted. */
    @Test
    void sameKeyDifferentBodyIsRejected() {
        long owner = newUser("HOLDER");
        long acct = newAccount(owner);
        String k = key();
        ledger.deposit(owner, k, acct, 100);
        assertThrows(LedgerService.IdempotencyConflictException.class, () -> ledger.deposit(owner, k, acct, 200));
        assertEquals(100, balance(acct));
    }

    /** THREATS #4: opposite-direction transfers in parallel must not deadlock or lose money. */
    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        long u1 = newUser("HOLDER");
        long u2 = newUser("HOLDER");
        long a = fundedAccount(u1, 100000);
        long b = fundedAccount(u2, 100000);
        ExecutorService pool = Executors.newFixedThreadPool(30);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            final boolean forward = i % 2 == 0;
            futures.add(pool.submit(() -> forward
                    ? ledger.transfer(u1, key(), a, b, 10)
                    : ledger.transfer(u2, key(), b, a, 10)));
        }
        for (Future<Long> f : futures) f.get(60, TimeUnit.SECONDS); // any failure or timeout fails the test
        pool.shutdown();
        assertEquals(200000, balance(a) + balance(b));
    }

    // ------------------------------------------------------------- ownership / validation

    /** THREATS #5: another user's account is untouchable, and the attempt is audited. */
    @Test
    void cannotUseSomeoneElsesAccount() {
        long alice = newUser("HOLDER");
        long bob = newUser("HOLDER");
        long aliceAcct = fundedAccount(alice, 1000);
        long bobAcct = newAccount(bob);

        assertThrows(LedgerService.NotFoundException.class, () -> ledger.withdraw(bob, key(), aliceAcct, 100));
        assertThrows(LedgerService.NotFoundException.class, () -> ledger.deposit(bob, key(), aliceAcct, 100));
        assertThrows(LedgerService.NotFoundException.class, () -> ledger.transfer(bob, key(), aliceAcct, bobAcct, 100));
        assertEquals(1000, balance(aliceAcct));
        assertEquals(0, balance(bobAcct));

        Long denied = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_id = ? AND outcome = 'DENIED'", Long.class, bob);
        assertEquals(3L, denied.longValue());
    }

    /** THREATS #8: zero, negative and oversized amounts are all rejected. */
    @Test
    void badAmountsAreRejected() {
        long owner = newUser("HOLDER");
        long acct = fundedAccount(owner, 1000);
        long[] bad = {0, -1, 1_000_000_001L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long amount : bad) {
            assertThrows(LedgerService.InvalidRequestException.class, () -> ledger.deposit(owner, key(), acct, amount));
            assertThrows(LedgerService.InvalidRequestException.class, () -> ledger.withdraw(owner, key(), acct, amount));
        }
        assertEquals(1000, balance(acct));
    }

    /** THREATS #9 */
    @Test
    void selfTransferIsRejected() {
        long owner = newUser("HOLDER");
        long acct = fundedAccount(owner, 1000);
        assertThrows(LedgerService.InvalidRequestException.class, () -> ledger.transfer(owner, key(), acct, acct, 10));
        assertEquals(1000, balance(acct));
    }

    // ------------------------------------------------------------ database-level protection

    /** THREATS #10: history cannot be edited or deleted, even with direct SQL. */
    @Test
    void ledgerIsAppendOnly() {
        long owner = newUser("HOLDER");
        long acct = fundedAccount(owner, 100);
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE ledger_entries SET amount = 5 WHERE account_id = ?", acct));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM ledger_entries WHERE account_id = ?", acct));
        assertEquals(100, balance(acct));
    }

    /** THREATS #11: a one-sided entry (money from nowhere) is refused by the database itself. */
    @Test
    void unbalancedEntryIsRejectedByDatabase() {
        long owner = newUser("HOLDER");
        long acct = newAccount(owner);
        long txn = jdbc.queryForObject(
                "INSERT INTO transactions(type, created_by) VALUES ('DEPOSIT', ?) RETURNING id", Long.class, owner);
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO ledger_entries(txn_id, account_id, amount) VALUES (?,?,100)", txn, acct));
    }

    /** THREATS #16: after everything above, the whole ledger still sums to zero and balances match. */
    @Test
    void booksBalance() {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", Long.class);
        assertEquals(0L, total.longValue());
        List<Long> bad = jdbc.queryForList(
                "SELECT a.id FROM accounts a "
                        + "LEFT JOIN (SELECT account_id, SUM(amount) AS s FROM ledger_entries GROUP BY account_id) e "
                        + "ON e.account_id = a.id WHERE a.balance <> COALESCE(e.s, 0)", Long.class);
        assertTrue(bad.isEmpty(), "accounts whose balance != sum of entries: " + bad);
    }

    // ------------------------------------------------------------------ HTTP / roles

    @Test
    void noCredentialsGets401() throws Exception {
        assertEquals(401, status("GET", "/api/accounts", null, null, null));
    }

    /** THREATS #5 over HTTP: reading someone else's account or statement is a 404, own account is 200. */
    @Test
    void holderCannotReadOthersAccount() throws Exception {
        long alice = newUser("HOLDER");
        long bob = newUser("HOLDER");
        long aliceAcct = newAccount(alice);
        assertEquals(200, status("GET", "/api/accounts/" + aliceAcct, usernameOf(alice), null, null));
        assertEquals(404, status("GET", "/api/accounts/" + aliceAcct, usernameOf(bob), null, null));
        assertEquals(404, status("GET", "/api/accounts/" + aliceAcct + "/statement", usernameOf(bob), null, null));
    }

    /** THREATS #7: an auditor can never move money, and the denial is in the audit log. */
    @Test
    void auditorCannotMoveMoneyAndIsAudited() throws Exception {
        long owner = newUser("HOLDER");
        long acct = newAccount(owner);
        long auditor = newUser("AUDITOR");
        int s = status("POST", "/api/accounts/" + acct + "/deposit", usernameOf(auditor), "{\"amount\":100}", key());
        assertEquals(403, s);
        assertEquals(0, balance(acct));
        Long denied = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'ACCESS_DENIED'", Long.class, auditor);
        assertEquals(1L, denied.longValue());
    }

    /** THREATS #7: only an admin can open accounts. */
    @Test
    void holderCannotCreateAccounts() throws Exception {
        long holder = newUser("HOLDER");
        String body = "{\"ownerId\":" + holder + ",\"name\":\"sneaky\"}";
        assertEquals(403, status("POST", "/api/accounts", usernameOf(holder), body, null));
    }

    @Test
    void adminCanCreateAccountsAndMissingKeyIs400() throws Exception {
        long admin = newUser("ADMIN");
        long holder = newUser("HOLDER");
        String body = "{\"ownerId\":" + holder + ",\"name\":\"Main\"}";
        assertEquals(201, status("POST", "/api/accounts", usernameOf(admin), body, null));
        long acct = newAccount(holder);
        // money endpoint without Idempotency-Key header
        assertEquals(400, status("POST", "/api/accounts/" + acct + "/deposit", usernameOf(holder), "{\"amount\":100}", null));
    }
}
