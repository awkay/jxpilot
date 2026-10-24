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

wreckage-default.txt: every Make_wreckage of a 180 s game on default.xp (4 robots, seed 1,
lib/defaults; 7 cannon and 4 ship explosions, 30 pieces, 659 piece-frames) with the rfrac()
values each call drew, and every wreckage object after every frame (patch 0003, see
tools/xpilot-ref/README.md), plus the player lines (patch 0002, XPILOT_TRACE=1, same run) of every
frame with wreckage and the frame before. WreckageGoldenTest replays each call with those draws and
compares every frame bit for bit, checks that the 2 pieces C removes early were within a ship's reach,
and checks Wreckage.ship's arguments (whole-pixel position cx >> 6) for the 4 ship explosions.

thrust-turn-impact.txt (139 frames): thrust, turn while thrusting, coast through the
gravity field, a 25 px/frame wall bounce, coast.
  tools/xpilot-ref/trace.sh --map default --seed 42 --after 1
    --server-opt -gravity --server-opt -0.14 --server-opt +sparksWallBounce
    --keys "down:Shift_L sleep:0.5 down:a sleep:0.3 up:a sleep:0.4 up:Shift_L sleep:9"

Game-mode traces (ModeTraceTest) use xpilot-ref patch 0005 (trace.sh --modes) and a map from this
directory (--map-file); the image is built with that patch (XPREF_IMAGE=xpilot-ref:server-modes build.sh).
The maps pin the options C's lib/defaults would otherwise set (gravity -0.25, no random items).

ball-tow.txt (75 frames): reach for the treasure ball with the connector (Control_L) while thrusting
up, connect at ballConnectorLength, tow and swing it on the spring connector.
  XPREF_IMAGE=xpilot-ref:server-modes tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/ball-tow.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:0.6 up:Shift_L sleep:3 up:Control_L sleep:0.5"

race-climb.txt (213 frames): pass checkpoint A on the base (lap 1 starts), climb through B, fall back
through A (lap 1 completed in 171 frames), bounce off the floor.
  XPREF_IMAGE=xpilot-ref:server-modes tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/race-climb.xp --seed 42 --after 1 --modes
    --keys "down:Shift_L sleep:0.8 up:Shift_L sleep:13"

asteroid-box.txt (210 frames): asteroids appear (C RNG), fall, bounce off walls and wall blocks, spin and
break. ModeTraceTest injects each asteroid C creates into the Java world and compares motion, wall bounces
(with the life and brake factors), spin and life until the first one breaks.
  XPREF_IMAGE=xpilot-ref:server-modes tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/asteroid-box.xp --seed 42 --after 1 --modes --keys "sleep:14"

ball-drop.txt (112 frames): as ball-tow, then the ball is dropped ('d') and flies free, bouncing off the
floor and the right wall (balls keep their life on bounces).
  XPREF_IMAGE=xpilot-ref:server-modes tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/ball-tow.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:0.6 up:Shift_L sleep:0.8 up:Control_L key:d sleep:5"
