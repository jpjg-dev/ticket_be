package com.jipi.ticket_ledger.payment.application.recovery;

/**
 * readonly 트랜잭션 안에서 CONFIRMING 결제로부터 뽑아낸 원시값 스냅샷.
 * 엔티티/컬렉션을 트랜잭션 밖으로 흘리지 않기 위해(LazyInitializationException 방지)
 * 결정에 필요한 값만 primitive 로 materialize 한다.
 */
record RecoverySnapshot(
        Long paymentId,
        String orderId,
        Integer expectedAmount,
        String currency,
        boolean reservationHeld
) {
}
