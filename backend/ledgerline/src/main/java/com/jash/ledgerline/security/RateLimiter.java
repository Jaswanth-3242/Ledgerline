package com.jash.ledgerline.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Fixed-window counters in Redis. The increment and the expiry are ONE atomic Lua script,
 * so a crash can never leave a counter without an expiry (which would block someone forever).
 * If Redis is unreachable we FAIL OPEN (log a warning) so a Redis outage does not take the bank down;
 * the database-level protections (locks, idempotency, ownership) do not depend on Redis.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    static final int MAX_FAILED_LOGINS = 5;
    static final Duration FAILED_LOGIN_WINDOW = Duration.ofMinutes(15);

    private static final DefaultRedisScript<Long> INCR_WITH_EXPIRY = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final boolean trustProxy;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${app.ratelimit.enabled:true}") boolean enabled,
                       @Value("${app.trust-proxy:false}") boolean trustProxy) {
        this.redis = redis;
        this.enabled = enabled;
        this.trustProxy = trustProxy;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Client IP. Behind a trusted proxy we use the LAST X-Forwarded-For entry, which is the one our own proxy
     * appended (anything the client sent is to the left of it, so it cannot be used to dodge limits).
     */
    public String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** Counts this request; returns true while the caller is still under the limit. */
    public boolean allow(String key, int limit, Duration window) {
        if (!enabled) return true;
        Long n = increment(key, window);
        return n == null || n <= limit;
    }

    private Long increment(String key, Duration window) {
        try {
            return redis.execute(INCR_WITH_EXPIRY, List.of(key), String.valueOf(window.toMillis()));
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, failing open: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------ failed-login lockout

    private String failKey(String ip, String user) {
        return "rl:fail:" + ip + ":" + user;
    }

    public void recordFailure(String ip, String user) {
        if (!enabled) return;
        increment(failKey(ip, user), FAILED_LOGIN_WINDOW);
    }

    public boolean lockedOut(String ip, String user) {
        if (!enabled) return false;
        try {
            String v = redis.opsForValue().get(failKey(ip, user));
            return v != null && Long.parseLong(v) >= MAX_FAILED_LOGINS;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, failing open: {}", e.getMessage());
            return false;
        }
    }

    /** The username in a Basic header (lower-cased, max 50 chars), or null if there is none. NOT verified. */
    public String usernameFromAuthHeader(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            String user = colon < 0 ? decoded : decoded.substring(0, colon);
            if (user.isBlank()) return null;
            user = user.toLowerCase(Locale.ROOT);
            return user.length() > 50 ? user.substring(0, 50) : user;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
