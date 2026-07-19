package com.jipi.ticket_ledger.admin.presentation;

import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminAccessControllerSecurityTest extends PostgresTestContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /admin: 인증되지 않은 요청은 401을 반환한다")
    void adminEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("GET /admin: ROLE_USER 요청은 403을 반환한다")
    void adminEndpoint_deniesUserRole() throws Exception {
        mockMvc.perform(get("/admin").with(user("user").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("GET /admin: ROLE_ADMIN 요청은 200과 관리자 접근 확인 응답을 반환한다")
    void adminEndpoint_allowsAdminRole() throws Exception {
        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminAccess").value(true));
    }
}
