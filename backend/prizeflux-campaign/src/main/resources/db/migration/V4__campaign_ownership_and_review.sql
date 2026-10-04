ALTER TABLE campaign
    ADD COLUMN owner_user_id BIGINT NULL AFTER status,
    ADD COLUMN submitted_at TIMESTAMP(6) NULL AFTER owner_user_id,
    DROP CHECK chk_campaign_status,
    ADD CONSTRAINT chk_campaign_status CHECK
        (status IN ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'CLOSED')),
    ADD CONSTRAINT fk_campaign_owner FOREIGN KEY (owner_user_id) REFERENCES app_user(id);

CREATE INDEX ix_campaign_owner_id ON campaign(owner_user_id, id);
CREATE INDEX ix_campaign_review_queue ON campaign(status, submitted_at, id);

CREATE TABLE campaign_id_registry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reserved_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_campaign_id_registry PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO campaign_id_registry (id)
SELECT id FROM campaign ORDER BY id;

CREATE TABLE campaign_review_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    comment VARCHAR(500) NOT NULL,
    decided_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_campaign_review_decision PRIMARY KEY (id),
    CONSTRAINT fk_review_decision_campaign FOREIGN KEY (campaign_id) REFERENCES campaign(id),
    CONSTRAINT fk_review_decision_operator FOREIGN KEY (operator_user_id) REFERENCES app_user(id),
    CONSTRAINT chk_review_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT chk_review_comment CHECK (CHAR_LENGTH(comment) <= 500)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_review_decision_campaign ON campaign_review_decision(campaign_id, id DESC);
