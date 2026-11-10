package com.jash.ledgerline.api;

import com.jash.ledgerline.ledger.UserService;
import com.jash.ledgerline.ledger.UserService.OnboardingView;
import com.jash.ledgerline.ledger.UserService.UserView;
import com.jash.ledgerline.security.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin-only: user management and onboarding decisions. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {}
    record DecisionRequest(@NotNull Boolean approve) {}

    private final UserService users;

    public AdminController(UserService users) {
        this.users = users;
    }

    @GetMapping("/users")
    public List<UserView> users() {
        return users.listUsers();
    }

    @PostMapping("/users/{id}/status")
    public Map<String, String> setStatus(@AuthenticationPrincipal AppUser admin, @PathVariable long id,
                                         @Valid @RequestBody StatusRequest body) {
        users.setStatus(admin.getId(), id, body.status());
        return Map.of("status", body.status());
    }

    @GetMapping("/onboarding")
    public List<OnboardingView> pending() {
        return users.pendingOnboarding();
    }

    @PostMapping("/onboarding/{id}/decision")
    public Map<String, String> decide(@AuthenticationPrincipal AppUser admin, @PathVariable long id,
                                      @Valid @RequestBody DecisionRequest body) {
        users.decide(admin.getId(), id, body.approve());
        return Map.of("decision", body.approve() ? "APPROVED" : "REJECTED");
    }
}
