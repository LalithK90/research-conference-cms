#!/usr/bin/env bash
set -euo pipefail
# Run the Spring Boot app using the Gradle wrapper with the `dev` profile.
# Usage: ./run-dev.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [ -x "./gradlew" ]; then
  echo "Starting app with dev profile using Gradle wrapper..."
  ./gradlew bootRun
else
  echo "Gradle wrapper (./gradlew) not found in $ROOT_DIR"
  echo "You can run: ./gradlew bootRun --args='--spring.profiles.active=dev'"
  exit 1
fi
