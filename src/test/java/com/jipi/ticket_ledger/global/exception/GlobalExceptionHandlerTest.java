package com.jipi.ticket_ledger.global.exception;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayTemporarilyUnavailableException;
import com.jipi.ticket_ledger.queue.application.QueueTemporarilyUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    @DisplayName("PG 회로가 OPEN이면 503과 재시도 가능 시간을 응답한다")
    void paymentGatewayUnavailableReturnsServiceUnavailable() {
        PaymentGatewayTemporarilyUnavailableException exception =
                new PaymentGatewayTemporarilyUnavailableException(
                        "PG 승인 요청을 일시적으로 처리할 수 없습니다.", 30, null);

        ResponseEntity<ErrorResponse> response =
                exceptionHandler.handlePaymentGatewayTemporarilyUnavailable(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("30", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNotNull(response.getBody());
        assertEquals("PAYMENT_GATEWAY_TEMPORARILY_UNAVAILABLE", response.getBody().code());
    }

    @Test
    @DisplayName("Redis 연결 장애는 503과 재시도 가능 시간을 응답한다")
    void redisUnavailableReturnsServiceUnavailable() {
        RedisConnectionFailureException exception =
                new RedisConnectionFailureException("redis down");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleRedisConnectionFailure(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNotNull(response.getBody());
        assertEquals("REDIS_TEMPORARILY_UNAVAILABLE", response.getBody().code());
    }

    @Test
    @DisplayName("대기열 Redis 장애는 503과 재시도 가능 시간을 응답한다")
    void queueUnavailableReturnsServiceUnavailable() {
        QueueTemporarilyUnavailableException exception =
                new QueueTemporarilyUnavailableException(new IllegalStateException("redis down"));

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleQueueTemporarilyUnavailable(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNotNull(response.getBody());
        assertEquals("QUEUE_TEMPORARILY_UNAVAILABLE", response.getBody().code());
    }
}
