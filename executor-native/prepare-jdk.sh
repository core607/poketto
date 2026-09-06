#!/usr/bin/env bash
set -euo pipefail
destination=${1:?Supply a new isolated JDK directory}
test ! -e "$destination"
mkdir -p "$destination"
archive="$destination/jdk.tar.gz"
curl --fail --location --connect-timeout 20 --max-time 300 --output "$archive" \
  'https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.2%2B10/OpenJDK26U-jdk_x64_linux_hotspot_26.0.2_10.tar.gz'
printf '%s  %s\n' '56f768372f6ca1e2eb4c5f46b78f627949e8dcfe9c9723926cf45a45faf35802' "$archive" | sha256sum --check
tar -xzf "$archive" -C "$destination"
"$destination/jdk-26.0.2+10/bin/java" -version
