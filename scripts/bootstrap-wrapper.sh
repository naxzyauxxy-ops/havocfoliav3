#!/usr/bin/env bash
# Generates ./gradlew for local development.
#
# CI does not need this — it uses the Gradle provisioned by setup-gradle. This is
# purely so `./gradlew` works on your machine, and so you can commit the wrapper
# and stop depending on whatever Gradle a given machine happens to have.
set -euo pipefail

GRADLE_VERSION="${GRADLE_VERSION:-9.6.1}"

if ! command -v gradle >/dev/null 2>&1; then
  echo "error: no 'gradle' on PATH. Install Gradle ${GRADLE_VERSION}, or copy the wrapper" >&2
  echo "       files from any project already using Gradle 9.x." >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Gradle 9 refuses to run in a directory that is not a build. Gradle 8 allowed an
# empty dir; that change is why the out-of-tree trick broke.
printf 'rootProject.name = "wrapper-bootstrap"\n' > "$TMP/settings.gradle.kts"

( cd "$TMP" && gradle wrapper --gradle-version "$GRADLE_VERSION" --no-daemon )

mkdir -p gradle/wrapper
cp "$TMP/gradlew" "$TMP/gradlew.bat" .
cp "$TMP"/gradle/wrapper/* gradle/wrapper/
chmod +x gradlew

echo "Wrapper written. Commit it so nothing depends on the local Gradle:"
echo "  git add -f gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties"
