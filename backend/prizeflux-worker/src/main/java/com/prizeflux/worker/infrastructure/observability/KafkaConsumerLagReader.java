package com.prizeflux.worker.infrastructure.observability;

/** Reads lag for the single configured Worker consumer group and fulfillment topic. */
interface KafkaConsumerLagReader {
    long readLag() throws Exception;
}
