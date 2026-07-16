package com.jipi.ticket_ledger.payment.application.recovery;

/**
 * 보정 1회의 최종 결과. metric 라벨 겸 반환값으로 쓰인다.
 * recovered=true 인 것만 배치 recoveredCount 에 집계한다(터미널 상태로 수렴).
 */
public enum RecoveryOutcome {
    APPROVED(true),            // 승인으로 수렴
    FAILED_RELEASED(true),     // 실패 처리 + 좌석 해제로 수렴
    REFUNDED_FAILED(true),     // 환불 후 실패 처리로 수렴
    PG_PROCESSING(false),      // PG 처리 중 → CONFIRMING 유지, 다음 주기 재조회
    HELD_MANUAL(false),        // orderId 불일치로 보류(자동 조치 안 함)
    REFUND_PENDING(false),     // 환불 호출 실패 → CONFIRMING 유지, 다음 주기 재시도
    SEAT_LOST_DEFERRED(false), // 승인하려 했으나 apply 시점 좌석 유실 → CONFIRMING 유지, 다음 주기 재결정
    NOOP_NOT_CONFIRMING(false),// 락 후 CONFIRMING 아님 → 무처리
    LOOKUP_UNRESOLVED(false);  // PG 조회 실패 → CONFIRMING 유지, 다음 주기 재시도

    private final boolean recovered;

    RecoveryOutcome(boolean recovered) {
        this.recovered = recovered;
    }

    public boolean isRecovered() {
        return recovered;
    }
}
