package com.prizeflux.contract.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FulfillmentStateMachineTest {
    @Test
    void allowsOnlyDocumentedTransitions() {
        assertTransition(FulfillmentStatus.PENDING, FulfillmentStatus.PROCESSING);
        assertTransition(FulfillmentStatus.PROCESSING, FulfillmentStatus.SUCCEEDED);
        assertTransition(FulfillmentStatus.PROCESSING, FulfillmentStatus.RETRYING);
        assertTransition(FulfillmentStatus.PROCESSING, FulfillmentStatus.MANUAL_REVIEW);
        assertTransition(FulfillmentStatus.RETRYING, FulfillmentStatus.PROCESSING);
        assertTransition(FulfillmentStatus.RETRYING, FulfillmentStatus.MANUAL_REVIEW);
        assertTransition(FulfillmentStatus.MANUAL_REVIEW, FulfillmentStatus.PROCESSING);
        assertTransition(FulfillmentStatus.MANUAL_REVIEW, FulfillmentStatus.SUCCEEDED);

        assertFalse(FulfillmentStateMachine.canTransition(null, FulfillmentStatus.PENDING));
        assertFalse(FulfillmentStateMachine.canTransition(FulfillmentStatus.PENDING, null));
        assertTrue(FulfillmentStateMachine.isTerminal(FulfillmentStatus.SUCCEEDED));
        for (FulfillmentStatus state : FulfillmentStatus.values()) {
            if (state != FulfillmentStatus.SUCCEEDED) {
                assertFalse(FulfillmentStateMachine.isTerminal(state));
            }
            assertFalse(FulfillmentStateMachine.canTransition(state, state));
        }
        assertThrows(IllegalArgumentException.class, () -> FulfillmentStateMachine.transition(
                FulfillmentStatus.SUCCEEDED, FulfillmentStatus.PROCESSING));
    }

    private static void assertTransition(FulfillmentStatus from, FulfillmentStatus to) {
        assertTrue(FulfillmentStateMachine.canTransition(from, to));
        assertEquals(to, FulfillmentStateMachine.transition(from, to));
    }
}
