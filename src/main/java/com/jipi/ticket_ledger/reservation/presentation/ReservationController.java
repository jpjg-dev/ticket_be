package com.jipi.ticket_ledger.reservation.presentation;

import com.jipi.ticket_ledger.reservation.application.ReservationService;
import com.jipi.ticket_ledger.reservation.presentation.dto.CreateReservationRequest;
import com.jipi.ticket_ledger.reservation.presentation.dto.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Reservation API", description = "공연 예매 관련 API")
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "공연 예매 생성", description = "사용자와 좌석 정보로 예약을 생성합니다.")
    @PostMapping
    public ReservationResponse createReservation(@AuthenticationPrincipal Long userId, @RequestBody @Valid CreateReservationRequest request) {
        Long reservationGroupId = reservationService.createReservation(userId, request.seatIds());
        return new ReservationResponse(reservationGroupId);
    }
}
