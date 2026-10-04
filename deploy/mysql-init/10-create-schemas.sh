#!/bin/sh
set -eu

require_hex_password() {
    name="$1"
    value="$2"
    if [ "${#value}" -ne 64 ]; then
        echo "${name} must be exactly 64 lowercase hexadecimal characters." >&2
        exit 1
    fi
    case "$value" in
        *[!0123456789abcdef]*)
            echo "${name} must be exactly 64 lowercase hexadecimal characters." >&2
            exit 1
            ;;
    esac
}

require_hex_password PFX_CAMPAIGN_RUNTIME_PASSWORD "$PFX_CAMPAIGN_RUNTIME_PASSWORD"
require_hex_password PFX_CAMPAIGN_MIGRATION_PASSWORD "$PFX_CAMPAIGN_MIGRATION_PASSWORD"
require_hex_password PFX_WORKER_RUNTIME_PASSWORD "$PFX_WORKER_RUNTIME_PASSWORD"
require_hex_password PFX_WORKER_MIGRATION_PASSWORD "$PFX_WORKER_MIGRATION_PASSWORD"
require_hex_password PFX_AGENT_RUNTIME_PASSWORD "$PFX_AGENT_RUNTIME_PASSWORD"
require_hex_password PFX_AGENT_MIGRATION_PASSWORD "$PFX_AGENT_MIGRATION_PASSWORD"

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root <<SQL
CREATE DATABASE IF NOT EXISTS prizeflux CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS prizeflux_worker CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS prizeflux_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'pfx_campaign_runtime'@'%' IDENTIFIED BY '$PFX_CAMPAIGN_RUNTIME_PASSWORD';
CREATE USER IF NOT EXISTS 'pfx_campaign_migrator'@'%' IDENTIFIED BY '$PFX_CAMPAIGN_MIGRATION_PASSWORD';
CREATE USER IF NOT EXISTS 'pfx_worker_runtime'@'%' IDENTIFIED BY '$PFX_WORKER_RUNTIME_PASSWORD';
CREATE USER IF NOT EXISTS 'pfx_worker_migrator'@'%' IDENTIFIED BY '$PFX_WORKER_MIGRATION_PASSWORD';
CREATE USER IF NOT EXISTS 'pfx_agent_runtime'@'%' IDENTIFIED BY '$PFX_AGENT_RUNTIME_PASSWORD';
CREATE USER IF NOT EXISTS 'pfx_agent_migrator'@'%' IDENTIFIED BY '$PFX_AGENT_MIGRATION_PASSWORD';

GRANT SELECT, INSERT, UPDATE, DELETE ON prizeflux.* TO 'pfx_campaign_runtime'@'%';
GRANT ALL PRIVILEGES ON prizeflux.* TO 'pfx_campaign_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON prizeflux_worker.* TO 'pfx_worker_runtime'@'%';
GRANT ALL PRIVILEGES ON prizeflux_worker.* TO 'pfx_worker_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON prizeflux_agent.* TO 'pfx_agent_runtime'@'%';
GRANT ALL PRIVILEGES ON prizeflux_agent.* TO 'pfx_agent_migrator'@'%';
SQL

echo "Initialized PrizeFlux schemas and isolated runtime/migration accounts."
