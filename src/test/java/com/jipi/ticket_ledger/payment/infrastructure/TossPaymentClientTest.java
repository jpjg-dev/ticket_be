package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayRejectedException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayTemporarilyUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class TossPaymentClientTest {

    private static final String SECRET_KEY = "super-secret-toss-key";

    private TossPaymentClient clientBoundTo(MockRestServiceServer[] serverHolder) {
        return clientBoundTo(serverHolder, CircuitBreakerRegistry.ofDefaults());
    }

    private TossPaymentClient clientBoundTo(MockRestServiceServer[] serverHolder,
                                             CircuitBreakerRegistry circuitBreakerRegistry) {
        RestClient.Builder builder = RestClient.builder();
        serverHolder[0] = MockRestServiceServer.bindTo(builder).build();
        return new TossPaymentClient(builder.build(), SECRET_KEY, circuitBreakerRegistry);
    }

    @Test
    @DisplayName("confirm 실패 시 표준 형식으로 로깅하고(operation/outcome/마스킹) 원래 예외를 재전파한다")
    void confirmFailureLogsStandardFields(CapturedOutput output) {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        TossPaymentClient client = clientBoundTo(serverHolder);
        serverHolder[0].expect(requestTo("/v1/payments/confirm")).andRespond(withServerError());

        assertThrows(PaymentGatewayException.class,
                () -> client.confirm("test_pk_123456789", "order-1", 1000, "confirm:order-1"));

        serverHolder[0].verify();
        String logs = output.getOut();
        assertThat(logs).contains("event=TOSS_CALL_FAIL");
        assertThat(logs).contains("operation=CONFIRM");
        assertThat(logs).contains("orderId=order-1");
        assertThat(logs).contains("outcome=HTTP_ERROR");
        assertThat(logs).contains("httpStatus=500");
        // 결제키는 앞 6자만 노출, 나머지는 마스킹
        assertThat(logs).contains("paymentKeyMasked=test_p");
        assertThat(logs).doesNotContain("test_pk_123456789");
        // 비밀키/인증헤더는 절대 로그에 남지 않는다
        assertThat(logs).doesNotContain(SECRET_KEY);
    }

    @Test
    @DisplayName("cancel 실패 시 operation=CANCEL, orderId 미보유는 N/A 로 남긴다")
    void cancelFailureLogsOperationAndNaOrderId(CapturedOutput output) {
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        TossPaymentClient client = clientBoundTo(serverHolder);
        serverHolder[0].expect(requestTo("/v1/payments/test_pk_9999/cancel")).andRespond(withServerError());

        assertThrows(PaymentGatewayException.class,
                () -> client.cancel("test_pk_9999", "reason", "KRW", "cancel:1"));

        serverHolder[0].verify();
        String logs = output.getOut();
        assertThat(logs).contains("event=TOSS_CALL_FAIL");
        assertThat(logs).contains("operation=CANCEL");
        assertThat(logs).contains("orderId=N/A");
        assertThat(logs).contains("idempotencyKey=cancel:1");
        assertThat(logs).doesNotContain(SECRET_KEY);
    }

    @Test
    @DisplayName("confirm/lookup/cancel breaker는 서로 열림 상태를 공유하지 않는다")
    void circuitBreakersAreIsolatedByOperation() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        TossPaymentClient client = clientBoundTo(serverHolder, registry);
        Resilience4jPaymentGatewayCircuitState circuitState =
                new Resilience4jPaymentGatewayCircuitState(registry);

        serverHolder[0].expect(requestTo("/v1/payments/pay-key"))
                .andRespond(withSuccess(
                        "{\"paymentKey\":\"pay-key\",\"orderId\":\"order-1\",\"status\":\"DONE\",\"method\":\"CARD\",\"totalAmount\":11000,\"currency\":\"KRW\"}",
                        MediaType.APPLICATION_JSON));
        serverHolder[0].expect(requestTo("/v1/payments/pay-key/cancel"))
                .andRespond(withSuccess(
                        "{\"paymentKey\":\"pay-key\",\"status\":\"CANCELED\",\"totalAmount\":11000,\"currency\":\"KRW\"}",
                        MediaType.APPLICATION_JSON));
        serverHolder[0].expect(requestTo("/v1/payments/pay-key/cancel"))
                .andRespond(withSuccess(
                        "{\"paymentKey\":\"pay-key\",\"status\":\"CANCELED\",\"totalAmount\":11000,\"currency\":\"KRW\"}",
                        MediaType.APPLICATION_JSON));

        registry.circuitBreaker(PaymentGatewayCircuitBreakers.CONFIRM).transitionToForcedOpenState();
        PaymentGatewayTemporarilyUnavailableException confirmBlocked =
                assertThrows(PaymentGatewayTemporarilyUnavailableException.class,
                        circuitState::acquireConfirmPermit);
        assertInstanceOf(CallNotPermittedException.class, confirmBlocked.getCause());
        assertEquals("DONE", client.getPaymentByPaymentKey("pay-key").status());
        assertEquals("CANCELED", client.cancel("pay-key", "reason", "KRW", "cancel:1").status());

        registry.circuitBreaker(PaymentGatewayCircuitBreakers.LOOKUP).transitionToForcedOpenState();
        PaymentGatewayException lookupBlocked = assertThrows(PaymentGatewayException.class,
                () -> client.getPaymentByPaymentKey("pay-key"));
        assertInstanceOf(CallNotPermittedException.class, lookupBlocked.getCause());
        assertEquals("CANCELED", client.cancel("pay-key", "reason", "KRW", "cancel:1").status());

        registry.circuitBreaker(PaymentGatewayCircuitBreakers.CANCEL).transitionToForcedOpenState();
        PaymentGatewayException cancelBlocked = assertThrows(PaymentGatewayException.class,
                () -> client.cancel("pay-key", "reason", "KRW", "cancel:1"));
        assertInstanceOf(CallNotPermittedException.class, cancelBlocked.getCause());

        assertEquals(CircuitBreaker.State.FORCED_OPEN,
                registry.circuitBreaker(PaymentGatewayCircuitBreakers.CONFIRM).getState());
        assertEquals(CircuitBreaker.State.FORCED_OPEN,
                registry.circuitBreaker(PaymentGatewayCircuitBreakers.LOOKUP).getState());
        assertEquals(CircuitBreaker.State.FORCED_OPEN,
                registry.circuitBreaker(PaymentGatewayCircuitBreakers.CANCEL).getState());
        serverHolder[0].verify();
    }

    @Test
    @DisplayName("PG 4xx 거절은 회로 실패율에 포함하지 않는다")
    void clientRejectionDoesNotIncreaseCircuitBreakerFailureRate() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .ignoreExceptions(PaymentGatewayRejectedException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
        TossPaymentClient client = clientBoundTo(serverHolder, registry);

        serverHolder[0].expect(requestTo("/v1/payments/pay-key"))
                .andRespond(withBadRequest());
        serverHolder[0].expect(requestTo("/v1/payments/pay-key"))
                .andRespond(withBadRequest());

        assertThrows(PaymentGatewayRejectedException.class,
                () -> client.getPaymentByPaymentKey("pay-key"));
        assertThrows(PaymentGatewayRejectedException.class,
                () -> client.getPaymentByPaymentKey("pay-key"));

        CircuitBreaker lookup = registry.circuitBreaker(PaymentGatewayCircuitBreakers.LOOKUP);
        assertEquals(CircuitBreaker.State.CLOSED, lookup.getState());
        assertEquals(0, lookup.getMetrics().getNumberOfFailedCalls());
        serverHolder[0].verify();
    }
}
