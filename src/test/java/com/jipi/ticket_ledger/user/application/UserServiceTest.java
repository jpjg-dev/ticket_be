package com.jipi.ticket_ledger.user.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMeDTO;
import com.jipi.ticket_ledger.user.presentation.dto.ResponseMyPageDTO;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("getMyInfo: 로그인 사용자 기본 정보를 반환한다")
    void getMyInfoSuccess() {
        User user = createUser(1L, "user@test.com", "테스터");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ResponseMeDTO response = userService.getMyInfo(1L);

        assertEquals(1L, response.id());
        assertEquals("user@test.com", response.email());
        assertEquals("테스터", response.name());
        assertEquals("ROLE_USER", response.role());
        assertEquals("ACTIVE", response.status());
    }

    @Test
    @DisplayName("getMyInfo: 사용자를 찾을 수 없으면 예외가 발생한다")
    void getMyInfoUserNotFound() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> userService.getMyInfo(404L));
    }

    @Test
    @DisplayName("getUserInfo: 본인 마이페이지의 예약/결제 내역을 group 기준으로 반환한다")
    void getUserInfoSuccess() {
        User user = createUser(1L, "user@test.com", "테스터");
        ReservationFixture approvedFixture = createConfirmedReservationFixture(user, 10L, 100L, "A-1", "A-2");
        ReservationFixture canceledFixture = createCanceledReservationFixture(user, 20L, 200L, "B-1");

        when(userRepository.existsById(1L)).thenReturn(true);
        when(reservationRepository.findByUserIdAndStatusIn(
                eq(1L),
                eq(List.of(ReservationStatus.CONFIRMED, ReservationStatus.CANCELED)),
                eq(Sort.by(Sort.Direction.DESC, "id"))
        )).thenReturn(List.of(
                approvedFixture.reservations().get(0),
                approvedFixture.reservations().get(1),
                canceledFixture.reservations().get(0)
        ));
        when(paymentRepository.findByReservationGroupUserIdAndStatusIn(
                eq(1L),
                eq(List.of(com.jipi.ticket_ledger.payment.domain.PaymentStatus.APPROVED, com.jipi.ticket_ledger.payment.domain.PaymentStatus.CANCELED)),
                eq(Sort.by(Sort.Direction.DESC, "requestedAt"))
        )).thenReturn(List.of(approvedFixture.payment(), canceledFixture.payment()));
        when(reservationRepository.findByReservationGroupId(10L)).thenReturn(approvedFixture.reservations());
        when(reservationRepository.findByReservationGroupId(20L)).thenReturn(canceledFixture.reservations());

        ResponseMyPageDTO response = userService.getUserInfo(1L, 1L);

        assertEquals(2, response.reservations().size());
        assertEquals(10L, response.reservations().get(0).reservationGroupId());
        assertEquals("CONFIRMED", response.reservations().get(0).status());
        assertEquals("테스트 공연", response.reservations().get(0).eventTitle());
        assertEquals(2, response.reservations().get(0).seats().size());
        assertEquals("A-1", response.reservations().get(0).seats().get(0).seatNumber());
        assertEquals(20L, response.reservations().get(1).reservationGroupId());
        assertEquals("CANCELED", response.reservations().get(1).status());

        assertEquals(2, response.payments().size());
        assertEquals(100L, response.payments().get(0).paymentId());
        assertEquals(10L, response.payments().get(0).reservationGroupId());
        assertEquals("APPROVED", response.payments().get(0).status());
        assertEquals(200000, response.payments().get(0).amount());
        assertEquals(2, response.payments().get(0).seats().size());
        assertEquals(200L, response.payments().get(1).paymentId());
        assertEquals("CANCELED", response.payments().get(1).status());
    }

    @Test
    @DisplayName("getUserInfo: 존재하지 않는 userId면 예외가 발생한다")
    void getUserInfoUserNotFound() {
        when(userRepository.existsById(404L)).thenReturn(false);

        assertThrows(EntityNotFoundException.class, () -> userService.getUserInfo(404L, 404L));
        verify(reservationRepository, never()).findByUserIdAndStatusIn(
                eq(404L),
                eq(List.of(ReservationStatus.CONFIRMED, ReservationStatus.CANCELED)),
                eq(Sort.by(Sort.Direction.DESC, "id"))
        );
    }

    @Test
    @DisplayName("getUserInfo: 다른 사용자의 마이페이지를 조회하면 예외가 발생한다")
    void getUserInfoForbiddenOtherUser() {
        when(userRepository.existsById(2L)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> userService.getUserInfo(2L, 1L));
        verify(paymentRepository, never()).findByReservationGroupUserIdAndStatusIn(
                eq(2L),
                eq(List.of(com.jipi.ticket_ledger.payment.domain.PaymentStatus.APPROVED, com.jipi.ticket_ledger.payment.domain.PaymentStatus.CANCELED)),
                eq(Sort.by(Sort.Direction.DESC, "requestedAt"))
        );
    }

    private ReservationFixture createConfirmedReservationFixture(User user, Long reservationGroupId, Long paymentId, String... seatNumbers) {
        LocalDateTime now = LocalDateTime.now();
        ReservationGroup reservationGroup = createReservationGroup(user, reservationGroupId, now);
        List<Reservation> reservations = createReservations(user, reservationGroup, now, seatNumbers);
        Payment payment = new Payment(reservationGroup, 200000, now, "order-" + paymentId, "KRW");
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.approve("payment-key-" + paymentId, "CARD", "DONE");
        reservations.forEach(reservation -> {
            reservation.confirm();
            reservation.getSeat().book();
        });
        return new ReservationFixture(reservations, payment);
    }

    private ReservationFixture createCanceledReservationFixture(User user, Long reservationGroupId, Long paymentId, String... seatNumbers) {
        ReservationFixture fixture = createConfirmedReservationFixture(user, reservationGroupId, paymentId, seatNumbers);
        fixture.payment().cancel(LocalDateTime.now());
        fixture.reservations().forEach(reservation -> {
            reservation.cancel();
            reservation.getSeat().releaseBooked();
        });
        return fixture;
    }

    private List<Reservation> createReservations(User user, ReservationGroup reservationGroup, LocalDateTime now, String... seatNumbers) {
        Event event = new Event("테스트 공연", "설명", "테스트홀", now.minusDays(1), now);
        Schedule schedule = new Schedule(event, now.plusDays(1), now.plusDays(1).plusHours(2), now);

        return java.util.stream.IntStream.range(0, seatNumbers.length)
                .mapToObj(index -> {
                    Seat seat = new Seat(schedule, seatNumbers[index], "VIP", 100000, now);
                    seat.hold();
                    Reservation reservation = new Reservation(user, seat, reservationGroup, now);
                    ReflectionTestUtils.setField(reservation, "id", reservationGroup.getId() * 10 + index);
                    return reservation;
                })
                .toList();
    }

    private ReservationGroup createReservationGroup(User user, Long reservationGroupId, LocalDateTime now) {
        ReservationGroup reservationGroup = new ReservationGroup(user, now);
        ReflectionTestUtils.setField(reservationGroup, "id", reservationGroupId);
        return reservationGroup;
    }

    private User createUser(Long userId, String email, String name) {
        User user = new User(email, "encoded-password", name, LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private record ReservationFixture(List<Reservation> reservations, Payment payment) {
    }
}
