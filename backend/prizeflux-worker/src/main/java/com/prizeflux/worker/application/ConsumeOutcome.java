package com.prizeflux.worker.application;

/** 一次事件消费的结果：新落账，或重复投递/重复中奖结果的幂等跳过。 */
public enum ConsumeOutcome {
    PROCESSED,
    DUPLICATE
}
