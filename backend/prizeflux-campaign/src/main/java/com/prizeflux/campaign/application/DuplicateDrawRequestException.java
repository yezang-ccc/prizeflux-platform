package com.prizeflux.campaign.application;

/** 数据库唯一键抢占失败；外层在事务结束后读取赢家结果。 */
public final class DuplicateDrawRequestException extends RuntimeException {
    public DuplicateDrawRequestException(Throwable cause) {
        super("Duplicate draw request", cause);
    }
}
