#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# _lib.sh - shared helpers for block.sh / unblock.sh / status.sh
# Sourced, not executed directly.
# -----------------------------------------------------------------------------
CONF="${CONF:-demo.properties}"

prop() {  # prop <key> -> value (first match), trimmed
  grep -E "^$1=" "$CONF" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/[[:space:]]*$//'
}

db_count() { local c; c=$(prop "db.count"); echo "${c:-2}"; }

# Given a database NAME, echo "host port". Defaults to db.1 if NAME is empty.
endpoint_for() {
  local want="$1" n ep i
  local count; count=$(db_count)
  if [ -z "$want" ]; then want=$(prop "db.1.name"); fi
  for i in $(seq 1 "$count"); do
    n=$(prop "db.$i.name")
    if [ "$n" = "$want" ]; then
      ep=$(prop "db.$i.endpoint"); ep="${ep#redis://}"; ep="${ep#rediss://}"
      echo "${ep%%:*} ${ep##*:}"
      return 0
    fi
  done
  return 1
}

resolve_ip() {  # resolve_ip <host> -> first IPv4
  getent ahosts "$1" | grep STREAM | awk '{print $1}' | head -1
}

require_conf() {
  [ -f "$CONF" ] || { echo "Config '$CONF' not found. Run ./scripts/configure.sh first." >&2; exit 1; }
}

# block/unblock rely on Linux iptables. Fail early with guidance elsewhere.
require_iptables() {
  if [ "$(uname -s)" != "Linux" ]; then
    echo "block/unblock need Linux + iptables (this host is $(uname -s))." >&2
    echo "On another OS, simulate the outage another way (host firewall, or take the" >&2
    echo "endpoint down) - the app itself runs anywhere with Java." >&2
    exit 1
  fi
  command -v iptables >/dev/null 2>&1 || {
    echo "iptables not found. Install it (e.g. 'sudo apt-get install -y iptables') or" >&2
    echo "run the demo on the load box, which has it." >&2
    exit 1
  }
}
