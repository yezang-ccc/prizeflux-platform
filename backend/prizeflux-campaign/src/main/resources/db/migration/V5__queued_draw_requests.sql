CREATE TABLE draw_queue_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    draw_result_id BIGINT NULL,
    error_code VARCHAR(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    CONSTRAINT pk_draw_queue_request PRIMARY KEY (id),
    CONSTRAINT uq_draw_queue_user_key UNIQUE (user_id, request_key),
    CONSTRAINT fk_draw_queue_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_draw_queue_campaign FOREIGN KEY (campaign_id) REFERENCES campaign(id),
    CONSTRAINT fk_draw_queue_result FOREIGN KEY (draw_result_id) REFERENCES draw_result(id),
    CONSTRAINT chk_draw_queue_user_positive CHECK (user_id > 0),
    CONSTRAINT chk_draw_queue_campaign_positive CHECK (campaign_id > 0),
    CONSTRAINT chk_draw_queue_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'REJECTED')),
    CONSTRAINT chk_draw_queue_terminal CHECK (
        (status IN ('QUEUED', 'PROCESSING') AND completed_at IS NULL AND draw_result_id IS NULL AND error_code IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND draw_result_id IS NOT NULL AND error_code IS NULL)
        OR (status = 'REJECTED' AND completed_at IS NOT NULL AND draw_result_id IS NULL AND error_code IS NOT NULL)),
    CONSTRAINT uq_draw_queue_result UNIQUE (draw_result_id),
    INDEX idx_draw_queue_status_created (status, created_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE draw_request DROP CHECK chk_draw_operation;
ALTER TABLE draw_request ADD CONSTRAINT chk_draw_operation
    CHECK (operation IN ('DRAW', 'FLASH_CLAIM', 'QUEUED_DRAW'));
