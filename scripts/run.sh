#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# run.sh - start the circuit breaker demo using ./demo.properties
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/circuit-breaker-jedis-1.0.jar
CONF=demo.properties

if [ ! -f "$JAR" ];  then echo "Jar not found. Run ./scripts/setup.sh first."     >&2; exit 1; fi
if [ ! -f "$CONF" ]; then echo "$CONF not found. Run ./scripts/configure.sh first." >&2; exit 1; fi

exec java -jar "$JAR" "$CONF"
