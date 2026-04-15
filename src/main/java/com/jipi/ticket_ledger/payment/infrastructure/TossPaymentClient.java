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

    public TossConfirmResponse confirm(String paymentKey, String orderId, Integer amount) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId, amount);

        return restClient.post()
                .uri(baseUrl + "/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", createAuthorizationHeader())
                .body(request)
                .retrieve()
                .body(TossConfirmResponse.class);
    }

    private String createAuthorizationHeader() {
        String credentials = secretKey + ":";
        String encodedCredentials = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return "Basic " + encodedCredentials;
    }
}
