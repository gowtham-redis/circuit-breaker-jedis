#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# status.sh - ping every configured database and show active block rules.
# Use it before a demo (everything UP, no rules) and to confirm cleanup after.
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/_lib.sh
require_conf

echo "=== Database reachability (from this machine) ==="
count=$(db_count)
for i in $(seq 1 "$count"); do
  n=$(prop "db.$i.name")
  ep=$(prop "db.$i.endpoint"); ep="${ep#redis://}"; ep="${ep#rediss://}"
  [ -n "$ep" ] || continue
  h="${ep%%:*}"; p="${ep##*:}"
  printf "  %-8s %-48s " "$n" "$ep"
  if out=$(redis-cli -h "$h" -p "$p" -t 2 ping 2>/dev/null) && [ "$out" = "PONG" ]; then
    echo "UP (PONG)"
  else
    echo "DOWN / unreachable"
  fi
done

echo
echo "=== Active iptables REJECT rules on OUTPUT ==="
if sudo iptables -L OUTPUT -n --line-numbers | grep -E "REJECT" >/tmp/cb_rules 2>/dev/null && [ -s /tmp/cb_rules ]; then
  cat /tmp/cb_rules
  echo "(an outage is currently being simulated - unblock.sh to clear)"
else
  echo "  (none - clean)"
fi
rm -f /tmp/cb_rules 2>/dev/null || true
