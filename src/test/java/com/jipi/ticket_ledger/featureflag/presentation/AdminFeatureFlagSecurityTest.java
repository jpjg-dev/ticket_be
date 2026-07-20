package com.jipi.ticket_ledger.featureflag.presentation;

import com.jipi.ticket_ledger.auth.infrastructure.JwtAuthenticationFilter;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.featureflag.application.FeatureFlagService;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;
import com.jipi.ticket_ledger.global.config.SecurityConfig;
import com.jipi.ticket_ledger.global.security.CsrfOriginFilter;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminFeatureFlagController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CsrfOriginFilter.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "security.csrf-origin.allowed-origins[0]=http://localhost:3000")
class AdminFeatureFlagSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeatureFlagService featureFlagService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("관리자 feature flag API는 인증되지 않은 요청을 401로 차단한다")
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/feature-flags"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("관리자 feature flag API는 USER 권한 요청을 403으로 차단한다")
    void userRequestIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/feature-flags"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자 feature flag API는 기존 /admin 권한 규칙으로 접근을 허용한다")
    void adminRequestIsAllowed() throws Exception {
        when(featureFlagService.getCurrentQueueMode()).thenReturn(new QueueModeSnapshot(QueueMode.OFF, 0L));

        mockMvc.perform(get("/api/v1/admin/feature-flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueMode").exists())
                .andExpect(jsonPath("$.version").isNumber());
    }
}
