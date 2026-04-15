package com.jipi.ticket_ledger.payment.presentation;

import com.jipi.ticket_ledger.payment.application.PaymentService;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.presentation.dto.ConfirmPaymentRequest;
import com.jipi.ticket_ledger.payment.presentation.dto.ConfirmPaymentResponse;
import com.jipi.ticket_ledger.payment.presentation.dto.ReadyPaymentRequest;
import com.jipi.ticket_ledger.payment.presentation.dto.ReadyPaymentResponse;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.seat.domain.Seat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payment API", description = "결제 관련 API")
public class PayMentController {
    private final PaymentService paymentService;

    @Operation (summary = "결제 준비", description = "예약 식별자로 결제 요청 정보를 생성합니다.")
    @PostMapping("/ready")
    public ReadyPaymentResponse readyPayment(@RequestBody @Valid ReadyPaymentRequest request) {
        Payment payment = paymentService.readyPayment(request.reservationId());
        Reservation reservation = payment.getReservation();

        String orderName = reservation.getSeat().getSchedule().getEvent().getTitle()
                + " "
                + reservation.getSeat().getSeatNumber();

        return new ReadyPaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                orderName
        );
    }
    @Operation(summary = "결제 승인 확인", description = "토스 결제 성공 후 paymentKey, orderId, amount로 결제를 승인합니다.")
    @PostMapping("/confirm")
    public ConfirmPaymentResponse confirmPayment(@RequestBody @Valid ConfirmPaymentRequest request) {
        Payment payment = paymentService.confirmPayment(
                request.paymentKey(),
                request.orderId(),
                request.amount()
        );

        Reservation reservation = payment.getReservation();
        Seat seat = reservation.getSeat();

        return new ConfirmPaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getStatus(),
                reservation.getStatus(),
                seat.getStatus()
        );
    }

    @Operation(summary = "결제 승인", description = "결제 식별자로 결제를 승인합니다.")
    @PostMapping("/{paymentId}/approve")
    public void approvePayment(@PathVariable Long paymentId) {
        paymentService.approvePayment(paymentId);
    }

    @Operation(summary = "결제 실패", description = "결제 식별자로 결제를 실패 처리합니다.")
    @PostMapping("/{paymentId}/fail")
    public void failPayment(@PathVariable Long paymentId) {
        paymentService.failPayment(paymentId);
    }

    @Operation(summary = "결제 취소", description = "결제 식별자로 결제를 취소합니다.")
    @PostMapping("/{paymentId}/cancel")
    public void cancelPayment(@PathVariable Long paymentId) {
        paymentService.cancelPayment(paymentId);
    }

}
