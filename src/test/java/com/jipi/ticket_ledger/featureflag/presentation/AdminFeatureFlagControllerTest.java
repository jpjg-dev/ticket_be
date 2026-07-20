package com.jipi.ticket_ledger.featureflag.presentation;

import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.featureflag.application.FeatureFlagService;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;
import com.jipi.ticket_ledger.global.security.CsrfOriginFilter;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminFeatureFlagController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminFeatureFlagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeatureFlagService featureFlagService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CsrfOriginFilter csrfOriginFilter;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("GET은 현재 queueMode 계약을 반환한다")
    void getFeatureFlagsReturnsCurrentQueueMode() throws Exception {
        when(featureFlagService.getCurrentQueueMode()).thenReturn(new QueueModeSnapshot(QueueMode.SHADOW, 3L));

        mockMvc.perform(get("/api/v1/admin/feature-flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueMode").value("SHADOW"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    @DisplayName("PUT은 mode를 받아 현재 queueMode 계약을 반환한다")
    void updateQueueModeReturnsCurrentQueueMode() throws Exception {
        when(featureFlagService.updateQueueMode(QueueMode.ENFORCED, 3L))
                .thenReturn(new QueueModeSnapshot(QueueMode.ENFORCED, 4L));

        mockMvc.perform(put("/api/v1/admin/feature-flags/queue-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ENFORCED\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueMode").value("ENFORCED"))
                .andExpect(jsonPath("$.version").value(4));
    }
}
