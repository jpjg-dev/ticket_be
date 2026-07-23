package com.jipi.ticket_ledger.user.application;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.user.application.model.ResponseMyPageDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MyPageResponseMapper {

    public ResponseMyPageDTO toResponse(List<Reservation> reservations, List<Payment> payments) {
        Map<Long, List<Reservation>> reservationsByGroupId = groupReservations(reservations);
        List<ResponseMyPageDTO.ReservationGroupItem> reservationItems = reservationsByGroupId.entrySet().stream()
                .map(entry -> toReservationGroupItem(entry.getKey(), entry.getValue()))
                .toList();
        List<ResponseMyPageDTO.PaymentItem> paymentItems = payments.stream()
                .map(payment -> toPaymentItem(payment,
                        reservationsByGroupId.getOrDefault(payment.getReservationGroup().getId(), List.of())))
                .toList();
        return new ResponseMyPageDTO(reservationItems, paymentItems);
    }

    public Map<Long, List<Reservation>> groupReservations(List<Reservation> reservations) {
        Map<Long, List<Reservation>> grouped = new LinkedHashMap<>();
        for (Reservation reservation : reservations) {
            grouped.computeIfAbsent(reservation.getReservationGroup().getId(), ignored -> new ArrayList<>())
                    .add(reservation);
        }
        return grouped;
    }

    public ResponseMyPageDTO.ReservationGroupItem toReservationGroupItem(
            Long reservationGroupId,
            List<Reservation> reservations
    ) {
        Reservation first = reservations.getFirst();
        return new ResponseMyPageDTO.ReservationGroupItem(
                reservationGroupId,
                first.getReservationGroup().getStatus().name(),
                first.getSeat().getSchedule().getEvent().getTitle(),
                first.getSeat().getSchedule().getEvent().getVenue(),
                first.getSeat().getSchedule().getStartAt(),
                toSeatItems(reservations)
        );
    }

    public ResponseMyPageDTO.PaymentItem toPaymentItem(Payment payment, List<Reservation> reservations) {
        return new ResponseMyPageDTO.PaymentItem(
                payment.getReservationGroup().getId(), payment.getId(), payment.getStatus().name(),
                payment.getAmount(), payment.getMethod(), payment.getRequestedAt(), toSeatItems(reservations)
        );
    }

    public List<ResponseMyPageDTO.SeatItem> toSeatItems(List<Reservation> reservations) {
        return reservations.stream()
                .map(reservation -> new ResponseMyPageDTO.SeatItem(
                        reservation.getSeat().getSeatNumber(), reservation.getSeat().getGrade()))
                .toList();
    }
}
