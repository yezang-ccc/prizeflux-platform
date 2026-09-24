package com.prizeflux.worker.infrastructure.fulfillment;

import com.prizeflux.worker.application.FulfillmentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local demonstration adapter only; it records no real external entitlement or shipment. */
@Component
@ConditionalOnProperty(name = "prizeflux.worker.fulfillment.mode", havingValue = "simulated")
public class SimulatedFulfillmentExecutor implements FulfillmentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SimulatedFulfillmentExecutor.class);

    @Override
    public void execute(String fulfillmentId, String eventPayload) {
        log.info("simulated fulfillment completed fulfillmentId={}", fulfillmentId);
    }
}
