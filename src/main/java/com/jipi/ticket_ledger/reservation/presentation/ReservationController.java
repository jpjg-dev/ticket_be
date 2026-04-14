package com.jipi.ticket_ledger.reservation.presentation;

import com.jipi.ticket_ledger.reservation.application.CreateReservationCommand;
import com.jipi.ticket_ledger.reservation.application.ReservationService;
import com.jipi.ticket_ledger.reservation.presentation.dto.CreateReservationRequest;
import com.jipi.ticket_ledger.reservation.presentation.dto.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Reservation API", description = "공연 예매 관련 API")
@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "공연 예매 생성", description = "사용자와 좌석 정보로 예약을 생성합니다.")
    @PostMapping
    public ReservationResponse createReservation(@RequestBody @Valid CreateReservationRequest request) {
        Long reservationId = reservationService.createReservation(
                new CreateReservationCommand(request.userId(), request.seatId())
        );
        return new ReservationResponse(reservationId);
    }

    @Operation(summary = "공연 예매 취소", description = "예약 식별자와 사용자 ID로 예약을 취소합니다.")
    @PostMapping("/{reservationId}/cancel")
    public void cancelReservation(@PathVariable Long reservationId,
                                  @RequestParam Long userId) {
        reservationService.cancelReservation(userId, reservationId);
    }
}
