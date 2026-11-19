#!/usr/bin/env bash
# Runs ./gradlew with the given tasks, retrying only when it failed to fetch something.
#
# Maven Central intermittently answers 404 to the CI runners for artifacts that exist, which fails a run
# before any of our code is built. A test, lint or coverage failure, or a dependency checksum mismatch,
# is a real result and fails at once without a retry.
#
# Usage: gradle-retry.sh <gradle tasks...>
# Env:   GRADLEW (default ./gradlew), GRADLE_ATTEMPTS (default 3), GRADLE_RETRY_DELAY seconds (default 15)
set -uo pipefail

gradlew=${GRADLEW:-./gradlew}
attempts=${GRADLE_ATTEMPTS:-3}
delay=${GRADLE_RETRY_DELAY:-15}
# Failures to download or resolve. "Resource missing" is deliberately absent: --info logs it for routine
# misses too. "Dependency verification failed" is absent on purpose, so a checksum mismatch stays loud.
transient='Could not resolve|Could not find|Could not GET|was not found in any of the following sources|Read timed out|Connection reset|Received status code 5'

log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$attempts"); do
  "$gradlew" "$@" --stacktrace --info 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ] && grep -E -q "$transient" "$log"; then
    echo "::warning::Gradle could not fetch a dependency (attempt $attempt of $attempts); retrying in ${delay}s"
    sleep "$delay"
  else
    exit "$status"
  fi
done
