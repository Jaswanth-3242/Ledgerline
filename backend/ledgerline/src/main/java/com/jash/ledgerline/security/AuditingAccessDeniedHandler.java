package com.jash.ledgerline.security;

import com.jash.ledgerline.ledger.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Every 403 (wrong role) leaves a DENIED row in the audit log, then returns a JSON 403. */
@Component
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private final AuditService audit;

    public AuditingAccessDeniedHandler(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long actor = (auth != null && auth.getPrincipal() instanceof AppUser u) ? u.getId() : null;
        String target = request.getMethod() + " " + request.getRequestURI();
        if (target.length() > 100) target = target.substring(0, 100);
        audit.log(actor, "ACCESS_DENIED", target, "DENIED", "403");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
    }
}
