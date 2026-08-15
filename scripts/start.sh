#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# HavocFolia start script.
#
# These flags are tuned for Folia specifically, which is not the same problem
# as tuning Paper. Folia spreads work across region threads, so the collector
# competes with many mutator threads rather than one, and pause time matters
# more than raw throughput.
# ---------------------------------------------------------------------------
set -euo pipefail

JAR="${JAR:-havocfolia.jar}"
# Give the JVM most of the box but leave room for the OS page cache — chunk IO
# lives there, and starving it costs more than the extra heap gains.
MEMORY="${MEMORY:-8G}"

if [ ! -f "$JAR" ]; then
  echo "error: $JAR not found. Set JAR=/path/to/havocfolia-<version>.jar" >&2
  exit 1
fi

JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
  echo "error: Java 21+ required, found $JAVA_MAJOR" >&2
  exit 1
fi

# Generational ZGC: sub-millisecond pauses and it scales with core count, which
# is the whole point of running Folia. G1 flags (the classic "Aikar's flags")
# are the wrong tool here — they trade pause time for throughput on a single
# tick thread, and tune region sizes ZGC does not have.
FLAGS=(
  -Xms"${MEMORY}" -Xmx"${MEMORY}"
  -XX:+UseZGC -XX:+ZGenerational
  -XX:+AlwaysPreTouch
  -XX:+PerfDisableSharedMem
  -XX:+UseTransparentHugePages
  -XX:MaxDirectMemorySize=1G
  -Dusing.aikars.flags=false
  -Dfile.encoding=UTF-8
)

# If you set scheduler.mode=AFFINITY, keep the JVM's own threads off the cores
# you pinned region workers to:
#   exec taskset -c 0-3 java "${FLAGS[@]}" -jar "$JAR" --nogui

exec java "${FLAGS[@]}" -jar "$JAR" --nogui "$@"
