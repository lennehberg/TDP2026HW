package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.Role;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the full auth pipeline through the real {@code SecurityFilterChain}
 * — no {@code TestSecurityConfig} import here. The seeded ADMIN is also
 * disabled (the seeder is {@code @Profile("!test")}) so we build a test user
 * explicitly in {@link #setup()}.
 * <p>
 * Note we are NOT {@code @Transactional}: the deny-list write in
 * {@code AuthService.logout} happens inside the controller's own transaction
 * and we need it to be visible to the subsequent filter call on the same MVC
 * invocation. A wrapping @Transactional would isolate that write.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RevokedTokenRepository revokedTokenRepository;

    @BeforeEach
    void setup() {
        // Order matters: RevokedToken has no FK to users but we clean both
        // tables on each test to keep tests independent.
        revokedTokenRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(User.builder()
                .username("alice")
                .email("alice@example.com")
                .fullName("Alice")
                .role(Role.DEVELOPER)
                .passwordHash(passwordEncoder.encode("secret123"))
                .build());
    }

    private String loginAndGetToken() throws Exception {
        MvcResult res = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    @Test
    void loginHappyPathReturnsParsableToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresIn", is(3600)));
    }

    @Test
    void loginWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginUnknownUserReturns401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ghost","password":"secret123"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithoutAuthorizationReturns401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("unauthorized")));
    }

    @Test
    void meWithValidTokenReturnsUser() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("alice")))
                .andExpect(jsonPath("$.role", is("DEVELOPER")));
    }

    @Test
    void logoutThenReuseTokenReturns401() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // Same token, now in the deny-list — should be treated as unauthenticated.
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
