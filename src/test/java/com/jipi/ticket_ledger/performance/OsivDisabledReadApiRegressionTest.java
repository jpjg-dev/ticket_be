package com.jipi.ticket_ledger.performance;

import com.jipi.ticket_ledger.auth.infrastructure.AuthCookieNames;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.event.application.cache.EventCache;
import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import com.jipi.ticket_ledger.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false"
})
@AutoConfigureMockMvc
class OsivDisabledReadApiRegressionTest extends PostgresTestContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    // Redis is intentionally replaced only at the cache port; JPA and expiration persistence stay real.
    @MockitoBean
    private EventCache eventCache;

    private Statistics statistics;
    private Fixture fixture;
    private Cookie accessTokenCookie;

    @BeforeEach
    void createFixtureOutsideTestTransaction() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        jdbcTemplate.execute("TRUNCATE TABLE users, events, schedules, seats, reservation_groups, "
                + "reservations, payments RESTART IDENTITY CASCADE");

        fixture = new TransactionTemplate(transactionManager).execute(status -> persistFixture());
        accessTokenCookie = new Cookie(AuthCookieNames.ACCESS_TOKEN,
                jwtTokenProvider.createAccessToken(fixture.userId()));
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        when(eventCache.findEventList()).thenReturn(Optional.empty());
        when(eventCache.findEventDetail(anyLong())).thenReturn(Optional.empty());
        when(eventCache.tryAcquireRefreshLock(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("OSIV 없이 주요 GET 응답을 서비스 트랜잭션 밖에서 직렬화한다")
    void majorGetEndpoints_serializeWithBoundedQueries() throws Exception {
        assertOsivDisabled();

        performWithSqlLimit(get("/api/v1/event"), 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(fixture.eventId()))
                .andExpect(jsonPath("$[0].schedules[0].id").value(fixture.scheduleId()));

        performWithSqlLimit(get("/api/v1/event/{eventId}", fixture.eventId()), 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.eventId()))
                .andExpect(jsonPath("$.schedules[0].id").value(fixture.scheduleId()));

        performWithSqlLimit(get("/api/v1/users/me"), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.userId()))
                .andExpect(jsonPath("$.email").value(fixture.email()));

        performWithSqlLimit(get("/api/v1/users/{userId}", fixture.userId()), 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations[0].reservationGroupId")
                        .value(fixture.confirmedGroupId()))
                .andExpect(jsonPath("$.reservations[0].seats[0].seatNumber").value("PAID-1"))
                .andExpect(jsonPath("$.payments[0].paymentId").value(fixture.approvedPaymentId()))
                .andExpect(jsonPath("$.payments[0].status").value("APPROVED"));
    }

    @Test
    @DisplayName("OSIV 없이 결제 상태와 회차별 좌석 요약을 직렬화한다")
    void paymentStatusAndAvailability_serializeOutsidePersistenceScope() throws Exception {
        assertOsivDisabled();

        performWithSqlLimit(get("/api/v1/payments/{paymentId}/status", fixture.approvedPaymentId()), 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(fixture.approvedPaymentId()))
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reservationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatStatus").value("BOOKED"));

        performWithSqlLimit(get("/api/v1/event/schedules/availability")
                        .param("scheduleIds", fixture.scheduleId().toString()), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scheduleId").value(fixture.scheduleId()))
                .andExpect(jsonPath("$[0].soldOut").value(false))
                .andExpect(jsonPath("$[0].available").value(0))
                .andExpect(jsonPath("$[0].held").value(1))
                .andExpect(jsonPath("$[0].booked").value(1));
    }

    @Test
    @DisplayName("좌석 GET은 실제 만료 PENDING 선점을 해제하고 확정 결제 좌석은 보존한다")
    void seatsGet_expiresPendingHoldAndPreservesConfirmedBooking() throws Exception {
        assertOsivDisabled();

        performWithSqlLimit(get("/api/v1/event/schedules/{scheduleId}/seats", fixture.scheduleId()), 12)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(fixture.scheduleId()))
                .andExpect(jsonPath("$.soldOut").value(false))
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andExpect(jsonPath("$.seats[0].seatNumber").value("EXPIRED-1"))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));

        assertEquals("EXPIRED", statusOf("reservation_groups", fixture.expiredGroupId()));
        assertEquals("EXPIRED", statusOf("reservations", fixture.expiredReservationId()));
        assertEquals("AVAILABLE", statusOf("seats", fixture.expiredSeatId()));
        assertEquals("FAILED", statusOf("payments", fixture.expiredPaymentId()));
        assertEquals("CONFIRMED", statusOf("reservation_groups", fixture.confirmedGroupId()));
        assertEquals("BOOKED", statusOf("seats", fixture.paidSeatId()));
        assertEquals("APPROVED", statusOf("payments", fixture.approvedPaymentId()));
    }

    private ResultActions performWithSqlLimit(MockHttpServletRequestBuilder request, long maximumStatements)
            throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        statistics.clear();
        ResultActions result = mockMvc.perform(request.cookie(accessTokenCookie));
        long actualStatements = statistics.getPrepareStatementCount();
        System.out.println("OSIV_SQL_COUNT request=" + request + " statements=" + actualStatements);
        assertTrue(actualStatements <= maximumStatements,
                () -> "Expected at most " + maximumStatements + " SQL statements but executed " + actualStatements);
        return result;
    }

    private void assertOsivDisabled() {
        assertEquals("false", environment.getProperty("spring.jpa.open-in-view"));
        assertTrue(applicationContext.getBeansOfType(OpenEntityManagerInViewInterceptor.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(OpenEntityManagerInViewFilter.class).isEmpty());
    }

    private String statusOf(String table, Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private Fixture persistFixture() {
        Instant now = Instant.now();
        User user = new User("osiv-regression@test.com", "encoded-password", "OSIV regression", now);
        Event event = new Event("OSIV regression event", "read API fixture", "test venue",
                now.minusSeconds(60), now);
        Schedule schedule = new Schedule(event, LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(2), now);

        Seat expiredSeat = new Seat(schedule, "EXPIRED-1", "VIP", 100_000, now);
        expiredSeat.hold();
        ReservationGroup expiredGroup = new ReservationGroup(user, now.minusSeconds(60), now.minusSeconds(1));
        Reservation expiredReservation = new Reservation(user, expiredSeat, expiredGroup,
                now.minusSeconds(60), now.minusSeconds(1));
        Payment expiredPayment = new Payment(expiredGroup, 100_000, now.minusSeconds(60), "expired-order");

        Seat paidSeat = new Seat(schedule, "PAID-1", "R", 80_000, now);
        paidSeat.hold();
        paidSeat.book();
        ReservationGroup confirmedGroup = new ReservationGroup(user, now, now.plusSeconds(300));
        confirmedGroup.confirm();
        Reservation confirmedReservation = new Reservation(user, paidSeat, confirmedGroup, now, now.plusSeconds(300));
        confirmedReservation.confirm();
        Payment approvedPayment = new Payment(confirmedGroup, 80_000, now, "approved-order");
        approvedPayment.confirming(now);
        approvedPayment.approve("approved-payment-key", "CARD", "DONE", now);

        for (Object entity : new Object[]{user, event, schedule, expiredSeat, paidSeat, expiredGroup,
                confirmedGroup, expiredReservation, confirmedReservation, expiredPayment, approvedPayment}) {
            entityManager.persist(entity);
        }
        entityManager.flush();
        return new Fixture(user.getId(), user.getEmail(), event.getId(), schedule.getId(), expiredSeat.getId(),
                paidSeat.getId(), expiredGroup.getId(), expiredReservation.getId(), expiredPayment.getId(),
                confirmedGroup.getId(), approvedPayment.getId());
    }

    private record Fixture(Long userId, String email, Long eventId, Long scheduleId, Long expiredSeatId,
                           Long paidSeatId, Long expiredGroupId, Long expiredReservationId, Long expiredPaymentId,
                           Long confirmedGroupId, Long approvedPaymentId) {
    }
}
