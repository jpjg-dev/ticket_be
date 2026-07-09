package com.jipi.ticket_ledger.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByReservationGroupId(Long reservationGroupId);

    Optional<Payment> findByOrderId(String orderId);

    long countByStatus(PaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.reservationGroup.id = :reservationGroupId")
    Optional<Payment> findByReservationGroupIdForUpdate(Long reservationGroupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(Long paymentId);

    @Query("""
            select p.id
            from Payment p
            where p.status = com.jipi.ticket_ledger.payment.domain.PaymentStatus.CONFIRMING
              and p.confirmingAt <= :threshold
            order by p.id asc
            """)
    List<Long> findStaleConfirmingIds(@Param("threshold") Instant threshold, Pageable pageable);

    @Query("""
            select p
            from Payment p
            join fetch p.reservationGroup rg
            where rg.user.id = :userId
              and p.status in :statusList
            """)
    List<Payment> findByReservationGroupUserIdAndStatusIn(
            @Param("userId") Long userId,
            @Param("statusList") List<PaymentStatus> statusList,
            Sort sort
    );
}
