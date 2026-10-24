package com.alexxavier.ledger.metrics;

import com.alexxavier.ledger.AbstractIntegrationTest;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.account.AccountService;
import com.alexxavier.ledger.transfer.TransferRequest;
import com.alexxavier.ledger.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
class MetricsEndpointTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    @Autowired
    TransferService transfers;

    @Test
    void businessMetricsAreExportedForPrometheus() throws Exception {
        UUID a = accounts.create(new CreateAccountRequest("m-a", null)).id();
        UUID b = accounts.create(new CreateAccountRequest("m-b", null)).id();
        accounts.deposit(UUID.randomUUID().toString(), a, 1_000);
        String key = UUID.randomUUID().toString();
        transfers.transfer(key, new TransferRequest(a, b, 100L));
        transfers.transfer(key, new TransferRequest(a, b, 100L));                       // replay
        transfers.transfer(UUID.randomUUID().toString(), new TransferRequest(a, b, 99_999L)); // rejected

        String body = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("ledger_transfers_total{application=\"ledger-service\",outcome=\"completed\"}")
                .contains("ledger_transfers_total{application=\"ledger-service\",outcome=\"rejected\"}")
                .contains("ledger_transfers_total{application=\"ledger-service\",outcome=\"replayed\"}")
                .contains("ledger_transfer_duration_seconds_bucket")
                .contains("ledger_outbox_backlog")
                .contains("ledger_outbox_oldest_unpublished_seconds")
                .contains("ledger_outbox_published_total")
                .contains("ledger_reconciliation_mismatches");
    }
}
