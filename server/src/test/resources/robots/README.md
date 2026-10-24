# Robot reference data

- `lib-robots`: the robots file of XPilot 4.5.5 (`lib/robots`), unmodified. GPL-2.0-or-later,
  part of the XPilot 4.5.5 distribution (see the top-level README's third-party table for XPilot
  data). Used by `RobotRosterTest` to check the built-in roster.
- `c-default-4robots.properties`, `c-default-8robots.properties`: robot behaviour statistics of the
  original C server on `default.xp` (12 FPS, rawMode) **with XPilot's `lib/defaults`** (gravity -0.25,
  SoftWalls, random items; the Java side applies the same bundled file), computed by `RobotStats` (test sources)
  from per-frame `XPTRACE` traces of the `tools/xpilot-ref` image (patch 0002). Regenerated 2026-10-23 with the
  defaults file named explicitly (the image installs it as `/usr/local/lib/xpilot/defaults` and C reads it
  even without `-defaults`; the new traces are byte-identical to earlier runs without the flag):

  ```
  docker run --rm --network none -e XPILOT_TRACE=1 -e XPILOT_SEED=N xpilot-ref:4.5.5 \
      timeout -s INT -k 5 SECONDS xpilots -map default -robots R -minRobots R -noQuit +reportToMetaServer \
      -rawMode -defaults /usr/local/lib/xpilot/defaults 2> cRsN.trace
  #   R=4: N=1, 2, 10, 12 (600 s), N=7, 8 (1200 s)
  #   R=8: N=3, 9, 10, 12, 14 (600 s), N=4, 6 (1200 s)
  java -cp server/target/test-classes:server/target/classes:common/target/classes \
      com.fulcrologic.jxpilot.server.robot.RobotStats maps/default.xp 12 TRACE...
  ```

  (`tools/xpilot-ref/server.sh --map default --robots R --seconds S --trace --seed N` runs the same
  command.) Seeds N and N|1 play the same game (the server's Mersenne Twister seeding ORs in bit 0), so each
  file uses distinct N|1 only (runs 2026-10-23 and, for N >= 9, 2026-10-24).
- `c-default-4robots-noitems.properties`, `c-default-8robots-noitems.properties`: the same with
  `-itemProbMult 0` added (no items in the world), 600 s each: R=4: N=1, 2, 7, 8, 10; R=8: N=3, 4, 6, 9, 10.
  Recorded 2026-10-23.

Each file holds the pooled statistics of all its traces plus, per compared key, `KEY.runs`, `KEY.mean` and
`KEY.sd`: the number of single traces and the mean and standard deviation of their single-trace values
(`RobotStats.spread`; `stuckThrustFraction` only over traces with stuck frames). `RobotStatsRegressionTest`
compares the means within 3 standard errors of the difference (pooled values would let one long stuck
episode decide stuckThrustFraction, and pooled speed quantiles move in whole pixels). `stuckShare` is the share of alive frames in stuck runs (10 s or more within
3 pixels); `movingThrustFraction` and `stuckThrustFraction` split the thrust share between moving and stuck
frames. The traces themselves (4-17 MB each) are not committed.

Stuck robots are C behaviour. Recorded with patch 0007 (`XPILOT_TRACE_ROBOT`), C robots get stuck the same two
ways Java's do: resting on a floor in an evade mode with the turn blocked by the wall and no thrust (the evade
check takes the gravity direction as the travel direction below 0.2 px/frame and sees the floor), or thrusting
into a wall or slope in refuel, idle or climb mode with the turn blocked. Without items both sides spend the same
share of time stuck, mostly in the first way (stuckThrustFraction 0.25/0.05 in C, 0.32/0.24 in Java). With
items, C's long stuck runs are mostly the second way; they come from few episodes (2 of the 4 four-robot runs,
both at the fuel station at 36,37), and Java's stuck runs thrust less (0.29/0.45 against 0.91/0.73). That is
within the test's tolerance, because C has so few stuck runs. The harvest share and the thrust share of moving
frames only matched C once the robot kept C's `Robot_default_play_check_objects` index mix-ups
(robotdef.c:2089-2170; see `DefaultRobot.checkObjects`): the harvest target is the first object with the found
item's owner, not the item itself, and the danger the evade check looks at is `Obj[cell-list position]`.
