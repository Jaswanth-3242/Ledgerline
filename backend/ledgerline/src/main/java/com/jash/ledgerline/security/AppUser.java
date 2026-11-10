package com.jash.ledgerline.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/** The logged-in user. Carries the DB id so controllers never trust an id from the request. */
public class AppUser extends User {

    private final long id;
    private final String role;

    public AppUser(long id, String username, String passwordHash, String role, boolean enabled) {
        super(username, passwordHash, enabled, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.id = id;
        this.role = role;
    }

    public long getId() { return id; }

    public String getRole() { return role; }

    public boolean isHolder() { return "HOLDER".equals(role); }
}
