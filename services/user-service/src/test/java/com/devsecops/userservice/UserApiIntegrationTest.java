package com.devsecops.userservice;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the REST layer: full Spring context, real H2 database,
 * seeding disabled so each test starts from a clean table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.seed.enabled=false")
class UserApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String json(String fullName, String email, String role) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "fullName", fullName,
                "email", email,
                "role", role
        ));
    }

    @Test
    void createUser_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{fullName:\"Broken\",\"email\":\"broken@example.com\",\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    void createThenFetchById_returnsCreatedUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Test User", "create.fetch@example.com", "USER")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("create.fetch@example.com"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        String location = result.getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Test User"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void listUsers_returnsJsonArray() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getUnknownUser_returns404WithErrorBody() throws Exception {
        mockMvc.perform(get("/api/users/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("User 999999 was not found"))
                .andExpect(jsonPath("$.path").value("/api/users/999999"));
    }

    @Test
    void createUser_withInvalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Bad Email", "not-an-email", "USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
    }

    @Test
    void createUser_withUnknownRole_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Bad Role", "bad.role@example.com", "SUPERUSER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("role")));
    }

    @Test
    void createUser_withDuplicateEmail_returns409() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("First Owner", "duplicate@example.com", "USER")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Second Owner", "duplicate@example.com", "ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void updateUser_changesNameAndRole() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Before Update", "update.me@example.com", "USER")))
                .andExpect(status().isCreated())
                .andReturn();

        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put("/api/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("After Update", "update.me@example.com", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("After Update"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void deleteUser_thenFetchReturns404() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("To Delete", "delete.me@example.com", "USER")))
                .andExpect(status().isCreated())
                .andReturn();

        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/users/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/" + id))
                .andExpect(status().isNotFound());
    }
}
