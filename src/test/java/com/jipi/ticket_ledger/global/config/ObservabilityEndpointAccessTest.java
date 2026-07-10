package com.jipi.ticket_ledger.global.config;

import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,prometheus")
@AutoConfigureMockMvc
@AutoConfigureObservability
class ObservabilityEndpointAccessTest extends PostgresTestContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Prometheus는 인증 없이 내부 수집용 메트릭을 조회할 수 있다")
    void prometheusEndpoint_isAvailableForInternalScraping() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_info")))
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")));
    }
}
