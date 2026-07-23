package com.jipi.ticket_ledger.payment.application.cancel;

/**
 * CANCELING 회색지대 보정 1회의 최종 결과. metric 라벨 겸 반환값으로 쓰인다.
 * confirm 쪽 {@code RecoveryOutcome} 과 대칭이며, recovered=true 인 것만 배치 recoveredCount 에 집계한다.
 */
public enum CancelOutcome {
    CANCELED(true),           // PG 취소 확인 → 취소 확정으로 수렴
    KEEP_CANCELING(false),    // PG 가 아직 승인(DONE) → 취소 미확정, CANCELING 유지(다음 주기 재시도)
    HELD_MANUAL(false),       // paymentKey/통화 불일치 등 의심 → 자동 조치 안 함, CANCELING 유지
    LOOKUP_UNRESOLVED(false), // PG 조회 실패로 상태 미확정(timeout 등) → CANCELING 유지, 다음 주기 재시도
    CANCEL_UNRESOLVED(false), // PG 취소 호출 실패 + 폴백 조회도 미확정 → CANCELING 유지, 다음 주기 재시도
    NOOP_NOT_CANCELING(false);// 로드 시점에 CANCELING 아님 → 무처리(이미 해소)

    private final boolean recovered;

    CancelOutcome(boolean recovered) {
        this.recovered = recovered;
    }

    public boolean isRecovered() {
        return recovered;
    }
}
