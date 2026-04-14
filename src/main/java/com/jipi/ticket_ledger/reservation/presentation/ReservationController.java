package com.jipi.ticket_ledger.reservation.presentation;

import com.jipi.ticket_ledger.reservation.application.CreateReservationCommand;
import com.jipi.ticket_ledger.reservation.application.ReservationService;
import com.jipi.ticket_ledger.reservation.presentation.dto.CreateReservationRequest;
import com.jipi.ticket_ledger.reservation.presentation.dto.ReservationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ReservationResponse createReservation(@RequestBody @Valid CreateReservationRequest request) {
        Long reservationId = reservationService.createReservation(
                new CreateReservationCommand(request.userId(), request.seatId())
        );
        return new ReservationResponse(reservationId);
    }

    @PostMapping("/{reservationId}/cancel")
    public void cancelReservation(@PathVariable Long reservationId,
                                  @RequestParam Long userId) {
        reservationService.cancelReservation(userId, reservationId);
    }
}
