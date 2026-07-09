#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# setup.sh - install Java 17 + Maven (if missing) and build the fat jar.
# Detects the host package manager (apt / dnf / yum / brew). Idempotent.
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

install_toolchain() {
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -y
    sudo apt-get install -y openjdk-17-jdk-headless maven
  elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y java-17-openjdk-devel maven
  elif command -v yum >/dev/null 2>&1; then
    sudo yum install -y java-17-openjdk-devel maven
  elif command -v brew >/dev/null 2>&1; then
    brew install openjdk@17 maven
  else
    echo "!!  No supported package manager (apt/dnf/yum/brew) found." >&2
    echo "!!  Please install Java 17 and Maven manually, then re-run this script." >&2
    exit 1
  fi
}

echo "==> Checking for Java and Maven..."
if command -v java >/dev/null 2>&1 && command -v mvn >/dev/null 2>&1; then
  echo "==> Java and Maven already present."
else
  echo "==> Installing Java 17 + Maven..."
  install_toolchain
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
