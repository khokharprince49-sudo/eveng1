#!/usr/bin/env bash
set -euo pipefail
if ! command -v buildozer >/dev/null 2>&1; then
  echo "Buildozer is not installed. Install Buildozer and its Linux prerequisites first." >&2
  exit 2
fi
buildozer android debug
printf '\nIf the build succeeded, check the bin/ directory for the APK.\n'
