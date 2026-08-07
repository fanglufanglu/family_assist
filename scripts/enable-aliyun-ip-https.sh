#!/usr/bin/env bash
set -euo pipefail

PUBLIC_IP="${PUBLIC_IP:-47.238.240.30}"
RELAY_PORT="${RELAY_PORT:-8787}"
LE_EMAIL="${LE_EMAIL:-}"
WEBROOT="/var/www/family-assist-acme"

if [[ "$(id -u)" != "0" ]]; then
  echo "Please run as root."
  exit 1
fi
if [[ -z "${LE_EMAIL}" ]]; then
  echo "Please set LE_EMAIL to the certificate contact email."
  echo "Example: LE_EMAIL='you@example.com' bash scripts/enable-aliyun-ip-https.sh"
  exit 1
fi

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y nginx snapd
snap install core >/dev/null 2>&1 || snap refresh core
snap install --classic certbot >/dev/null 2>&1 || snap refresh certbot
ln -sf /snap/bin/certbot /usr/local/bin/certbot
mkdir -p "${WEBROOT}/.well-known/acme-challenge"

cat >/etc/nginx/sites-available/family-assist <<EOF
server {
    listen 80;
    server_name ${PUBLIC_IP};
    location /.well-known/acme-challenge/ {
        root ${WEBROOT};
    }
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
nginx -t
systemctl reload nginx

certbot certonly \
  --non-interactive \
  --agree-tos \
  --email "${LE_EMAIL}" \
  --preferred-profile shortlived \
  --webroot \
  --webroot-path "${WEBROOT}" \
  --ip-address "${PUBLIC_IP}"

cat >/etc/nginx/sites-available/family-assist <<EOF
server {
    listen 80;
    server_name ${PUBLIC_IP};
    location /.well-known/acme-challenge/ {
        root ${WEBROOT};
    }
    location / {
        return 308 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl http2;
    server_name ${PUBLIC_IP};
    ssl_certificate /etc/letsencrypt/live/${PUBLIC_IP}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${PUBLIC_IP}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options "nosniff" always;
    client_max_body_size 8m;

    location / {
        proxy_pass http://127.0.0.1:${RELAY_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
EOF

mkdir -p /etc/letsencrypt/renewal-hooks/deploy
cat >/etc/letsencrypt/renewal-hooks/deploy/reload-family-assist-nginx.sh <<'EOF'
#!/usr/bin/env bash
set -e
nginx -t
systemctl reload nginx
EOF
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-family-assist-nginx.sh

nginx -t
systemctl reload nginx
systemctl enable --now snap.certbot.renew.timer 2>/dev/null || true
curl -fsS "https://${PUBLIC_IP}/health"
echo
echo "Family Assist HTTPS is ready on https://${PUBLIC_IP}"
