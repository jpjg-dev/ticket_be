package com.jipi.ticket_ledger.payment.application.cancel;

/**
 * write 트랜잭션(markCanceling) 안에서 CANCELING 결제로부터 뽑아낸 원시값 스냅샷.
 * 엔티티/컬렉션을 트랜잭션 밖(외부 PG 호출 구간)으로 흘리지 않기 위해 결정에 필요한 값만 primitive 로 materialize 한다.
 * alreadyCanceled=true 는 이미 CANCELED 인 멱등 종료 신호로, 이 경우 외부 호출 없이 즉시 반환한다.
 */
record CancelingPaymentSnapshot(
        Long paymentId,
        String orderId,
        Long reservationGroupId,
        String paymentKey,
        Integer totalAmount,
        String currency,
        Long ownerUserId,
        boolean alreadyCanceled
) {
    static CancelingPaymentSnapshot alreadyCanceled(Long paymentId, String orderId, Long reservationGroupId) {
        return new CancelingPaymentSnapshot(paymentId, orderId, reservationGroupId, null, null, null, null, true);
    }
}
