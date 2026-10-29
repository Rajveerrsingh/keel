#!/usr/bin/env bash
# Start keel with the AOF off and print a few load-test lines.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${PORT:-6397}"
DURATION="${DURATION:-4}"
CLIENTS="${CLIENTS:-32}"
PIPELINE="${PIPELINE:-16}"
DIR="$(mktemp -d)"

./gradlew -q jar
java -jar build/libs/keel.jar --port "$PORT" --appendonly no --dir "$DIR" >"$DIR/keel.log" 2>&1 &
PID=$!
cleanup() {
  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 50); do
  if redis-cli -p "$PORT" PING >/dev/null 2>&1; then
    break
  fi
  sleep 0.1
done
redis-cli -p "$PORT" PING

CP="build/libs/keel.jar"
for mode in ping set get mixed; do
  java -cp "$CP" dev.rajveer.keel.bench.LoadTest \
    --port "$PORT" --mode "$mode" --clients "$CLIENTS" \
    --pipeline "$PIPELINE" --seconds "$DURATION" --warmup 1
done

if command -v redis-benchmark >/dev/null 2>&1; then
  echo "redis-benchmark pipeline 1:"
  redis-benchmark -h 127.0.0.1 -p "$PORT" -t set,get -n 100000 -c "$CLIENTS" -q
  echo "redis-benchmark pipeline ${PIPELINE}:"
  redis-benchmark -h 127.0.0.1 -p "$PORT" -t set,get -n 200000 -c "$CLIENTS" -P "$PIPELINE" -q
fi
