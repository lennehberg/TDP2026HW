package com.att.tdp.issueflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permissive filter chain for slice/MVC tests that don't care about auth.
 * Imported via {@code @Import(TestSecurityConfig.class)} on each test class
 * that needs it (e.g. {@link com.att.tdp.issueflow.user.UserControllerTest})
 * — auth-specific tests like {@code AuthControllerTest} skip the import so
 * they exercise the real chain.
 * <p>
 * Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it wins over the
 * production chain in {@code SecurityConfig} during tests.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }
}
