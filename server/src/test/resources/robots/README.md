# Robot reference data

- `lib-robots`: the robots file of XPilot 4.5.5 (`lib/robots`), unmodified. GPL-2.0-or-later,
  part of the XPilot 4.5.5 distribution (see the top-level README's third-party table for XPilot
  data). Used by `RobotRosterTest` to check the built-in roster.
- `c-default-4robots.properties`, `c-default-8robots.properties`: robot behaviour statistics of the
  original C server on `default.xp` (12 FPS, rawMode), computed by `RobotStats` (test sources) from
  per-frame `XPTRACE` traces of `tools/xpilot-ref` (patch 0002):

  ```
  tools/xpilot-ref/server.sh --map default --robots 4 --seconds 600  --trace --seed 1   # and --seed 2
  tools/xpilot-ref/server.sh --map default --robots 4 --seconds 1200 --trace --seed 7   # and --seed 8
  tools/xpilot-ref/server.sh --map default --robots 8 --seconds 600  --trace --seed 3
  tools/xpilot-ref/server.sh --map default --robots 8 --seconds 1200 --trace --seed 4   # and --seed 6
  java -cp server/target/test-classes:server/target/classes:common/target/classes \
      com.fulcrologic.jxpilot.server.robot.RobotStats maps/default.xp 12 TRACE...
  ```

  (Seed 5 was also run but equals seed 4: the server's Mersenne Twister seeding ORs in bit 0.)
  The traces themselves (4-17 MB each) are not committed.
