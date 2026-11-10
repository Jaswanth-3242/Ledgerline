package com.jash.ledgerline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registration, onboarding, admin controls, disabled users, health/readiness. Run: ./mvnw test */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnboardingIntegrationTest {

    static final String PW = "Test@1234!!";
    static final String REG_PW = "Str0ng!Passw0rd";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Value("${local.server.port}") int port;

    // ------------------------------------------------------------------ helpers

    String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    long newUser(String role) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password_hash, role) VALUES (?,?,?) RETURNING id",
                Long.class, uniqueName(role.toLowerCase()), encoder.encode(PW), role);
    }

    String usernameOf(long userId) {
        return jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, userId);
    }

    long idOf(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    int call(String method, String path, String user, String pass, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (user != null) {
            String cred = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + cred);
        }
        b.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return HttpClient.newHttpClient().send(b.method(method, pub).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    String registerBody(String name, String password, String extra) {
        return "{\"username\":\"" + name + "\",\"password\":\"" + password + "\"" + extra + "}";
    }

    // ------------------------------------------------------------------ registration

    @Test
    void registrationNeverGrantsAnAdminRole() throws Exception {
        String name = uniqueName("reg");
        int s = call("POST", "/api/auth/register", null, null, registerBody(name, REG_PW, ",\"role\":\"ADMIN\""));
        if (s == 201) {
            assertEquals("HOLDER", jdbc.queryForObject("SELECT role FROM users WHERE username = ?", String.class, name));
        } else {
            assertEquals(400, s); // rejecting the unknown field is also safe
        }
    }

    @Test
    void weakOrOddPasswordsAreRejected() throws Exception {
        assertEquals(400, call("POST", "/api/auth/register", null, null, registerBody(uniqueName("weak"), "short", "")));
        String tooLong = "a".repeat(73);
        assertEquals(400, call("POST", "/api/auth/register", null, null, registerBody(uniqueName("long"), tooLong, "")));
    }

    @Test
    void duplicateUsernamesAreRejectedCaseInsensitively() throws Exception {
        String name = uniqueName("dup");
        assertEquals(201, call("POST", "/api/auth/register", null, null, registerBody(name, REG_PW, "")));
        assertEquals(409, call("POST", "/api/auth/register", null, null, registerBody(name, REG_PW, "")));
        assertEquals(409, call("POST", "/api/auth/register", null, null, registerBody(name.toUpperCase(), REG_PW, "")));
    }

    // ------------------------------------------------------------------ onboarding workflow

    @Test
    void onboardingFlowEndToEnd() throws Exception {
        String name = uniqueName("onb");
        assertEquals(201, call("POST", "/api/auth/register", null, null, registerBody(name, REG_PW, "")));
        long userId = idOf(name);

        // a fresh holder has no accounts, can request one, but not twice
        assertEquals(201, call("POST", "/api/onboarding", name, REG_PW, null));
        assertEquals(409, call("POST", "/api/onboarding", name, REG_PW, null));

        // a holder cannot see or decide the admin queue
        assertEquals(403, call("GET", "/api/admin/onboarding", name, REG_PW, null));

        long requestId = jdbc.queryForObject(
                "SELECT id FROM onboarding_requests WHERE user_id = ?", Long.class, userId);
        assertEquals(403, call("POST", "/api/admin/onboarding/" + requestId + "/decision", name, REG_PW,
                "{\"approve\":true}"));
        Long before = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE owner_id = ?", Long.class, userId);
        assertEquals(0L, before.longValue());

        // an admin approves: an account appears; deciding again is refused
        String admin = usernameOf(newUser("ADMIN"));
        assertEquals(200, call("GET", "/api/admin/onboarding", admin, PW, null));
        assertEquals(200, call("POST", "/api/admin/onboarding/" + requestId + "/decision", admin, PW,
                "{\"approve\":true}"));
        Long after = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE owner_id = ?", Long.class, userId);
        assertEquals(1L, after.longValue());
        assertEquals(409, call("POST", "/api/admin/onboarding/" + requestId + "/decision", admin, PW,
                "{\"approve\":false}"));
        Long audited = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'ONBOARDING_APPROVED' AND target = ?",
                Long.class, "user:" + userId);
        assertEquals(1L, audited.longValue());
    }

    // ------------------------------------------------------------------ admin user management

    @Test
    void disabledUserCannotLogInAndAdminCannotDisableSelf() throws Exception {
        long holderId = newUser("HOLDER");
        String holder = usernameOf(holderId);
        long adminId = newUser("ADMIN");
        String admin = usernameOf(adminId);

        assertEquals(200, call("GET", "/api/accounts", holder, PW, null));
        assertEquals(200, call("POST", "/api/admin/users/" + holderId + "/status", admin, PW,
                "{\"status\":\"DISABLED\"}"));
        assertEquals(401, call("GET", "/api/accounts", holder, PW, null)); // effective immediately
        assertEquals(200, call("POST", "/api/admin/users/" + holderId + "/status", admin, PW,
                "{\"status\":\"ACTIVE\"}"));
        assertEquals(200, call("GET", "/api/accounts", holder, PW, null));

        assertEquals(400, call("POST", "/api/admin/users/" + adminId + "/status", admin, PW,
                "{\"status\":\"DISABLED\"}"));
        assertEquals(403, call("GET", "/api/admin/users", holder, PW, null)); // holders cannot list users
        assertEquals(400, call("POST", "/api/admin/users/" + holderId + "/status", admin, PW,
                "{\"status\":\"HACKED\"}"));
    }

    // ------------------------------------------------------------------ health / readiness

    @Test
    void healthAndReadinessArePublicAndReady() throws Exception {
        assertEquals(200, call("GET", "/api/health", null, null, null));
        assertEquals(200, call("GET", "/api/ready", null, null, null));
        assertTrue(true);
    }

    @Test
    void whoAmIWorksAfterLogin() throws Exception {
        long id = newUser("HOLDER");
        assertEquals(200, call("GET", "/api/auth/me", usernameOf(id), PW, null));
        assertEquals(401, call("GET", "/api/auth/me", null, null, null));
    }
}
