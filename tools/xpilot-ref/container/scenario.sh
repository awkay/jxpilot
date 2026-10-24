#!/bin/bash
# Runs INSIDE the xpilot-ref container.
#
# Starts Xvfb, the original xpilots server and the original xpilot X11
# client (which joins the server), optionally sends keys with xdotool, then
# captures one or more PNG screenshots of the client window into --out.
#
# Every child process is started by this script and is killed by PID on exit.
# The whole script is also expected to be wrapped in `timeout` by the caller.
#
# Output files in --out:
#   server.log   server stdout/stderr (minus trace lines)
#   client.log   client stdout/stderr
#   trace.txt    XPTRACE lines (only with --trace)
#   shot.png     single capture (default mode)
#   frame_NNN.png + contact.png   (with --frames N)
#   info.txt     window geometry, timings
set -u

MAP=default
ROBOTS=2
AFTER=5
SIZE=1026x768
KEYS=""
FRAMES=0
INTERVAL=0.5
OUT=/out
NAME=refpilot
TRACE=0
TRACE_NAME=""
TRACE_OBJS=0
TRACE_MODES=0
SEED=""
SERVER_OPTS=()
CLIENT_OPTS=()
TILE=""

usage() {
    sed -n '2,20p' "$0"
    echo "options: --map M --robots N --after S --size WxH --keys 'ACTIONS' --frames N"
    echo "         --interval S --tile CxR --out DIR --name NAME --trace [--trace-name NAME] [--trace-objs] [--trace-modes] --seed N"
    echo "         --server-opt OPT (repeatable) --client-opt OPT (repeatable)"
    echo "ACTIONS: space separated: down:KEY up:KEY key:KEY sleep:SECS type:TEXT"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --map) MAP="$2"; shift 2;;
        --robots) ROBOTS="$2"; shift 2;;
        --after) AFTER="$2"; shift 2;;
        --size) SIZE="$2"; shift 2;;
        --keys) KEYS="$2"; shift 2;;
        --frames) FRAMES="$2"; shift 2;;
        --interval) INTERVAL="$2"; shift 2;;
        --tile) TILE="$2"; shift 2;;
        --out) OUT="$2"; shift 2;;
        --name) NAME="$2"; shift 2;;
        --trace) TRACE=1; shift;;
        --trace-name) TRACE_NAME="$2"; shift 2;;
        --trace-objs) TRACE_OBJS=1; shift;;
        --trace-modes) TRACE_MODES=1; shift;;
        --seed) SEED="$2"; shift 2;;
        --server-opt) SERVER_OPTS+=("$2"); shift 2;;
        --client-opt) CLIENT_OPTS+=("$2"); shift 2;;
        -h|--help) usage; exit 0;;
        *) echo "scenario.sh: unknown option $1" >&2; usage >&2; exit 2;;
    esac
done

