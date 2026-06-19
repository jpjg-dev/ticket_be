package com.jipi.ticket_ledger.global.exception;

import com.jipi.ticket_ledger.global.log.LogEvents;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 예외를 한 곳에서 로깅한다. traceId/userId 는 MDC 로 모든 로그 줄에 자동 부착된다.
    // 클라이언트 책임(4xx)은 warn(스택 생략), 서버 책임(5xx)은 error(스택 포함)로 구분한다.

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException e) {
        log.warn("event={} code=NOT_FOUND status=404 message={}", LogEvents.API_ERROR, e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.warn("event={} code=ILLEGAL_STATE status=409 message={}", LogEvents.API_ERROR, e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ILLEGAL_STATE", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("event={} code=BAD_REQUEST status=400 message={}", LogEvents.API_ERROR, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("잘못된 요청입니다.");

        log.warn("event={} code=BAD_REQUEST status=400 message={}", LogEvents.API_ERROR, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("event={} code=INTERNAL_SERVER_ERROR status=500", LogEvents.API_ERROR, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
    }

    @ExceptionHandler(AuthUnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleAuthUnauthorized(AuthUnauthorizedException e) {
        log.warn("event={} code=AUTH_REQUIRED status=401 message={}", LogEvents.API_ERROR, e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("AUTH_REQUIRED", "인증이 필요합니다."));
    }
}
