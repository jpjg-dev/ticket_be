package com.jipi.ticket_ledger.user.application;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import com.jipi.ticket_ledger.user.presentation.dto.RequestSignUpDTO;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMeDTO;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMyPageDTO;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;

    /**
     * {@link RequestSignUpDTO}
     *
     * @param request
     * @return
     */
    public String signUp(RequestSignUpDTO request) {
        if (userRepository.existsByEmail(request.email())) {
            log.warn("event={} email={} reason={}", LogEvents.USER_SIGNUP_REJECT, request.email(), "DUPLICATE_EMAIL");
            throw new IllegalStateException("이미 존재하는 이메일입니다.");
        }
        User user = userRepository.save(new User(request.email(), passwordEncoder.encode(request.password()), request.name(), LocalDateTime.now()));
        log.info("event={} userId={} email={} name={}", LogEvents.USER_SIGNUP_SUCCESS, user.getId(), request.email(), request.name());
        return "회원가입이 완료되었습니다.";
    }

    /**
     *
     * @param userId
     * @return
     */
    public ResponseMeDTO getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("일치하는 사용자가 없습니다."));
        return new ResponseMeDTO(user.getId(), user.getEmail(), user.getName(), user.getRole().name(), user.getStatus().name());
    }

    /**
     *
     * @param userId
     * @return
     */
    public ResponseMyPageDTO getUserInfo(Long userId, Long principalUserId) {
        if (!userRepository.existsById(userId)) {
            log.warn("event={} requestedUserId={} principalUserId={} reason={}",
                    LogEvents.USER_MYPAGE_REJECT, userId, principalUserId, "USER_NOT_FOUND");
            throw new EntityNotFoundException("일치하는 사용자가 없습니다.");
        }
        if (!userId.equals(principalUserId)) {
            log.warn("event={} requestedUserId={} principalUserId={} reason={}",
                    LogEvents.USER_MYPAGE_REJECT, userId, principalUserId, "FORBIDDEN_MYPAGE_ACCESS");
            throw new IllegalStateException("잘못된 접근 입니다.");
        }
        log.info("=========================mypage 쿼리 발생 시작======================================");
        List<Reservation> reservations = reservationRepository.findByReservationGroupUserIdAndReservationGroupStatusIn(
                userId,
                List.of(ReservationGroupStatus.CONFIRMED, ReservationGroupStatus.CANCELED),
                Sort.by(Sort.Direction.DESC, "id")
        );

        List<Payment> payments = paymentRepository.findByReservationGroupUserIdAndStatusIn(
                userId,
                List.of(PaymentStatus.APPROVED, PaymentStatus.CANCELED),
                Sort.by(Sort.Direction.DESC, "requestedAt")
        );
        log.info("=========================mypage 쿼리 발생 끝======================================");

        List<ResponseMyPageDTO.ReservationGroupItem> reservationItems = groupReservations(reservations).entrySet().stream()
                .map(entry -> toReservationGroupItem(entry.getKey(), entry.getValue()))
                .toList();

        List<ResponseMyPageDTO.PaymentItem> paymentItems = payments.stream()
                .map(payment -> toPaymentItem(payment, reservationsForPayment(payment)))
                .toList();
        return new ResponseMyPageDTO(reservationItems, paymentItems);
    }

    private Map<Long, List<Reservation>> groupReservations(List<Reservation> reservations) {
        Map<Long, List<Reservation>> reservationMap = new LinkedHashMap<>();
        for (Reservation reservation : reservations) {
            Long groupId = reservation.getReservationGroup().getId();
            reservationMap.computeIfAbsent(groupId, ignored -> new java.util.ArrayList<>()).add(reservation);
        }
        return reservationMap;
    }

    private ResponseMyPageDTO.ReservationGroupItem toReservationGroupItem(Long reservationGroupId, List<Reservation> reservations) {
        Reservation firstReservation = reservations.get(0);
        return new ResponseMyPageDTO.ReservationGroupItem(
                reservationGroupId,
                firstReservation.getReservationGroup().getStatus().name(),
                firstReservation.getSeat().getSchedule().getEvent().getTitle(),
                firstReservation.getSeat().getSchedule().getEvent().getVenue(),
                firstReservation.getSeat().getSchedule().getStartAt(),
                toSeatItems(reservations)
        );
    }

    private ResponseMyPageDTO.PaymentItem toPaymentItem(Payment payment, List<Reservation> reservations) {
        return new ResponseMyPageDTO.PaymentItem(
                payment.getReservationGroup().getId(),
                payment.getId(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getRequestedAt(),
                toSeatItems(reservations)
        );
    }

    private List<Reservation> reservationsForPayment(Payment payment) {
        return reservationRepository.findByReservationGroupId(payment.getReservationGroup().getId());
    }

    private List<ResponseMyPageDTO.SeatItem> toSeatItems(List<Reservation> reservations) {
        return reservations.stream()
                .map(reservation -> new ResponseMyPageDTO.SeatItem(
                        reservation.getSeat().getSeatNumber(),
                        reservation.getSeat().getGrade()
                ))
                .toList();
    }
}
