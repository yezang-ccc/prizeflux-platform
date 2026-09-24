package com.prizeflux.campaign.controller.dto;

import com.prizeflux.campaign.application.DrawHistoryPage;

import java.util.List;

/** 个人历史分页响应；{@code nextCursor} 为空表示没有更多，作为下一次 {@code before} 传入。 */
public record DrawHistoryResponse(List<DrawHistoryItem> items, Long nextCursor) {

    public static DrawHistoryResponse from(DrawHistoryPage page) {
        List<DrawHistoryItem> items = page.items().stream().map(DrawHistoryItem::from).toList();
        return new DrawHistoryResponse(items, page.nextCursor());
    }
}
