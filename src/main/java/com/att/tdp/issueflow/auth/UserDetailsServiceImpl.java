package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter from the domain {@link User} entity to Spring Security's
 * {@link UserDetails}. Lives in {@code auth/} (not {@code user/}) because it is
 * pure auth glue — it only exists so the {@code DaoAuthenticationProvider} can
 * verify passwords during {@code /auth/login}.
 * <p>
 * Authority naming follows Spring's convention: a {@code ROLE_ADMIN} authority
 * is what {@code hasRole("ADMIN")} expands to, so we prepend the prefix here
 * and {@link JwtAuthenticationFilter} does the same when reading back from the
 * token.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("user not found: " + username));

        return org.springframework.security.core.userdetails.User.builder()
                .username(u.getUsername())
                .password(u.getPasswordHash())   // already bcrypt-hashed by UserService
                .roles(u.getRole().name())       // .roles() auto-prepends "ROLE_"
                .build();
    }
}