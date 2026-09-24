-- Worker 自己的 Flyway 历史（与 Campaign 各自独立）。
-- 两条表在同一个消费用例事务内写入；唯一键共同保证"至少一次投递"下的幂等：
--   * processed_event.event_id —— 同一事件重复投递只记一次。
--   * fulfillment.event_id      —— 账本按事件去重（与处理记录一一对应）。
--   * fulfillment.draw_id       —— 同一中奖结果不重复发奖（防御不同 eventId 的重放）。

CREATE TABLE processed_event (
    event_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumed_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE fulfillment (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    event_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draw_id     BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    prize_id    BIGINT NOT NULL,
    status      VARCHAR(16) NOT NULL,
    payload     JSON NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_fulfillment PRIMARY KEY (id),
    CONSTRAINT uq_fulfillment_event UNIQUE (event_id),
    CONSTRAINT uq_fulfillment_draw UNIQUE (draw_id),
    CONSTRAINT chk_fulfillment_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'SUCCEEDED', 'RETRYING', 'MANUAL_REVIEW')),
    CONSTRAINT chk_fulfillment_draw_positive CHECK (draw_id > 0),
    CONSTRAINT chk_fulfillment_campaign_positive CHECK (campaign_id > 0),
    CONSTRAINT chk_fulfillment_user_positive CHECK (user_id > 0),
    CONSTRAINT chk_fulfillment_prize_positive CHECK (prize_id > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
