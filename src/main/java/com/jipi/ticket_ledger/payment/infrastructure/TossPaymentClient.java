package com.jipi.ticket_ledger.payment.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Component
public class TossPaymentClient {
    private final RestClient restClient;
    private final String secretKey;

    public TossPaymentClient(
            RestClient.Builder restClientBuilder,
            @Value("${toss.payments.base-url}") String baseUrl,
            @Value("${toss.payments.secret-key}") String secretKey,
            @Value("${toss.payments.connect-timeout}") Duration connectTimeout,
            @Value("${toss.payments.read-timeout}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.secretKey = secretKey;
    }

    public TossConfirmResponse confirm(String paymentKey, String orderId, Integer amount, String idempotencyKey) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId, amount);

        return restClient.post()
                .uri("/v1/payments/confirm")
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
                .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", createAuthorizationHeader())
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .body(TossCancelResponse.class);
    }

    public TossPaymentLookupResponse getPaymentByPaymentKey(String paymentKey) {
        return restClient.get()
                .uri("/v1/payments/{paymentKey}", paymentKey)
                .header("Authorization", createAuthorizationHeader())
                .retrieve()
                .body(TossPaymentLookupResponse.class);
    }

    public TossPaymentLookupResponse getPaymentByOrderId(String orderId) {
        return restClient.get()
                .uri("/v1/payments/orders/{orderId}", orderId)
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
