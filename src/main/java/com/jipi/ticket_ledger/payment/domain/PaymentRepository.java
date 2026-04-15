package com.jipi.ticket_ledger.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    //특정 예약에 결제조회 중복결제 확인
    Optional<Payment> findByReservationId(Long reservationId);

    //토스 결제 승인 요청 시 orderId로 결제 조회
    Optional<Payment> findByOrderId(String orderId);
}
