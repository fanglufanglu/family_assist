#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if pgrep -f "node relay/server.js" >/dev/null 2>&1; then
  echo "亲情帮帮 relay is already running."
else
  mkdir -p .codespaces
  nohup node relay/server.js > .codespaces/relay.log 2>&1 &
  echo "亲情帮帮 relay started on port 8787."
fi

echo
echo "Next:"
echo "1. Open the Codespaces Ports tab."
echo "2. Set port 8787 visibility to Public if it is not already public."
echo "3. Copy the forwarded HTTPS URL into both Android phones as the Relay address."
echo
echo "Recent relay log:"
tail -20 .codespaces/relay.log || true
