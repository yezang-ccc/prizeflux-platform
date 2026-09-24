package com.prizeflux.ai.api;

import com.prizeflux.contract.api.ApiResponse;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/sessions")
public class AiSessionController {
    private final AiConversationService conversations;

    public AiSessionController(AiConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AiSessionCreated>> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        AiSessionCreated created = conversations.create(AiBearerToken.parse(authorization));
        return ResponseEntity.created(URI.create("/api/ai/sessions/" + created.sessionId()))
                .body(ApiResponse.success(created));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<AiSessionMessageResponse> send(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AiSessionMessageRequest request) {
        return ApiResponse.success(conversations.send(sessionId, request, AiBearerToken.parse(authorization)));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<AiSessionView> read(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.success(conversations.read(sessionId, AiBearerToken.parse(authorization)));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> delete(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        conversations.delete(sessionId, AiBearerToken.parse(authorization));
        return ApiResponse.success(null);
    }
}
