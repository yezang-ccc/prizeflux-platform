CREATE TABLE campaign_config_version (
    campaign_id BIGINT NOT NULL,
    config_version INT NOT NULL,
    daily_limit INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    CONSTRAINT pk_campaign_config_version PRIMARY KEY (campaign_id, config_version),
    CONSTRAINT fk_config_campaign FOREIGN KEY (campaign_id) REFERENCES campaign(id),
    CONSTRAINT chk_config_version_positive CHECK (config_version > 0),
    CONSTRAINT chk_config_daily_limit CHECK (daily_limit > 0),
    CONSTRAINT chk_config_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE prize_config (
    campaign_id BIGINT NOT NULL,
    config_version INT NOT NULL,
    prize_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    probability DECIMAL(7, 6) NOT NULL,
    total_stock INT NOT NULL,
    CONSTRAINT pk_prize_config PRIMARY KEY (campaign_id, config_version, prize_id),
    CONSTRAINT fk_prize_config_version FOREIGN KEY (campaign_id, config_version)
        REFERENCES campaign_config_version(campaign_id, config_version),
    CONSTRAINT chk_prize_id_positive CHECK (prize_id > 0),
    CONSTRAINT chk_prize_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_prize_probability CHECK (probability >= 0 AND probability <= 1),
    CONSTRAINT chk_prize_total_stock CHECK (total_stock >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE prize_inventory (
    campaign_id BIGINT NOT NULL,
    config_version INT NOT NULL,
    prize_id BIGINT NOT NULL,
    remaining_stock INT NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_prize_inventory PRIMARY KEY (campaign_id, config_version, prize_id),
    CONSTRAINT fk_inventory_prize FOREIGN KEY (campaign_id, config_version, prize_id)
        REFERENCES prize_config(campaign_id, config_version, prize_id),
    CONSTRAINT chk_inventory_nonnegative CHECK (remaining_stock >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE draw_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation VARCHAR(16) NOT NULL,
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    config_version INT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    CONSTRAINT pk_draw_request PRIMARY KEY (id),
    CONSTRAINT uq_draw_request_key UNIQUE (operation, user_id, request_key),
    CONSTRAINT fk_draw_request_config FOREIGN KEY (campaign_id, config_version)
        REFERENCES campaign_config_version(campaign_id, config_version),
    CONSTRAINT chk_draw_user_positive CHECK (user_id > 0),
    CONSTRAINT chk_draw_operation CHECK (operation IN ('DRAW', 'FLASH_CLAIM')),
    CONSTRAINT chk_draw_request_status CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT chk_draw_request_completion CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE draw_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    config_version INT NOT NULL,
    user_id BIGINT NOT NULL,
    prize_id BIGINT NULL,
    outcome VARCHAR(16) NOT NULL,
    fulfillment_status VARCHAR(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_draw_result PRIMARY KEY (id),
    CONSTRAINT uq_draw_result_request UNIQUE (request_id),
    CONSTRAINT fk_draw_result_request FOREIGN KEY (request_id) REFERENCES draw_request(id),
    CONSTRAINT fk_draw_result_prize FOREIGN KEY (campaign_id, config_version, prize_id)
        REFERENCES prize_config(campaign_id, config_version, prize_id),
    CONSTRAINT chk_draw_result_outcome CHECK (outcome IN ('NO_PRIZE', 'SOLD_OUT', 'WON')),
    CONSTRAINT chk_draw_result_prize CHECK (
        (outcome = 'NO_PRIZE' AND prize_id IS NULL)
        OR (outcome IN ('SOLD_OUT', 'WON') AND prize_id IS NOT NULL)),
    CONSTRAINT chk_draw_fulfillment_status CHECK (
        (outcome <> 'WON' AND fulfillment_status IS NULL)
        OR (outcome = 'WON' AND fulfillment_status IN
            ('PENDING', 'PROCESSING', 'SUCCEEDED', 'RETRYING', 'MANUAL_REVIEW'))),
    INDEX idx_draw_history (user_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_campaign_quota (
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    business_day DATE NOT NULL,
    used_count INT NOT NULL,
    CONSTRAINT pk_user_campaign_quota PRIMARY KEY (campaign_id, user_id, business_day),
    CONSTRAINT fk_quota_campaign FOREIGN KEY (campaign_id) REFERENCES campaign(id),
    CONSTRAINT chk_quota_user_positive CHECK (user_id > 0),
    CONSTRAINT chk_quota_count_nonnegative CHECK (used_count >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    available_at DATETIME(6) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id),
    CONSTRAINT chk_outbox_schema_version CHECK (schema_version > 0),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'LEASED', 'SENT', 'DEAD')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_lease CHECK (
        (status = 'LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'LEASED' AND lease_token IS NULL AND lease_until IS NULL)),
    INDEX idx_outbox_ready (status, available_at, event_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
