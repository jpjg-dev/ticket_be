package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationExpirationService {

    private final ReservationGroupRepository reservationGroupRepository;
    private final ReservationExpirationTransactionService transactionService;
    private final ReservationExpirationMetrics metrics;
    private final Clock clock;

    @Value("${reservation.expire-scheduler.batch-size}")
    private int batchSize;

    // 스케줄러 후보 조회에는 batch-size를 적용하고, 실제 만료는 그룹마다 독립 트랜잭션으로 처리한다.
    // 한 건이 실패해도 나머지 후보는 계속 처리하며, 실패 건은 다음 주기에 다시 조회된다.
    public int expireAll() {
        return expireGroups(
                reservationGroupRepository.findExpiredPendingIds(clock.instant(), PageRequest.of(0, batchSize)),
                "SCHEDULER",
                true
        );
    }

    // 수동 만료는 좌석 조회(/seats)마다 호출되는 고빈도 경로다. 만료되는 족족 비워져 후보가 얕게 유지되므로
    // 상한을 두지 않고 그 회차를 즉시 최신 상태로 만든다. (전역 백스톱은 사람이 안 볼 때를 위한 스케줄러가 담당)
    public int expireByScheduleId(Long scheduleId) {
        return expireGroups(
                reservationGroupRepository.findExpiredPendingIdsByScheduleId(scheduleId, clock.instant()),
                "SEAT_QUERY",
                false
        );
    }

    private int expireGroups(List<Long> expiredGroupIds, String trigger, boolean continueOnFailure) {
        int expiredCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (Long reservationGroupId : expiredGroupIds) {
            try {
                int groupExpiredCount = transactionService.expireGroup(reservationGroupId, trigger);
                if (groupExpiredCount > 0) {
                    expiredCount += groupExpiredCount;
                    metrics.record(trigger, "expired");
                } else {
                    skippedCount++;
                    metrics.record(trigger, "skipped");
                }
            } catch (RuntimeException exception) {
                failedCount++;
                metrics.record(trigger, "failed");
                log.error("Expire reservation group failed. trigger={} reservationGroupId={} reason={}",
                        trigger, reservationGroupId, exception.getMessage(), exception);
                if (!continueOnFailure) {
                    throw exception;
                }
            }
        }

        if (!expiredGroupIds.isEmpty()) {
            log.info("Expire reservation batch finished. trigger={} candidateGroupCount={} expiredReservationCount={} skippedGroupCount={} failedGroupCount={}",
                    trigger, expiredGroupIds.size(), expiredCount, skippedCount, failedCount);
        }
        return expiredCount;
    }
}
