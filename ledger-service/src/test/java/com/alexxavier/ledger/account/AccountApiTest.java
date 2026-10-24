package com.alexxavier.ledger.account;

import com.alexxavier.ledger.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void createsAccountWithZeroBalance() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Ada\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Ada"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balanceCents").value(0));
    }

    @Test
    void depositIncreasesBalance() throws Exception {
        String id = createAccount("Grace");

        mvc.perform(post("/accounts/{id}/deposit", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":12345}"))
                .andExpect(status().isOk());

        mvc.perform(get("/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(12345));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(get("/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void rejectsBlankOwnerAndNonPositiveDeposit() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\" \"}"))
                .andExpect(status().isBadRequest());

        String id = createAccount("Linus");
        mvc.perform(post("/accounts/{id}/deposit", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":0}"))
                .andExpect(status().isBadRequest());
    }

    private String createAccount(String owner) throws Exception {
        String body = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"" + owner + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("id").asText();
    }
}
