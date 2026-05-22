package com.att.tdp.issueflow.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the bcrypt hash written by the create flow round-trips back
 * to the encoder. The plaintext password is never stored, only the hash.
 */
@SpringBootTest
@Transactional
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void bcryptHashRoundTrips() {
        String plaintext = "secret123";
        User saved = userRepository.save(User.builder()
                .username("jdoe")
                .email("jdoe@example.com")
                .fullName("John Doe")
                .role(Role.DEVELOPER)
                .passwordHash(passwordEncoder.encode(plaintext))
                .build());

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertNotEquals(plaintext, reloaded.getPasswordHash());
        assertTrue(reloaded.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches(plaintext, reloaded.getPasswordHash()));
    }
}
