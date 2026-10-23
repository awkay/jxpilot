#!/bin/bash
# Build the xpilot-ref Docker image from the ORIGINAL XPilot 4.5.5 C source.
#
# usage: build.sh [extra docker build args...]
#   XPILOT_SRC   path to the unpacked xpilot-4.5.5 tree
#                (default /Users/tonykay/Downloads/xpilot-4.5.5)
#   XPREF_IMAGE  image tag (default xpilot-ref:4.5.5)
#   PLATFORM     optional, e.g. linux/amd64 (default: host architecture)
#
# The source is passed as a named build context ("xpsrc"); it is never
# copied into this repository.  Patches in patches/ are applied in order.
set -eu
. "$(dirname "$0")/lib.sh"

[ -f "$XPILOT_SRC/src/server/server.c" ] || die "XPILOT_SRC=$XPILOT_SRC does not look like xpilot-4.5.5"

args=(build --build-context "xpsrc=$XPILOT_SRC" -t "$XPREF_IMAGE")
[ -n "${PLATFORM:-}" ] && args+=(--platform "$PLATFORM")

start=$(date +%s)
"$DOCKER" "${args[@]}" "$@" "$XPREF_DIR"
end=$(date +%s)
echo "built $XPREF_IMAGE in $((end - start))s"
"$DOCKER" image ls "$XPREF_IMAGE"
