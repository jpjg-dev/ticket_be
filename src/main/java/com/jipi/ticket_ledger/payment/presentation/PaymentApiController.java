package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.application.PaymentService;
import com.jipi.ticket_ledger.payment.application.model.PaymentStatusResult;
import com.jipi.ticket_ledger.payment.application.model.ReadyPaymentResult;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayException;
import com.jipi.ticket_ledger.payment.application.recovery.PaymentRecoveryService;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.presentation.dto.CancelPaymentResponse;
import com.jipi.ticket_ledger.payment.presentation.dto.ConfirmPaymentRequest;
import com.jipi.ticket_ledger.payment.presentation.dto.ConfirmPaymentResponse;
import com.jipi.ticket_ledger.payment.presentation.dto.ReadyPaymentRequest;
import com.jipi.ticket_ledger.payment.presentation.dto.ReadyPaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payment API", description = "결제 관련 API")
@Slf4j
public class PaymentApiController {
    private final PaymentService paymentService;
    private final PaymentRecoveryService paymentRecoveryService;

    @Operation(summary = "결제 준비", description = "예약 식별자로 결제 요청 정보를 생성합니다.")
    @PostMapping("/ready")
    public ReadyPaymentResponse readyPayment(@RequestBody @Valid ReadyPaymentRequest request,
                                             @AuthenticationPrincipal Long userId) {
        ReadyPaymentResult result = paymentService.readyPaymentResult(request.reservationGroupId(), userId);
        return ReadyPaymentResponse.from(result);
    }

    @Operation(summary = "결제 승인 확인", description = "토스 결제 성공 후 paymentKey, orderId, amount로 결제를 승인합니다.")
    @PostMapping("/confirm")
    public ConfirmPaymentResponse confirmPayment(@RequestBody @Valid ConfirmPaymentRequest request) {
        try {
            PaymentStatusResult result = paymentService.confirmPaymentResult(
                    request.paymentKey(),
                    request.orderId(),
                    request.amount()
            );
            return ConfirmPaymentResponse.from(result);
        } catch (PaymentGatewayException | IllegalStateException e) {
            // confirm 도중 회색지대(통신 끊김/검증 실패 등)로 결제가 CONFIRMING에 남았을 수 있으므로 동기 재조회로 최종 확인을 시도한다.
            PaymentRecoveryService.SyncReconcileResult result =
                    paymentRecoveryService.reconcileConfirmingPaymentByOrderId(request.orderId());
            if (!result.handled()) {
                // CONFIRMING 회색지대가 아니라 confirm 진입 전 정상 비즈니스 거절(만료/금액 등) → 원래 예외를 그대로 전파한다.
                throw e;
            }
            // 동기 재조회로 해소됐으면 그 상태를, 아직 PG가 응답 못 하면 CONFIRMING을 반환하고 보정 스케줄러에 위임한다.
            return ConfirmPaymentResponse.from(paymentService.paymentStatusResult(result.paymentId()));
        }
    }

    @Operation(summary = "결제 상태 조회", description = "결제 식별자로 현재 결제/예약/좌석 상태를 조회합니다.")
    @GetMapping("/{paymentId}/status")
    public ConfirmPaymentResponse getPaymentStatus(@PathVariable Long paymentId,
                                                   @AuthenticationPrincipal Long userId) {
        return ConfirmPaymentResponse.from(paymentService.getPaymentStatusResult(paymentId, userId));
    }

    @Operation(summary = "결제 실패 리다이렉트 기록", description = "failUrl로 전달된 code, message, orderId를 백엔드 로그에 기록합니다.")
    @PostMapping("/fail-redirect")
    public void recordFailRedirect(@RequestBody Map<String, String> request) {
        String orderId = request.get("orderId");
        String code = request.get("code");
        String message = request.get("message");

        log.info("event={} orderId={} reason={} message={}",
                LogEvents.PAYMENT_FAIL_REDIRECT_RECEIVED,
                (orderId != null && !orderId.isBlank()) ? orderId : "N/A",
                (code != null && !code.isBlank()) ? code : "UNKNOWN_FAIL_CODE",
                (message != null && !message.isBlank()) ? message : "N/A");
    }

    @Operation(summary = "결제 취소", description = "결제 식별자로 결제를 취소합니다. 확정 시 CANCELED, PG 미확정 시 CANCELING 을 반환합니다.")
    @PostMapping("/{paymentId}/cancel")
    public CancelPaymentResponse cancelPayment(@PathVariable Long paymentId,
                                               @RequestBody String cancelReason,
                                               @AuthenticationPrincipal Long userId) {
        PaymentStatus paymentStatus = paymentService.cancelPayment(paymentId, cancelReason, userId);
        return new CancelPaymentResponse(paymentId, paymentStatus);
    }

}
