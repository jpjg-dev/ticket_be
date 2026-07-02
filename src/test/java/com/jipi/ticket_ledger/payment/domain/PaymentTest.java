package com.jipi.ticket_ledger.payment.domain;

import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @ParameterizedTest
    @CsvSource({
            "0, 0, 0",
            "1, 0, 1",
            "4, 0, 4",
            "5, 1, 6",
            "9, 1, 10",
            "10, 1, 11",
            "11, 1, 12",
            "10001, 1000, 11001"
    })
    @DisplayName("PaymentAmount: 원 단위 정수 계산으로 VAT와 총액을 계산한다")
    void paymentAmount(Integer seatTotalAmount, Integer expectedVatAmount, Integer expectedTotalAmount) {
        PaymentAmount amount = PaymentAmount.fromSeatTotalAmount(seatTotalAmount);

        assertEquals(seatTotalAmount, amount.seatTotalAmount());
        assertEquals(expectedVatAmount, amount.vatAmount());
        assertEquals(expectedTotalAmount, amount.totalAmount());
    }

    @Test
    @DisplayName("PaymentAmount: 음수 금액은 허용하지 않는다")
    void paymentAmountRejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> PaymentAmount.fromSeatTotalAmount(-1));
    }
}
