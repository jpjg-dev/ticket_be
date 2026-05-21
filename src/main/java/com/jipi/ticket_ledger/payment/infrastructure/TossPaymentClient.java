package com.jipi.ticket_ledger.payment.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class TossPaymentClient {
    private final RestClient restClient = RestClient.create();

    @Value("${toss.payments.base-url}")
    private String baseUrl;

    @Value("${toss.payments.secret-key}")
    private String secretKey;

    public TossConfirmResponse confirm(String paymentKey, String orderId, Integer amount, String idempotencyKey) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId, amount);

        return restClient.post()
                .uri(baseUrl + "/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", createAuthorizationHeader())
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .body(TossConfirmResponse.class);
    }

    public TossCancelResponse cancel(String paymentKey, String cancelReason, String currency, String idempotencyKey) {
        TossCancelRequest request = new TossCancelRequest(cancelReason, currency);

        return restClient.post()
                .uri(baseUrl + "/v1/payments/{paymentKey}/cancel", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", createAuthorizationHeader())
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .body(TossCancelResponse.class);
    }

    public TossPaymentLookupResponse getPaymentByPaymentKey(String paymentKey) {
        return restClient.get()
                .uri(baseUrl + "/v1/payments/{paymentKey}", paymentKey)
                .header("Authorization", createAuthorizationHeader())
                .retrieve()
                .body(TossPaymentLookupResponse.class);
    }

    public TossPaymentLookupResponse getPaymentByOrderId(String orderId) {
        return restClient.get()
                .uri(baseUrl + "/v1/payments/orders/{orderId}", orderId)
                .header("Authorization", createAuthorizationHeader())
                .retrieve()
                .body(TossPaymentLookupResponse.class);
    }

    private String createAuthorizationHeader() {
        String credentials = secretKey + ":";
        String encodedCredentials = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return "Basic " + encodedCredentials;
    }
}
