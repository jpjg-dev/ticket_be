package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.global.log.PaymentLogFormatter;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGateway;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayRejectedException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayTemporarilyUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Supplier;

@Component
@Slf4j
public class TossPaymentClient implements PaymentGateway {
    private final RestClient restClient;
    private final String secretKey;
    private final CircuitBreaker lookupCircuitBreaker;
    private final CircuitBreaker cancelCircuitBreaker;

    @Autowired
    public TossPaymentClient(
            RestClient.Builder restClientBuilder,
            @Value("${toss.payments.base-url}") String baseUrl,
            @Value("${toss.payments.secret-key}") String secretKey,
            @Value("${toss.payments.connect-timeout}") Duration connectTimeout,
            @Value("${toss.payments.read-timeout}") Duration readTimeout,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        RestClient built = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.restClient = built;
        this.secretKey = secretKey;
        this.lookupCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.LOOKUP);
        this.cancelCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.CANCEL);
    }

    // 테스트에서 MockRestServiceServer 로 바인딩한 RestClient 를 직접 주입하기 위한 생성자.
    TossPaymentClient(RestClient restClient, String secretKey) {
        this(restClient, secretKey, CircuitBreakerRegistry.ofDefaults());
    }

    TossPaymentClient(RestClient restClient, String secretKey, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.restClient = restClient;
        this.secretKey = secretKey;
        this.lookupCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.LOOKUP);
        this.cancelCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.CANCEL);
    }

    @Override
    public TossConfirmResponse confirm(String paymentKey, String orderId, Integer amount, String idempotencyKey) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId, amount);

        return call(TossOperation.CONFIRM, orderId, paymentKey, idempotencyKey, () ->
                restClient.post()
                        .uri("/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", createAuthorizationHeader())
                        .header("Idempotency-Key", idempotencyKey)
                        .body(request)
                        .retrieve()
                        .body(TossConfirmResponse.class));
    }

    @Override
    public TossCancelResponse cancel(String paymentKey, String cancelReason, String currency, String idempotencyKey) {
        TossCancelRequest request = new TossCancelRequest(cancelReason, currency);

        return execute(cancelCircuitBreaker, () -> call(TossOperation.CANCEL, null, paymentKey, idempotencyKey, () ->
                restClient.post()
                        .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", createAuthorizationHeader())
                        .header("Idempotency-Key", idempotencyKey)
                        .body(request)
                        .retrieve()
                        .body(TossCancelResponse.class)));
    }

    @Override
    public TossPaymentLookupResponse getPaymentByPaymentKey(String paymentKey) {
        return execute(lookupCircuitBreaker, () -> call(TossOperation.LOOKUP_BY_PAYMENT_KEY, null, paymentKey, null, () ->
                restClient.get()
                        .uri("/v1/payments/{paymentKey}", paymentKey)
                        .header("Authorization", createAuthorizationHeader())
                        .retrieve()
                        .body(TossPaymentLookupResponse.class)));
    }

    @Override
    public TossPaymentLookupResponse getPaymentByOrderId(String orderId) {
        return execute(lookupCircuitBreaker, () -> call(TossOperation.LOOKUP_BY_ORDER_ID, orderId, null, null, () ->
                restClient.get()
                        .uri("/v1/payments/orders/{orderId}", orderId)
                        .header("Authorization", createAuthorizationHeader())
                        .retrieve()
                        .body(TossPaymentLookupResponse.class)));
    }

    private <T> T execute(CircuitBreaker circuitBreaker, Supplier<T> action) {
        try {
            return circuitBreaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            long waitMillis = circuitBreaker.getCircuitBreakerConfig()
                    .getWaitIntervalFunctionInOpenState()
                    .apply(1);
            long retryAfterSeconds = Math.max(1, Duration.ofMillis(waitMillis).toSeconds());
            throw new PaymentGatewayTemporarilyUnavailableException(
                    "PG 요청을 일시적으로 처리할 수 없습니다.", retryAfterSeconds, e);
        }
    }

    // 모든 Toss 외부호출을 감싸 실패 시 같은 형식으로 로깅하고 원래 예외를 그대로 재전파한다.
    // 로그는 호출 단위(여기)에서만 남기고, 서비스 계층은 같은 예외를 다시 덤프하지 않는다.
    private <T> T call(TossOperation operation, String orderId, String paymentKey, String idempotencyKey, Supplier<T> action) {
        try {
            return action.get();
        } catch (ResourceAccessException timeout) {
            // 연결/응답 타임아웃 등 I/O 실패 = 성공/실패 미확정(결과 불명) → CONFIRMING 보정 대상.
            logFailure(operation, orderId, paymentKey, idempotencyKey, "TIMEOUT", "N/A", timeout, false);
            throw new PaymentGatewayException("PG 호출 결과를 확인할 수 없습니다.", timeout);
        } catch (RestClientResponseException httpError) {
            // PG 가 상태코드를 돌려준 실패(4xx/5xx).
            logFailure(operation, orderId, paymentKey, idempotencyKey, "HTTP_ERROR",
                    String.valueOf(httpError.getStatusCode().value()), httpError, false);
            int status = httpError.getStatusCode().value();
            if (status < 500 && status != 408 && status != 429) {
                throw new PaymentGatewayRejectedException("PG가 요청을 거절했습니다.", httpError);
            }
            throw new PaymentGatewayException("PG 호출에 실패했습니다.", httpError);
        } catch (RestClientException other) {
            // 분류하지 못한 통신 예외.
            logFailure(operation, orderId, paymentKey, idempotencyKey, "OTHER", "N/A", other, true);
            throw new PaymentGatewayException("PG 호출에 실패했습니다.", other);
        }
    }

    private void logFailure(TossOperation operation, String orderId, String paymentKey, String idempotencyKey,
                            String outcome, String httpStatus, RestClientException exception, boolean withStack) {
        String format = "event={} operation={} orderId={} paymentKeyMasked={} idempotencyKey={} outcome={} httpStatus={} exceptionClass={} message={}";
        Object[] args = {
                LogEvents.TOSS_CALL_FAIL,
                operation,
                orderId == null ? "N/A" : orderId,
                PaymentLogFormatter.maskPaymentKey(paymentKey),
                idempotencyKey == null ? "N/A" : idempotencyKey,
                outcome,
                httpStatus,
                exception.getClass().getSimpleName(),
                exception.getMessage()
        };
        if (withStack) {
            // 분류 못 한 예외(OTHER)만 스택까지 남겨 원인 추적을 돕는다.
            Object[] withException = Arrays.copyOf(args, args.length + 1);
            withException[args.length] = exception;
            log.error(format, withException);
        } else if ("TIMEOUT".equals(outcome)) {
            log.warn(format, args);
        } else {
            log.error(format, args);
        }
    }

    private String createAuthorizationHeader() {
        String credentials = secretKey + ":";
        String encodedCredentials = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return "Basic " + encodedCredentials;
    }
}