mkdir -p "$OUT"
W=${SIZE%x*}
H=${SIZE#*x}
export DISPLAY=:99
T0=$(date +%s.%N)

XVFB_PID=""
SERVER_PID=""
CLIENT_PID=""

cleanup() {
    for p in $CLIENT_PID $SERVER_PID $XVFB_PID; do
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            kill -INT "$p" 2>/dev/null
        fi
    done
    sleep 0.5
    for p in $CLIENT_PID $SERVER_PID $XVFB_PID; do
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            kill -KILL "$p" 2>/dev/null
        fi
    done
    if [ -f "$OUT/server.raw" ]; then
        grep -aE '^XP(TRACE|OBJ|PULSE|ECM|ITEMS|MOBJ|PL2|TRS|TGT|TEAM|MT)' "$OUT/server.raw" > "$OUT/trace.txt" || true
        grep -avE '^XP(TRACE|OBJ|PULSE|ECM|ITEMS|MOBJ|PL2|TRS|TGT|TEAM|MT)' "$OUT/server.raw" > "$OUT/server.log" || true
        rm -f "$OUT/server.raw"
        [ "$TRACE" = 1 ] || rm -f "$OUT/trace.txt"
    fi
}
trap cleanup EXIT

log() { echo "[scenario $(date +%H:%M:%S)] $*"; }

# 1. X server.  Screen exactly the client size; client is placed at +0+0.
Xvfb :99 -screen 0 "${W}x${H}x24" -nolisten tcp -noreset > "$OUT/xvfb.log" 2>&1 &
XVFB_PID=$!
for _ in $(seq 1 50); do
    xdpyinfo > /dev/null 2>&1 && break
    sleep 0.1
done
xdpyinfo > /dev/null 2>&1 || { log "Xvfb did not start"; cat "$OUT/xvfb.log"; exit 3; }

# 2. Server.  -minRobots/-robots both N gives exactly N robots next to the
# human.  No meta server reporting (the container also has no network).
if [ "$TRACE" = 1 ]; then
    export XPILOT_TRACE=1
    export XPILOT_TRACE_NAME="$TRACE_NAME"
    [ "$TRACE_OBJS" = 1 ] && export XPILOT_TRACE_OBJS=1
    [ "$TRACE_MODES" = 1 ] && export XPILOT_TRACE_MODES=1
fi
[ -n "$SEED" ] && export XPILOT_SEED="$SEED"
xpilots -map "$MAP" -robots "$ROBOTS" -minRobots "$ROBOTS" -noQuit \
        +reportToMetaServer "${SERVER_OPTS[@]}" > "$OUT/server.raw" 2>&1 &
SERVER_PID=$!
unset XPILOT_TRACE XPILOT_TRACE_NAME XPILOT_TRACE_OBJS XPILOT_TRACE_MODES XPILOT_SEED
for _ in $(seq 1 100); do
    grep -aq "Server runs at" "$OUT/server.raw" 2>/dev/null && break
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 0.1
done
grep -aq "Server runs at" "$OUT/server.raw" || { log "server did not start"; cat "$OUT/server.raw"; exit 4; }

# 3. Client.  -join skips the interactive server menu.
xpilot -display :99 -name "$NAME" -join -noLocalMotd \
       -autoServerMotdPopup no -geometry "${W}x${H}+0+0" \
       "${CLIENT_OPTS[@]}" localhost > "$OUT/client.log" 2>&1 &
CLIENT_PID=$!
WID=""
for _ in $(seq 1 150); do
    WID=$(xdotool search --onlyvisible --name 'Server at' 2>/dev/null | head -1)
    [ -n "$WID" ] && break
    kill -0 "$CLIENT_PID" 2>/dev/null || break
    sleep 0.1
done
if [ -z "$WID" ]; then
    log "client window did not appear"; cat "$OUT/client.log"; exit 5
fi
# The window appears well before the ship is in play (login, map
# transfer).  Wait for the server to place the human ship on a base.
joined=0
for _ in $(seq 1 200); do
    if grep -a 'starts at startpos' "$OUT/server.raw" | grep -avq 'robot)'; then
        joined=1; break
    fi
    kill -0 "$CLIENT_PID" 2>/dev/null || break
    sleep 0.1
done
if [ "$joined" != 1 ]; then
    log "player never entered the game"; cat "$OUT/client.log"; exit 6
fi
T_JOIN=$(date +%s.%N)
log "client window $WID; player in play; waiting ${AFTER}s"
sleep "$AFTER"

# 4. Keys.  No window manager, so give the window input focus explicitly
# and use XTEST (real key events, not XSendEvent).
if [ -n "$KEYS" ]; then
    # (no --sync: without a window manager xdotool's --sync waits for
    # events that never come and only gives up after a long timeout)
    xdotool mousemove $((W / 2)) $((H / 2)) 2>/dev/null
    xdotool windowfocus "$WID" 2>/dev/null
    sleep 0.1
    for act in $KEYS; do
        op=${act%%:*}
        arg=${act#*:}
        case "$op" in
            down) xdotool keydown "$arg";;
            up) xdotool keyup "$arg";;
            key) xdotool key "$arg";;
            sleep) sleep "$arg";;
            type) xdotool type -- "$arg";;
            *) log "unknown key action $act";;
        esac
    done
fi

capture() {
    xwd -silent -id "$WID" | convert xwd:- "png:$1"
}

# 5. Capture.
if [ "$FRAMES" -gt 0 ]; then
    for i in $(seq 1 "$FRAMES"); do
        capture "$(printf '%s/frame_%03d.png' "$OUT" "$i")"
        [ "$i" -lt "$FRAMES" ] && sleep "$INTERVAL"
    done
    if [ -z "$TILE" ]; then
        cols=4
        [ "$FRAMES" -lt 4 ] && cols=$FRAMES
        TILE="${cols}x"
    fi
    montage -font DejaVu-Sans -label '%t' -fill white -background '#404040' \
            "$OUT"/frame_*.png -tile "$TILE" -geometry "$((W / 2))x$((H / 2))+4+4" \
            "$OUT/contact.png"
else
    capture "$OUT/shot.png"
fi
T_END=$(date +%s.%N)

{
    echo "map=$MAP robots=$ROBOTS after=$AFTER size=$SIZE frames=$FRAMES interval=$INTERVAL"
    echo "keys=$KEYS"
    xwininfo -id "$WID" | grep -E 'Width|Height|Absolute'
    echo "startup_to_play_s=$(awk -v a="$T_JOIN" -v b="$T0" 'BEGIN{printf "%.2f", a-b}')"
    echo "total_s=$(awk -v a="$T_END" -v b="$T0" 'BEGIN{printf "%.2f", a-b}')"
} > "$OUT/info.txt"
log "done"
exit 0
