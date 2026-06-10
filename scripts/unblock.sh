#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# unblock.sh [DB_NAME] - remove the REJECT rule added by block.sh (default: the
# primary, db.1). Removes ALL matching rules and verifies none remain.
#
#   ./scripts/unblock.sh North
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/_lib.sh
require_conf

NAME="${1:-}"
read -r HOST PORT < <(endpoint_for "$NAME") || { echo "No database named '${NAME:-<primary>}' in $CONF" >&2; exit 1; }
[ -n "${HOST:-}" ] || { echo "Could not determine endpoint for '${NAME:-<primary>}'." >&2; exit 1; }
DISPLAY="${NAME:-$(prop db.1.name)}"

IP=$(resolve_ip "$HOST")
[ -n "$IP" ] || { echo "Could not resolve $HOST" >&2; exit 1; }

echo "Unblocking '$DISPLAY'  ($HOST -> $IP:$PORT)"
# Delete every matching rule (in case block.sh ran more than once)
removed=0
while sudo iptables -C OUTPUT -d "$IP" -p tcp --dport "$PORT" -j REJECT 2>/dev/null; do
  sudo iptables -D OUTPUT -d "$IP" -p tcp --dport "$PORT" -j REJECT
  removed=$((removed+1))
done

if sudo iptables -L OUTPUT -n | grep -q "$IP"; then
  echo "WARNING: a rule for $IP still remains - inspect with: sudo iptables -L OUTPUT -n --line-numbers" >&2
else
  echo "Done. Removed $removed rule(s). Traffic to '$DISPLAY' restored - watch for failback."
fi
