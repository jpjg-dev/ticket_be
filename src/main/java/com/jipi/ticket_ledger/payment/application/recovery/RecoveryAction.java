package com.jipi.ticket_ledger.payment.application.recovery;

/**
 * CONFIRMING 회색지대 보정에서 순수 정책이 내린 결정 종류.
 * 닫힌 매트릭스이므로 enum + record 로 표현하고 전략 패턴을 쓰지 않는다.
 */
enum RecoveryAction {
    APPROVE,          // PG 승인 + 좌석 유효 → 결제 승인
    FAIL,             // PG 최종 실패/취소 → 실패 처리 후 좌석 해제
    REFUND_THEN_FAIL, // PG 승인이지만 좌석 유실/데이터 불일치 → 환불 후 실패 처리
    RETRY_LATER,      // PG 처리 중 → CONFIRMING 유지 후 다음 주기 재조회
    HOLD_MANUAL       // orderId 불일치/알 수 없는 PG 상태 → 자동 조치하지 않고 수동 확인
}
