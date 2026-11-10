package com.jash.ledgerline.api;

import com.jash.ledgerline.ledger.UserService;
import com.jash.ledgerline.ledger.UserService.OnboardingView;
import com.jash.ledgerline.security.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/onboarding")
@PreAuthorize("hasRole('HOLDER')")
public class OnboardingController {

    private final UserService users;

    public OnboardingController(UserService users) {
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> request(@AuthenticationPrincipal AppUser user) {
        return Map.of("requestId", users.requestOnboarding(user.getId()), "status", "PENDING");
    }

    @GetMapping
    public List<OnboardingView> mine(@AuthenticationPrincipal AppUser user) {
        return users.myOnboarding(user.getId());
    }
}
