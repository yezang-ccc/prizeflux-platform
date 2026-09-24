package com.prizeflux.campaign.application;

import java.util.List;

/** 基于稳定主键游标的个人历史分页结果；{@code nextCursor} 为空表示没有更多。 */
public record DrawHistoryPage(List<DrawHistoryEntry> items, Long nextCursor) {
}
