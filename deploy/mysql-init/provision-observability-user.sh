#!/bin/sh
set -eu

password_file=/run/secrets/mysql_lock_metrics_password
lock_metrics_password="$(cat "$password_file")"

if [ "${#lock_metrics_password}" -ne 64 ]; then
    echo "MySQL lock-metrics secret must be exactly 64 lowercase hexadecimal characters." >&2
    exit 1
fi
case "$lock_metrics_password" in
    *[!0123456789abcdef]*)
        echo "MySQL lock-metrics secret must be exactly 64 lowercase hexadecimal characters." >&2
        exit 1
        ;;
esac

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
    --protocol=tcp \
    --host=mysql \
    --port=3306 \
    --user=root \
    --batch <<SQL
CREATE USER IF NOT EXISTS 'pfx_observability'@'%' IDENTIFIED BY '$lock_metrics_password';
ALTER USER 'pfx_observability'@'%' IDENTIFIED BY '$lock_metrics_password';
GRANT SELECT ON performance_schema.data_lock_waits TO 'pfx_observability'@'%';
SQL

unset lock_metrics_password MYSQL_PWD
echo "Provisioned the least-privilege MySQL lock-wait observer account."
