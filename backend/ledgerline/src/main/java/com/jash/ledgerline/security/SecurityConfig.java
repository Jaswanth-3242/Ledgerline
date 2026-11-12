package com.jash.ledgerline.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import java.util.List;

@Configuration
@EnableMethodSecurity   // turns on @PreAuthorize on controller methods
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Loads the user (with role and status) from the DB on EVERY request (stateless Basic auth),
     * so disabling a user or changing a role takes effect immediately.
     * Usernames are stored lower-case, so the lookup is case-insensitive.
     */
    @Bean
    UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return username -> {
            List<AppUser> found = jdbc.query(
                    "SELECT id, username, password_hash, role, status FROM users WHERE username = lower(?)",
                    (rs, n) -> new AppUser(rs.getLong("id"), rs.getString("username"),
                            rs.getString("password_hash"), rs.getString("role"),
                            "ACTIVE".equals(rs.getString("status"))),
                    username);
            if (found.isEmpty()) throw new UsernameNotFoundException("Bad credentials");
            return found.get(0);
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuditingAccessDeniedHandler deniedHandler,
                                    FailedAuthEntryPoint entryPoint, RateLimiter limiter) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless API, no cookies/sessions, so no CSRF surface
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/index.html", "/favicon.ico", "/api/health", "/api/ready", "/api/auth/register").permitAll()
                    .anyRequest().authenticated())   // everything else needs a login
            .exceptionHandling(e -> e
                    .accessDeniedHandler(deniedHandler)      // audit every 403
                    .authenticationEntryPoint(entryPoint))   // count every failed login
            .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
            .addFilterBefore(new RateLimitFilter(limiter), BasicAuthenticationFilter.class);
        return http.build();
    }
}
