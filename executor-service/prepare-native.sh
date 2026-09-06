#!/usr/bin/env bash
set -euo pipefail
# Disposable test toolchain. Never installs global packages or changes host services.
target=${1:?Usage: prepare-native.sh NEW_DIRECTORY SPIKE_DIRECTORY}
spike=${2:?Provide executor-spike directory containing the pinned npm lockfile}
mkdir -m 755 -- "$target"
target=$(cd -- "$target" && pwd)
node_archive=node-v22.22.0-linux-x64.tar.xz
curl --fail --silent --show-error --location "https://nodejs.org/dist/v22.22.0/$node_archive" -o "$target/$node_archive"
curl --fail --silent --show-error --location https://nodejs.org/dist/v22.22.0/SHASUMS256.txt -o "$target/SHASUMS256.txt"
(
  cd -- "$target"
  grep " $node_archive\$" SHASUMS256.txt | sha256sum --check --status
  tar -xf "$node_archive"
  cp "node-v22.22.0-linux-x64/bin/node" node
  cp -- "$spike/package.json" "$spike/package-lock.json" .
  PATH="$target/node-v22.22.0-linux-x64/bin:$PATH" npm ci --ignore-scripts --no-audit --no-fund
  apt download bubblewrap socat ripgrep
  for package in ./*.deb; do dpkg-deb -x "$package" extracted; done
)
chmod -R a+rX -- "$target"
