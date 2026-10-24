# Robot reference data

- `lib-robots`: the robots file of XPilot 4.5.5 (`lib/robots`), unmodified. GPL-2.0-or-later,
  part of the XPilot 4.5.5 distribution (see the top-level README's third-party table for XPilot
  data). Used by `RobotRosterTest` to check the built-in roster.
- `c-default-4robots.properties`, `c-default-8robots.properties`: robot behaviour statistics of the
  original C server on `default.xp` (12 FPS, rawMode) **with XPilot's `lib/defaults`** (gravity -0.25,
  SoftWalls; the Java side applies the same bundled file), computed by `RobotStats` (test sources) from
  per-frame `XPTRACE` traces of the `tools/xpilot-ref` image (patch 0002). Regenerated 2026-10-23 with the
  defaults file named explicitly (the image installs it as `/usr/local/lib/xpilot/defaults` and C reads it
  even without `-defaults`; the new traces are byte-identical to earlier runs without the flag):

  ```
  docker run --rm --network none -e XPILOT_TRACE=1 -e XPILOT_SEED=N xpilot-ref:4.5.5 \
      timeout -s INT -k 5 SECONDS xpilots -map default -robots R -minRobots R -noQuit +reportToMetaServer \
      -rawMode -defaults /usr/local/lib/xpilot/defaults 2> cRsN.trace
  #   R=4: N=1, 2 (600 s), N=7, 8 (1200 s)     R=8: N=3 (600 s), N=4, 6 (1200 s)
  java -cp server/target/test-classes:server/target/classes:common/target/classes \
      com.fulcrologic.jxpilot.server.robot.RobotStats maps/default.xp 12 TRACE...
  ```

  (`tools/xpilot-ref/server.sh --map default --robots R --seconds S --trace --seed N` runs the same
  command.) Seed 5 equals seed 4: the server's Mersenne Twister seeding ORs in bit 0.
  `stuckShare` is the share of alive frames in stuck runs (10 s or more within 3 pixels);
  `movingThrustFraction` and `stuckThrustFraction` split the thrust share between moving and stuck
  frames. The traces themselves (4-17 MB each) are not committed.
