package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationExpirationService reservationExpirationService;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("getSeats: 해당 회차의 만료 예약을 정리한 뒤 좌석을 조회한다")
    void getSeatsExpiresRequestedScheduleBeforeReadingSeats() {
        when(scheduleRepository.existsById(10L)).thenReturn(true);
        when(seatRepository.findByScheduleId(10L)).thenReturn(List.of());

        eventService.getSeats(10L);

        var inOrder = inOrder(reservationExpirationService, seatRepository);
        inOrder.verify(reservationExpirationService).expireByScheduleId(10L);
        inOrder.verify(seatRepository).findByScheduleId(10L);
    }
}
