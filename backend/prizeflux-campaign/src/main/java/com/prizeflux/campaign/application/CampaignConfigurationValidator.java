package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.draw.DrawCandidate;
import com.prizeflux.campaign.domain.draw.LinearWeightDrawStrategy;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.campaign.domain.model.Probability;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/** 共享草稿保存、提审和发布边界的奖品概率/库存规则。 */
public final class CampaignConfigurationValidator {
    private CampaignConfigurationValidator() {
    }

    public static void validate(long campaignId, int dailyLimit, List<Prize> prizes) {
        if (campaignId <= 0 || dailyLimit <= 0 || prizes == null || prizes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        List<DrawCandidate> candidates = new ArrayList<>(prizes.size());
        for (Prize prize : prizes) {
            if (prize == null || prize.campaignId() != campaignId) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            candidates.add(new DrawCandidate(prize.id(), Probability.of(prize.probability())));
        }
        new LinearWeightDrawStrategy(candidates);
    }
}
