#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# block.sh [DB_NAME] - simulate an outage of a database by REJECTing TCP
# traffic to its endpoint (default: the primary, db.1).
#
#   ./scripts/block.sh North
#
# Reverse it with:  ./scripts/unblock.sh North
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

echo "Blocking '$DISPLAY'  ($HOST -> $IP:$PORT)"
sudo iptables -I OUTPUT -d "$IP" -p tcp --dport "$PORT" -j REJECT
echo "Done. Watch the app fail over."
echo "Undo with:  ./scripts/unblock.sh $DISPLAY"
