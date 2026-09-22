package com.jipi.ticket_ledger.event.application.cache;

public final class CacheDiagnosticContext {
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private CacheDiagnosticContext() {
    }

    public static Scope open(String role) {
        String previous = ROLE.get();
        ROLE.set(role);
        return () -> {
            if (previous == null) ROLE.remove();
            else ROLE.set(previous);
        };
    }

    public static String role() {
        return ROLE.get();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
