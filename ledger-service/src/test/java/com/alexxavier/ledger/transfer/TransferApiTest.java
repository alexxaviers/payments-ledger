package com.alexxavier.ledger.transfer;

import com.alexxavier.ledger.AbstractIntegrationTest;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.account.AccountService;
import com.alexxavier.ledger.common.SystemAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TransferApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    UUID alice;
    UUID bob;

    @BeforeEach
    void setUp() {
        alice = accounts.create(new CreateAccountRequest("Alice", null)).id();
        bob = accounts.create(new CreateAccountRequest("Bob", null)).id();
        accounts.deposit(UUID.randomUUID().toString(), alice, 10_000);
    }

    @Test
    void completedTransferMovesMoney() throws Exception {
        transfer(UUID.randomUUID().toString(), alice, bob, 2_500)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.transactionId").isNotEmpty());

        assertThat(accounts.get(alice).balanceCents()).isEqualTo(7_500);
        assertThat(accounts.get(bob).balanceCents()).isEqualTo(2_500);
    }

    @Test
    void insufficientFundsIsRejectedAndNothingMoves() throws Exception {
        transfer(UUID.randomUUID().toString(), alice, bob, 10_001)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));

        assertThat(accounts.get(alice).balanceCents()).isEqualTo(10_000);
        assertThat(accounts.get(bob).balanceCents()).isZero();
    }

    @Test
    void retryWithSameKeyReturnsSameAnswerWithoutMovingMoneyAgain() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = transfer(key, alice, bob, 1_000).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = transfer(key, alice, bob, 1_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(accounts.get(alice).balanceCents()).isEqualTo(9_000);
    }

    @Test
    void rejectionIsAlsoReplayed() throws Exception {
        String key = UUID.randomUUID().toString();
        transfer(key, alice, bob, 50_000).andExpect(status().isUnprocessableEntity());
        accounts.deposit(UUID.randomUUID().toString(), alice, 100_000);   // now there would be enough

        transfer(key, alice, bob, 50_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void sameKeyDifferentBodyIs422() throws Exception {
        String key = UUID.randomUUID().toString();
        transfer(key, alice, bob, 1_000).andExpect(status().isCreated());

        transfer(key, alice, bob, 2_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(accounts.get(alice).balanceCents()).isEqualTo(9_000);
    }

    @Test
    void sameAccountIs400() throws Exception {
        transfer(UUID.randomUUID().toString(), alice, alice, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SAME_ACCOUNT"));
    }

    @Test
    void zeroAndNegativeAmountsAre400() throws Exception {
        transfer(UUID.randomUUID().toString(), alice, bob, 0).andExpect(status().isBadRequest());
        transfer(UUID.randomUUID().toString(), alice, bob, -5).andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccountIs404() throws Exception {
        transfer(UUID.randomUUID().toString(), alice, UUID.randomUUID(), 100)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void systemAccountCannotBeUsedDirectly() throws Exception {
        transfer(UUID.randomUUID().toString(), SystemAccounts.EXTERNAL, bob, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SYSTEM_ACCOUNT"));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(alice, bob, 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_HEADER"));
    }

    private ResultActions transfer(String key, UUID from, UUID to, long amount) throws Exception {
        return mvc.perform(post("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(from, to, amount)));
    }

    private static String body(UUID from, UUID to, long amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountCents\":" + amount + "}";
    }
}
