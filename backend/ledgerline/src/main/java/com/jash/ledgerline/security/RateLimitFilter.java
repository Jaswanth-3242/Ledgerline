package com.jash.ledgerline.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Runs BEFORE authentication. Limits (per client IP):
 *   300 requests / minute on the whole API
 *   5 registrations / 10 minutes
 *   60 money requests / minute
 *   5 failed logins / 15 minutes for the same (IP, username): then 429, even with the right password
 * Created in SecurityConfig (not a @Component) so it only runs inside the security chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limiter.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = limiter.clientIp(req);
        String path = req.getRequestURI();
        boolean post = req.getMethod().equals("POST");

        if (!limiter.allow("rl:all:" + ip, 300, Duration.ofMinutes(1))) { reject(res, 60); return; }

        if (post && path.equals("/api/auth/register")
                && !limiter.allow("rl:reg:" + ip, 5, Duration.ofMinutes(10))) { reject(res, 600); return; }

        if (post && isMoneyPath(path)
                && !limiter.allow("rl:money:" + ip, 60, Duration.ofMinutes(1))) { reject(res, 60); return; }

        String user = limiter.usernameFromAuthHeader(req.getHeader("Authorization"));
        if (user != null && limiter.lockedOut(ip, user)) { reject(res, 900); return; }

        chain.doFilter(req, res);
    }

    private boolean isMoneyPath(String path) {
        return path.equals("/api/transfers") || path.endsWith("/deposit") || path.endsWith("/withdraw");
    }

    private void reject(HttpServletResponse res, int retryAfterSeconds) throws IOException {
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded, try again later\"}");
    }
}
