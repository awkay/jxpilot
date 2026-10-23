Per-frame physics traces of the original XPilot 4.5.5 server (C), recorded with the
reference harness in tools/xpilot-ref (patch 0002, trace.sh) on arm64. They are program
output of XPilot 4.5.5 (GPL-2.0-or-later, Copyright (C) 1991-2010 by Bjoern Stabell,
Ken Ronny Schouten, Bert Gijsbers, Dick Balaska et al.) and are distributed under the
same license as the rest of JXPilot (GPL-2.0-or-later, see LICENSE at the repo root).
CTraceTest replays the same inputs in the Java simulation and compares every frame.

thrust-coast.txt (64 frames): rest on the base, thrust 13 frames, coast.
  tools/xpilot-ref/trace.sh --map default --seed 42 --after 1
    --server-opt -gravity --server-opt -0.14 --server-opt +sparksWallBounce
    --keys "down:Shift_L sleep:1 up:Shift_L sleep:3"

rest-turn.txt (68 frames): rest on the base (repeated floor bounces), a turn that the
wall blocks, turn velocity decay.
  tools/xpilot-ref/trace.sh --map default --seed 42 --after 1
    --server-opt -gravity --server-opt -0.14
    --keys "sleep:1.5 down:a sleep:0.4 up:a sleep:2.5"

thrust-turn-impact.txt (139 frames): thrust, turn while thrusting, coast through the
gravity field, a 25 px/frame wall bounce, coast.
  tools/xpilot-ref/trace.sh --map default --seed 42 --after 1
    --server-opt -gravity --server-opt -0.14 --server-opt +sparksWallBounce
    --keys "down:Shift_L sleep:0.5 down:a sleep:0.3 up:a sleep:0.4 up:Shift_L sleep:9"
