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
    Optional<Payment> findByReservationGroupId(Long reservationGroupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.reservationGroup.id = :reservationGroupId")
    Optional<Payment> findByReservationGroupIdForUpdate(Long reservationGroupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(Long paymentId);

    List<Payment> findByReservationGroupUserIdAndStatusIn(Long userId, List<PaymentStatus> statusList, Sort sort);
}
