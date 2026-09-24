package com.prizeflux.ai.api;

import com.prizeflux.ai.application.AiCampaignService;
import com.prizeflux.ai.harness.context.ContextWindow;
import com.prizeflux.ai.harness.context.ContextWindowBuilder;
import com.prizeflux.ai.harness.context.ContextLimitExceededException;
import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.Role;
import com.prizeflux.ai.harness.session.ConversationSnapshot;
import com.prizeflux.ai.harness.session.SessionCapacityException;
import com.prizeflux.ai.harness.session.SessionConflictException;
import com.prizeflux.ai.harness.session.SessionId;
import com.prizeflux.ai.harness.session.SessionNotFoundException;
import com.prizeflux.ai.harness.session.SessionStore;
import com.prizeflux.ai.infrastructure.campaign.CampaignIdentityClient;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Owner-scoped orchestration. Identity is revalidated at Campaign for every request. */
@Service
public class AiConversationService {
    private final CampaignIdentityClient identities;
    private final AiCampaignService campaigns;
    private final SessionStore sessions;
    private final ContextWindowBuilder windows;
    private final Clock clock;

    public AiConversationService(CampaignIdentityClient identities, AiCampaignService campaigns,
                                 SessionStore sessions, ContextWindowBuilder windows, Clock clock) {
        this.identities = identities;
        this.campaigns = campaigns;
        this.sessions = sessions;
        this.windows = windows;
        this.clock = clock;
    }

    public AiSessionCreated create(String token) {
        Actor actor = operator(token);
        try {
            ConversationSnapshot session = sessions.create(actor.userId(), clock.instant());
            return new AiSessionCreated(session.sessionId().toString(), session.version(), session.createdAt());
        } catch (SessionCapacityException exception) {
            throw new AiRequestException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    public AiSessionView read(String rawSessionId, String token) {
        Actor actor = operator(token);
        ConversationSnapshot session = findOwned(rawSessionId, actor.userId());
        List<AiSessionEventView> events = session.events().stream()
                .map(message -> new AiSessionEventView(message.kind().name(), message.content(),
                        message.call() == null ? null : message.call().name(), message.toolSuccess()))
                .toList();
        return new AiSessionView(session.sessionId().toString(), session.version(), session.createdAt(),
                session.updatedAt(), events);
    }

    public AiSessionMessageResponse send(String rawSessionId, AiSessionMessageRequest request, String token) {
        Actor actor = operator(token);
        SessionId sessionId = parseId(rawSessionId);
        ConversationSnapshot before = sessions.findOwned(sessionId, actor.userId(), clock.instant())
                .orElseThrow(() -> new AiRequestException(HttpStatus.NOT_FOUND));
        if (before.version() != request.expectedVersion()) {
            throw new AiRequestException(HttpStatus.CONFLICT);
        }

        ContextWindow window;
        try {
            window = windows.build(before.events(), request.question());
        } catch (ContextLimitExceededException | IllegalArgumentException exception) {
            throw new AiRequestException(HttpStatus.BAD_REQUEST);
        }
        var modelRequest = new AiCampaignRequest(request.campaignId(), request.question());
        var run = campaigns.runSummary(modelRequest, token, actor, window.messages());
        AiCampaignResponse result = campaigns.response(run);
        int firstNewMessage = window.currentMessageIndex();
        List<AgentMessage> newEvents = run.transcript().subList(firstNewMessage, run.transcript().size());
        try {
            ConversationSnapshot after = sessions.appendOwned(sessionId, actor.userId(), request.expectedVersion(),
                    newEvents, clock.instant());
            return new AiSessionMessageResponse(sessionId.toString(), after.version(),
                    window.omittedMessageCount(), result);
        } catch (SessionConflictException exception) {
            throw new AiRequestException(HttpStatus.CONFLICT);
        } catch (SessionNotFoundException exception) {
            throw new AiRequestException(HttpStatus.NOT_FOUND);
        } catch (SessionCapacityException exception) {
            throw new AiRequestException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    public void delete(String rawSessionId, String token) {
        Actor actor = operator(token);
        SessionId sessionId = parseId(rawSessionId);
        if (!sessions.deleteOwned(sessionId, actor.userId(), clock.instant())) {
            throw new AiRequestException(HttpStatus.NOT_FOUND);
        }
    }

    private Actor operator(String token) {
        Actor actor = identities.verify(token);
        if (!actor.role().allows(Role.OPERATOR)) {
            throw new AiRequestException(HttpStatus.FORBIDDEN);
        }
        return actor;
    }

    private ConversationSnapshot findOwned(String rawSessionId, long ownerUserId) {
        SessionId sessionId = parseId(rawSessionId);
        return sessions.findOwned(sessionId, ownerUserId, clock.instant())
                .orElseThrow(() -> new AiRequestException(HttpStatus.NOT_FOUND));
    }

    private SessionId parseId(String rawSessionId) {
        try {
            return SessionId.parse(rawSessionId);
        } catch (IllegalArgumentException exception) {
            throw new AiRequestException(HttpStatus.BAD_REQUEST);
        }
    }
}
