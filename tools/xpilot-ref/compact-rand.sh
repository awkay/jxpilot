#!/bin/bash
# Shrinks the patch 0005 random-draw lines of a trace (trace.sh --rand) for
# committing as a golden replay trace:
#   - drops the draws of the frame code (Frame_* functions: JXPilot draws
#     those from a per-viewer RNG by design) and the per-particle draws of
#     the thrust exhaust's Make_debris calls (JXPilot seeds the exhaust from
#     the tick by design; their particle-count draw, the first after each
#     XPDEBRIS line, is kept);
#   - merges runs of XPRAND lines with the same function, site and kind into
#     one line holding all their values, in order.
# Every other line is copied unchanged.
#
# usage: compact-rand.sh IN.txt > OUT.txt
set -eu
[ $# -eq 1 ] || { echo "usage: $(basename "$0") IN.txt > OUT.txt" >&2; exit 2; }
LC_ALL=C awk '
function flush() { if (key != "") { print key vals; key = ""; vals = "" } }
$1 == "XPDEBRIS" { flush(); want = 1; thrust = ($3 == "Thrust"); print; next }
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
