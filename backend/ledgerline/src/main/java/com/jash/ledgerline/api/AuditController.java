package com.jash.ledgerline.api;

import com.jash.ledgerline.ledger.AccountService;
import com.jash.ledgerline.ledger.AccountService.AuditView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AccountService accounts;

    public AuditController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public List<AuditView> recent() {
        return accounts.recentAudit();
    }
}
