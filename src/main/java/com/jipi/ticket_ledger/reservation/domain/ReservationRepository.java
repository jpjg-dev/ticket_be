package com.jipi.ticket_ledger.reservation.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    //나의 예약 목록
    List<Reservation> findByUserId(Long userId);
    //나의 예약 확인 및 검증
    Optional<Reservation> findByIdAndUserId(Long reservationId, Long userId);
    //예약만료확인
    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime time);
}
