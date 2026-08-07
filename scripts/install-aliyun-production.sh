#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/family_assist}"
RELAY_PORT="${RELAY_PORT:-8787}"
PUBLIC_IP="${PUBLIC_IP:-47.238.240.30}"
PRIVATE_IP="${PRIVATE_IP:-172.29.103.233}"
TURN_USER="${TURN_USER:-familyassist}"
TURN_PASSWORD="${TURN_PASSWORD:-change-this-turn-password}"
RELAY_DATA_DIR="${RELAY_DATA_DIR:-/var/lib/family-assist-relay}"

if [[ "${TURN_PASSWORD}" == "change-this-turn-password" ]]; then
  echo "Please set TURN_PASSWORD before running this script."
  echo "Example: TURN_PASSWORD='a-long-random-password' bash scripts/install-aliyun-production.sh"
  exit 1
fi

if [[ "$(id -u)" != "0" ]]; then
  echo "Please run as root."
  exit 1
fi

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y git nginx nodejs npm coturn curl logrotate

mkdir -p "${RELAY_DATA_DIR}" /var/log/family-assist
chmod 700 "${RELAY_DATA_DIR}"

cat >/etc/systemd/system/family-assist-relay.service <<EOF
[Unit]
Description=Family Assist Relay
After=network.target

[Service]
WorkingDirectory=${APP_DIR}
Environment=PORT=${RELAY_PORT}
Environment=RELAY_DATA_DIR=${RELAY_DATA_DIR}
Environment=TURN_URLS=turn:${PUBLIC_IP}:3478?transport=udp,turn:${PUBLIC_IP}:3478?transport=tcp
Environment=TURN_USERNAME=${TURN_USER}
Environment=TURN_CREDENTIAL=${TURN_PASSWORD}
ExecStart=/usr/bin/node relay/server.js
Restart=always
RestartSec=3
User=root
StandardOutput=append:/var/log/family-assist/relay.log
StandardError=append:/var/log/family-assist/relay-error.log

[Install]
WantedBy=multi-user.target
EOF

cat >/etc/nginx/sites-available/family-assist <<EOF
server {
    listen 80;
    server_name _;

    client_max_body_size 8m;

    location / {
        proxy_pass http://127.0.0.1:${RELAY_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

ln -sf /etc/nginx/sites-available/family-assist /etc/nginx/sites-enabled/family-assist
rm -f /etc/nginx/sites-enabled/default

cat >/etc/turnserver.conf <<EOF
listening-port=3478
relay-ip=${PRIVATE_IP}
external-ip=${PUBLIC_IP}
min-port=49152
max-port=65535
fingerprint
lt-cred-mech
user=${TURN_USER}:${TURN_PASSWORD}
realm=family-assist
no-cli
no-tls
no-dtls
EOF

sed -i 's/^#TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
sed -i 's/^TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn

cat >/etc/logrotate.d/family-assist <<'EOF'
/var/log/family-assist/*.log {
    daily
    rotate 14
    compress
    missingok
    notifempty
    copytruncate
}
EOF

nginx -t
systemctl daemon-reload
systemctl enable family-assist-relay
systemctl restart family-assist-relay
systemctl restart nginx
systemctl enable coturn
systemctl restart coturn

curl -fsS "http://127.0.0.1:${RELAY_PORT}/health"
curl -fsS "http://${PUBLIC_IP}/health"
echo
echo "Family Assist production services are ready on http://${PUBLIC_IP}"
