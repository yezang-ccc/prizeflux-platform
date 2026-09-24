CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT chk_app_user_username CHECK
        (CHAR_LENGTH(username) BETWEEN 3 AND 64 AND username REGEXP '^[a-z0-9._-]+$'),
    CONSTRAINT chk_app_user_password_hash CHECK
        (CHAR_LENGTH(password_hash) BETWEEN 59 AND 100 AND password_hash LIKE '$2%'),
    CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'OPERATOR')),
    CONSTRAINT chk_app_user_state CHECK (state IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
