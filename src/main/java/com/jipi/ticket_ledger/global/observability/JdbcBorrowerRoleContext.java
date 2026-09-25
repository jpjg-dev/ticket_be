package com.jipi.ticket_ledger.global.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JdbcBorrowerRoleContext {
    public static final String UNKNOWN = "unknown";
    public static final String CACHE_OWNER = "cache_owner";
    public static final String JWT_USER_LOOKUP = "jwt_user_lookup";
    public static final String HTTP_SEATS = "http_seats";
    public static final String HTTP_RESERVATION = "http_reservation";
    public static final String HTTP_PAYMENT = "http_payment";
    public static final String HTTP_EVENTS = "http_events";
    public static final String HTTP_USER = "http_user";
    public static final String HTTP_OTHER = "http_other";
    public static final String RESERVATION_EXPIRATION = "reservation_expiration";
    public static final String PAYMENT_RECOVERY_CONFIRM = "payment_recovery_confirm";
    public static final String PAYMENT_RECOVERY_CANCEL = "payment_recovery_cancel";
    public static final String PAYMENT_RECOVERY_GAUGE = "payment_recovery_gauge";
    public static final String OUTBOX_RELAY = "outbox_relay";
    public static final String AUDIT_CONSUMER = "audit_consumer";

    private static final Scope NOOP = () -> {};
    private final boolean enabled;
    private final ThreadLocal<State> state = new ThreadLocal<>();

    public JdbcBorrowerRoleContext(@Value("${ticketledger.cache.diagnostic:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public Scope openRoot(String role) {
        if (!enabled) return NOOP;
        State previous = state.get();
        state.set(new State(known(role), null));
        return () -> restore(previous);
    }

    public Scope openPhase(String role) {
        if (!enabled) return NOOP;
        State previous = state.get();
        state.set(new State(previous == null ? null : previous.root(), known(role)));
        return () -> restore(previous);
    }

    public String acquisitionRole() {
        State current = state.get();
        return current == null ? UNKNOWN
                : current.phase() == null ? known(current.root()) : current.phase();
    }

    public String holdRole() {
        State current = state.get();
        if (current == null) return UNKNOWN;
        return known(current.root() == null ? current.phase() : current.root());
    }

    private String known(String role) {
        if (role == null) return UNKNOWN;
        return switch (role) {
            case CACHE_OWNER, JWT_USER_LOOKUP, HTTP_SEATS, HTTP_RESERVATION, HTTP_PAYMENT, HTTP_EVENTS,
                    HTTP_USER, HTTP_OTHER, RESERVATION_EXPIRATION, PAYMENT_RECOVERY_CONFIRM,
                    PAYMENT_RECOVERY_CANCEL, PAYMENT_RECOVERY_GAUGE, OUTBOX_RELAY, AUDIT_CONSUMER -> role;
            default -> UNKNOWN;
        };
    }

    private void restore(State previous) {
        if (previous == null) state.remove();
        else state.set(previous);
    }

    private record State(String root, String phase) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
