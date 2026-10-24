Per-frame item/weapon traces of the original XPilot 4.5.5 server (C), recorded with the
reference harness in tools/xpilot-ref (patches 0002 + 0003, trace.sh --objs, image
xpilot-ref:items) on arm64. They are program output of XPilot 4.5.5 (GPL-2.0-or-later,
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

Every recording below is
  XPREF_IMAGE=xpilot-ref:items tools/xpilot-ref/trace.sh --objs --map default --seed 42
    --after 1 --server-opt -gravity --server-opt -0.14 <options> --keys "<keys>"
    --out server/src/test/resources/ctrace/items/<name>.txt
with 0 robots and only the human player traced. <options> are listed as server words;
each word is passed with its own --server-opt. Frame numbers below are the frame
column (frame_loops). Key timing is wall-clock, so on a re-recording events can move by
a frame or so.

torpedo.txt (85 frames): torpedo fired at rest, then one while thrusting.
  options: -initialMissiles 4
  keys: "sleep:0.5 down:quoteright sleep:0.15 up:quoteright sleep:1 down:Shift_L sleep:0.5
         down:quoteright sleep:0.15 up:quoteright sleep:0.3 up:Shift_L sleep:3"
  Torpedo 1 F22-F50, torpedo 2 F43-F67 (thrust F37-F48).

heat-seeker.txt (80 frames): thrust and fire a heat-seeker, which locks on its own ship
(info = 1) and turns back.
  options: -initialMissiles 4
  keys: "sleep:0.3 down:Shift_L sleep:0.4 down:semicolon sleep:0.15 up:semicolon
         sleep:0.4 up:Shift_L sleep:4"
  Thrust F19-F31, heat-seeker F24-F45.

mines.txt (78 frames): drop a mine at rest, then detach (throw) one while thrusting.
  options: -initialMines 4 -mineFuseTime 0
  keys: "sleep:0.5 down:Tab sleep:0.15 up:Tab sleep:0.6 down:Shift_L sleep:0.5
         down:bracketright sleep:0.15 up:bracketright sleep:0.2 up:Shift_L sleep:3"
  Dropped mine from F20 (stationary), thrust F30-F40, detached mine from F37 (moving).

laser.txt (65 frames): lift off, turn to point right, hold the laser; later pulses are cut
short by the right-hand wall (len < 85 from F35).
  options: -initialLasers 2
  keys: "sleep:0.3 down:Shift_L sleep:0.25 up:Shift_L down:a sleep:0.4 up:a down:slash
         sleep:1 up:slash sleep:2"
  Pulses F28-F40.

wideangle-rearshot.txt (58 frames): hold fire with wideangles and rearshots.
  options: -initialWideangles 2 -initialRearshots 3
  keys: "sleep:0.5 down:Return sleep:0.6 up:Return sleep:2.5"
  Volleys of 5 shots every second frame F23-F29.

afterburner.txt (62 frames): thrust 1 s with 5 afterburners.
  options: -initialAfterburners 5
  keys: "sleep:0.3 down:Shift_L sleep:1 up:Shift_L sleep:2.5"
  Thrust F19-F31.

emergency-thrust.txt (64 frames): engage emergency thrust, then thrust.
  options: -initialEmergencyThrusts 1
  keys: "sleep:0.3 down:j sleep:0.15 up:j sleep:0.2 down:Shift_L sleep:0.35 up:Shift_L
         sleep:3"
  Engaged F19 (left = 48), thrust F24-F27.

cloak-phasing.txt (108 frames): cloak 1 s, uncloak, thrust off the base, phase and wait
out the phasing.
  options: -initialCloaks 1 -initialPhasings 1
  keys: "sleep:0.3 down:Delete sleep:0.15 up:Delete sleep:1 down:Delete sleep:0.15
         up:Delete sleep:0.3 down:Shift_L sleep:0.4 up:Shift_L down:p sleep:0.15 up:p
         sleep:5"
  Cloaked F19-F32, thrust F40-F44, phasing F45-F91.

ecm.txt (54 frames): fire one ECM at rest.
  options: -initialECMs 2
  keys: "sleep:0.5 down:bracketleft sleep:0.15 up:bracketleft sleep:2.5"
  ECM F21-F28.

lose-item.txt (89 frames): select an item and lose it: two mines, then a missile.
  options: -initialMines 3 -initialMissiles 2
  keys: "sleep:0.3 down:KP_0 sleep:0.15 up:KP_0 sleep:0.3 down:KP_Delete sleep:0.15
         up:KP_Delete sleep:0.6 down:KP_Delete sleep:0.15 up:KP_Delete sleep:0.6
         down:KP_0 sleep:0.15 up:KP_0 sleep:0.3 down:KP_Delete sleep:0.15 up:KP_Delete
         sleep:3"
  Mine items dropped F25 and F35, missile lost F50.

tanks.txt (74 frames): switch tanks and thrust so that fuel moves between tanks.
  options: -initialTanks 2
  keys: "sleep:0.3 down:e sleep:0.15 up:e sleep:0.2 down:Shift_L sleep:0.5 up:Shift_L
         sleep:0.3 down:e sleep:0.15 up:e sleep:0.2 down:Shift_L sleep:0.4 up:Shift_L
         sleep:0.3 down:w sleep:0.15 up:w sleep:2"
  Current tank 1 from F18, thrust F23-F29, tank 2 from F34, thrust F38-F42, tank 1 from
  F47.
