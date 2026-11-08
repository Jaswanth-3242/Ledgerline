package com.jash.ledgerline.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
@EnableMethodSecurity   // turns on @PreAuthorize on controller methods
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Loads the user (with role) from our users table. */
    @Bean
    UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return username -> {
            List<AppUser> found = jdbc.query(
                    "SELECT id, username, password_hash, role FROM users WHERE username = ?",
                    (rs, n) -> new AppUser(rs.getLong("id"), rs.getString("username"),
                            rs.getString("password_hash"), rs.getString("role")),
                    username);
            if (found.isEmpty()) throw new UsernameNotFoundException("Bad credentials");
            return found.get(0);
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuditingAccessDeniedHandler deniedHandler) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless API, no cookies/sessions, so no CSRF surface
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/health").permitAll()
                    .anyRequest().authenticated())   // everything else needs a login
            .exceptionHandling(e -> e.accessDeniedHandler(deniedHandler)) // audit every 403
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
