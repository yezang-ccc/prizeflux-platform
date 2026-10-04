package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.DrawCommand;
import com.prizeflux.campaign.application.DrawQueueApplicationService;
import com.prizeflux.campaign.controller.dto.DrawQueueResponse;
import com.prizeflux.campaign.controller.dto.DrawRequest;
import com.prizeflux.contract.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 高峰异步开奖入口；只表达已持久化受理，不在 HTTP 请求线程内开奖。 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = {"prizeflux.persistence.campaign.enabled", "prizeflux.kafka.draw-queue-enabled"},
        havingValue = "true")
public final class DrawQueueController {
    private final DrawQueueApplicationService draws;

    public DrawQueueController(DrawQueueApplicationService draws) {
        this.draws = draws;
    }

    @PostMapping("/draw-queue")
    public ResponseEntity<ApiResponse<DrawQueueResponse>> enqueue(@Valid @RequestBody DrawRequest request) {
        var queued = draws.enqueue(new DrawCommand(request.campaignId(), request.requestKey()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(DrawQueueResponse.from(queued)));
    }

    @GetMapping("/me/draw-queue/{requestKey}")
    public ApiResponse<DrawQueueResponse> status(@PathVariable String requestKey) {
        return ApiResponse.success(DrawQueueResponse.from(draws.status(requestKey)));
    }
}
