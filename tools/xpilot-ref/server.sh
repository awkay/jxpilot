#!/bin/bash
# Run a headless original XPilot 4.5.5 server (xpilots) in a container and
# print its log.  No client connects.
#
# usage: server.sh --map NAME [--robots N] [--seconds S] [--trace] [--seed N] [--] [extra xpilots options...]
#
#   --map NAME     map from lib/maps without .xp (default: default)
#   --robots N     exactly N robots (sets -robots N -minRobots N; default 2)
#   --seconds S    run time; the server gets SIGINT after S seconds (default 10)
#   --no-raw       do NOT pass -rawMode.  Without -rawMode the 4.5.5 server
#                  does not simulate (or add robots) until a human joins.
#   --seed N       set XPILOT_SEED=N (patch 0002): fixed RNG seed
#   --trace        set XPILOT_TRACE=1 (patch 0002): per-tick XPTRACE lines
#                  for every player, including robots, on stderr
#   extra options  passed verbatim to xpilots, e.g. -gravity -0.5 -FPS 14
#
# The container has no network (--network none); nothing reaches the meta
# server.
set -u
. "$(dirname "$0")/lib.sh"

MAP=default; ROBOTS=2; SECS=10; RAW=1; TRACE=0; SEED=""; EXTRA=()
while [ $# -gt 0 ]; do
    case "$1" in
        --map) MAP="$2"; shift 2;;
        --robots) ROBOTS="$2"; shift 2;;
        --seconds) SECS="$2"; shift 2;;
        --no-raw) RAW=0; shift;;
        --trace) TRACE=1; shift;;
        --seed) SEED="$2"; shift 2;;
        --) shift; EXTRA+=("$@"); break;;
        -h|--help) sed -n '2,20p' "$0"; exit 0;;
        *) EXTRA+=("$1"); shift;;
    esac
done
xpref_require_image

opts=(-map "$MAP" -robots "$ROBOTS" -minRobots "$ROBOTS" -noQuit +reportToMetaServer)
[ "$RAW" = 1 ] && opts+=(-rawMode)
envs=()
[ "$TRACE" = 1 ] && envs+=(-e XPILOT_TRACE=1)
[ -n "$SEED" ] && envs+=(-e "XPILOT_SEED=$SEED")

cname=$(xpref_container_name server)
xpref_run_deadline "$((SECS + 20))" "$cname" --network none ${envs[@]+"${envs[@]}"} "$XPREF_IMAGE" \
    timeout -s INT -k 5 "$SECS" xpilots "${opts[@]}" ${EXTRA[@]+"${EXTRA[@]}"}
rc=$?
# timeout(1) returns 124 when it had to stop the server: that is the normal end.
[ "$rc" = 124 ] && rc=0
exit "$rc"
