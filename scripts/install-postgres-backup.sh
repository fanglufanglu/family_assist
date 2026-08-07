#!/usr/bin/env bash
set -euo pipefail

DB_NAME="${DB_NAME:-family_assist}"
DB_USER="${DB_USER:-family_assist}"
DB_PASSWORD="${DB_PASSWORD:-change-this-db-password}"
APP_DIR="${APP_DIR:-/opt/family_assist}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/family-assist-postgres}"

if [[ "${DB_PASSWORD}" == "change-this-db-password" ]]; then
  echo "Please set DB_PASSWORD before running this script."
  echo "Example: DB_PASSWORD='a-long-random-password' bash scripts/install-postgres-backup.sh"
  exit 1
fi

if [[ "$(id -u)" != "0" ]]; then
  echo "Please run as root."
  exit 1
fi

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql postgresql-contrib

systemctl enable postgresql
systemctl start postgresql

sudo -u postgres psql <<SQL
DO \$\$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${DB_USER}') THEN
      CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}';
   ELSE
      ALTER ROLE ${DB_USER} WITH LOGIN PASSWORD '${DB_PASSWORD}';
   END IF;
END
\$\$;

SELECT 'CREATE DATABASE ${DB_NAME} OWNER ${DB_USER}'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${DB_NAME}')\\gexec
SQL

sudo -u postgres psql -d "${DB_NAME}" -f "${APP_DIR}/db/schema.sql"

mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"

cat >/usr/local/bin/family-assist-pg-backup <<EOF
#!/usr/bin/env bash
set -euo pipefail
BACKUP_DIR="${BACKUP_DIR}"
DB_NAME="${DB_NAME}"
mkdir -p "\${BACKUP_DIR}"
chmod 700 "\${BACKUP_DIR}"
sudo -u postgres pg_dump "\${DB_NAME}" | gzip > "\${BACKUP_DIR}/\${DB_NAME}-\$(date +%F-%H%M%S).sql.gz"
find "\${BACKUP_DIR}" -name "\${DB_NAME}-*.sql.gz" -mtime +14 -delete
EOF

chmod +x /usr/local/bin/family-assist-pg-backup

cat >/etc/cron.d/family-assist-pg-backup <<'EOF'
17 3 * * * root /usr/local/bin/family-assist-pg-backup >/var/log/family-assist-pg-backup.log 2>&1
EOF

/usr/local/bin/family-assist-pg-backup

echo "PostgreSQL is ready."
echo "Database: ${DB_NAME}"
echo "User: ${DB_USER}"
echo "Backups: ${BACKUP_DIR}"
