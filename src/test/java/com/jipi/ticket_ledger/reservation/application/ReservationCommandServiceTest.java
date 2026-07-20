package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionPermit;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionService;
import com.jipi.ticket_ledger.queue.application.QueueTemporarilyUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCommandServiceTest {

    @Mock
    private QueueAdmissionService queueAdmissionService;

    @Mock
    private SeatReservationCoordinator seatReservationCoordinator;

    @InjectMocks
    private ReservationCommandService reservationCommandService;

    @Test
    void completesAdmissionAfterReservationCommit() {
        QueueAdmissionPermit permit = QueueAdmissionPermit.claimed(1L, 10L, "token");
        when(queueAdmissionService.claimForReservation(1L, 10L, "token")).thenReturn(permit);
        when(seatReservationCoordinator.createReservation(1L, 10L, List.of(100L))).thenReturn(77L);

        Long result = reservationCommandService.createReservation(1L, 10L, List.of(100L), "token");

        assertEquals(77L, result);
        verify(queueAdmissionService).complete(permit);
        verify(queueAdmissionService, never()).release(permit);
    }

    @Test
    void releasesAdmissionWhenReservationFails() {
        QueueAdmissionPermit permit = QueueAdmissionPermit.claimed(1L, 10L, "token");
        when(queueAdmissionService.claimForReservation(1L, 10L, "token")).thenReturn(permit);
        when(seatReservationCoordinator.createReservation(1L, 10L, List.of(100L)))
                .thenThrow(new IllegalStateException("좌석 선점 실패"));

        assertThrows(
                IllegalStateException.class,
                () -> reservationCommandService.createReservation(1L, 10L, List.of(100L), "token")
        );
        verify(queueAdmissionService).release(permit);
        verify(queueAdmissionService, never()).complete(permit);
    }

    @Test
    void preservesReservationSuccessWhenQueueCleanupFails() {
        QueueAdmissionPermit permit = QueueAdmissionPermit.claimed(1L, 10L, "token");
        when(queueAdmissionService.claimForReservation(1L, 10L, "token")).thenReturn(permit);
        when(seatReservationCoordinator.createReservation(1L, 10L, List.of(100L))).thenReturn(77L);
        doThrow(new QueueTemporarilyUnavailableException(new RuntimeException("redis down")))
                .when(queueAdmissionService).complete(permit);

        Long result = reservationCommandService.createReservation(1L, 10L, List.of(100L), "token");

        assertEquals(77L, result);
        verify(queueAdmissionService).complete(permit);
        verify(queueAdmissionService, never()).release(permit);
    }

    @Test
    void preservesReservationFailureWhenQueueReleaseAlsoFails() {
        QueueAdmissionPermit permit = QueueAdmissionPermit.claimed(1L, 10L, "token");
        IllegalStateException reservationFailure = new IllegalStateException("좌석 선점 실패");
        when(queueAdmissionService.claimForReservation(1L, 10L, "token")).thenReturn(permit);
        when(seatReservationCoordinator.createReservation(1L, 10L, List.of(100L))).thenThrow(reservationFailure);
        doThrow(new QueueTemporarilyUnavailableException(new RuntimeException("redis down")))
                .when(queueAdmissionService).release(permit);

        IllegalStateException result = assertThrows(
                IllegalStateException.class,
                () -> reservationCommandService.createReservation(1L, 10L, List.of(100L), "token")
        );

        assertEquals(reservationFailure, result);
        assertEquals(1, result.getSuppressed().length);
        verify(queueAdmissionService, never()).complete(permit);
    }
}
