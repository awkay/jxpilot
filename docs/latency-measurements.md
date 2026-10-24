# Latency measurements

Phase 0 of [latency-fixes.md](latency-fixes.md) (§7, §8): the yardstick that later phases are measured
against. Everything here is measured headless, in virtual time, on the real Java server and the real
client pipeline, through an emulated lossy link.

## Headline

**Own-ship input-to-photon today is RTT + 43 ms on average on a clean link (RTT + 43 ms at the median,
RTT + 77 ms at p95).** That is not the RTT + 135 ms that the original §2.1 of latency-fixes.md
estimated.

| RTT | Original §2.1 estimate | Measured mean | p50 | p95 |
|---|---|---|---|---|
| 0 | ≈ 135 ms | 43.3 ms | 43 ms | 77 ms |
| 100 ms | ≈ 235 ms | 143.3 ms | 143 ms | 177 ms |
| 200 ms | ≈ 335 ms | 243.3 ms | 243 ms | 277 ms |
| 300 ms | ≈ 435 ms | 343.3 ms | 343 ms | 377 ms |

The table is seed 1, 48 trials per cell. Seeds 1 to 3 give a mean of RTT + 43.3..45.8, p50 RTT + 41..46 and
p95 RTT + 75..77. The stages below add up to about 44.

