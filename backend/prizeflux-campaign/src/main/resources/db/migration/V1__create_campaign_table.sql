CREATE TABLE campaign (
    id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    ends_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_campaign PRIMARY KEY (id),
    CONSTRAINT chk_campaign_positive_id CHECK (id > 0),
    CONSTRAINT chk_campaign_name_not_blank CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_campaign_time_window CHECK (starts_at < ends_at),
    CONSTRAINT chk_campaign_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
