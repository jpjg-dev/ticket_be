package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReservationExpirationService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationGroupRepository reservationGroupRepository;
    private final Clock clock;

    @Value("${reservation.expire-scheduler.batch-size}")
    private int batchSize;

    // 스케줄러 만료는 저빈도(1분 주기)·전역(모든 회차) 일괄 처리라 한 주기에 후보가 크게 쌓일 수 있으므로
    // batch-size 로 한 트랜잭션 작업량을 제한한다(긴 트랜잭션·락 보유 방지). 못 비운 잔여분은 다음 주기에 이어서 처리한다.
    public int expireAll() {
        return expireGroups(
                reservationGroupRepository.findExpiredPendingIds(clock.instant(), PageRequest.of(0, batchSize)),
                "SCHEDULER"
        );
    }

    // 수동 만료는 좌석 조회(/seats)마다 호출되는 고빈도 경로다. 만료되는 족족 비워져 후보가 얕게 유지되므로
    // 상한을 두지 않고 그 회차를 즉시 최신 상태로 만든다. (전역 백스톱은 사람이 안 볼 때를 위한 스케줄러가 담당)
    public int expireByScheduleId(Long scheduleId) {
        return expireGroups(
                reservationGroupRepository.findExpiredPendingIdsByScheduleId(scheduleId, clock.instant()),
                "SEAT_QUERY"
        );
    }

    private int expireGroups(List<Long> expiredGroupIds, String trigger) {
        int expiredCount = 0;
        int skippedCount = 0;
        for (Long reservationGroupId : expiredGroupIds) {
            Payment payment = paymentRepository.findByReservationGroupIdForUpdate(reservationGroupId).orElse(null);
            ReservationGroup reservationGroup = reservationGroupRepository.findByIdForUpdate(reservationGroupId).orElse(null);
            if (reservationGroup == null
                    || reservationGroup.getStatus() != ReservationGroupStatus.PENDING
                    || !reservationGroup.isExpiredAt(clock.instant())) {
                skippedCount++;
                continue;
            }

            if (payment != null && payment.getStatus() == PaymentStatus.CONFIRMING) {
                log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                        LogEvents.RESERVATION_EXPIRE_START, payment.getOrderId(), payment.getId(), reservationGroup.getId(), "SKIP_CONFIRMING_PAYMENT");
                skippedCount++;
                continue;
            }

            List<Reservation> pendingReservations = reservationRepository.findByReservationGroupIdWithSeat(reservationGroup.getId()).stream()
                    .filter(Reservation::isPending)
                    .toList();
            if (pendingReservations.isEmpty()) {
                skippedCount++;
                continue;
            }

            String orderId = payment != null ? payment.getOrderId() : "N/A";
            Long paymentId = payment != null ? payment.getId() : null;
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                    LogEvents.RESERVATION_EXPIRE_START, orderId, paymentId, reservationGroup.getId(), trigger);

            if (payment != null && payment.getStatus() == PaymentStatus.READY) {
                payment.fail();
                log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                        LogEvents.PAYMENT_EXPIRE_SUCCESS, orderId, paymentId, reservationGroup.getId(), "READY_TO_FAILED");
            }

            reservationGroup.expire();
            pendingReservations.forEach(reservation -> {
                reservation.expire();
                reservation.getSeat().release();
            });
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} expiredCount={} reason={}",
                    LogEvents.RESERVATION_EXPIRE_SUCCESS, orderId, paymentId, reservationGroup.getId(), pendingReservations.size(), trigger);
            expiredCount += pendingReservations.size();
        }
        if (!expiredGroupIds.isEmpty()) {
            log.info("Expire reservation batch finished. trigger={} candidateGroupCount={} expiredReservationCount={} skippedGroupCount={}",
                    trigger, expiredGroupIds.size(), expiredCount, skippedCount);
        }
        return expiredCount;
    }
}
