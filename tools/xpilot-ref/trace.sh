#!/bin/bash
# Numeric physics trace from the ORIGINAL XPilot 4.5.5 server (patch 0002).
#
# Runs server + client (+ Xvfb) like screenshot.sh, with XPILOT_TRACE set in
# the server's environment, and writes the XPTRACE lines for the human
# player to --out.  Use --keys to script input (e.g. hold thrust), and
# --server-opt to set physics options.
#
# usage: trace.sh --map NAME --out FILE.txt [--after S] [--keys "ACTIONS"]
#                 [--robots N] [--all-players] [--trace-objs] [--png FILE.png] [...]
#
#   --after S      seconds after the ship enters play before the keys start
#   --keys ACTIONS as screenshot.sh; the trace covers the whole run
#   --robots N     default 0 here (no robots disturbing the scenario)
#   --all-players  trace robots too (default: only the human player)
#   --map-file F   use the map file F from the host instead of a shipped map
#   --trace-objs   also XPOBJ/XPPL2/XPTRS/XPTGT/XPTEAM lines (patch 0003): balls,
#                  asteroids, wreckage, race and treasure/target state
#   --png FILE     also save a final screenshot
#
# Output columns (see header line XPTRACE_HDR, name is the last field and may
# contain spaces):
#   loop frame id robot x y cx cy vx vy ax ay dir float_dir turnvel turnacc
#   power mass fuel thrusting status name
# One line per player per server update tick (main_loops); x,y pixels,
# cx,cy clicks (1 pixel = 64 clicks), vel in pixels/tick.
set -u
. "$(dirname "$0")/lib.sh"
case " $* " in *" -h "*|*" --help "*) awk 'NR>1 && /^#/{sub(/^# ?/,""); print; next} NR>1{exit}' "$0"; exit 0;; esac
exec "$XPREF_DIR/scenario-host.sh" --mode trace "$@"
