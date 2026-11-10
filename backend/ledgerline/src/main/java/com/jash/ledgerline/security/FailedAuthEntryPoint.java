package com.jash.ledgerline.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Called on every 401. If the request carried credentials (so it was a failed login, not just
 * an anonymous request), the failure is counted for the lockout.
 * No WWW-Authenticate header on purpose: it would make browsers pop up their own login box over the UI.
 */
@Component
public class FailedAuthEntryPoint implements AuthenticationEntryPoint {

    private final RateLimiter limiter;

    public FailedAuthEntryPoint(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        String user = limiter.usernameFromAuthHeader(request.getHeader("Authorization"));
        if (user != null) limiter.recordFailure(limiter.clientIp(request), user);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required or invalid credentials\"}");
    }
}
