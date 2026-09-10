#!/usr/bin/env bash
# Pull existing-layout images; standard input accepts only temporary registry credentials.
set -euo pipefail
umask 077
fail() { echo "existing pull: $*" >&2; exit 1; }
[ "$#" = 2 ] || fail "two image digest references are required"
for image in "$@"; do
    [[ "$image" =~ ^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$ ]] || fail "immutable image digests are required"
done
registry="${1%%/*}"
[ "$registry" = "${2%%/*}" ] || fail "both images must use the same registry"
username="" password="" config="" context=""
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
        '') ;;
        REGISTRY_USERNAME=*) username="${line#*=}" ;;
        REGISTRY_PASSWORD=*) password="${line#*=}" ;;
        *) fail "only REGISTRY_USERNAME and REGISTRY_PASSWORD are accepted" ;;
    esac
done
trap '[ -z "$config" ] || rm -rf "$config"' EXIT
trap 'exit 143' HUP INT TERM
registry_docker() {
    if [ -n "$config" ]; then
        DOCKER_CONFIG="$config" DOCKER_CONTEXT="$context" timeout 600 docker "$@"
    else
        timeout 600 docker "$@"
    fi
}
if [ -n "$password" ]; then
    [ -n "$username" ] || fail "REGISTRY_PASSWORD requires REGISTRY_USERNAME"
    source="${DOCKER_CONFIG:-$HOME/.docker}"
    context="$(docker context show)"
    config="$(mktemp -d "${TMPDIR:-/tmp}/poketto-docker.XXXXXX")"
    printf '%s\n' '{"auths":{"https://index.docker.io/v1/":{}}}' > "$config/config.json"
    if [ -d "$source/contexts" ]; then
        source="$(cd "$source" && pwd -P)"
        ln -s "$source/contexts" "$config/contexts" || cp -R "$source/contexts" "$config/contexts"
    fi
    printf '%s' "$password" | registry_docker login "$registry" --username "$username" --password-stdin >/dev/null
fi
unset password line
registry_docker pull "$1"
registry_docker pull "$2"
