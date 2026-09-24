package com.prizeflux.campaign.application;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

/** 用户请求只携带活动和重试键；用户 ID 从 ActorProvider 获取。 */
public record DrawCommand(long campaignId, String requestKey) {
    public DrawCommand {
        if (campaignId <= 0 || requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
