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
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Needs Redis running locally (redis-cli ping -> PONG). Each test uses its own random client IP via
 * X-Forwarded-For (app.trust-proxy=true), so tests never share counters with each other or with earlier runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.ratelimit.enabled=true", "app.trust-proxy=true"})
class RateLimitIntegrationTest {

    static final String GOOD = "Good@Pass12345";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Value("${local.server.port}") int port;

    String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "198.18." + r.nextInt(0, 256) + "." + r.nextInt(1, 255);
    }

    int call(String method, String path, String user, String pass, String body, String ip) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        b.header("X-Forwarded-For", ip);
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

    /** 5 sign-ups per 10 minutes per IP; the 6th is refused. */
    @Test
    void registrationIsRateLimited() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 5; i++) {
            String name = "rl_" + UUID.randomUUID().toString().substring(0, 8);
            String body = "{\"username\":\"" + name + "\",\"password\":\"Str0ng!Passw0rd\"}";
            assertEquals(201, call("POST", "/api/auth/register", null, null, body, ip));
        }
        String name = "rl_" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"username\":\"" + name + "\",\"password\":\"Str0ng!Passw0rd\"}";
        assertEquals(429, call("POST", "/api/auth/register", null, null, body, ip));
    }

    /** 5 failed logins lock that (IP, username) for 15 minutes, even for the right password; other IPs unaffected. */
    @Test
    void repeatedBadLoginsLockOut() throws Exception {
        String user = "lock_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO users(username, password_hash, role) VALUES (?,?,'HOLDER')", user, encoder.encode(GOOD));
        String ip = randomIp();

        for (int i = 0; i < 5; i++) {
            assertEquals(401, call("GET", "/api/accounts", user, "wrong-password", null, ip));
        }
        assertEquals(429, call("GET", "/api/accounts", user, "wrong-password", null, ip));
        assertEquals(429, call("GET", "/api/accounts", user, GOOD, null, ip));
        assertEquals(200, call("GET", "/api/accounts", user, GOOD, null, randomIp()));
    }

    /** 60 money requests per minute per IP; the 61st is refused (these are anonymous, so they get 401 first). */
    @Test
    void moneyEndpointsAreRateLimited() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 60; i++) {
            assertEquals(401, call("POST", "/api/transfers", null, null, "{}", ip));
        }
        assertEquals(429, call("POST", "/api/transfers", null, null, "{}", ip));
    }
}
