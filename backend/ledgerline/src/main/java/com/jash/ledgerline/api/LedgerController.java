package com.jash.ledgerline.api;

import com.jash.ledgerline.ledger.LedgerService;
import com.jash.ledgerline.security.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Money movement. HOLDER only: admins and auditors can never move money. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('HOLDER')")
public class LedgerController {

    record AmountRequest(@NotNull Long amount) {}
    record TransferRequest(@NotNull Long fromAccountId, @NotNull Long toAccountId, @NotNull Long amount) {}

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/accounts/{id}/deposit")
    public Map<String, Object> deposit(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody AmountRequest body) {
        return Map.of("transactionId", ledger.deposit(user.getId(), key, id, body.amount()));
    }

    @PostMapping("/accounts/{id}/withdraw")
    public Map<String, Object> withdraw(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                        @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody AmountRequest body) {
        return Map.of("transactionId", ledger.withdraw(user.getId(), key, id, body.amount()));
    }

    @PostMapping("/transfers")
    public Map<String, Object> transfer(@AuthenticationPrincipal AppUser user,
                                        @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody TransferRequest body) {
        return Map.of("transactionId",
                ledger.transfer(user.getId(), key, body.fromAccountId(), body.toAccountId(), body.amount()));
    }
}
