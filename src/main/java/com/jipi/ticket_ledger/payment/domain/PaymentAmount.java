package com.jipi.ticket_ledger.payment.domain;

public record PaymentAmount(
        Integer seatTotalAmount,
        Integer vatAmount,
        Integer totalAmount
) {
    private static final int VAT_RATE_PERCENT = 10;
    private static final int ROUNDING_HALF_UNIT = 50;
    private static final int PERCENT_DENOMINATOR = 100;

    public static PaymentAmount fromSeatTotalAmount(Integer seatTotalAmount) {
        if (seatTotalAmount == null) {
            throw new IllegalArgumentException("결제 금액은 필수입니다.");
        }
        if (seatTotalAmount < 0) {
            throw new IllegalArgumentException("결제 금액은 음수일 수 없습니다.");
        }

        int vatAmount = calculateVatAmount(seatTotalAmount);
        return new PaymentAmount(
                seatTotalAmount,
                vatAmount,
                Math.addExact(seatTotalAmount, vatAmount)
        );
    }

    private static int calculateVatAmount(int seatTotalAmount) {
        long vatNumerator = (long) seatTotalAmount * VAT_RATE_PERCENT + ROUNDING_HALF_UNIT;
        return Math.toIntExact(vatNumerator / PERCENT_DENOMINATOR);
    }
}
