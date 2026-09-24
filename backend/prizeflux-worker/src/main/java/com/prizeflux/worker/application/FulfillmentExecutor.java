package com.prizeflux.worker.application;

/** Port for an idempotent fulfillment side effect. The stable fulfillment ID is sent on every retry. */
public interface FulfillmentExecutor {
    void execute(String fulfillmentId, String eventPayload) throws FulfillmentExecutionException;
}
