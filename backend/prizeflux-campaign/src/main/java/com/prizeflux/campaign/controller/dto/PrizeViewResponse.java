package com.prizeflux.campaign.controller.dto;

import java.math.BigDecimal;

/** 给用户展示的奖品信息，不含总库存或剩余库存。 */
public record PrizeViewResponse(long id, String name, BigDecimal probability) {
}
