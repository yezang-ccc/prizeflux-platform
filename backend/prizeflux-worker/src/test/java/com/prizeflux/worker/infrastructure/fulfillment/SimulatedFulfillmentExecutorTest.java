package com.prizeflux.worker.infrastructure.fulfillment;

import com.prizeflux.worker.application.FulfillmentExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulatedFulfillmentExecutorTest {

    @Test
    void canInjectOneTransientFailureThenSucceedForTheSameStableId() {
        var executor = new SimulatedFulfillmentExecutor(true);

        var failure = assertThrows(FulfillmentExecutionException.class,
                () -> executor.execute("draw-42", "{}"));

        assertEquals("SIMULATED_TRANSIENT_FAILURE", failure.errorCode());
        assertDoesNotThrow(() -> executor.execute("draw-42", "{}"));
    }

    @Test
    void failureInjectionIsOffByDefault() {
        var executor = new SimulatedFulfillmentExecutor(false);

        assertDoesNotThrow(() -> executor.execute("draw-43", "{}"));
    }
}
