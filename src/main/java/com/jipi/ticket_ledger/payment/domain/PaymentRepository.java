package com.jipi.ticket_ledger.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    //특정 예약에 결제조회 중복결제 확인
    Optional<Payment> findByReservationId(Long reservationId);

    Optional<Payment> findByReservationGroupId(Long reservationGroupId);

    //토스 결제 승인 요청 시 orderId로 결제 조회
    Optional<Payment> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.reservation.id = :reservationId")
    Optional<Payment> findByReservationIdForUpdate(Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.reservationGroup.id = :reservationGroupId")
    Optional<Payment> findByReservationGroupIdForUpdate(Long reservationGroupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(Long paymentId);
    // 예약자 기준 상태별 결제 조회
    List<Payment> findByReservationUserIdAndStatusIn(Long userId, List<PaymentStatus> statusList, Sort sort);

    List<Payment> findByReservationGroupUserIdAndStatusIn(Long userId, List<PaymentStatus> statusList, Sort sort);
}
