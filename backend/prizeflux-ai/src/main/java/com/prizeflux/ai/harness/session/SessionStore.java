package com.prizeflux.ai.harness.session;

import com.prizeflux.ai.harness.model.AgentMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Application-facing owner-scoped append-only conversation store. */
public interface SessionStore {
    ConversationSnapshot create(long ownerUserId, Instant now);

    Optional<ConversationSnapshot> findOwned(SessionId sessionId, long ownerUserId, Instant now);

    ConversationSnapshot appendOwned(SessionId sessionId, long ownerUserId, long expectedVersion,
                                     List<AgentMessage> events, Instant now);

    boolean deleteOwned(SessionId sessionId, long ownerUserId, Instant now);
}
