#!/bin/bash
# Screenshot of the ORIGINAL XPilot 4.5.5 X11 client playing on the original
# server, both inside one container on Xvfb.
#
# usage: screenshot.sh --map NAME --out FILE.png [--robots N] [--after S]
#                      [--size WxH] [--keys "ACTIONS"] [--name NICK] [--seed N]
#                      [--server-opt OPT]... [--client-opt OPT]...
#                      [--keep-logs DIR]
#
#   --map NAME     map from lib/maps without .xp (default: default)
#   --out FILE     PNG written on the host (required)
#   --robots N     exactly N robots (default 2)
#   --after S      seconds to wait after the ship enters play (default 5)
#   --size WxH     client window and Xvfb screen size (default 1026x768,
#                  the client's built-in default)
#   --keys ACTIONS xdotool actions run after --after, before the capture.
#                  Space separated: down:KEY up:KEY key:KEY sleep:SECS type:TEXT
#                  Keys left down stay held during the capture.
#                  e.g. "down:Shift_L sleep:2"   (thrust for 2s, still held)
#   --seed N       fixed server RNG seed (XPILOT_SEED, patch 0002)
#   --name NICK    player nick (default refpilot; server capitalises it)
#   --server-opt / --client-opt   one extra option word for xpilots / xpilot,
#                  repeatable, e.g. --server-opt +restrictRobots
#   --keep-logs DIR  copy server.log, client.log, info.txt there as well
set -u
. "$(dirname "$0")/lib.sh"
case " $* " in *" -h "*|*" --help "*) awk 'NR>1 && /^#/{sub(/^# ?/,""); print; next} NR>1{exit}' "$0"; exit 0;; esac
exec "$XPREF_DIR/scenario-host.sh" --mode shot "$@"
