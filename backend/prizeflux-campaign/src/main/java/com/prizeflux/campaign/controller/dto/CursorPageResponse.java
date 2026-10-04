package com.prizeflux.campaign.controller.dto;

import java.util.List;

/** 稳定游标列表响应。 */
public record CursorPageResponse<T>(List<T> items, Long nextCursor) {
    public CursorPageResponse {
        items = List.copyOf(items);
    }
}
