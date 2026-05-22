package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    private static final String VALID_BODY = """
            {
              "username": "jdoe",
              "email": "jdoe@example.com",
              "fullName": "John Doe",
              "role": "DEVELOPER",
              "password": "secret123"
            }
            """;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void createReturnsUserAndPersists() throws Exception {
        MvcResult res = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.username", is("jdoe")))
                .andExpect(jsonPath("$.email", is("jdoe@example.com")))
                .andExpect(jsonPath("$.fullName", is("John Doe")))
                .andExpect(jsonPath("$.role", is("DEVELOPER")))
                // Password and timestamps must not leak through the response.
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andReturn();

        Long id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
        User saved = userRepository.findById(id).orElseThrow();
        // Stored value must be the hash, not the plaintext.
        org.junit.jupiter.api.Assertions.assertNotEquals("secret123", saved.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getPasswordHash().startsWith("$2"));
    }

    @Test
    void duplicateUsernameReturns409() throws Exception {
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());

        String sameUsername = """
                {
                  "username": "jdoe",
                  "email": "other@example.com",
                  "fullName": "Other Person",
                  "role": "DEVELOPER",
                  "password": "secret123"
                }
                """;
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(sameUsername))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("username already exists")));
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());

        String sameEmail = """
                {
                  "username": "asmith",
                  "email": "jdoe@example.com",
                  "fullName": "Alice Smith",
                  "role": "ADMIN",
                  "password": "secret123"
                }
                """;
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(sameEmail))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("email already exists")));
    }

    @Test
    void invalidRoleReturns400() throws Exception {
        String body = """
                {
                  "username": "jdoe",
                  "email": "jdoe@example.com",
                  "fullName": "John Doe",
                  "role": "OWNER",
                  "password": "secret123"
                }
                """;
        // OWNER fails Jackson enum deserialization → HttpMessageNotReadableException → 400.
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("malformed request body")));
    }

    @Test
    void blankUsernameReturns400() throws Exception {
        String body = """
                {
                  "username": "",
                  "email": "jdoe@example.com",
                  "fullName": "John Doe",
                  "role": "DEVELOPER",
                  "password": "secret123"
                }
                """;
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username", notNullValue()));
    }

    @Test
    void malformedEmailReturns400() throws Exception {
        String body = """
                {
                  "username": "jdoe",
                  "email": "not-an-email",
                  "fullName": "John Doe",
                  "role": "DEVELOPER",
                  "password": "secret123"
                }
                """;
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email", notNullValue()));
    }

    @Test
    void usernameWithDotReturns400() throws Exception {
        // Mention regex (§3.6) only matches [A-Za-z0-9_]+, so a "." in the username
        // would create unreachable mentions. The @Pattern guard catches it.
        String body = """
                {
                  "username": "j.doe",
                  "email": "jdoe@example.com",
                  "fullName": "John Doe",
                  "role": "DEVELOPER",
                  "password": "secret123"
                }
                """;
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username", notNullValue()));
    }

    @Test
    void getByUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/users/{id}", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("user 9999 not found")));
    }

    @Test
    void partialUpdatePreservesUnsetFields() throws Exception {
        MvcResult res = mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andReturn();
        Long id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        // Update fullName only — role must remain DEVELOPER.
        mockMvc.perform(post("/users/update/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "fullName": "Jane Doe" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName", is("Jane Doe")))
                .andExpect(jsonPath("$.role", is("DEVELOPER")));
    }

    @Test
    void roleUpdatePreservesFullName() throws Exception {
        MvcResult res = mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andReturn();
        Long id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/users/update/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "role": "ADMIN" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("ADMIN")))
                .andExpect(jsonPath("$.fullName", is("John Doe")));
    }

    @Test
    void deleteRemovesUserAndSubsequentGetIs404() throws Exception {
        MvcResult res = mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andReturn();
        Long id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/users/{id}", id)).andExpect(status().isOk());
        mockMvc.perform(get("/users/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownIdReturns404() throws Exception {
        mockMvc.perform(delete("/users/{id}", 9999))
                .andExpect(status().isNotFound());
    }
}
