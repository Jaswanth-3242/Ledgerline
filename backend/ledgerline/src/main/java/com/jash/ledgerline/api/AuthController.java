package com.jash.ledgerline.api;

import com.jash.ledgerline.ledger.UserService;
import com.jash.ledgerline.security.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** No role field: new users are always HOLDERs. Password limited to printable ASCII, 10 to 72 chars (bcrypt limit). */
    record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "^[A-Za-z0-9_]+$") String username,
            @NotBlank @Pattern(regexp = "^[\\x20-\\x7E]{10,72}$", message = "must be 10 to 72 printable characters") String password) {}

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest body) {
        return Map.of("userId", users.register(body.username(), body.password()), "role", "HOLDER");
    }

    /** Who am I? Used by the UI right after login. */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AppUser user) {
        return Map.of("id", user.getId(), "username", user.getUsername(), "role", user.getRole());
    }
}
