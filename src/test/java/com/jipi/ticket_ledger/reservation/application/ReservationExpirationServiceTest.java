package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationServiceTest {

    @Mock
    private ReservationGroupRepository reservationGroupRepository;

    @Mock
    private ReservationExpirationTransactionService transactionService;

    @Mock
    private ReservationExpirationMetrics metrics;

    @Spy
    private Clock clock = Clock.systemDefaultZone();

    @InjectMocks
    private ReservationExpirationService reservationExpirationService;

    @BeforeEach
    void setUpBatchSize() {
        ReflectionTestUtils.setField(reservationExpirationService, "batchSize", 100);
    }

    @Test
    @DisplayName("expireAll: 주입된 Clock의 시각을 만료 기준으로 사용한다")
    void expireAllUsesInjectedClockInstant() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        ReflectionTestUtils.setField(reservationExpirationService, "clock", Clock.fixed(fixedNow, ZoneOffset.UTC));
        when(reservationGroupRepository.findExpiredPendingIds(eq(fixedNow), any(Pageable.class)))
                .thenReturn(List.of());

        reservationExpirationService.expireAll();

        verify(reservationGroupRepository).findExpiredPendingIds(eq(fixedNow), any(Pageable.class));
    }

    @Test
    @DisplayName("expireAll: 설정된 batch-size 만큼만 만료 후보를 조회한다")
    void expireAllUsesConfiguredBatchSize() {
        ReflectionTestUtils.setField(reservationExpirationService, "batchSize", 2);
        when(reservationGroupRepository.findExpiredPendingIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        reservationExpirationService.expireAll();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reservationGroupRepository).findExpiredPendingIds(any(Instant.class), pageableCaptor.capture());
        assertEquals(2, pageableCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("expireAll: 한 건이 실패해도 나머지 후보를 독립적으로 처리한다")
    void expireAllContinuesAfterSingleGroupFailure() {
        when(reservationGroupRepository.findExpiredPendingIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(transactionService.expireGroup(1L, "SCHEDULER")).thenReturn(2);
        when(transactionService.expireGroup(2L, "SCHEDULER")).thenThrow(new IllegalStateException("invalid seat state"));
        when(transactionService.expireGroup(3L, "SCHEDULER")).thenReturn(1);

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(3, expiredCount);
        var inOrder = inOrder(transactionService);
        inOrder.verify(transactionService).expireGroup(1L, "SCHEDULER");
        inOrder.verify(transactionService).expireGroup(2L, "SCHEDULER");
        inOrder.verify(transactionService).expireGroup(3L, "SCHEDULER");
        verify(metrics, org.mockito.Mockito.times(2)).record("SCHEDULER", "expired");
        verify(metrics).record("SCHEDULER", "failed");
    }

    @Test
    @DisplayName("expireByScheduleId: 요청 경로의 실패는 호출자에게 전파한다")
    void expireByScheduleIdPropagatesFailure() {
        when(reservationGroupRepository.findExpiredPendingIdsByScheduleId(eq(10L), any(Instant.class)))
                .thenReturn(List.of(1L, 2L));
        when(transactionService.expireGroup(1L, "SEAT_QUERY"))
                .thenThrow(new IllegalStateException("expiration failed"));

        assertThrows(IllegalStateException.class, () -> reservationExpirationService.expireByScheduleId(10L));

        verify(transactionService, never()).expireGroup(2L, "SEAT_QUERY");
        verify(metrics).record("SEAT_QUERY", "failed");
    }
}
