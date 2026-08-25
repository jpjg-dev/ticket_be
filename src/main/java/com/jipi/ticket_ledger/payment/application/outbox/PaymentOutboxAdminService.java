package com.jipi.ticket_ledger.payment.application.outbox;

import com.jipi.ticket_ledger.payment.application.observability.PaymentOutboxMetrics;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxAdminStore;
import com.jipi.ticket_ledger.global.exception.ForbiddenAccessException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOutboxAdminService {

    private final PaymentOutboxAdminStore paymentOutboxAdminStore;
    private final PaymentOutboxMetrics paymentOutboxMetrics;
    private final Clock clock;

    public PaymentOutboxRequeueResult requeue(UUID eventId, Long adminUserId, String reason) {
        if (adminUserId == null) {
            throw new ForbiddenAccessException("관리자 식별자가 필요합니다.");
        }
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("재처리 사유는 1자 이상 200자 이하여야 합니다.");
        }
        Long paymentId = paymentOutboxAdminStore.requeueHoldManual(
                        eventId,
                        adminUserId,
                        reason,
                        clock.instant()
                )
                .orElseGet(() -> rejectRequeue(eventId));
        paymentOutboxMetrics.recordRequeue("success");
        log.info("Payment Outbox event requeued by admin. eventId={} paymentId={} adminUserId={}",
                eventId, paymentId, adminUserId);
        return new PaymentOutboxRequeueResult(eventId, paymentId, "PENDING");
    }

    private Long rejectRequeue(UUID eventId) {
        String status = paymentOutboxAdminStore.findStatus(eventId).orElse(null);
        if (status == null) {
            paymentOutboxMetrics.recordRequeue("not_found");
            throw new EntityNotFoundException("Outbox 이벤트를 찾을 수 없습니다.");
        }
        paymentOutboxMetrics.recordRequeue("rejected");
        throw new IllegalStateException("HOLD_MANUAL 이벤트만 재처리할 수 있습니다. status=" + status);
    }
}
