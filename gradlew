#!/bin/sh
exec "$(dirname "$0")/gradle/wrapper/gradlew" "$@" 2>/dev/null || \
  gradle "$@"
