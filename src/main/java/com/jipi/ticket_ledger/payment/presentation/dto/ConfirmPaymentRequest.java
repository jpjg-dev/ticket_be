package com.jipi.ticket_ledger.payment.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmPaymentRequest(@NotBlank(message = "Payment key must not be blank")
                                    String paymentKey,

                                    @NotBlank(message = "Order ID must not be blank")
                                    String orderId,

                                    @NotNull(message = "Amount must not be null")
                                    Integer amount) {
}
