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
tools/xpilot-ref/README.md). WreckageGoldenTest replays each call with those draws and compares
every frame bit for bit.

thrust-turn-impact.txt (139 frames): thrust, turn while thrusting, coast through the
gravity field, a 25 px/frame wall bounce, coast.
  tools/xpilot-ref/trace.sh --map default --seed 42 --after 1
    --server-opt -gravity --server-opt -0.14 --server-opt +sparksWallBounce
    --keys "down:Shift_L sleep:0.5 down:a sleep:0.3 up:a sleep:0.4 up:Shift_L sleep:9"

Game-mode traces (ModeTraceTest) use xpilot-ref patch 0005 (trace.sh --modes) and a map from this
directory (--map-file); the image is built with that patch (XPREF_IMAGE=xpilot-ref:4.5.5 build.sh).
The maps pin the options C's lib/defaults would otherwise set (gravity -0.25, no random items).
When a trace carries C's random generator, the replay also makes C's own sparks and debris (Make_debris
draws in C's order, Debris.cParticles and Debris.cThrust) instead of the client-replayable bursts, so
particles hitting ships, asteroids and balls match C too.

ball-tow.txt (75 frames): reach for the treasure ball with the connector (Control_L) while thrusting
up, connect at ballConnectorLength, tow and swing it on the spring connector.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/ball-tow.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:0.6 up:Shift_L sleep:3 up:Control_L sleep:0.5"

race-climb.txt (213 frames): pass checkpoint A on the base (lap 1 starts), climb through B, fall back
through A (lap 1 completed in 171 frames), bounce off the floor.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/race-climb.xp --seed 42 --after 1 --modes
    --keys "down:Shift_L sleep:0.8 up:Shift_L sleep:13"

asteroid-box.txt (214 frames): asteroids appear, fall, bounce off walls and wall blocks, spin and break
again and again (wreckage). The trace carries C's random generator (XPMTP/XPMTN draw counts, the XPMT state):
ModeTraceTest replays C's own generator (CRandom, seeded 42 like the server) and compares placement, motion,
breaks, wreckage and the number of random draws in every frame.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/asteroid-box.xp --seed 42 --after 1 --modes --keys "sleep:14"

race-respawn.txt (255 frames): lap 1 starts at checkpoint A on the base; the ship self-destructs (End),
explodes (wreckage) and reappears at A with C's random speed and heading (Go_home), replayed with C's generator.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/race-climb.xp --seed 42 --after 1 --modes
    --keys "sleep:1 key:End sleep:16"

ball-drop.txt (112 frames): as ball-tow, then the ball is dropped ('d') and flies free, bouncing off the
floor and the right wall (balls keep their life on bounces).
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/ball-tow.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:0.6 up:Shift_L sleep:0.8 up:Control_L key:d sleep:5"

target-shoot.txt (171 frames): team play; the team 1 ship fires up at a team 2 target (fire held; C's client
lowers the shield to fire, autoShield): every shot wears it down, it repairs +7 a frame between shots, is
destroyed (dead 3 s, targetDeadTime) and revives. C's random stream is replayed.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/target-shoot.xp --seed 42 --after 1 --modes
    --keys "sleep:3 down:Return sleep:3 up:Return sleep:5"

treasure-cash.txt (95 frames): team play; the team 1 ship connects to the team 2 ball and pulls it sideways
into its own treasure: with nobody of team 2 in the game C only awards Rate/2 (Punish_team) and the ball is
remade at home.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/treasure-cash.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:0.5 up:Shift_L sleep:4 up:Control_L sleep:1"

race-laps.txt (403 frames): race-laps.xp (race-climb with raceLaps 2): two climbs complete both laps, the race
finishes (scores, Race_game_over resets everyone) and a new race starts. C's random stream is replayed.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/race-laps.xp --seed 42 --after 1 --modes
    --keys "down:Shift_L sleep:0.8 up:Shift_L sleep:13 down:Shift_L sleep:0.8 up:Shift_L sleep:13"

asteroid-ram.txt (268 frames): an idle shielded ship under an asteroid concentrator (asteroid-ram.xp): asteroids
hit it and break, their debris hits it, one pushes it into the treasure (death), and the breaking goes on.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/asteroid-ram.xp --seed 42 --after 1 --modes
    --keys "sleep:18"

asteroid-thrust.txt (194 frames): asteroid-box with the ship thrusting up among the asteroids, turning and
thrusting again: thrust sparks push asteroids, the ship rams and breaks them.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/asteroid-box.xp --seed 42 --after 1 --modes
    --keys "sleep:4 down:Shift_L sleep:1.2 up:Shift_L down:s sleep:0.3 up:s down:Shift_L sleep:1 up:Shift_L sleep:6"

asteroid-turn.txt (213 frames): asteroid-box, turn and thrust long into the asteroids, breaking them down to
size 1.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/asteroid-box.xp --seed 42 --after 1 --modes
    --keys "sleep:3 down:s sleep:0.4 up:s down:Shift_L sleep:2.5 up:Shift_L sleep:8"

ball-shaft.txt (109 frames): the ball lies in its treasure at the bottom of a shaft (ball-shaft.xp); the ship lifts
it out, drops it, and it falls back into its treasure and rests there again (life restarted), bouncing.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/ball-shaft.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:0.5 up:Shift_L up:Control_L key:d sleep:6"

ball-snap.txt (120 frames): as ball-shaft with maxBallConnectorRatio 0.05 (ball-snap.xp): the connector key
is held, the over-stretched connector snaps, the ship reaches again and reconnects, three times.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/ball-snap.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:1.2 up:Shift_L sleep:2 down:Shift_L sleep:1 up:Shift_L sleep:3 up:Control_L"

ball-ram.txt (108 frames): tow the ball up, then drop it: it bounces off the floor back into the shielded
ship, which bounces off it and pays ED_BALL_HIT (planned with the Java simulation: thrust 12-14 frames, drop
5-8 frames later).
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/ball-tow.xp --seed 42 --after 1 --modes
    --keys "down:Control_L down:Shift_L sleep:0.93 up:Shift_L sleep:0.46 up:Control_L key:d sleep:5"

team-rounds.txt (340 frames): team play with targets and one life (team-rounds.xp): the team 1 ship wears team 2's
target down (it repairs between shots), self-destructs, team 1 is dead and the round ends in a draw
(Team_game_over), then the next round starts.
  XPREF_IMAGE=xpilot-ref:4.5.5 tools/xpilot-ref/trace.sh
    --map-file server/src/test/resources/ctrace/team-rounds.xp --seed 42 --after 1 --modes
    --keys "sleep:3 down:Return sleep:2 up:Return key:End sleep:18"
