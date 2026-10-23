#!/bin/bash
# Capture N screenshots of the original XPilot 4.5.5 client at a fixed
# interval and assemble them into a labelled contact sheet (ImageMagick
# montage) for motion review.
#
# usage: frames.sh --map NAME --out-dir DIR [--frames N] [--interval S]
#                  [--tile CxR] [plus every screenshot.sh option except --out]
#
#   --out-dir DIR  receives frame_001.png..frame_NNN.png and contact.png
#   --frames N     number of captures (default 8)
#   --interval S   seconds between captures (default 0.5).  Each capture
#                  itself takes ~0.1-0.2s, so the real spacing is a bit more.
#   --tile CxR     montage layout (default 4 columns); tiles are half size
#
# Keys (--keys) run before the first capture; a key left down stays held
# for all frames, e.g. --keys "down:Shift_L" to watch a thrusting ship.
set -u
. "$(dirname "$0")/lib.sh"
case " $* " in *" -h "*|*" --help "*) awk 'NR>1 && /^#/{sub(/^# ?/,""); print; next} NR>1{exit}' "$0"; exit 0;; esac
exec "$XPREF_DIR/scenario-host.sh" --mode frames "$@"
