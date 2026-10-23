#!/bin/bash
# Run the ORIGINAL C XPilot 4.5.5 server (image xpilot-ref:4.5.5, built by
# tools/xpilot-ref/build.sh in the main tree) with its UDP ports published to
# the host, so the JXPilot client (--protocol xpilot / xp://host:port) can
# play on it.
#
# usage: xp-c-server.sh [--map NAME] [--robots N] [--seconds S] [--port P]
#                       [--seed N] [--] [extra xpilots options...]
#
#   --map NAME     map from lib/maps without .xp (default: default)
#   --robots N     -robots N -minRobots N (default 2); -rawMode is always on so
#                  robots play before a human joins
#   --seconds S    server run time; SIGINT after S seconds (default 60)
#   --port P       contact port P; player ports are pinned to P+1..P+8
#                  (-clientPortStart/-clientPortEnd) and all nine are
#                  published as UDP on 127.0.0.1 (default 15345)
#   --seed N       XPILOT_SEED=N (fixed server RNG)
#
# Why it works on Docker Desktop for Mac: the C server answers the contact
# packet to its source address and later connect()s the player socket to the
# address the contact came from plus the source port of the first packet on
# the player port.  Docker's UDP port publishing presents every host flow from
# the same gateway address, so both checks pass.  The per-player port is
# chosen by the server, so it is pinned into the published range.
#
# The container is named xpilot-ref-compat-<pid>-<rand> and runs with --rm.
# An in-container timeout ends the server; a host deadline stops only that
# container by name and only the docker CLI process this script started.
set -u
DOCKER="${DOCKER:-$(command -v docker || echo /Users/tonykay/.docker/bin/docker)}"
IMAGE="${XPREF_IMAGE:-xpilot-ref:4.5.5}"
MAP=default; ROBOTS=2; SECS=60; PORT=15345; SEED=""; EXTRA=()
while [ $# -gt 0 ]; do
    case "$1" in
        --map) MAP="$2"; shift 2;;
        --robots) ROBOTS="$2"; shift 2;;
        --seconds) SECS="$2"; shift 2;;
        --port) PORT="$2"; shift 2;;
        --seed) SEED="$2"; shift 2;;
        --) shift; EXTRA+=("$@"); break;;
        -h|--help) sed -n '2,28p' "$0"; exit 0;;
        *) EXTRA+=("$1"); shift;;
    esac
done
"$DOCKER" image inspect "$IMAGE" > /dev/null 2>&1 || { echo "image $IMAGE missing (tools/xpilot-ref/build.sh)" >&2; exit 1; }
FIRST=$((PORT + 1)); LAST=$((PORT + 8))
cname="xpilot-ref-compat-$$-$RANDOM"
envs=()
[ -n "$SEED" ] && envs+=(-e "XPILOT_SEED=$SEED")
echo "container $cname: contact udp 127.0.0.1:$PORT, player ports $FIRST-$LAST" >&2
"$DOCKER" run --rm --name "$cname" \
    -p "127.0.0.1:$PORT:$PORT/udp" -p "127.0.0.1:$FIRST-$LAST:$FIRST-$LAST/udp" \
    ${envs[@]+"${envs[@]}"} "$IMAGE" \
    timeout -s INT -k 5 "$SECS" xpilots -map "$MAP" -robots "$ROBOTS" -minRobots "$ROBOTS" \
        -rawMode -noQuit +reportToMetaServer -contactPort "$PORT" \
        -clientPortStart "$FIRST" -clientPortEnd "$LAST" ${EXTRA[@]+"${EXTRA[@]}"} &
pid=$!
waited=0
while kill -0 "$pid" 2> /dev/null; do
    if [ "$waited" -ge "$(((SECS + 20) * 10))" ]; then
        echo "host deadline reached; stopping $cname" >&2
        "$DOCKER" stop -t 2 "$cname" > /dev/null 2>&1
        sleep 1
        kill "$pid" 2> /dev/null
        wait "$pid" 2> /dev/null
        exit 124
    fi
    sleep 0.1
    waited=$((waited + 1))
done
wait "$pid"
rc=$?
[ "$rc" = 124 ] && rc=0
exit "$rc"
