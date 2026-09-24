package com.prizeflux.ai.infrastructure.session;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.session.ConversationSnapshot;
import com.prizeflux.ai.harness.session.SessionCapacityException;
import com.prizeflux.ai.harness.session.SessionConflictException;
import com.prizeflux.ai.harness.session.SessionId;
import com.prizeflux.ai.harness.session.SessionNotFoundException;
import com.prizeflux.ai.harness.session.SessionPolicy;
import com.prizeflux.ai.harness.session.SessionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/** Single-process adapter. Data is intentionally ephemeral and never written to disk. */
public final class InMemorySessionStore implements SessionStore {
    private final SessionPolicy policy;
    private final Map<SessionId, ConversationSnapshot> sessions = new HashMap<>();

    public InMemorySessionStore(SessionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public synchronized ConversationSnapshot create(long ownerUserId, Instant now) {
        requireOwner(ownerUserId);
        Objects.requireNonNull(now, "now");
        purgeExpired(now);
        if (sessions.size() >= policy.maxSessionsTotal()) {
            throw new SessionCapacityException();
        }
        long count = sessions.values().stream().filter(session -> session.ownerUserId() == ownerUserId).count();
        if (count >= policy.maxSessionsPerOwner()) {
            throw new SessionCapacityException();
        }
        SessionId id = SessionId.random();
        ConversationSnapshot created = new ConversationSnapshot(id, ownerUserId, 0, now, now, List.of());
        sessions.put(id, created);
        return created;
    }

    @Override
    public synchronized Optional<ConversationSnapshot> findOwned(SessionId sessionId, long ownerUserId, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireOwner(ownerUserId);
        Objects.requireNonNull(now, "now");
        purgeExpired(now);
        ConversationSnapshot session = sessions.get(sessionId);
        return session != null && session.ownerUserId() == ownerUserId ? Optional.of(session) : Optional.empty();
    }

    @Override
    public synchronized ConversationSnapshot appendOwned(SessionId sessionId, long ownerUserId,
                                                          long expectedVersion, List<AgentMessage> events,
                                                          Instant now) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(now, "now");
        ConversationSnapshot current = findOwned(sessionId, ownerUserId, now)
                .orElseThrow(SessionNotFoundException::new);
        if (current.version() != expectedVersion) {
            throw new SessionConflictException();
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("cannot append an empty event batch");
        }
        List<AgentMessage> appended = new ArrayList<>(current.events());
        appended.addAll(events);
        ConversationSnapshot replacement = new ConversationSnapshot(sessionId, ownerUserId,
                current.version() + 1, current.createdAt(), now, appended);
        if (replacement.events().size() > policy.maxMessagesPerSession()
                || replacement.characterCount() > policy.maxCharactersPerSession()) {
            throw new SessionCapacityException();
        }
        sessions.put(sessionId, replacement);
        return replacement;
    }

    @Override
    public synchronized boolean deleteOwned(SessionId sessionId, long ownerUserId, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireOwner(ownerUserId);
        Objects.requireNonNull(now, "now");
        purgeExpired(now);
        ConversationSnapshot current = sessions.get(sessionId);
        if (current == null || current.ownerUserId() != ownerUserId) {
            return false;
        }
        sessions.remove(sessionId);
        return true;
    }

    private void purgeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().updatedAt().plus(policy.idleTtl())));
    }

    private static void requireOwner(long ownerUserId) {
        if (ownerUserId <= 0) {
            throw new IllegalArgumentException("owner user id must be positive");
        }
    }
}
