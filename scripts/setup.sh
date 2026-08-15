#!/usr/bin/env bash
# One-time developer setup for a HavocFolia checkout.
set -euo pipefail

echo "==> Checking Java"
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
  echo "error: JDK 21+ required (found ${JAVA_MAJOR:-none})." >&2
  exit 1
fi
echo "    Java $JAVA_MAJOR ok"

echo "==> Fetching the GPL-3.0 text"
# HavocFolia is a fork of Paper/Folia/CanvasMC, which are GPL-3.0. Redistributing
# a build without the full licence text is a licence violation, so fetch it.
if [ ! -s LICENSE ]; then
  curl -fsSL https://www.gnu.org/licenses/gpl-3.0.txt -o LICENSE
  echo "    wrote LICENSE"
else
  echo "    LICENSE already present"
fi

echo "==> Generating the Gradle wrapper"
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  gradle wrapper --gradle-version 8.14
fi

echo "==> Configuring git for paperweight"
git config user.name  >/dev/null 2>&1 || git config user.name  "havocfolia-dev"
git config user.email >/dev/null 2>&1 || git config user.email "dev@localhost"

COMMIT=$(grep -E '^upstreamCommit' gradle.properties | cut -d= -f2 | tr -d ' ')
if [ "$COMMIT" = "0000000000000000000000000000000000000000" ]; then
  cat <<'WARN'

  !! upstreamCommit is still the placeholder.

     Pick the CanvasMC commit you want to build against and put its full SHA in
     gradle.properties. Do not track a branch — patches rot silently when
     upstream moves under them.

       git ls-remote https://github.com/CraftCanvasMC/Canvas.git main

WARN
fi

echo "==> Done. Next:  ./gradlew applyAllPatches && ./gradlew createMojmapPaperclipJar"
