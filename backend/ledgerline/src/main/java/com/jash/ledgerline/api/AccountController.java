package com.jash.ledgerline.api;

import com.jash.ledgerline.ledger.AccountService;
import com.jash.ledgerline.ledger.AccountService.AccountView;
import com.jash.ledgerline.ledger.AccountService.EntryView;
import com.jash.ledgerline.security.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    record CreateAccountRequest(@NotNull Long ownerId, @NotBlank @Size(max = 100) String name) {}

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Only an ADMIN can open accounts. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@AuthenticationPrincipal AppUser user, @Valid @RequestBody CreateAccountRequest body) {
        return accounts.create(user.getId(), body.ownerId(), body.name());
    }

    @GetMapping
    public List<AccountView> list(@AuthenticationPrincipal AppUser user) {
        return accounts.list(user.getId(), user.isHolder());
    }

    @GetMapping("/{id}")
    public AccountView get(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return accounts.get(user.getId(), user.isHolder(), id);
    }

    @GetMapping("/{id}/statement")
    public List<EntryView> statement(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return accounts.statement(user.getId(), user.isHolder(), id);
    }
}