Why the original §2.1 is off: it was written against `31e9f40`, when the own ship was interpolated like
every other ship (1.28 ticks ≈ 91 ms behind). Commit `d722c79` (F1, "own ship predicted to the present
with error blending") landed after the doc. Since then `ClientWorld.predictSelf` draws the own ship from
the *newest* SELF, dead-reckoned to the estimated server tick, and uses the newest SELF's heading as is.
A SELF that carries a key's effect shows on the very next render frame, so the 91 ms interpolation term
is gone. What is left matches the measurement:

| Stage | Time |
|---|---|
| Key down until the next render frame polls it (120 Hz) | 0-8.3 ms, mean 4.2 |
| Uplink | RTT/2 |
| Wait for the next server tick (inputs apply at the next tick, 14 Hz) | 0-71 ms, mean 36 |
| Downlink | RTT/2 |
| Snapshot received until the next render frame draws it | 0-8.3 ms, mean ≈ 4 |
| **Total** | **≈ RTT + 44 ms mean (RTT + 0..88 ms)** |

The measurement stops at the frame that draws the change. Buffer swap and display (§2.1's "render and
present", 5-10 ms) are not modelled.

The four courses give the same I2P for the same link cell. I2P depends on timing (tick phase, frame
phase, link), not on where the ship flies, and the courses share their seeds.

Turn and thrust come out the same. The first tick of TURN_LEFT already moves the heading the server
sends (`dir`, 128 steps per turn) by at least one step at the default turn speed. The first tick of
THRUST changes the velocity and sets the THRUSTING flag, so the flame shows at the I2P frame. The drawn
*position* follows later: it moves more than 1 px away from the no-key path 16-33 ms after the flame
(median 25 ms over seeds 1-3; column "thrust >1px"). F1 blends a new SELF's prediction error out over
about 60 ms instead of jumping to the new path.

**Jitter and loss** move the median little but stretch the tail:

The figures below are seeds 1-3 on the walls course, against the clean link at the same RTT:

- **σ 15 ms jitter at RTT 0** adds 12-21 ms to the median. The delay is clamped at 0, so each direction
  gets a half-normal ≈ 6 ms on average, and the FIFO path adds a little (a late packet holds back the
  ones behind it).
- **σ 15 ms jitter at RTT 100-300** shifts the median by -7..+13 ms and the p95 by +13..+39 ms: the
  symmetric jitter mostly averages out in the median, not in the tail.
- **Pareto spikes** add 0-10 ms to the median and up to 68 ms to the p95.
- **1% uniform loss** barely shows: p95 RTT + 75..82.
- **5% bursty loss** (bursts of mean length 3 packets) puts the p95 anywhere from RTT + 82 to RTT + 430
  ms. A burst can eat the INPUT that carries the key and its redundant copy one tick later; then the SELF
  that shows it is lost too, and the next one has to arrive.

## Shots

**A shot shows up RTT + 138 ms after the fire key on average on a clean link (p50 RTT + 138, p95 RTT +
167): 95 ms more than the own ship's response to the same press.**

| RTT | Own ship mean | Shot mean | Shot p50 | Shot p95 | Shot mean, σ 15 ms jitter | Shot p95, σ 15 ms |
|---|---|---|---|---|---|---|
| 0 | 43 ms | 138 ms | 138 ms | 167 ms | 147 ms | 179 ms |
| 100 ms | 143 ms | 238 ms | 238 ms | 267 ms | 249 ms | 299 ms |
| 200 ms | 243 ms | 338 ms | 338 ms | 367 ms | 331 ms | 360 ms |
| 300 ms | 343 ms | 438 ms | 438 ms | 467 ms | 436 ms | 487 ms |

The table is the free flight course, seed 1, 24 presses per cell. On walls the clean figures are the same.
Seeds 1-3 put the clean shot mean at RTT + 137.6..138.2 ms on both courses.

**Where the extra 95 ms comes from:** every press is measured twice, once when the own ship shows it (its
SELF carries the shot's recoil) and once when the shot is drawn. The shot is drawn 11 or 12 frames after
the own ship, in every trial (91.7-100 ms; median 91.7). That gap is the interpolation delay:

- **The tick the fire lands on is the same tick a turn or thrust lands on.** A press is applied at the next
  server tick like any key. `ShipKeys` fires on the press itself: no fireRepeatRate wait for the first
  shot, and the shot limit cannot bite a single press. The new shot is in the same snapshot as the SELF
  that shows the recoil. So the part up to that snapshot is the own-ship path (RTT + ≈ 43 ms: key poll,
  uplink, wait for the tick, downlink, draw frame).
- **The interpolation delay adds 1.28 ticks.** The own ship is drawn from the newest SELF, dead-reckoned to
  the present (F1). Shots are drawn on the interpolated timeline, from the snapshot at or before the render
  tick (`ClientWorld.drawShots`). The render tick is the clock's estimate of the tick now minus 1 snapshot
  interval + 20 ms jitter margin, which is 1.28 T = 91.4 ms at 14 Hz. A new shot is drawn once the render
  tick reaches its tick, at the next 120 Hz frame: 11 or 12 frames after its SELF.
- **Nothing else.** No shot is ever drawn before its snapshot arrived (asserted), and the press-to-tick part
  matches the own ship's.

So a predicted own shot (latency-fixes §4.7) removes the whole RTT plus about 140 ms. Even without
prediction, drawing the own shots on the own ship's timeline instead of the interpolated one would take
about 92 ms off at any RTT.

With σ 15 ms jitter the per-cell median gap between the own ship and the shot is 83-108 ms (seeds 1-3,
10-13 frames). The own ship is drawn as soon as its SELF arrives, but the shot waits for the smoothed render
clock, so a SELF that arrives early or late moves the gap by about the jitter.

**Method, shots** (`InputToPhoton.measureShots`): the same games, baselines and stratified phases as the
turn and thrust trials, every press FIRE_SHOT.

- **Spawn shield.** Ships spawn shielded (`playerStartsShielded`, the default), and a shielded ship cannot
  fire. So shot games tap SHIELD 100-200 ms after the spawn, as a player does to drop it, and presses start
  2.5 s after the spawn. Where shields are not allowed, the spawn shield lasts 2 s.
- **The server side.** The press's tick is the first server tick whose own player fired: its last fire
  frame differs from the baseline's. Every press must fire a server shot within 2 s (asserted).
- **Shots that die at once.** A press whose shot died in the tick it was fired (into a wall next to the
  ship) never reaches a snapshot. It counts as `SHOT_DIED`, not as a late shot. A ship that died and
  respawned before the press is shielded again, so it counts as dead at t0.
- **The drawn side.** The keyed game follows the press's own shot on the server: the first own shot of the
  fire tick, by its handle, until the handle dies or is reused (its life counter jumps back up).
  - The I2P frame is the first frame whose drawn shot list (the one `SceneFiller` paints from) has a shot
    within 8 px of where that server shot is at the frame's render tick. The position is interpolated
    between the ticks around it, as the client does.
  - A match counts only while the press's shot lives, so a later autofire shot never stands in for it.
  - It must come after a snapshot of the fire tick arrived (asserted: `invisible` 0).
  - A press whose shot died before the render tick reached a drawn snapshot of it (its snapshots were lost)
    is `SHOT_UNSEEN`.
- **Retries.** A press that is lost (dead at t0, crashed, `SHOT_DIED` or `SHOT_UNSEEN`) is retried in the
  same stratum 7 ticks later (the tick/frame period), up to 3 times. Dropping it would leave a different
  set of phases out at each RTT and bias the mean. With retries every press of every cell was measured
  over seeds 1-3. Walls needs 2-12 retries per cell (shots into the box's wall, crashes, bursts), free
  flight up to 5 (with jitter).
- **Courses.** Shots are measured on free flight and walls only. Unshielded, the ship crashes within
  seconds on the gravity well and the dogfight, losing whole blocks of presses. Shot I2P does not depend
  on the course anyway.
- **Calibration** (`InputToPhotonCalibrationTest`, default build): per press, the shot I2P at RTT 100 is
  exactly 100.000 ms later than at RTT 0. Every press fires and shows, none early, and each shot is drawn
  91.7-100 ms after its own-ship SELF. The RTT 0 mean is 137.6 ms (asserted 130..148). The own-ship
  calibration in the same class covers the part up to the SELF.

## Method

`client/src/test/java/com/fulcrologic/jxpilot/client/latency/`:

- **`LatencyRig`**: one game in virtual time.
  - The real server (`VirtualServer`: options read as `Server.start` reads them, `SimGameHost`,
    `ServerNet`) and the real client (`ClientSim`: connection, `ClientGame`, `ClientWorld`, `TickClock`)
    are joined by an `EmulatedTransport` over a `LoopbackPipe`.
  - Events run in time order: uplink deliveries when due, server ticks at the map's rate (14 Hz), and
    client render frames at 120 Hz. Same seeds and inputs give the same game, frame for frame.
- **`InputToPhoton`**: a baseline game without the key, and per trial the same game with the key held
  from t0.
  - **Detection.** The first server tick whose sent heading (turn) or velocity (thrust) differs from the
    baseline is where the key landed. The I2P frame is the first render frame whose drawn own ship comes
    from a SELF of that tick or later (`ClientWorld.selfDrawnFromTick`, the SELF actually drawn, not merely
    received). Comparing server state, not drawn pixels, keeps the link's timing out of it: the
    trial's extra INPUT shifts the emulator's random stream for the packets behind it.
  - **Checks.** Every frame before t0 must be bit-identical to the baseline. The I2P frame must draw what
    the baseline frame does not: another heading (turn), or the THRUSTING flag (thrust).
  - **Phase sampling.** Trial j of N puts the key down at a t0 such that t0 plus the uplink's base delay
    falls at phase (j + ½)·T/N of a server tick; these phases are stratified exactly over [0, T). The INPUT
    leaves at the next render frame that polls the key, so it reaches the server 0-8.3 ms after that
    phase. That key-poll wait is part of the measured I2P.
  - **Frame alignment.** 7 ticks at 14 Hz are exactly 60 render frames, and the tick index is fixed modulo 7
    by j, so a trial's position relative to the render frames depends only on j, not on when the ship
    spawned.
  - **Draw phase.** Trial j sits on a tick k with k ≡ j (mod 7), so the trials also cycle through the 7
    positions of the tick grid against the frame grid (when the changed SELF arrives within a frame).
  - **Calibration** (`InputToPhotonCalibrationTest`, in the default build): with the same seed, every
    trial at RTT 100 is exactly 100.000 ms later than at RTT 0, and the RTT 0 mean is 42.8 ms (seed 3,
    24 trials). A first
    version spaced trials 263 ms apart from the spawn. That sampled only 12 tick phases in three
    clusters, which biased the mean by RTT mod T (review M1).
  - **Trials.** 48 per cell, alternating turn and thrust, in 4 blocks of 12. Each block has its own
    baseline game and seed, so no trial starts long after the spawn (courses crash sooner or later). A
    trial whose ship crashes after t0, before the key shows, is left out and counted.
- **`MotionMetrics`**: a 60 s game with robots per cell (6 on the dogfight course, 3 elsewhere):
  - **own error**: the drawn own ship vs the server's position at its drawn tick;
  - **aim error**: a remote ship's drawn position vs the server's position at the own ship's drawn tick.
    It is measured only while robots are in view, so the frame count is shown. The dogfight course
    (`doggy.xp`, the smallest map, 6 robots) has robots in view in every cell (asserted > 100 frames;
    673 at least over seeds 1-3);
  - **smoothness**: the `SmoothMotionTest` step change |p(i) - 2 p(i-1) + p(i-2)| per 120 Hz frame;
  - **snaps**: own step changes above 96 px (3 ship lengths) per minute alive. Server-side teleports
    (wormholes, the "tp" count) are excluded;
  - **HUD rtt/jitter/loss**: what the net diagnostics line shows at the end of the run, from the client's
    own PING RTT, its snapshot jitter, and the loss of the last 2 s window (late snapshots are counted
    apart, not as lost). This checks the HUD against the link. The client reads a PONG or a snapshot only
    at a render frame, so RTT reads up to one frame high and the jitter has a floor of about half a frame
    on a clean link: 8.3 ms and about 4 ms at 120 Hz, more at lower frame rates. The loss is one 2 s
    window, so with bursty loss it jumps around.
- **Not measurable before phase 1c**: correction size and prediction exactness need the predictor's
  reconciliations. They print `n/a (1c)`. `MotionMetrics.Sink` is where the predictor reports them.
- **Courses** (`Course`): each thrusts for 0.5 s after spawning and then coasts:
  - free flight: `globe.xp` (open space, the map's gravity, wormholes);
  - walls: `tournament.xp` (a small walled box; the ship keeps bouncing);
  - gravity well: `pad.xp` (no global gravity, attractor blocks pull the ship back and forth);
  - dogfight: `doggy.xp` (the smallest map, so the robots stay close).

  Ball carrying (§7.4) is deferred to phase 3, which models the ball connector.
- **Link scenarios** (`Scenario`), applied per direction:
  - RTT {0, 100, 200, 300} ms (RTT/2 each way);
  - jitter none, normal with σ 15 ms (clamped at 0), or Pareto spikes on 5% of packets (scale 20 ms,
    shape 1.5, capped at 300 ms). The path is FIFO, so a late packet holds back the ones behind it;
  - loss none, 1% uniform, or 5% Gilbert loss in bursts of mean length 3.

**The emulator** is `common/src/test/java/com/fulcrologic/jxpilot/common/net/link/` (shared through the
`jxpilot-common` test-jar): `LinkEmulator`, `LinkDirection` and `LinkProfile`.

- It covers delay, normal jitter, Pareto spikes (any delay capped at 60 s), Gilbert-Elliott loss,
  reordering, duplication and a bandwidth cap with a drop-tail queue.
- It is seeded, and every packet consumes the same 7 random draws whatever the profile, so cells with the
  same seed share their random sequence (`0/0/0` and `0/0/1%u` differ only where a loss hit).
- It allocates nothing in steady state.
- The decorators are `EmulatedTransport` (client `Transport`) and `EmulatedDatagrams` (server
  `Datagrams`).
- `tools/lagproxy` runs the same emulator in real time between real programs, with its own path per
  client.

## Rerun

The baseline is opt-in (about 65 s), so `mvn install` does not pay for it:

```
MAVEN_OPTS= mvn -q -o -pl client -am test -Platency
```

The `latency` profile skips every other test and runs `LatencyBaselineTest` once. The table is printed
and written to `client/target/latency-baseline.md`. Properties:

- `-Djxpilot.latency.trials=N`: I2P trials per cell (default 48);
- `-Djxpilot.latency.seconds=S`: motion run length (default 60);
- `-Djxpilot.latency.seed=N`: default 1.

`LatencyBaselineTest` asserts bounds around today's design, so that added own-ship latency fails. The
bounds were checked with seeds 1, 2 and 3:

- clean links:
  - I2P mean RTT + 40..48 ms (measured 43.3..45.8);
  - p50 RTT + 30..55 ms (measured 41..46);
  - p95 at most RTT + 90 ms (measured at most 77);
  - no key shows sooner than one RTT;
- every cell:
  - p50 at most RTT + 90 ms (measured at most 69);
  - p95 at most RTT + 250 ms without bursty loss (measured at most 196), and at most RTT + 600 ms with it
    (measured at most 467);
- the HUD's RTT reads RTT..RTT + 10 ms on clean links, with 0% loss;
- own error p95 < 20 px;
- on clean links, own step change p95 < 0.5 px and no snaps;
- robots are in view in every dogfight cell;
- the harness checks: frames before t0 identical, every key shows, at most a quarter of the trials lost to
  crashes (measured at most 7 of 48);
- shots:
  - on clean links mean RTT + 130..148 ms (measured 137.6..138.2), p95 at most RTT + 190 (measured at most
    167), never under RTT + 91;
  - every shot drawn 83-101 ms after its own-ship SELF on clean links;
  - every press fires a server shot that is drawn; none drawn before its snapshot;
  - at most 1 in 24 presses still lost after the retries (measured none).

Phase 1c is expected to break the lower bounds on purpose. It must restate them, and record its
before/after table in its PR, as §7.4 asks.

### Mutation testing of the harness parts

- **The emulator** lives in test classes, which pitest-maven cannot mutate. A profile runs PIT on it from
  `common/src/pit/PitLink.java`:

  ```
  MAVEN_OPTS= mvn -q -o -pl common test -Ppit-link
  ```

  Result: 204 mutations, 197 killed, 96.6%; the threshold is 85%. The report is in
  `common/target/pit-link`. The 7 survivors are equivalent mutants:
  - the tie order on unique sequence numbers;
  - two `siftDown` bounds where the slot just past the heap always holds the element being sifted;
  - `chance` with `<=`;
  - the two `inverseNormal` region boundaries, where the regions meet continuously.
- **The client latency harness** is left out of the client's PIT run, because it is a measurement, not a
  unit under test.

## Baseline table

Seed 1, 48 I2P trials and 24 shot presses per cell, 60 s motion runs. Latencies are in ms; errors and
smoothness in px.

- "thrust >1px" is when the drawn position leaves the no-key path.
- "shot" columns: from the fire key to the first frame drawing the new shot. "shot - own" is that minus the
  own-ship I2P of the same press (the interpolation delay). "shot trials" counts the measured presses and
  how many attempts were retried in their stratum. Shots are measured on free flight and walls only ("-"
  elsewhere).
- "own err" is the own ship's drawn position vs the server's.
- "aim err" is a robot's drawn position vs the server's at the own ship's tick (in brackets, the number of
  robot-frames measured).

| course | RTT/jitter/loss | I2P p50 | I2P p95 | p50 - RTT | mean - RTT | turn p50 | thrust p50 | thrust >1px p50 | trials | shot mean - RTT | shot p50 | shot p95 | shot - own p50 | shot trials | own err p50/p95 | aim err p50/p95 (frames) | smooth own p95/max | smooth others p95 | snaps/min (teleports) | HUD rtt/jitter/loss | correction p95 | exact % |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| free flight | 0/0/0 | 43 | 77 | 43 | 43.3 | 45 | 39 | 64 | 48/48 | 138.2 | 138 | 167 | 92 | 24/24 | 0.2/1.0 | 11.5/17.0 (10) | 0.02/5.1 | 0.15 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/0/1%u | 45 | 82 | 45 | 46.3 | 45 | 39 | 64 | 48/48 | 138.2 | 138 | 167 | 92 | 24/24 | 0.2/1.0 | 11.5/17.0 (10) | 0.02/5.1 | 0.15 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/0/5%b | 43 | 208 | 43 | 60.0 | 45 | 39 | 64 | 48/48 | 141.3 | 139 | 171 | 92 | 24/24 | 0.2/0.7 | 12.3/17.0 (10) | 0.02/39.1 | 0.16 | 0.0 | 8/4.0/37.9 | n/a (1c) | n/a (1c) |
| free flight | 0/s15/0 | 53 | 90 | 53 | 55.7 | 53 | 55 | 80 | 48/48 | 147.2 | 147 | 179 | 100 | 24/24 | 0.2/0.9 | 10.8/16.7 (27) | 0.02/6.4 | 0.16 | 0.0 | 24/9.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/s15/1%u | 55 | 99 | 55 | 58.6 | 58 | 55 | 80 | 48/48 | 147.6 | 147 | 179 | 100 | 24/24 | 0.2/0.9 | 9.7/16.7 (30) | 0.02/6.4 | 0.14 | 0.0 | 23/9.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/s15/5%b | 55 | 208 | 55 | 71.6 | 53 | 55 | 80 | 48/48 | 150.3 | 147 | 193 | 100 | 24/24 | 0.2/1.3 | 9.7/16.7 (29) | 0.03/46.2 | 0.20 | 0.0 | 21/11.7/39.3 | n/a (1c) | n/a (1c) |
| free flight | 0/spk/0 | 53 | 111 | 53 | 56.4 | 53 | 51 | 76 | 48/48 | 145.5 | 146 | 179 | 100 | 24/24 | 0.2/1.0 | 12.3/17.0 (14) | 0.02/5.6 | 0.13 | 0.0 | 19/5.3/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/spk/1%u | 55 | 118 | 55 | 58.6 | 53 | 55 | 80 | 48/48 | 145.5 | 146 | 179 | 100 | 24/24 | 0.2/1.0 | 12.3/17.0 (14) | 0.02/5.6 | 0.13 | 0.0 | 29/4.9/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/spk/5%b | 58 | 245 | 58 | 75.1 | 58 | 55 | 80 | 48/48 | 148.6 | 152 | 185 | 100 | 24/24 | 0.2/1.4 | 12.3/17.0 (11) | 0.03/26.4 | 0.15 | 0.0 | 16/5.4/25.0 | n/a (1c) | n/a (1c) |
| free flight | 100/0/0 | 143 | 177 | 43 | 43.3 | 145 | 139 | 164 | 48/48 | 138.2 | 238 | 267 | 92 | 24/24 | 0.2/1.7 | 21.9/29.7 (167) | 0.03/31.3 | 0.18 | 0.0 (2 tp) | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/0/1%u | 145 | 180 | 45 | 44.7 | 145 | 139 | 164 | 48/48 | 138.2 | 238 | 267 | 92 | 24/24 | 0.2/2.0 | 21.9/29.7 (167) | 0.04/31.3 | 0.18 | 0.0 (2 tp) | 100/4.0/3.6 | n/a (1c) | n/a (1c) |
| free flight | 100/0/5%b | 145 | 182 | 45 | 50.6 | 145 | 143 | 168 | 48/48 | 171.2 | 239 | 412 | 92 | 24/24 | 0.2/3.2 | 22.3/29.7 (154) | 0.06/35.1 | 0.17 | 0.0 (2 tp) | 100/4.2/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/s15/0 | 154 | 205 | 54 | 49.4 | 154 | 152 | 176 | 48/48 | 149.0 | 252 | 299 | 92 | 24/24 | 0.2/2.3 | 21.9/29.7 (159) | 0.04/35.5 | 0.22 | 0.0 (2 tp) | 111/16.7/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/s15/1%u | 154 | 207 | 54 | 51.3 | 154 | 152 | 176 | 48/48 | 149.7 | 252 | 299 | 92 | 24/24 | 0.2/2.5 | 21.9/29.7 (158) | 0.05/35.9 | 0.20 | 0.0 (2 tp) | 111/18.5/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/s15/5%b | 154 | 205 | 54 | 49.4 | 154 | 152 | 176 | 48/48 | 184.0 | 258 | 421 | 100 | 24/24 | 0.2/2.8 | 22.3/29.7 (147) | 0.07/35.4 | 0.21 | 0.0 (2 tp) | 116/19.2/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/spk/0 | 145 | 182 | 45 | 51.5 | 145 | 139 | 164 | 48/48 | 142.7 | 239 | 271 | 100 | 24/24 | 0.2/1.9 | 21.9/29.7 (181) | 0.04/37.2 | 0.23 | 0.0 (2 tp) | 107/6.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/spk/1%u | 145 | 191 | 45 | 52.2 | 149 | 139 | 164 | 48/48 | 142.7 | 239 | 271 | 100 | 24/24 | 0.2/2.1 | 21.9/29.7 (181) | 0.04/50.3 | 0.24 | 0.0 (2 tp) | 109/6.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/spk/5%b | 145 | 191 | 45 | 56.0 | 145 | 143 | 168 | 48/48 | 175.7 | 246 | 421 | 100 | 24/24 | 0.2/3.0 | 22.6/29.7 (163) | 0.07/68.8 | 0.22 | 0.0 (2 tp) | 105/16.0/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/0/0 | 243 | 277 | 43 | 43.3 | 245 | 239 | 264 | 48/48 | 138.2 | 338 | 367 | 92 | 24/24 | 0.2/0.9 | -/- (0) | 0.02/10.3 | - | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/0/1%u | 245 | 282 | 45 | 46.3 | 245 | 243 | 268 | 48/48 | 138.2 | 338 | 367 | 92 | 24/24 | 0.2/1.1 | -/- (0) | 0.03/10.3 | - | 0.0 | 200/4.0/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/0/5%b | 243 | 466 | 43 | 65.9 | 245 | 239 | 264 | 48/48 | 141.3 | 339 | 371 | 92 | 24/24 | 0.2/1.1 | -/- (0) | 0.04/44.4 | - | 0.0 | 200/4.1/7.1 | n/a (1c) | n/a (1c) |
| free flight | 200/s15/0 | 247 | 294 | 47 | 44.9 | 250 | 243 | 268 | 48/48 | 130.6 | 331 | 360 | 92 | 24/24 | 0.2/1.0 | -/- (0) | 0.03/12.4 | - | 0.0 | 212/15.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/s15/1%u | 247 | 294 | 47 | 44.9 | 250 | 243 | 268 | 48/48 | 130.6 | 331 | 360 | 92 | 24/24 | 0.2/1.0 | -/- (0) | 0.03/11.9 | - | 0.0 | 214/17.9/3.6 | n/a (1c) | n/a (1c) |
| free flight | 200/s15/5%b | 251 | 406 | 51 | 63.1 | 257 | 243 | 268 | 48/48 | 135.4 | 331 | 385 | 92 | 24/24 | 0.2/1.8 | -/- (0) | 0.05/43.1 | - | 0.0 | 208/13.3/7.1 | n/a (1c) | n/a (1c) |
| free flight | 200/spk/0 | 249 | 343 | 49 | 55.3 | 249 | 239 | 264 | 48/48 | 145.5 | 339 | 379 | 100 | 24/24 | 0.2/1.0 | -/- (0) | 0.03/10.1 | - | 0.0 | 208/17.0/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/spk/1%u | 249 | 345 | 49 | 57.1 | 253 | 243 | 268 | 48/48 | 145.5 | 339 | 379 | 100 | 24/24 | 0.2/1.0 | -/- (0) | 0.03/10.1 | - | 0.0 | 201/14.8/3.6 | n/a (1c) | n/a (1c) |
| free flight | 200/spk/5%b | 253 | 473 | 53 | 81.5 | 258 | 239 | 264 | 48/48 | 149.3 | 352 | 393 | 100 | 24/24 | 0.2/1.6 | -/- (0) | 0.06/55.1 | - | 0.0 | 201/15.7/10.7 | n/a (1c) | n/a (1c) |
| free flight | 300/0/0 | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | 138.2 | 438 | 467 | 92 | 24/24 | 0.2/0.7 | -/- (0) | 0.02/6.1 | - | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/0/1%u | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | 138.2 | 438 | 467 | 92 | 24/24 | 0.2/0.7 | -/- (0) | 0.02/6.1 | - | 0.0 | 300/4.0/3.6 | n/a (1c) | n/a (1c) |
| free flight | 300/0/5%b | 345 | 380 | 45 | 44.9 | 345 | 343 | 368 | 48/48 | 189.2 | 439 | 687 | 92 | 24/24 | 0.2/1.2 | -/- (0) | 0.02/127.2 | - | 2.0 | 300/4.1/3.6 | n/a (1c) | n/a (1c) |
| free flight | 300/s15/0 | 347 | 399 | 47 | 50.8 | 362 | 343 | 368 | 48/48 | 135.8 | 432 | 487 | 92 | 24/24 | 0.2/1.2 | -/- (0) | 0.02/6.7 | - | 0.0 | 312/22.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/s15/1%u | 347 | 399 | 47 | 50.8 | 362 | 343 | 368 | 48/48 | 135.8 | 432 | 487 | 92 | 24/24 | 0.2/1.1 | -/- (0) | 0.02/6.7 | - | 0.0 | 312/22.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/s15/5%b | 360 | 414 | 60 | 68.7 | 362 | 347 | 372 | 48/48 | 159.0 | 438 | 601 | 100 | 24/24 | 0.2/1.3 | -/- (0) | 0.03/43.8 | - | 0.0 | 302/17.5/25.0 | n/a (1c) | n/a (1c) |
| free flight | 300/spk/0 | 349 | 393 | 49 | 49.9 | 349 | 343 | 368 | 48/48 | 145.1 | 439 | 475 | 100 | 24/24 | 0.2/0.6 | -/- (0) | 0.02/13.3 | - | 0.0 | 321/10.4/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/spk/1%u | 349 | 393 | 49 | 49.9 | 349 | 343 | 368 | 48/48 | 145.1 | 439 | 475 | 100 | 24/24 | 0.2/0.7 | -/- (0) | 0.02/14.0 | - | 0.0 | 317/8.7/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/spk/5%b | 351 | 395 | 51 | 51.7 | 353 | 347 | 372 | 48/48 | 196.2 | 446 | 687 | 100 | 24/24 | 0.2/1.5 | -/- (0) | 0.02/127.5 | - | 2.0 | 330/9.1/3.6 | n/a (1c) | n/a (1c) |
| walls | 0/0/0 | 43 | 77 | 43 | 43.3 | 45 | 39 | 64 | 48/48 | 138.2 | 138 | 167 | 92 | 24/24 (2 retried) | 0.2/1.0 | 5.6/17.8 (317) | 0.02/3.2 | 0.07 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/0/1%u | 45 | 82 | 45 | 46.3 | 45 | 43 | 68 | 48/48 | 138.2 | 138 | 167 | 92 | 24/24 (2 retried) | 0.2/1.1 | 5.7/18.3 (326) | 0.02/3.2 | 0.07 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/0/5%b | 43 | 202 | 43 | 59.7 | 45 | 39 | 64 | 48/48 | 138.2 | 138 | 167 | 92 | 24/24 (3 retried) | 0.2/8.7 | 4.2/17.1 (534) | 0.04/90.5 | 0.07 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/s15/0 | 55 | 91 | 55 | 55.5 | 57 | 51 | 76 | 48/48 | 146.5 | 146 | 179 | 100 | 24/24 (2 retried) | 0.2/1.1 | 5.5/17.7 (1016) | 0.02/3.0 | 0.07 | 0.0 | 20/9.9/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/s15/1%u | 56 | 99 | 56 | 58.8 | 58 | 55 | 80 | 48/48 | 146.5 | 146 | 179 | 100 | 24/24 (2 retried) | 0.2/1.1 | 5.5/17.8 (1018) | 0.03/3.0 | 0.07 | 0.0 | 20/10.6/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/s15/5%b | 56 | 212 | 56 | 70.6 | 58 | 55 | 80 | 48/48 | 146.2 | 145 | 179 | 100 | 24/24 (3 retried) | 0.2/1.1 | 4.7/17.2 (1173) | 0.03/21.9 | 0.07 | 0.0 | 26/7.8/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/spk/0 | 53 | 139 | 53 | 58.8 | 49 | 55 | 80 | 48/48 | 144.8 | 146 | 179 | 100 | 24/24 (2 retried) | 0.2/1.4 | 5.4/17.7 (527) | 0.03/7.8 | 0.07 | 0.0 | 19/7.8/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/spk/1%u | 53 | 120 | 53 | 59.7 | 53 | 55 | 80 | 48/48 | 144.8 | 146 | 179 | 100 | 24/24 (2 retried) | 0.2/1.1 | 5.5/17.8 (506) | 0.03/3.1 | 0.08 | 0.0 | 15/6.8/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/spk/5%b | 55 | 238 | 55 | 75.1 | 53 | 60 | 84 | 48/48 | 145.8 | 147 | 179 | 100 | 24/24 (3 retried) | 0.2/0.9 | 4.6/17.1 (719) | 0.03/21.1 | 0.08 | 0.0 | 15/6.5/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/0/0 | 143 | 177 | 43 | 43.3 | 145 | 139 | 164 | 48/48 | 138.2 | 238 | 267 | 92 | 24/24 (2 retried) | 0.2/0.7 | 3.7/6.9 (269) | 0.02/3.9 | 0.05 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/0/1%u | 145 | 180 | 45 | 44.9 | 145 | 143 | 168 | 48/48 | 141.3 | 239 | 271 | 92 | 24/24 (2 retried) | 0.2/0.8 | 3.6/6.9 (269) | 0.02/3.9 | 0.05 | 0.0 | 100/4.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/0/5%b | 147 | 214 | 47 | 52.2 | 145 | 147 | 172 | 48/48 | 153.1 | 239 | 387 | 92 | 24/24 (2 retried) | 0.2/0.9 | 3.7/6.9 (271) | 0.02/15.4 | 0.05 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/s15/0 | 153 | 205 | 53 | 48.0 | 153 | 148 | 173 | 48/48 | 150.0 | 252 | 299 | 92 | 24/24 (2 retried) | 0.2/1.1 | 3.8/6.9 (361) | 0.02/3.6 | 0.05 | 0.0 | 111/13.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/s15/1%u | 147 | 205 | 47 | 44.7 | 137 | 147 | 172 | 48/48 | 153.1 | 253 | 299 | 92 | 24/24 (2 retried) | 0.2/1.2 | 3.7/7.0 (359) | 0.03/3.6 | 0.05 | 0.0 | 112/13.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/s15/5%b | 153 | 207 | 53 | 49.9 | 153 | 155 | 176 | 48/48 | 157.3 | 252 | 306 | 92 | 24/24 (2 retried) | 0.2/1.0 | 3.7/6.9 (360) | 0.03/12.9 | 0.05 | 0.0 | 115/13.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/spk/0 | 145 | 185 | 45 | 47.5 | 145 | 142 | 164 | 48/48 | 143.1 | 239 | 275 | 100 | 24/24 (2 retried) | 0.2/0.7 | 3.6/6.9 (276) | 0.02/3.8 | 0.05 | 0.0 | 104/6.4/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/spk/1%u | 147 | 203 | 47 | 50.3 | 149 | 143 | 168 | 48/48 | 146.2 | 246 | 275 | 100 | 24/24 (2 retried) | 0.2/0.7 | 3.6/6.9 (272) | 0.02/3.8 | 0.05 | 0.0 | 104/6.4/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/spk/5%b | 147 | 214 | 47 | 53.6 | 145 | 147 | 172 | 48/48 | 159.0 | 246 | 395 | 100 | 24/24 (2 retried) | 0.2/0.7 | 3.7/6.9 (275) | 0.02/14.8 | 0.05 | 0.0 | 107/5.5/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/0/0 | 243 | 277 | 43 | 43.3 | 245 | 239 | 264 | 48/48 | 138.2 | 338 | 367 | 92 | 24/24 (5 retried) | 0.2/1.1 | 9.0/22.3 (4887) | 0.02/5.0 | 0.07 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/0/1%u | 245 | 282 | 45 | 46.3 | 245 | 239 | 264 | 48/48 | 138.2 | 338 | 367 | 92 | 24/24 (5 retried) | 0.2/1.1 | 9.0/22.3 (4887) | 0.02/5.0 | 0.07 | 0.0 | 200/4.0/3.6 | n/a (1c) | n/a (1c) |
| walls | 200/0/5%b | 243 | 402 | 43 | 59.7 | 245 | 239 | 264 | 48/48 | 138.2 | 338 | 367 | 92 | 24/24 (5 retried) | 0.2/1.2 | 8.8/22.3 (4887) | 0.03/11.2 | 0.07 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/s15/0 | 249 | 292 | 49 | 48.9 | 245 | 251 | 272 | 48/48 | 139.9 | 338 | 385 | 100 | 24/24 (3 retried) | 0.2/1.2 | 9.0/22.3 (4886) | 0.02/5.5 | 0.07 | 0.0 | 211/15.3/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/s15/1%u | 255 | 308 | 55 | 55.2 | 258 | 251 | 272 | 48/48 | 140.3 | 338 | 385 | 100 | 24/24 (3 retried) | 0.2/1.2 | 9.0/22.3 (4886) | 0.02/5.5 | 0.07 | 0.0 | 209/15.6/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/s15/5%b | 251 | 398 | 51 | 64.2 | 241 | 255 | 276 | 48/48 | 175.7 | 345 | 622 | 100 | 24/24 (5 retried) | 0.2/1.4 | 8.8/22.3 (4883) | 0.03/9.7 | 0.07 | 0.0 | 222/13.6/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/spk/0 | 249 | 345 | 49 | 58.8 | 249 | 247 | 268 | 48/48 | 145.5 | 346 | 379 | 100 | 24/24 (5 retried) | 0.2/1.2 | 9.0/22.4 (4886) | 0.03/8.1 | 0.07 | 0.0 | 212/4.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/spk/1%u | 249 | 339 | 49 | 61.1 | 249 | 247 | 268 | 48/48 | 145.5 | 346 | 379 | 100 | 24/24 (5 retried) | 0.2/1.1 | 9.0/22.3 (4886) | 0.03/5.2 | 0.07 | 0.0 | 210/4.5/3.6 | n/a (1c) | n/a (1c) |
| walls | 200/spk/5%b | 253 | 466 | 53 | 75.1 | 258 | 247 | 268 | 48/48 | 148.3 | 347 | 385 | 100 | 24/24 (5 retried) | 0.2/1.3 | 8.8/22.4 (4886) | 0.04/10.3 | 0.07 | 0.0 | 210/4.3/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/0/0 | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | 138.2 | 438 | 467 | 92 | 24/24 (4 retried) | 0.2/0.8 | 9.4/23.4 (5748) | 0.04/4.6 | 0.07 | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/0/1%u | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | 138.2 | 438 | 467 | 92 | 24/24 (4 retried) | 0.2/0.9 | 9.4/23.4 (5742) | 0.04/4.6 | 0.07 | 0.0 | 300/4.1/3.6 | n/a (1c) | n/a (1c) |
| walls | 300/0/5%b | 345 | 382 | 45 | 46.3 | 345 | 343 | 368 | 48/48 | 177.1 | 439 | 762 | 92 | 24/24 (8 retried) | 0.2/1.2 | 9.1/23.5 (5782) | 0.08/17.6 | 0.07 | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/s15/0 | 343 | 393 | 43 | 44.0 | 345 | 334 | 359 | 48/48 | 146.2 | 445 | 499 | 92 | 24/24 (3 retried) | 0.2/1.0 | 7.5/33.6 (3380) | 0.05/4.8 | 0.07 | 0.0 | 316/16.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/s15/1%u | 343 | 393 | 43 | 44.0 | 345 | 334 | 359 | 48/48 | 146.2 | 445 | 499 | 92 | 24/24 (3 retried) | 0.2/0.9 | 7.5/33.6 (3380) | 0.05/4.8 | 0.07 | 0.0 | 309/18.0/3.6 | n/a (1c) | n/a (1c) |
| walls | 300/s15/5%b | 344 | 401 | 44 | 60.5 | 349 | 335 | 360 | 48/48 | 160.8 | 445 | 601 | 92 | 24/24 (5 retried) | 0.2/1.8 | 7.2/33.5 (3365) | 0.08/18.5 | 0.08 | 0.0 | 319/11.4/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/spk/0 | 347 | 387 | 47 | 48.7 | 349 | 343 | 364 | 48/48 | 144.1 | 439 | 474 | 100 | 24/24 (4 retried) | 0.2/0.9 | 9.4/23.4 (5747) | 0.04/7.5 | 0.07 | 0.0 | 303/8.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/spk/1%u | 347 | 387 | 47 | 48.7 | 349 | 343 | 364 | 48/48 | 144.4 | 440 | 474 | 100 | 24/24 (4 retried) | 0.2/1.1 | 9.4/23.4 (5743) | 0.05/7.5 | 0.08 | 0.0 | 314/7.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/spk/5%b | 349 | 401 | 49 | 50.8 | 349 | 346 | 368 | 48/48 | 183.0 | 445 | 762 | 100 | 24/24 (8 retried) | 0.2/1.5 | 9.1/23.5 (5791) | 0.09/17.3 | 0.08 | 0.0 | 306/6.6/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/0/0 | 43 | 77 | 43 | 43.3 | 45 | 39 | 64 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/0.5 | - | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/0/1%u | 45 | 82 | 45 | 46.3 | 45 | 43 | 68 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/0.5 | - | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/0/5%b | 43 | 202 | 43 | 59.7 | 45 | 39 | 64 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/41.1 | - | 0.0 | 8/4.1/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 0/s15/0 | 55 | 91 | 55 | 55.5 | 57 | 51 | 76 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/0.3 | - | 0.0 | 22/9.9/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/s15/1%u | 56 | 99 | 56 | 58.8 | 58 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/0.3 | - | 0.0 | 22/11.0/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/s15/5%b | 56 | 212 | 56 | 70.6 | 58 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.02/42.5 | - | 0.0 | 21/10.5/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 0/spk/0 | 53 | 139 | 53 | 58.8 | 49 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/37.5 | - | 0.0 | 18/8.5/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/spk/1%u | 53 | 120 | 53 | 59.7 | 53 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/37.5 | - | 0.0 | 13/7.7/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/spk/5%b | 55 | 238 | 55 | 75.1 | 53 | 60 | 84 | 48/48 | - | - | - | - | 0/0 | 0.0/0.8 | -/- (0) | 0.01/69.9 | - | 0.0 | 19/8.1/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 100/0/0 | 143 | 177 | 43 | 43.3 | 145 | 139 | 164 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | 9.2/21.2 (348) | 0.01/0.9 | 0.11 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/0/1%u | 145 | 180 | 45 | 44.9 | 145 | 143 | 168 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | 9.2/21.2 (348) | 0.01/1.0 | 0.11 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/0/5%b | 147 | 214 | 47 | 52.2 | 145 | 147 | 172 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 9.2/21.2 (348) | 0.01/67.8 | 0.11 | 0.0 | 100/4.0/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 100/s15/0 | 153 | 205 | 53 | 48.0 | 153 | 148 | 173 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.02/0.5 | - | 0.0 | 109/14.2/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/s15/1%u | 147 | 205 | 47 | 44.7 | 137 | 147 | 172 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.02/3.2 | - | 0.0 | 107/14.6/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/s15/5%b | 153 | 207 | 53 | 49.9 | 153 | 155 | 176 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | -/- (0) | 0.02/52.9 | - | 0.0 | 106/13.9/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/spk/0 | 145 | 185 | 45 | 47.5 | 145 | 142 | 164 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | 9.1/21.2 (367) | 0.01/24.3 | 0.09 | 0.0 | 134/9.5/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/spk/1%u | 147 | 203 | 47 | 50.3 | 149 | 143 | 168 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 9.1/21.2 (366) | 0.01/24.9 | 0.10 | 0.0 | 134/8.7/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 100/spk/5%b | 147 | 214 | 47 | 53.6 | 145 | 147 | 172 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 9.0/21.2 (359) | 0.02/67.1 | 0.10 | 0.0 | 131/8.6/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/0/0 | 243 | 277 | 43 | 43.3 | 245 | 239 | 264 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 20.3/24.4 (514) | 0.01/1.2 | 0.13 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/0/1%u | 245 | 282 | 45 | 46.3 | 245 | 239 | 264 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 20.3/24.4 (514) | 0.01/1.2 | 0.13 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/0/5%b | 243 | 402 | 43 | 59.7 | 245 | 239 | 264 | 48/48 | - | - | - | - | 0/0 | 0.0/0.8 | 20.3/24.4 (514) | 0.01/56.0 | 0.13 | 0.0 | 200/4.0/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 200/s15/0 | 249 | 292 | 49 | 48.9 | 245 | 251 | 272 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 20.3/24.4 (513) | 0.02/1.1 | 0.12 | 0.0 | 219/16.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/s15/1%u | 255 | 308 | 55 | 55.2 | 258 | 251 | 272 | 48/48 | - | - | - | - | 0/0 | 0.0/0.8 | 20.3/24.4 (514) | 0.02/2.8 | 0.14 | 0.0 | 209/14.7/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/s15/5%b | 251 | 398 | 51 | 64.2 | 241 | 255 | 276 | 48/48 | - | - | - | - | 0/0 | 0.0/0.8 | 20.3/24.4 (514) | 0.03/75.8 | 0.12 | 0.0 | 215/20.6/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 200/spk/0 | 249 | 345 | 49 | 58.8 | 249 | 247 | 268 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 20.3/24.4 (514) | 0.02/24.0 | 0.14 | 0.0 | 221/7.7/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/spk/1%u | 249 | 339 | 49 | 61.1 | 249 | 247 | 268 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 20.3/24.4 (514) | 0.02/25.7 | 0.14 | 0.0 | 213/6.8/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/spk/5%b | 253 | 466 | 53 | 75.1 | 258 | 247 | 268 | 48/48 | - | - | - | - | 0/0 | 0.0/0.8 | 20.3/24.4 (514) | 0.02/71.3 | 0.12 | 0.0 | 215/15.3/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 300/0/0 | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/0.4 | - | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/0/1%u | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/0.4 | - | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/0/5%b | 345 | 382 | 45 | 46.3 | 345 | 343 | 368 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/80.0 | - | 0.0 | 300/4.1/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 300/s15/0 | 343 | 393 | 43 | 44.0 | 345 | 334 | 359 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | 12.9/24.6 (2391) | 0.02/0.9 | 0.10 | 0.0 | 316/14.4/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/s15/1%u | 343 | 393 | 43 | 44.0 | 345 | 334 | 359 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | 12.9/24.6 (2391) | 0.02/1.3 | 0.10 | 0.0 | 311/13.7/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 300/s15/5%b | 344 | 401 | 44 | 60.5 | 349 | 335 | 360 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | 12.2/24.8 (2390) | 0.02/67.2 | 0.11 | 0.0 | 313/22.0/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/spk/0 | 347 | 387 | 47 | 48.7 | 349 | 343 | 364 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/31.8 | - | 0.0 | 332/10.3/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/spk/1%u | 347 | 387 | 47 | 48.7 | 349 | 343 | 364 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/32.0 | - | 0.0 | 324/9.9/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/spk/5%b | 349 | 401 | 49 | 50.8 | 349 | 346 | 368 | 48/48 | - | - | - | - | 0/0 | 0.0/0.6 | -/- (0) | 0.01/66.3 | - | 0.0 | 309/8.3/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 0/0/0 | 43 | 77 | 43 | 43.3 | 45 | 39 | 64 | 48/48 | - | - | - | - | 0/0 | 0.2/0.9 | 3.1/13.0 (753) | 0.07/6.0 | 0.09 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 0/0/1%u | 45 | 80 | 45 | 44.9 | 45 | 39 | 64 | 48/48 | - | - | - | - | 0/0 | 0.2/0.9 | 3.2/14.2 (776) | 0.07/6.0 | 0.09 | 0.0 | 8/4.0/3.6 | n/a (1c) | n/a (1c) |
| dogfight | 0/0/5%b | 43 | 204 | 43 | 58.4 | 45 | 39 | 64 | 48/48 | - | - | - | - | 0/0 | 0.2/0.3 | 2.5/3.9 (6106) | 0.04/38.1 | 0.06 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 0/s15/0 | 53 | 92 | 53 | 54.6 | 49 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.2/0.9 | 3.1/13.2 (2862) | 0.07/6.5 | 0.09 | 0.0 | 25/8.9/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 0/s15/1%u | 58 | 95 | 58 | 58.3 | 58 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.2/0.9 | 3.1/13.2 (2859) | 0.07/6.4 | 0.09 | 0.0 | 36/8.9/3.6 | n/a (1c) | n/a (1c) |
| dogfight | 0/s15/5%b | 55 | 195 | 55 | 66.4 | 53 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 3.1/14.3 (2965) | 0.08/38.4 | 0.10 | 0.0 | 25/10.7/3.6 | n/a (1c) | n/a (1c) |
| dogfight | 0/spk/0 | 49 | 111 | 49 | 59.3 | 49 | 51 | 76 | 48/48 | - | - | - | - | 0/0 | 0.2/1.1 | 3.1/13.9 (1455) | 0.07/6.2 | 0.09 | 0.0 | 10/4.3/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 0/spk/1%u | 53 | 111 | 53 | 61.6 | 49 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.2/1.1 | 3.1/14.2 (1438) | 0.07/6.2 | 0.10 | 0.0 | 10/4.2/3.6 | n/a (1c) | n/a (1c) |
| dogfight | 0/spk/5%b | 53 | 268 | 53 | 74.4 | 49 | 55 | 80 | 48/48 | - | - | - | - | 0/0 | 0.2/0.3 | 2.5/5.0 (6465) | 0.04/37.8 | 0.07 | 0.0 | 14/4.9/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/0/0 | 143 | 177 | 43 | 43.3 | 145 | 139 | 164 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 4.7/19.4 (12464) | 0.09/6.6 | 0.08 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/0/1%u | 143 | 177 | 43 | 43.3 | 145 | 139 | 164 | 48/48 | - | - | - | - | 0/0 | 0.2/1.4 | 4.7/19.4 (12483) | 0.09/6.6 | 0.08 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/0/5%b | 145 | 182 | 45 | 50.6 | 145 | 143 | 168 | 48/48 | - | - | - | - | 0/0 | 0.2/1.7 | 4.4/14.7 (15716) | 0.07/62.5 | 0.05 | 0.0 | 100/4.0/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/s15/0 | 139 | 199 | 39 | 41.8 | 128 | 143 | 168 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 6.1/15.0 (13326) | 0.07/5.7 | 0.09 | 0.0 | 113/11.3/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/s15/1%u | 143 | 199 | 43 | 43.5 | 137 | 143 | 168 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 6.1/15.0 (13357) | 0.07/5.7 | 0.09 | 0.0 | 106/16.3/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/s15/5%b | 139 | 199 | 39 | 41.8 | 128 | 143 | 168 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 3.7/14.1 (15108) | 0.05/37.4 | 0.08 | 0.0 | 109/16.8/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/spk/0 | 146 | 203 | 46 | 49.2 | 145 | 146 | 168 | 48/48 | - | - | - | - | 0/0 | 0.2/1.5 | 4.7/19.4 (13117) | 0.10/10.7 | 0.09 | 0.0 | 109/4.3/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/spk/1%u | 145 | 205 | 45 | 49.2 | 145 | 146 | 168 | 48/48 | - | - | - | - | 0/0 | 0.2/1.6 | 4.7/19.4 (13099) | 0.10/8.7 | 0.09 | 0.0 | 109/4.3/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 100/spk/5%b | 146 | 241 | 46 | 53.1 | 145 | 146 | 168 | 48/48 | - | - | - | - | 0/0 | 0.2/1.8 | 4.4/14.7 (16008) | 0.07/129.0 | 0.05 | 2.1 | 116/8.3/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 200/0/0 | 243 | 277 | 43 | 43.3 | 245 | 239 | 264 | 48/48 | - | - | - | - | 0/0 | 0.2/1.1 | 4.3/16.6 (26150) | 0.05/3.5 | 0.09 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 200/0/1%u | 245 | 282 | 45 | 46.3 | 245 | 243 | 268 | 48/48 | - | - | - | - | 0/0 | 0.2/1.1 | 4.3/16.6 (26150) | 0.05/5.4 | 0.09 | 0.0 | 200/3.9/3.6 | n/a (1c) | n/a (1c) |
| dogfight | 200/0/5%b | 243 | 393 | 43 | 56.9 | 245 | 239 | 264 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 4.2/16.4 (26153) | 0.05/17.8 | 0.09 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 200/s15/0 | 247 | 294 | 47 | 49.8 | 241 | 251 | 272 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 4.3/16.6 (26167) | 0.05/4.4 | 0.09 | 0.0 | 223/12.6/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 200/s15/1%u | 253 | 308 | 53 | 56.4 | 245 | 255 | 276 | 48/48 | - | - | - | - | 0/0 | 0.2/1.3 | 4.3/16.6 (26167) | 0.05/3.3 | 0.09 | 0.0 | 227/16.0/3.6 | n/a (1c) | n/a (1c) |
| dogfight | 200/s15/5%b | 253 | 324 | 53 | 60.2 | 249 | 255 | 273 | 48/48 | - | - | - | - | 0/0 | 0.2/1.8 | 7.5/14.8 (24961) | 0.02/29.2 | 0.05 | 0.0 | 217/15.8/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 200/spk/0 | 249 | 339 | 49 | 54.6 | 249 | 247 | 272 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 4.3/16.6 (26149) | 0.05/4.9 | 0.09 | 0.0 | 207/5.8/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 200/spk/1%u | 251 | 320 | 51 | 55.5 | 253 | 247 | 272 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 4.3/16.6 (26149) | 0.05/4.9 | 0.10 | 0.0 | 201/5.8/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 200/spk/5%b | 253 | 466 | 53 | 68.2 | 253 | 247 | 272 | 48/48 | - | - | - | - | 0/0 | 0.2/1.7 | 4.3/16.5 (26134) | 0.06/17.5 | 0.10 | 0.0 | 209/5.8/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/0/0 | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | - | - | - | - | 0/0 | 0.2/1.0 | 5.4/18.3 (19921) | 0.05/2.7 | 0.09 | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/0/1%u | 343 | 377 | 43 | 43.3 | 345 | 339 | 364 | 48/48 | - | - | - | - | 0/0 | 0.2/1.1 | 5.4/18.3 (19921) | 0.05/2.7 | 0.10 | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/0/5%b | 345 | 382 | 45 | 46.5 | 345 | 347 | 372 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 5.3/13.7 (18108) | 0.02/10.7 | 0.04 | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/s15/0 | 351 | 403 | 51 | 52.2 | 357 | 343 | 363 | 48/48 | - | - | - | - | 0/0 | 0.2/1.0 | 8.3/22.9 (11346) | 0.03/6.7 | 0.10 | 0.0 | 315/16.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/s15/1%u | 351 | 403 | 51 | 52.2 | 357 | 343 | 363 | 48/48 | - | - | - | - | 0/0 | 0.2/0.9 | 8.3/22.9 (11347) | 0.03/6.7 | 0.10 | 0.0 | 323/16.1/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/s15/5%b | 354 | 411 | 54 | 59.3 | 362 | 338 | 360 | 48/48 | - | - | - | - | 0/0 | 0.2/1.6 | 7.3/18.7 (13829) | 0.02/44.5 | 0.05 | 0.0 | 320/22.5/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/spk/0 | 345 | 391 | 45 | 46.5 | 349 | 339 | 364 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 5.3/18.3 (19919) | 0.06/15.8 | 0.10 | 0.0 | 310/5.6/0.0 | n/a (1c) | n/a (1c) |
| dogfight | 300/spk/1%u | 345 | 391 | 45 | 46.5 | 349 | 339 | 364 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 5.3/18.3 (19929) | 0.06/15.9 | 0.10 | 0.0 | 301/5.6/3.6 | n/a (1c) | n/a (1c) |
| dogfight | 300/spk/5%b | 349 | 401 | 49 | 50.8 | 349 | 347 | 372 | 48/48 | - | - | - | - | 0/0 | 0.2/1.2 | 6.3/16.0 (13812) | 0.02/4.3 | 0.01 | 0.0 | 303/4.3/0.0 | n/a (1c) | n/a (1c) |
