package com.jipi.ticket_ledger.global.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcBorrowerRoleContextTest {

    @Test
    void disabledContextDoesNotSetRole() {
        JdbcBorrowerRoleContext context = new JdbcBorrowerRoleContext(false);

        try (JdbcBorrowerRoleContext.Scope ignored = context.openRoot(JdbcBorrowerRoleContext.HTTP_SEATS)) {
            assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.acquisitionRole());
            assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.holdRole());
        }
    }

    @Test
    void nestedPhaseAffectsAcquisitionButHoldUsesStableRootRole() {
        JdbcBorrowerRoleContext context = new JdbcBorrowerRoleContext(true);

        try (JdbcBorrowerRoleContext.Scope ignored = context.openRoot(JdbcBorrowerRoleContext.HTTP_RESERVATION)) {
            assertEquals(JdbcBorrowerRoleContext.HTTP_RESERVATION, context.acquisitionRole());
            assertEquals(JdbcBorrowerRoleContext.HTTP_RESERVATION, context.holdRole());

            try (JdbcBorrowerRoleContext.Scope phase = context.openPhase(JdbcBorrowerRoleContext.JWT_USER_LOOKUP)) {
                assertEquals(JdbcBorrowerRoleContext.JWT_USER_LOOKUP, context.acquisitionRole());
                assertEquals(JdbcBorrowerRoleContext.HTTP_RESERVATION, context.holdRole());
            }

            assertEquals(JdbcBorrowerRoleContext.HTTP_RESERVATION, context.acquisitionRole());
        }

        assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.acquisitionRole());
        assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.holdRole());
    }

    @Test
    void nestedRootClearsAndThenRestoresAnOuterPhase() {
        JdbcBorrowerRoleContext context = new JdbcBorrowerRoleContext(true);

        try (JdbcBorrowerRoleContext.Scope phase = context.openPhase(JdbcBorrowerRoleContext.JWT_USER_LOOKUP)) {
            try (JdbcBorrowerRoleContext.Scope root = context.openRoot(JdbcBorrowerRoleContext.OUTBOX_RELAY)) {
                assertEquals(JdbcBorrowerRoleContext.OUTBOX_RELAY, context.acquisitionRole());
                assertEquals(JdbcBorrowerRoleContext.OUTBOX_RELAY, context.holdRole());
            }

            assertEquals(JdbcBorrowerRoleContext.JWT_USER_LOOKUP, context.acquisitionRole());
            assertEquals(JdbcBorrowerRoleContext.JWT_USER_LOOKUP, context.holdRole());
        }

        assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.acquisitionRole());
    }

    @Test
    void unknownRolesCollapseToBoundedUnknownTag() {
        JdbcBorrowerRoleContext context = new JdbcBorrowerRoleContext(true);

        try (JdbcBorrowerRoleContext.Scope ignored = context.openRoot("/api/v1/users/12345")) {
            assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.acquisitionRole());
            assertEquals(JdbcBorrowerRoleContext.UNKNOWN, context.holdRole());
        }
    }
}
