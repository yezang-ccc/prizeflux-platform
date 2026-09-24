package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.DrawApplicationService;
import com.prizeflux.campaign.controller.dto.DrawHistoryItem;
import com.prizeflux.campaign.controller.dto.DrawHistoryResponse;
import com.prizeflux.contract.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人抽奖历史 HTTP 适配器（只读）。身份由 {@code DrawApplicationService} 经 {@code ActorProvider}
 * 从安全上下文读取，不接受任何客户端身份参数；游标分页用稳定主键 {@code draw_result.id}。
 */
@RestController
@RequestMapping("/api/me/draws")
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class DrawHistoryController {
    private final DrawApplicationService drawApplicationService;

    public DrawHistoryController(DrawApplicationService drawApplicationService) {
        this.drawApplicationService = drawApplicationService;
    }

    /** 返回当前用户抽奖历史（按时间倒序）；{@code before} 为上一页返回的 {@code nextCursor}，缺省取首页。 */
    @GetMapping
    public ApiResponse<DrawHistoryResponse> list(
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        long beforeId = before == null ? Long.MAX_VALUE : before;
        return ApiResponse.success(DrawHistoryResponse.from(
                drawApplicationService.history(beforeId, limit)));
    }

    /** 返回当前用户单条抽奖明细（含发奖状态）；非本人记录返回 404。 */
    @GetMapping("/{id}")
    public ApiResponse<DrawHistoryItem> detail(@PathVariable long id) {
        return ApiResponse.success(DrawHistoryItem.from(drawApplicationService.historyEntry(id)));
    }
}
