#!/usr/bin/env bash
set -euo pipefail

PUBLIC_IP="${PUBLIC_IP:-47.238.240.30}"
PUBLIC_SCHEME="${PUBLIC_SCHEME:-https}"

echo "== family-assist-relay =="
systemctl --no-pager --full status family-assist-relay || true

echo
echo "== nginx =="
systemctl --no-pager --full status nginx || true

echo
echo "== coturn =="
systemctl --no-pager --full status coturn || true

echo
echo "== health =="
curl -fsS "${PUBLIC_SCHEME}://${PUBLIC_IP}/health"
echo

echo "== ice-config =="
curl -fsS "${PUBLIC_SCHEME}://${PUBLIC_IP}/api/ice-config"
echo

echo "== recent relay logs =="
journalctl -u family-assist-relay -n 80 --no-pager || true
