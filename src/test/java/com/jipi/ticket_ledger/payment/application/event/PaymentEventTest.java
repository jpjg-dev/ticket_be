package com.jipi.ticket_ledger.payment.application.event;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentEventTest {

    @Test
    @DisplayName("APPROVED 결제는 민감한 paymentKey 없이 PaymentApproved.v1 계약을 만든다")
    void approvedEventContract() {
        Payment payment = payment();
        payment.confirming(java.time.Instant.now());
        payment.approve("secret-payment-key", "CARD", "DONE", java.time.Instant.now());

        PaymentEvent event = PaymentEvent.approved(payment, PaymentEventSource.NORMAL);

        assertEquals("PaymentApproved", event.eventType());
        assertEquals(1, event.eventVersion());
        assertEquals(30L, event.paymentId());
        assertEquals("order-1", event.orderId());
        assertEquals(20L, event.reservationGroupId());
        assertEquals(10L, event.userId());
        assertEquals(11000, event.totalAmount());
        assertEquals("KRW", event.currency());
        assertEquals(PaymentEventSource.NORMAL, event.source());
    }

    @Test
    @DisplayName("CONFIRMING 결제에서는 승인 완료 이벤트를 만들 수 없다")
    void confirmingCannotCreateApprovedEvent() {
        Payment payment = payment();
        payment.confirming(java.time.Instant.now());

        assertThrows(
                IllegalStateException.class,
                () -> PaymentEvent.approved(payment, PaymentEventSource.NORMAL)
        );
    }

    @Test
    @DisplayName("CANCELED 결제는 PaymentCanceled.v1 계약을 만든다")
    void canceledEventContract() {
        Payment payment = payment();
        payment.confirming(java.time.Instant.now());
        payment.approve("secret-payment-key", "CARD", "DONE", java.time.Instant.now());
        payment.startCanceling(Instant.now());
        payment.cancel(Instant.now());

        PaymentEvent event = PaymentEvent.canceled(payment, PaymentEventSource.RECOVERY);

        assertEquals("PaymentCanceled", event.eventType());
        assertEquals(1, event.eventVersion());
        assertEquals(PaymentEventSource.RECOVERY, event.source());
    }

    private Payment payment() {
        User user = new User("outbox@test.com", "password", "Outbox User", Instant.now());
        ReflectionTestUtils.setField(user, "id", 10L);

        ReservationGroup group = new ReservationGroup(user, Instant.now(), Instant.now().plusSeconds(300));
        ReflectionTestUtils.setField(group, "id", 20L);

        Payment payment = new Payment(group, 10000, Instant.now(), "order-1", "KRW");
        ReflectionTestUtils.setField(payment, "id", 30L);
        return payment;
    }
}
