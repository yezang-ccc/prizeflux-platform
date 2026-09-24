ALTER TABLE fulfillment
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER attempt_count,
    ADD COLUMN lease_token VARCHAR(36) NULL AFTER next_attempt_at,
    ADD COLUMN lease_until DATETIME(6) NULL AFTER lease_token,
    ADD COLUMN last_error_code VARCHAR(64) NULL AFTER lease_until,
    ADD INDEX idx_fulfillment_due (status, next_attempt_at, id),
    ADD INDEX idx_fulfillment_expired_lease (status, lease_until, id);

CREATE TABLE fulfillment_compensation_task (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    fulfillment_id  BIGINT NOT NULL,
    draw_id         BIGINT NOT NULL,
    reason_code     VARCHAR(64) NOT NULL,
    state           VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at      DATETIME(6) NOT NULL,
    resolved_at     DATETIME(6) NULL,
    CONSTRAINT pk_fulfillment_compensation_task PRIMARY KEY (id),
    CONSTRAINT uq_fulfillment_compensation_fulfillment UNIQUE (fulfillment_id),
    CONSTRAINT chk_fulfillment_compensation_state CHECK (state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT fk_fulfillment_compensation_fulfillment FOREIGN KEY (fulfillment_id) REFERENCES fulfillment (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
