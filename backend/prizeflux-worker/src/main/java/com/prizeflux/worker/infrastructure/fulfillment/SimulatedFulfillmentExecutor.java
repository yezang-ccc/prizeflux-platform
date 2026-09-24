package com.prizeflux.worker.infrastructure.fulfillment;

import com.prizeflux.worker.application.FulfillmentExecutor;
import com.prizeflux.worker.application.FulfillmentExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Local demonstration adapter only; it records no real external entitlement or shipment. */
@Component
@ConditionalOnProperty(name = "prizeflux.worker.fulfillment.mode", havingValue = "simulated")
public class SimulatedFulfillmentExecutor implements FulfillmentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SimulatedFulfillmentExecutor.class);
    private final boolean failFirstAttempt;
    private final Set<String> failedFulfillments = ConcurrentHashMap.newKeySet();

    public SimulatedFulfillmentExecutor(
            @Value("${prizeflux.worker.fulfillment.simulated-fail-first-attempt:false}") boolean failFirstAttempt) {
        this.failFirstAttempt = failFirstAttempt;
    }

    @Override
    public void execute(String fulfillmentId, String eventPayload) {
        if (failFirstAttempt && failedFulfillments.add(fulfillmentId)) {
            log.warn("simulated transient fulfillment failure fulfillmentId={}", fulfillmentId);
            throw new FulfillmentExecutionException("SIMULATED_TRANSIENT_FAILURE", false);
        }
        log.info("simulated fulfillment completed fulfillmentId={}", fulfillmentId);
    }
}
