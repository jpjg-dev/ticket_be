package com.jipi.ticket_ledger.performance;

import com.jipi.ticket_ledger.auth.infrastructure.AuthCookieNames;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.event.presentation.EventController;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.resource.jdbc.spi.PhysicalConnectionHandlingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@AutoConfigureMockMvc
@Import(SeatConnectionLifetimeProbeTest.ProbeTestConfiguration.class)
class SeatConnectionLifetimeProbeTest extends PostgresTestContainerSupport {

    private static final String PROBE_HEADER = "X-Test-Connection-Lifetime-Probe";
    private static final String PROBE_VALUE = "issue-71";
    private static final ProbeRecorder RECORDER = new ProbeRecorder();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationGroupRepository reservationGroupRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext applicationContext;

    private Fixture fixture;

    @BeforeEach
    void createFixtureWithoutExpiredCandidates() {
        RECORDER.reset();
        AtomicReference<Fixture> created = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            User user = userRepository.save(new User(
                    "connection-probe-" + System.nanoTime() + "@test.com",
                    "encoded-test-password",
                    "connection probe",
                    now
            ));
            Event event = eventRepository.save(new Event(
                    "connection lifetime probe",
                    "single request fixture",
                    "test venue",
                    now.minusSeconds(60),
                    now
            ));
            Schedule schedule = scheduleRepository.save(new Schedule(
                    event,
                    LocalDateTime.now().plusDays(1),
                    LocalDateTime.now().plusDays(1).plusHours(2),
                    now
            ));
            seatRepository.save(new Seat(schedule, "PROBE-1", "VIP", 100000, now));
            created.set(new Fixture(user.getId(), schedule.getId()));
        });

        fixture = created.get();
        assertNotNull(fixture);
        assertTrue(reservationGroupRepository
                .findExpiredPendingIdsByScheduleId(fixture.scheduleId(), Instant.now())
                .isEmpty());
        RECORDER.reset();
    }

    @Test
    @DisplayName("단일 인증 좌석 조회의 JDBC connection 반환 시점을 MVC 직렬화 경계와 함께 기록한다")
    void authenticatedSeatRequest_recordsConnectionLifetimeAgainstSerialization() throws Exception {
        String accessToken = jwtTokenProvider.createAccessToken(fixture.userId());

        mockMvc.perform(get("/api/v1/event/schedules/{scheduleId}/seats", fixture.scheduleId())
                        .cookie(new Cookie(AuthCookieNames.ACCESS_TOKEN, accessToken))
                        .header(PROBE_HEADER, PROBE_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(fixture.scheduleId()))
                .andExpect(jsonPath("$.soldOut").value(false))
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andExpect(jsonPath("$.seats[0].seatNumber").value("PROBE-1"))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));

        ProbeTrace trace = RECORDER.completedTrace();
        assertNotNull(trace);
        assertRequiredStages(trace);
        assertBalancedConnectionLifecycle(trace);
        assertTrue(trace.events().stream()
                .filter(event -> event.stage().equals("mvc.preHandle"))
                .allMatch(ProbeEvent::authenticated));

        PhysicalConnectionHandlingMode factoryMode = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getSessionFactoryOptions()
                .getPhysicalConnectionHandlingMode();
        assertNotNull(factoryMode);

        boolean openEntityManagerInView =
                !applicationContext.getBeansOfType(OpenEntityManagerInViewInterceptor.class).isEmpty()
                        || !applicationContext.getBeansOfType(OpenEntityManagerInViewFilter.class).isEmpty();
        System.out.println(trace.format(factoryMode, openEntityManagerInView));
    }

    private void assertRequiredStages(ProbeTrace trace) {
        List<String> stages = trace.events().stream().map(ProbeEvent::stage).toList();
        for (String required : List.of(
                "request.enter",
                "mvc.preHandle",
                "controller.enter",
                "controller.exit",
                "body.beforeWrite",
                "mvc.postHandle",
                "mvc.afterCompletion",
                "request.exit"
        )) {
            assertTrue(stages.contains(required), () -> "Missing probe stage: " + required);
        }

        for (int index = 1; index < trace.events().size(); index++) {
            assertTrue(trace.events().get(index).nanoTime() >= trace.events().get(index - 1).nanoTime(),
                    "Probe timestamps must be monotonic");
        }
    }

    private void assertBalancedConnectionLifecycle(ProbeTrace trace) {
        Map<String, ProbeEvent> checkouts = new LinkedHashMap<>();
        Map<String, ProbeEvent> closes = new LinkedHashMap<>();
        for (ProbeEvent event : trace.events()) {
            if (event.stage().equals("connection.checkout")) {
                assertFalse(checkouts.containsKey(event.connection()), "Duplicate connection checkout identity");
                checkouts.put(event.connection(), event);
            } else if (event.stage().equals("connection.close")) {
                assertFalse(closes.containsKey(event.connection()), "Connection handle closed more than once");
                closes.put(event.connection(), event);
            }
        }

        assertFalse(checkouts.isEmpty(), "The request must perform real JDBC work");
        assertEquals(checkouts.keySet(), closes.keySet(), "Every checked-out connection handle must be closed");
        checkouts.forEach((connection, checkout) -> assertTrue(
                closes.get(connection).nanoTime() >= checkout.nanoTime(),
                () -> "Connection closed before checkout: " + connection
        ));
    }

    private record Fixture(Long userId, Long scheduleId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeTestConfiguration {

        @Bean
        static BeanPostProcessor recordingDataSourceBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof DataSource dataSource && !(bean instanceof RecordingDataSource)) {
                        return new RecordingDataSource(dataSource, RECORDER);
                    }
                    return bean;
                }
            };
        }

        @Bean
        FilterRegistrationBean<ProbeActivationFilter> probeActivationFilter() {
            FilterRegistrationBean<ProbeActivationFilter> registration =
                    new FilterRegistrationBean<>(new ProbeActivationFilter(RECORDER));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

        @Bean
        WebMvcConfigurer probeWebMvcConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new ProbeHandlerInterceptor(RECORDER));
                }
            };
        }

        @Bean
        ProbeControllerAspect probeControllerAspect() {
            return new ProbeControllerAspect(RECORDER);
        }

        @Bean
        ProbeResponseBodyAdvice probeResponseBodyAdvice() {
            return new ProbeResponseBodyAdvice(RECORDER);
        }
    }

    static final class RecordingDataSource extends DelegatingDataSource implements AutoCloseable {

        private final ProbeRecorder recorder;

        RecordingDataSource(DataSource targetDataSource, ProbeRecorder recorder) {
            super(targetDataSource);
            this.recorder = recorder;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return recorder.wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return recorder.wrap(super.getConnection(username, password));
        }

        @Override
        public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    static final class ProbeActivationFilter extends OncePerRequestFilter {

        private final ProbeRecorder recorder;

        ProbeActivationFilter(ProbeRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (!PROBE_VALUE.equals(request.getHeader(PROBE_HEADER))) {
                filterChain.doFilter(request, response);
                return;
            }

            recorder.begin();
            recorder.mark("request.enter", null);
            try {
                filterChain.doFilter(request, response);
            } finally {
                recorder.mark("request.exit", null);
                recorder.complete();
            }
        }
    }

    static final class ProbeHandlerInterceptor implements HandlerInterceptor {

        private final ProbeRecorder recorder;

        ProbeHandlerInterceptor(ProbeRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            recorder.mark("mvc.preHandle", null);
            return true;
        }

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                               ModelAndView modelAndView) {
            recorder.mark("mvc.postHandle", null);
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                    Exception exception) {
            recorder.mark("mvc.afterCompletion", null);
        }
    }

    @Aspect
    static final class ProbeControllerAspect {

        private final ProbeRecorder recorder;

        ProbeControllerAspect(ProbeRecorder recorder) {
            this.recorder = recorder;
        }

        @Around("execution(* com.jipi.ticket_ledger.event.presentation.EventController.getSeats(..))")
        Object recordController(ProceedingJoinPoint joinPoint) throws Throwable {
            recorder.mark("controller.enter", null);
            try {
                return joinPoint.proceed();
            } finally {
                recorder.mark("controller.exit", null);
            }
        }
    }

    @ControllerAdvice(assignableTypes = EventController.class)
    static final class ProbeResponseBodyAdvice implements ResponseBodyAdvice<Object> {

        private final ProbeRecorder recorder;

        ProbeResponseBodyAdvice(ProbeRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public boolean supports(MethodParameter returnType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
            Class<?> controllerType = returnType.getContainingClass();
            return EventController.class.isAssignableFrom(controllerType)
                    && returnType.getMethod() != null
                    && returnType.getMethod().getName().equals("getSeats");
        }

        @Override
        public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                      Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                      ServerHttpRequest request, ServerHttpResponse response) {
            recorder.mark("body.beforeWrite", null);
            return body;
        }
    }

    static final class ProbeRecorder {

        private final ThreadLocal<MutableTrace> active = new ThreadLocal<>();
        private final AtomicReference<ProbeTrace> completed = new AtomicReference<>();
        private final AtomicInteger connectionSequence = new AtomicInteger();

        void reset() {
            active.remove();
            completed.set(null);
            connectionSequence.set(0);
        }

        void begin() {
            active.set(new MutableTrace(System.nanoTime()));
        }

        void complete() {
            MutableTrace trace = active.get();
            if (trace != null) {
                completed.set(trace.snapshot());
            }
            active.remove();
        }

        ProbeTrace completedTrace() {
            return completed.get();
        }

        Connection wrap(Connection connection) {
            MutableTrace trace = active.get();
            if (trace == null) {
                return connection;
            }

            String connectionId = "c" + connectionSequence.incrementAndGet()
                    + "@" + Integer.toHexString(System.identityHashCode(connection));
            mark("connection.checkout", connectionId);
            AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(
                    connection.getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("close")
                                    && method.getParameterCount() == 0
                                    && closed.compareAndSet(false, true)) {
                                mark("connection.close", connectionId);
                            }
                            return result;
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    }
            );
        }

        void mark(String stage, String connection) {
            MutableTrace trace = active.get();
            if (trace == null) {
                return;
            }

            EntityManager entityManager = TransactionSynchronizationManager.getResourceMap().values().stream()
                    .filter(EntityManagerHolder.class::isInstance)
                    .map(EntityManagerHolder.class::cast)
                    .map(EntityManagerHolder::getEntityManager)
                    .findFirst()
                    .orElse(null);
            String entityManagerId = entityManager == null
                    ? "-"
                    : Integer.toHexString(System.identityHashCode(entityManager));
            String sessionMode = sessionConnectionHandlingMode(entityManager);
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean authenticated = authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken);
            trace.add(new ProbeEvent(
                    trace.nextSequence(),
                    System.nanoTime(),
                    stage,
                    connection == null ? "-" : connection,
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    entityManagerId,
                    sessionMode,
                    authenticated
            ));
        }

        private String sessionConnectionHandlingMode(EntityManager entityManager) {
            if (entityManager == null) {
                return "-";
            }
            try {
                return entityManager.unwrap(SessionImplementor.class)
                        .getJdbcCoordinator()
                        .getLogicalConnection()
                        .getConnectionHandlingMode()
                        .name();
            } catch (RuntimeException exception) {
                return "unavailable";
            }
        }
    }

    static final class MutableTrace {

        private final long originNanos;
        private final List<ProbeEvent> events = new ArrayList<>();
        private int sequence;

        MutableTrace(long originNanos) {
            this.originNanos = originNanos;
        }

        int nextSequence() {
            return ++sequence;
        }

        void add(ProbeEvent event) {
            events.add(event);
        }

        ProbeTrace snapshot() {
            return new ProbeTrace(originNanos, List.copyOf(events));
        }
    }

    record ProbeTrace(long originNanos, List<ProbeEvent> events) {

        String format(PhysicalConnectionHandlingMode factoryMode, boolean openEntityManagerInView) {
            long bodyBoundary = events.stream()
                    .filter(event -> event.stage().equals("body.beforeWrite"))
                    .mapToLong(ProbeEvent::nanoTime)
                    .findFirst()
                    .orElse(Long.MIN_VALUE);
            long lastClose = events.stream()
                    .filter(event -> event.stage().equals("connection.close"))
                    .mapToLong(ProbeEvent::nanoTime)
                    .max()
                    .orElse(Long.MAX_VALUE);
            StringBuilder output = new StringBuilder("ISSUE71_CONNECTION_TRACE")
                    .append(" factoryMode=").append(factoryMode)
                    .append(" openEntityManagerInView=").append(openEntityManagerInView)
                    .append(" allConnectionsClosedBeforeBodyWrite=").append(lastClose < bodyBoundary);
            for (ProbeEvent event : events) {
                output.append(System.lineSeparator())
                        .append(String.format("%02d +%dus %-24s conn=%s tx=%s em=%s sessionMode=%s auth=%s",
                                event.sequence(),
                                (event.nanoTime() - originNanos) / 1_000,
                                event.stage(),
                                event.connection(),
                                event.transactionActive(),
                                event.entityManagerIdentity(),
                                event.sessionConnectionHandlingMode(),
                                event.authenticated()));
            }
            return output.toString();
        }
    }

    record ProbeEvent(
            int sequence,
            long nanoTime,
            String stage,
            String connection,
            boolean transactionActive,
            String entityManagerIdentity,
            String sessionConnectionHandlingMode,
            boolean authenticated
    ) {
    }
}
