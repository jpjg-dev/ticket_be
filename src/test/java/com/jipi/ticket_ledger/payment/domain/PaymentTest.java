package com.jipi.ticket_ledger.payment.domain;

import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentTest {

    @Test
    @DisplayName("totalAmountWithVat: 결제 공급가에 VAT 10%를 더한 총 결제 금액을 반환한다")
    void totalAmountWithVat() {
        User user = new User("user@test.com", "password", "테스터", LocalDateTime.now());
        ReservationGroup group = new ReservationGroup(
                user,
                LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(5)
        );
        Payment payment = new Payment(group, 10000, LocalDateTime.now(), "order-payment-total", "KRW");

        assertEquals(11000, payment.totalAmountWithVat());
    }
}
