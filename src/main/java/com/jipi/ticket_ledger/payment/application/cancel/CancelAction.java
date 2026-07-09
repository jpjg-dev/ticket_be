package com.jipi.ticket_ledger.payment.application.cancel;

/**
 * CANCELING 회색지대에서 순수 정책이 내린 결정 종류.
 * 닫힌 매트릭스이므로 enum + record 로 표현하고 전략 패턴을 쓰지 않는다.
 * REVERT(APPROVED 복귀)는 의도적으로 없다 — CANCELING 탈출구는 CANCELED(또는 수동)뿐이다.
 */
enum CancelAction {
    FINALIZE,     // PG 취소 확인(paymentKey/통화 일치) → 내부 상태 취소 확정
    CANCEL_AGAIN, // PG 가 아직 승인(DONE) 상태 → 취소가 아직 안 먹음, CANCELING 유지(Phase 3 스케줄러가 재시도)
    HOLD_MANUAL   // paymentKey/통화 불일치 등 의심 → 자동 조치하지 않고 보류(수동 확인 필요)
}
