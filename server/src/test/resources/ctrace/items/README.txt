Per-frame item/weapon traces of the original XPilot 4.5.5 server (C), recorded with the
reference harness in tools/xpilot-ref (patches 0002, 0004 and 0006: trace.sh --objs --rand)
on arm64. They are program output of XPilot 4.5.5 (GPL-2.0-or-later,
Copyright (C) 1991-2010 by Bjoern Stabell, Ken Ronny Schouten, Bert Gijsbers, Dick Balaska
et al.) and are distributed under the same license as the rest of JXPilot
(GPL-2.0-or-later, see LICENSE at the repo root).

Line types (all whitespace separated; a *_HDR line at the top names the columns):
  XPTRACE  player physics, as in ../README.txt (patch 0002).
  XPOBJ    one per object in Obj[] except OBJ_SPARK/OBJ_DEBRIS:
           loop frame index type id team cx cy prevx prevy vx vy ax ay mass life status
           info count missile_dir fuselife pl_range pl_radius
           nuclear warhead velocity mini spread power laser  [extra]
           extra: OBJ_MINE(64) owner spread_left ecm_range; OBJ_TORPEDO(128) max_speed
           turnspeed spread_left; OBJ_HEAT_SHOT(256) max_speed turnspeed;
           OBJ_SMART_SHOT(32) max_speed turnspeed new_info ecm_range; others nothing.
           index is the Obj[] slot (sparks/debris occupy slots too, and deletion moves the
           last object into the freed slot, so an object's index can change).
  XPPULSE  loop frame index id team x y dir len life refl     (laser pulses, pixels)
  XPECM    loop frame index id x y size                       (active ECMs, pixels)
  XPITEMS  loop frame id item[0..20] used have fuel_sum fuel_max num_tanks current
           tank[0..8] emergency_thrust_left emergency_shield_left phasing_left
           lock_tagged lock_pl_id lock_distance sensor_range forceVisible damaged stunned
           num_pulses ecmcount
Order within a tick: XPTRACE, XPOBJ..., XPPULSE..., XPECM..., XPITEMS.

Patch 0006 lines (tools/xpilot-ref/patches/0006-server-random-trace.patch); they carry no
usable frame number and belong to the tick of the next XPTRACE line:
  XPRAND   func file:line f|i value...   every C rfrac() (f) and randomMT() (i) draw, in
           order; value is the 32-bit randomMT() number (rfrac() = value / 2^32). Several
           values on one line are consecutive draws of the same site.
  XPDEBRIS frame func file:line x y vx vy id team type mass status color radius
           min_debris max_debris min_dir max_dir min_speed max_speed min_life max_life
           every Make_debris call: its caller and arguments (floats as %a).
  XPKEYS   frame id k1,k2,...|-   a keyboard update from a client: the keys_t held down.
and, printed after the tick's other lines:
  XPDEB    loop frame index type id cx cy vx vy mass life status   explosion debris and
           wall-bounce sparks (every spark and debris object but the exhaust), kept only in
           the frame of each non-exhaust Make_debris call and the frame after it.
  XPCANNON loop frame index dead_time damaged tractor_count used emergency_shield_left
           phasing_left   every cannon that is not idle.

The replay (CReplay, CDraws) feeds the Java world C's draws by call site, C's keys, and
C's particles (explosion debris, wall-bounce sparks, cluster/flak fragments, in C's Obj[]
order); see CTraceItemsTest. The exhaust is the one thing it cannot give Java (its spark
count comes from JXPilot's (tick, id) seed, see DEVIATIONS-server.md), so keep other ships
out of a thrusting ship's exhaust.

Image: XPREF_IMAGE=xpilot-ref:items3-traces tools/xpilot-ref/build.sh (all patches in
tools/xpilot-ref/patches). Recording, for every trace below:
  XPREF_IMAGE=xpilot-ref:items3-traces tools/xpilot-ref/trace.sh --objs --rand --map default
    --seed <seed> --after 1 --server-opt -gravity --server-opt -0.14
    --server-opt +sparksWallBounce <options> --keys "<keys>"
    [<extra>] --out <name>.raw
  tools/xpilot-ref/compact-rand.sh <name>.raw [<last>] > <name>.txt
with 0 robots. <options> are server words, each passed with its own --server-opt.
+sparksWallBounce turns spark wall bounce off (exhaust sparks do not come back into the
ship). Cannons fire flak (default.xp's default). Only the human player is traced
unless <extra> has --all-players. Key timing is wall-clock: a re-recording differs by a
frame or so, and a recording whose client sent two keyboard updates in one tick (the replay
flags it) is redone. The C server and client default to autoShield (the shield is up unless
firing); --client-opt -autoShield --client-opt no plus a space tap drops it for good.
Frame numbers are the frame column (frame_loops).

Seed 42 puts the ship on base '3' at (121,41), next to a cannon; seed 2 on base (51,150) in
open space, far from cannons.

torpedo.txt (84 frames), seed 42: torpedo fired at rest, then one while thrusting.
  options: -initialMissiles 4
  keys: "sleep:0.5 down:quoteright sleep:0.15 up:quoteright sleep:1 down:Shift_L sleep:0.5
         down:quoteright sleep:0.15 up:quoteright sleep:0.3 up:Shift_L sleep:3"

heat-seeker.txt (79 frames), seed 42: thrust and fire a heat-seeker, which locks on its own
ship (info = 1) and turns back; the cannon shoots at the ship.
  options: -initialMissiles 4
  keys: "sleep:0.3 down:Shift_L sleep:0.4 down:semicolon sleep:0.15 up:semicolon
         sleep:0.4 up:Shift_L sleep:4"

heat-seeker-full.txt (105 frames), seed 2: the heat-seeker's whole flight: fired while
thrusting (close range, error 0), the ship coasts (error grows), thrusts again while the
missile is at mid range (error 8), coasts.
  options: -initialMissiles 4   extra: --client-opt -autoShield --client-opt no
  keys: "down:space sleep:0.15 up:space sleep:0.3 down:Shift_L sleep:0.3 down:semicolon
         sleep:0.15 up:semicolon sleep:0.1 up:Shift_L sleep:1.2 down:Shift_L sleep:1.2
         up:Shift_L sleep:4"

heat-search.txt (88 frames), seed 2: a heat-seeker fired at rest has no target (info -1);
when the ship starts thrusting the missile is at mid range and its search locks on with the
mid-range error (F40: info 1, count 8).
  options: -initialMissiles 4   extra: --client-opt -autoShield --client-opt no
  keys: "down:space sleep:0.15 up:space sleep:0.3 down:semicolon sleep:0.15 up:semicolon
         sleep:1.4 down:Shift_L sleep:1.5 up:Shift_L sleep:2.5"

smart.txt (140 frames, <last> 140), seed 2, two players: a second client ("Target", id 2,
shielded) joins at F41 on base (106,72); the first locks on it (Up) and fires a smart
missile, which steers around the walls for about 70 frames.
  options: -initialMissiles 4
  extra: --client-opt -autoShield --client-opt no --all-players --name2 target
  keys: "down:space sleep:0.15 up:space sleep:0.3 down:Up sleep:0.15 up:Up sleep:0.3
         down:backslash sleep:0.15 up:backslash sleep:6"

mines.txt (78 frames), seed 42: drop a mine at rest, then detach (throw) one while thrusting.
  options: -initialMines 4 -mineFuseTime 0
  keys: "sleep:0.5 down:Tab sleep:0.15 up:Tab sleep:0.6 down:Shift_L sleep:0.5
         down:bracketright sleep:0.15 up:bracketright sleep:0.2 up:Shift_L sleep:3"

mine-fuse.txt (74 frames), seed 2: drop a mine under the unshielded ship with a one second
fuse: owner immunity runs out after 12 frames, the mine goes off and kills the ship
(particles, wreckage).
  options: -initialMines 2 -mineFuseTime 1   extra: --client-opt -autoShield --client-opt no
  keys: "down:space sleep:0.15 up:space sleep:0.5 down:Tab sleep:0.15 up:Tab sleep:4"

laser.txt (69 frames), seed 42: lift off, turn left, hold the laser; the pulses hit the wall.
  options: -initialLasers 2
  keys: "sleep:0.3 down:Shift_L sleep:0.25 up:Shift_L sleep:0.1 down:a sleep:0.55 up:a
         sleep:0.1 down:slash sleep:1 up:slash sleep:2"

wideangle-rearshot.txt (58 frames), seed 42: hold fire on the base with wideangles and
rearshots (the rear shots go into the ceiling).
  options: -initialWideangles 2 -initialRearshots 3
  keys: "sleep:0.5 down:Return sleep:0.6 up:Return sleep:2.5"

rearshot.txt (68 frames), seed 42: thrust off the base, then hold fire with 3 rearshots.
  options: -initialRearshots 3
  keys: "sleep:0.3 down:Shift_L sleep:0.8 up:Shift_L sleep:0.3 down:Return sleep:0.5
         up:Return sleep:2.5"

afterburner.txt (61 frames), seed 42: thrust 1 s with 5 afterburners.
  options: -initialAfterburners 5
  keys: "sleep:0.3 down:Shift_L sleep:1 up:Shift_L sleep:2.5"

emergency-thrust.txt (63 frames), seed 42: engage emergency thrust, then thrust.
  options: -initialEmergencyThrusts 1
  keys: "sleep:0.3 down:j sleep:0.15 up:j sleep:0.2 down:Shift_L sleep:0.35 up:Shift_L
         sleep:3"

cloak-phasing.txt (107 frames), seed 42: cloak 1 s, uncloak, thrust off the base, phase and
wait out the phasing.
  options: -initialCloaks 1 -initialPhasings 1
  keys: "sleep:0.3 down:Delete sleep:0.15 up:Delete sleep:1 down:Delete sleep:0.15
         up:Delete sleep:0.3 down:Shift_L sleep:0.4 up:Shift_L sleep:0.1 down:p sleep:0.15
         up:p sleep:5"

ecm.txt (53 frames), seed 42: fire one ECM at rest; the cannon 5.8 blocks away is damaged
(XPCANNON) and stops firing.
  options: -initialECMs 2
  keys: "sleep:0.5 down:bracketleft sleep:0.15 up:bracketleft sleep:2.5"

lose-item.txt (84 frames), seed 42: thrust off the base, then select an item and lose it: a
mine, a pack of mines, then (after selecting the next item) a pack of missiles.
  options: -initialMines 3 -initialMissiles 2
  keys: "sleep:0.3 down:Shift_L sleep:0.5 up:Shift_L sleep:0.2 down:KP_0 sleep:0.15
         up:KP_0 sleep:0.2 down:KP_Delete sleep:0.15 up:KP_Delete sleep:0.4
         down:KP_Delete sleep:0.15 up:KP_Delete sleep:0.4 down:KP_0 sleep:0.15 up:KP_0
         sleep:0.2 down:KP_Delete sleep:0.15 up:KP_Delete sleep:2.5"

lose-item-right.txt (80 frames), seed 42: thrust, turn left, thrust again so that the ship
moves right (vx > 0), select the mines and lose a pack (F40); a second pack is lost once
gravity has turned vx negative (F48): both branches of Place_item's drop offset and speed.
  options: -initialMines 3 -initialMissiles 2
  keys: "sleep:0.3 down:Shift_L sleep:0.3 up:Shift_L sleep:0.1 down:a sleep:0.25 up:a
         sleep:0.1 down:Shift_L sleep:0.3 up:Shift_L sleep:0.2 down:KP_0 sleep:0.15 up:KP_0
         sleep:0.2 down:KP_Delete sleep:0.15 up:KP_Delete sleep:0.4 down:KP_Delete
         sleep:0.15 up:KP_Delete sleep:2.5"

tanks.txt (73 frames), seed 42: switch tanks and thrust so that fuel moves between tanks.
  options: -initialTanks 2
  keys: "sleep:0.3 down:e sleep:0.15 up:e sleep:0.2 down:Shift_L sleep:0.5 up:Shift_L
         sleep:0.3 down:e sleep:0.15 up:e sleep:0.2 down:Shift_L sleep:0.4 up:Shift_L
         sleep:0.3 down:w sleep:0.15 up:w sleep:2"

The arena traces use items-arena.xp (this directory; not an XPilot original, GPL as the
rest of JXPilot): a closed 21x14 box with two bases, the upper one 4 blocks above the lower,
and an item concentrator just above the upper base. Recording:
  tools/xpilot-ref/trace.sh --objs --rand --map-file
    server/src/test/resources/ctrace/items/items-arena.xp --seed 4 --after 1
    --server-opt -gravity --server-opt 0 <common> <options> --all-players --name2 target
    --keys "<keys>" --out <name>.raw;  compact-rand.sh <name>.raw 100 > <name>.txt
  common: -initialLasers 3 -initialECMs 1 -initialMissiles 4 -itemConcentratorProb 1
          -itemConcentratorRadius 1 -movingItemProb 0
  keys: "focus:2 down:space sleep:0.15 up:space sleep:0.2 focus:1 sleep:2.5
         down:bracketleft sleep:0.15 up:bracketleft sleep:0.5 down:slash sleep:0.6 up:slash
         sleep:1.5"
Seed 4 puts the first player on the lower base; the second ("Target", id 2) joins at F7 on
the upper one, drops its shield (space tap in its window) and picks up the items that
spawn at the concentrator; the first fires an ECM at it, then its laser straight up into it.

arena-laser.txt (100 frames): energy packs picked up (C's ENERGY_PACK_FUEL draw, F27), an
armoured target: the first laser hit costs it ED_LASER_HIT (F78), it dies at F79.
  options: -itemMineProb 0.01 -itemEnergyPackProb 0.01 -maxItemDensity 0.02 -initialArmors 1

arena-throw.txt (100 frames): the target picks up 10 mines and is killed by the laser (F78);
with dropItemOnKillProb 1 they are thrown as one pack with the dead ship's random push.
  options: -itemMineProb 9.4e-5 -maxItemDensity 0.1 -dropItemOnKillProb 1
           -detonateItemOnKillProb 0

arena-detonate.txt (121 frames, to the end of the recording; keys end with sleep:2.5 instead
of sleep:1.5, <last> 130): the target picks up 9 mines and is killed by the laser (F78); with
detonateItemOnKillProb 1 every mine above the initial count goes off (Detonate_items' random
direction and speed) and explodes, and the mine explosions' debris throws the first ship
about between the walls. Particles made in one tick while older objects die are moved into
the freed Obj[] slots (Delete_shot, object.c:97), and the order in which they push a ship
(cell lists, cell.c:229-233) matters: the replay gives Java's particles C's states in C's
slot order (XPDEB, which must cover every particle of the frame, wall-bounce sparks included).
  options: -itemMineProb 0.01 -maxItemDensity 0.05 -dropItemOnKillProb 0
           -detonateItemOnKillProb 1

ball-ecm.txt (110 frames, <last> 110), on ball-ecm.xp (this directory; ball-tow.xp with a
second base 6 blocks to the right), seed 1, two players: the second ("Target", id 2) joins
on the base next to the treasure, grabs the ball with the connector while thrusting up,
turns right and tows it towards the first, which fires ECMs: the second ECM draw (item.c:1175)
is below 20 * range + 5 and the ball comes off (F65).
  tools/xpilot-ref/trace.sh --objs --rand --map-file
    server/src/test/resources/ctrace/items/ball-ecm.xp --seed 1 --after 1
    --server-opt +sparksWallBounce --server-opt -initialECMs --server-opt 8
    --all-players --name2 target --keys "focus:2 down:Control_L sleep:0.1 down:Shift_L
    sleep:0.6 up:Shift_L sleep:0.4 up:Control_L sleep:0.1 down:s sleep:0.3 up:s sleep:0.1
    down:Shift_L sleep:0.25 up:Shift_L sleep:0.2 focus:1 <8 x: down:bracketleft sleep:0.15
    up:bracketleft sleep:0.3> sleep:1.5" --out ball-ecm.raw;  compact-rand.sh ball-ecm.raw 110
