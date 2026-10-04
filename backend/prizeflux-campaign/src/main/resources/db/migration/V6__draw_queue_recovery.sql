ALTER TABLE draw_queue_request
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER error_code,
    ADD COLUMN last_error_code VARCHAR(16) NULL AFTER attempts,
    ADD COLUMN claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER last_error_code,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER claim_token,
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER lease_expires_at;

ALTER TABLE draw_queue_request DROP CHECK chk_draw_queue_status;
ALTER TABLE draw_queue_request ADD CONSTRAINT chk_draw_queue_status
    CHECK (status IN ('QUEUED', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'REJECTED', 'MANUAL_REVIEW'));

ALTER TABLE draw_queue_request DROP CHECK chk_draw_queue_terminal;
ALTER TABLE draw_queue_request ADD CONSTRAINT chk_draw_queue_terminal
    CHECK (
        (status IN ('QUEUED', 'RETRY_WAIT') AND completed_at IS NULL AND draw_result_id IS NULL
            AND error_code IS NULL AND claim_token IS NULL AND lease_expires_at IS NULL)
        OR (status = 'PROCESSING' AND completed_at IS NULL AND draw_result_id IS NULL
            AND error_code IS NULL AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND draw_result_id IS NOT NULL
            AND error_code IS NULL AND claim_token IS NULL AND lease_expires_at IS NULL)
        OR (status = 'REJECTED' AND completed_at IS NOT NULL AND draw_result_id IS NULL
            AND error_code IS NOT NULL AND claim_token IS NULL AND lease_expires_at IS NULL)
        OR (status = 'MANUAL_REVIEW' AND completed_at IS NULL AND draw_result_id IS NULL
            AND error_code IS NOT NULL AND claim_token IS NULL AND lease_expires_at IS NULL)
    );

ALTER TABLE draw_queue_request
    ADD CONSTRAINT chk_draw_queue_attempts CHECK (attempts >= 0),
    ADD INDEX idx_draw_queue_due (status, next_attempt_at, lease_expires_at, id);
