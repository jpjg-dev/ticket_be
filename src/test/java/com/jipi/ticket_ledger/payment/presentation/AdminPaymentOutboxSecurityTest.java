package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.auth.infrastructure.JwtAuthenticationFilter;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.global.config.SecurityConfig;
import com.jipi.ticket_ledger.global.security.CsrfOriginFilter;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxAdminService;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRequeueResult;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@WebMvcTest(controllers = AdminPaymentOutboxController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CsrfOriginFilter.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "security.csrf-origin.allowed-origins[0]=http://localhost:3000")
class AdminPaymentOutboxSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentOutboxAdminService paymentOutboxAdminService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(post(path(UUID.randomUUID()))
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"payload fixed\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userRequestIsForbidden() throws Exception {
        mockMvc.perform(post(path(UUID.randomUUID()))
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"payload fixed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanRequeueHoldManualEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(paymentOutboxAdminService.requeue(eventId, 1L, "payload fixed"))
                .thenReturn(new PaymentOutboxRequeueResult(eventId, 10L, "PENDING"));

        mockMvc.perform(post(path(eventId))
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                1L,
                                null,
                                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        )))
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"payload fixed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private String path(UUID eventId) {
        return "/api/v1/admin/payment-outbox/events/" + eventId + "/requeue";
    }
}
