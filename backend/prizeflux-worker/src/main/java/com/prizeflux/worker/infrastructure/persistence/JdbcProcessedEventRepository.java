package com.prizeflux.worker.infrastructure.persistence;

import com.prizeflux.worker.application.ProcessedEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** 处理标记须与新账本同事务成功；重复或非法标记应让事务回滚。 */
@Repository
public class JdbcProcessedEventRepository implements ProcessedEventRepository {
    private final JdbcTemplate jdbc;

    public JdbcProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void mark(String eventId, Instant consumedAt) {
        jdbc.update("INSERT INTO processed_event (event_id, consumed_at) VALUES (?, ?)",
                eventId, Timestamp.from(consumedAt));
    }
}
