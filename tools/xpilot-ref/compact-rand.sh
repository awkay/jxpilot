#!/bin/bash
# Shrinks the patch 0006 random-draw lines of a trace (trace.sh --rand) for
# committing as a golden replay trace:
#   - drops the draws of the frame code (Frame_* functions: JXPilot draws
#     those from a per-viewer RNG by design) and the per-particle draws of
#     the thrust exhaust's Make_debris calls (JXPilot seeds the exhaust from
#     the tick by design; their particle-count draw, the first after each
#     XPDEBRIS line, is kept);
#   - keeps the explosion particles (XPDEB) only for the frame of each
#     explosion (a non-thrust XPDEBRIS line) and the frame after it;
#   - merges runs of XPRAND lines with the same function, site and kind into
#     one line holding all their values, in order.
# Every other line is copied unchanged.
#
# With LAST, the trace ends after frame LAST (everything from the first
# XPTRACE line of a later frame on is dropped).
#
# usage: compact-rand.sh IN.txt [LAST] > OUT.txt
set -eu
[ $# -ge 1 ] && [ $# -le 2 ] || { echo "usage: $(basename "$0") IN.txt [LAST] > OUT.txt" >&2; exit 2; }
LC_ALL=C awk -v last="${2:-}" '
$1 == "XPTRACE" && last != "" && $3 + 0 > last + 0 { flush(); exit }
function flush() { if (key != "") { print key vals; key = ""; vals = "" } }
$1 == "XPDEBRIS" { flush(); want = 1; thrust = ($3 == "Thrust"); if (!thrust) boom = $2; print; next }
$1 == "XPDEB" { flush(); if (boom != "" && $3 - boom <= 1) print; next }
$1 == "XPRAND" {
    if ($2 ~ /^Frame_/) next
    if ($2 == "Make_debris") { if (!want && thrust) next; want = 0 }
    k = $1 " " $2 " " $3 " " $4
    if (k != key) { flush(); key = k }
    vals = vals " " $5
    next
}
{ flush(); print }
END { flush() }
' "$1"
