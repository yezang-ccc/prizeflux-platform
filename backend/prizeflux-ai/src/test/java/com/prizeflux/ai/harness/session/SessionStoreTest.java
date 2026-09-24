package com.prizeflux.ai.harness.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.infrastructure.session.InMemorySessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Test
    void createsOwnerScopedImmutableSessionAndAppendsByVersion() {
        InMemorySessionStore store = store(3, 10, 100, Duration.ofMinutes(5));
        ConversationSnapshot created = store.create(7, NOW);

        assertThat(created.sessionId().toString()).matches("[0-9a-f-]{36}");
        assertThat(created.ownerUserId()).isEqualTo(7);
        assertThat(created.version()).isZero();
        assertThat(store.findOwned(created.sessionId(), 8, NOW)).isEmpty();

        ConversationSnapshot appended = store.appendOwned(created.sessionId(), 7, 0,
                List.of(AgentMessage.user("one question")), NOW.plusSeconds(1));
        assertThat(appended.version()).isEqualTo(1);
        assertThat(appended.events()).containsExactly(AgentMessage.user("one question"));
        assertThat(created.events()).isEmpty();
    }

    @Test
    void rejectsStaleVersionAndDoesNotOverwriteNewerEvents() {
        InMemorySessionStore store = store(3, 10, 100, Duration.ofMinutes(5));
        ConversationSnapshot session = store.create(7, NOW);
        store.appendOwned(session.sessionId(), 7, 0, List.of(AgentMessage.user("first")), NOW);

        assertThatThrownBy(() -> store.appendOwned(session.sessionId(), 7, 0,
                List.of(AgentMessage.user("stale")), NOW.plusSeconds(1)))
                .isInstanceOf(SessionConflictException.class);
        assertThat(store.findOwned(session.sessionId(), 7, NOW.plusSeconds(1)).orElseThrow().events())
                .containsExactly(AgentMessage.user("first"));
    }

    @Test
    void onlyOneConcurrentAppendCanCommitForSameVersion() throws Exception {
        InMemorySessionStore store = store(3, 10, 100, Duration.ofMinutes(5));
        ConversationSnapshot session = store.create(7, NOW);
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Object>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(index -> pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            store.appendOwned(session.sessionId(), 7, 0,
                                    List.of(AgentMessage.user("request-" + index)), NOW.plusSeconds(1));
                            committed.incrementAndGet();
                        } catch (SessionConflictException ignored) {
                            // Optimistic concurrency permits exactly one winner for this version.
                        }
                        return null;
                    })).toList();
            ready.await();
            start.countDown();
            for (Future<?> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(committed).hasValue(1);
        assertThat(store.findOwned(session.sessionId(), 7, NOW.plusSeconds(1)).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void expiresIdleSessionsAndEnforcesPerOwnerAndTranscriptCaps() {
        InMemorySessionStore store = store(1, 2, 10, Duration.ofSeconds(30));
        ConversationSnapshot first = store.create(7, NOW);
        assertThatThrownBy(() -> store.create(7, NOW.plusSeconds(1)))
                .isInstanceOf(SessionCapacityException.class);
        assertThat(store.create(8, NOW)).isNotNull();
        assertThat(store.findOwned(first.sessionId(), 7, NOW.plusSeconds(30))).isEmpty();
        ConversationSnapshot replacement = store.create(7, NOW.plusSeconds(31));
        assertThat(replacement.sessionId()).isNotEqualTo(first.sessionId());

        store.appendOwned(replacement.sessionId(), 7, 0, List.of(AgentMessage.user("1234567890")),
                NOW.plusSeconds(32));
        assertThatThrownBy(() -> store.appendOwned(replacement.sessionId(), 7, 1,
                List.of(AgentMessage.answer("x")), NOW.plusSeconds(33)))
                .isInstanceOf(SessionCapacityException.class);
    }

    @Test
    void enforcesGlobalSessionCapAcrossOwners() {
        InMemorySessionStore store = new InMemorySessionStore(
                new SessionPolicy(2, 2, 10, 100, Duration.ofMinutes(5)));
        store.create(7, NOW);
        store.create(8, NOW);

        assertThatThrownBy(() -> store.create(9, NOW.plusSeconds(1)))
                .isInstanceOf(SessionCapacityException.class);
    }

    private static InMemorySessionStore store(int maxPerOwner, int maxMessages, int maxCharacters, Duration ttl) {
        return new InMemorySessionStore(new SessionPolicy(maxPerOwner, 100, maxMessages, maxCharacters, ttl));
    }
}
