package com.prizeflux.campaign.infrastructure.observability;

/** Reads broker offsets for the configured draw notification topic and consumer group. */
interface DrawQueueKafkaLagReader {
    long readLag() throws Exception;
}
