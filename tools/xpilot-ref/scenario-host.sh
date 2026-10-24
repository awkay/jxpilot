#!/bin/bash
# Host-side runner shared by screenshot.sh, frames.sh and trace.sh.
# Not meant to be called directly; see those scripts for usage.
set -u
. "$(dirname "$0")/lib.sh"

MODE=shot
OUT=""
OUT_DIR=""
PNG=""
KEEP_LOGS=""
AFTER=5
FRAMES=8
INTERVAL=0.5
ROBOTS=""
ALL_PLAYERS=0
OBJS=0
RAND=0
NAME2=""
MAP_FILE=""
NAME=refpilot
KEYS=""
PASS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --mode) MODE="$2"; shift 2;;
        --out) OUT="$2"; shift 2;;
        --out-dir) OUT_DIR="$2"; shift 2;;
        --png) PNG="$2"; shift 2;;
        --keep-logs) KEEP_LOGS="$2"; shift 2;;
        --after) AFTER="$2"; shift 2;;
        --frames) FRAMES="$2"; shift 2;;
        --interval) INTERVAL="$2"; shift 2;;
        --robots) ROBOTS="$2"; shift 2;;
        --all-players) ALL_PLAYERS=1; shift;;
        --objs) OBJS=1; shift;;
        --rand) RAND=1; shift;;
        --name2) NAME2="$2"; shift 2;;
        --map-file) MAP_FILE="$2"; shift 2;;
        --name) NAME="$2"; shift 2;;
        --keys) KEYS="$2"; shift 2;;
        --map|--size|--tile|--seed|--server-opt|--client-opt) PASS+=("$1" "$2"); shift 2;;
        -h|--help) die "see screenshot.sh / frames.sh / trace.sh --help";;
        *) die "unknown option $1";;
    esac
done

case "$MODE" in
    shot) [ -n "$OUT" ] || die "--out FILE.png is required";;
    frames) [ -n "$OUT_DIR" ] || die "--out-dir DIR is required";;
    trace) [ -n "$OUT" ] || die "--out FILE.txt is required"
           [ -n "$ROBOTS" ] || ROBOTS=0;;
    *) die "bad mode $MODE";;
esac
[ -n "$ROBOTS" ] || ROBOTS=2
xpref_require_image

args=(--name "$NAME" --after "$AFTER" --robots "$ROBOTS" --out /out ${PASS[@]+"${PASS[@]}"})
[ -n "$KEYS" ] && args+=(--keys "$KEYS")
[ -n "$NAME2" ] && args+=(--name2 "$NAME2")
[ "$MODE" = frames ] && args+=(--frames "$FRAMES" --interval "$INTERVAL")
if [ "$MODE" = trace ]; then
    args+=(--trace)
    [ "$OBJS" = 1 ] && args+=(--trace-objs)
    [ "$RAND" = 1 ] && args+=(--trace-rand)
    if [ "$ALL_PLAYERS" = 0 ]; then
        # The server capitalises the first letter of the nick.
        cap="$(printf '%s' "${NAME:0:1}" | tr '[:lower:]' '[:upper:]')${NAME:1}"
        args+=(--trace-name "$cap")
    fi
fi

# Budget: startup + wait + keys + captures, generously rounded up.
key_sleep=$(printf '%s\n' $KEYS | awk -F: '$1=="sleep"{s+=$2} END{print s+0}')
budget=$(awk -v a="$AFTER" -v k="$key_sleep" -v f="$FRAMES" -v i="$INTERVAL" -v m="$MODE" \
    -v two="${NAME2:+1}" 'BEGIN{t=a+k+20; if (m=="frames") t+=f*(i+1); if (two) t+=15; printf "%d", t+0.999}')

tmp="$(xpref_tmpdir)"
if [ -n "$MAP_FILE" ]; then
    # a map that is not in the image: copied into the run's /out mount
    cp "$MAP_FILE" "$tmp/" || die "cannot read $MAP_FILE"
    chmod 644 "$tmp/$(basename "$MAP_FILE")"
    args+=(--map "/out/$(basename "$MAP_FILE")")
fi
cname=$(xpref_container_name "$MODE")
start=$(date +%s)
xpref_run_deadline "$((budget + 15))" "$cname" --network none -v "$tmp:/out" "$XPREF_IMAGE" \
    timeout -k 5 "$budget" /usr/local/share/xpilot-ref/scenario.sh "${args[@]}"
rc=$?
end=$(date +%s)

if [ "$rc" != 0 ]; then
    echo "$(basename "$0"): scenario failed (rc=$rc); logs kept in $tmp" >&2
    for f in server.log client.log xvfb.log; do
        [ -f "$tmp/$f" ] && { echo "--- $f"; tail -20 "$tmp/$f"; } >&2
    done
    exit "$rc"
fi

case "$MODE" in
    shot)
        mkdir -p "$(dirname "$OUT")"
        cp "$tmp/shot.png" "$OUT" || die "no shot.png produced (logs in $tmp)"
        echo "wrote $OUT";;
    frames)
        mkdir -p "$OUT_DIR"
        cp "$tmp"/frame_*.png "$tmp/contact.png" "$OUT_DIR"/ || die "no frames produced (logs in $tmp)"
        echo "wrote $FRAMES frames and contact.png to $OUT_DIR";;
    trace)
        mkdir -p "$(dirname "$OUT")"
        cp "$tmp/trace.txt" "$OUT" || die "no trace produced (logs in $tmp)"
        echo "wrote $(grep -c '^XPTRACE ' "$OUT") trace lines to $OUT"
        if [ -n "$PNG" ]; then cp "$tmp/shot.png" "$PNG" && echo "wrote $PNG"; fi;;
esac
if [ -n "$KEEP_LOGS" ]; then
    mkdir -p "$KEEP_LOGS"
    cp "$tmp"/server.log "$tmp"/client.log "$tmp"/info.txt "$KEEP_LOGS"/ 2> /dev/null
fi
echo "run took $((end - start))s"
rm -rf "$tmp"
