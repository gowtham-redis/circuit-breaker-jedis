#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# setup.sh - install Java 17 + Maven (if missing) and build the fat jar.
# Idempotent: safe to run more than once.
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Checking for Java and Maven..."
need_install=0
command -v java >/dev/null 2>&1 || need_install=1
command -v mvn  >/dev/null 2>&1 || need_install=1

if [ "$need_install" -eq 1 ]; then
  echo "==> Java and/or Maven not found. Installing openjdk-17 + maven (needs sudo)..."
  sudo apt-get update -y
  sudo apt-get install -y openjdk-17-jdk-headless maven
else
  echo "==> Java and Maven already present."
fi

echo
java -version
mvn -v | head -1
echo

echo "==> Building fat jar (first run downloads dependencies, ~1 min)..."
mvn -q clean package -DskipTests

JAR=target/circuit-breaker-jedis-1.0.jar
if [ -f "$JAR" ]; then
  ls -lh "$JAR"
  echo
  echo "==> Build complete."
  echo "    Next:  ./scripts/configure.sh    (set your endpoints)"
  echo "    Then:  ./scripts/run.sh          (start the demo)"
else
  echo "!!  Build did not produce $JAR - check the Maven output above." >&2
  exit 1
fi
