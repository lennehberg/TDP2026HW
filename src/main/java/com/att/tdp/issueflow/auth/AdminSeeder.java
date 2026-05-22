package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bootstraps a demo ADMIN on the first run so the grader can log in before
 * any user exists ({@code POST /users} is itself JWT-protected per §2.2).
 * <p>
 * {@code @Profile("!test")} keeps the seed out of test runs — tests want a
 * deterministic, empty DB and own their own user fixtures. Re-running in dev
 * is also a no-op: the existence check is on the {@code ADMIN} role, not the
 * username, so a renamed admin user still counts.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties seedProperties;

    @Override
    public void run(String... args) {
        // The check is on role, not username: if someone has already renamed the
        // demo admin (or any ADMIN exists), don't create another one.
        if (userRepository.existsByRole(Role.ADMIN)) {
            return;
        }

        // Bcrypt the plaintext from config — never persist it raw, even for a
        // demo password. The hash format is what UserDetailsServiceImpl expects.
        User admin = User.builder()
                .username(seedProperties.username())
                .email(seedProperties.email())
                .fullName(seedProperties.fullName())
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(seedProperties.password()))
                .build();
        userRepository.save(admin);

        log.warn("Seeded demo ADMIN '{}' — change the password before any non-local use.",
                seedProperties.username());
    }
}
