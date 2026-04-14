package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.common.dto.GlobalExceptionHandler;
import com.jipi.ticket_ledger.payment.application.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PayMentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PayMentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("결제 승인 성공 시 200을 반환한다")
    void approvePaymentSuccess() throws Exception {
        mockMvc.perform(post("/payments/1/approve"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("결제 실패 처리 성공 시 200을 반환한다")
    void failPaymentSuccess() throws Exception {
        mockMvc.perform(post("/payments/1/fail"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("결제 취소 성공 시 200을 반환한다")
    void cancelPaymentSuccess() throws Exception {
        mockMvc.perform(post("/payments/1/cancel"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("잘못된 상태 전이는 409를 반환한다")
    void approvePaymentConflict() throws Exception {
        doThrow(new IllegalStateException("결제 대기 상태에서만 승인할 수 있습니다."))
                .when(paymentService).approvePayment(1L);

        mockMvc.perform(post("/payments/1/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }
}


