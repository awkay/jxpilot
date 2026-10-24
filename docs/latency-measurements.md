# Latency measurements

Phase 0 of [latency-fixes.md](latency-fixes.md) (§7, §8): the yardstick that later phases are measured
against. Everything here is measured headless, in virtual time, on the real Java server and the real
client pipeline, through an emulated lossy link.

## Headline

**Since phase 1c (own-ship prediction) a key and a shot show 4 ms after the key at the median, 8 ms at p95,
at any RTT, jitter and loss** ([After phase 1c](#after-phase-1c-own-ship-prediction)). The rest of this
headline is phase 0's.

**Own-ship input-to-photon in phase 0 was RTT + 43 ms on average on a clean link (RTT + 43 ms at the median,
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

## After phase 1b (protocol v2)

Phase 1b tags each input with the client's predicted tick and the server applies it at that tick
(latency-fixes.md §4.3). The own ship is still drawn by F1 dead reckoning (the predictor is phase 1c), so
own-ship I2P now pays the input lead:

| link (seed 1, 4 courses, 48 trials per cell) | phase 0 | phase 1b |
|---|---|---|
| clean, mean | RTT + 43 | RTT + 80..93 |
| clean, p95 | RTT + 77 | at most RTT + 124 |
| σ 15 ms jitter, p50 | RTT + 36..64 | RTT + 124..146 |
| Pareto spikes, p50 / p95 | RTT + 43..53 / at most RTT + 145 | RTT + 107..124 / at most RTT + 353 |
| 5% bursty loss, p95 | RTT + 82..430 | at most RTT + 273 (no jitter), 276 (σ15), 380 (spikes) |

The cause: a key goes into the entry of the next predicted tick (the same T/2 average wait as before) and that
entry reaches the server `slack` ahead of its tick. The lead controller holds the slack at 0.5 tick plus
2 × the measured jitter (plus up to half its 0.125-tick deadband): about 40 ms at 14 Hz on a clean link,
about 75 ms more with σ 15 ms jitter. Today's arrival-time landing has no such margin, and it is exactly
that margin which lets the client know the landing tick (latency-fixes.md §4.3). Bursty loss is better than
before: a starvation jumps the lead up at once, and the mid-tick repeat sends each entry twice before its
deadline. Phase 1c's predictor shows the own ship at the predicted tick, so the own ship's I2P drops to the
render pipeline whatever the lead; what remains of the lead is how late others see your inputs.

The lead controller itself (`LeadControlTest`, virtual time, real server and client):

- **RTT step 100 → 300 ms:** the first starvation report jumps the lead after 372 ms, and the slack is back
  in the band after 1.66 s with no starvation after.
- **RTT step 300 → 100 ms:** no starvation; the 1.4 ticks of extra slack drain at 3% and reach the band
  after 4.6 s. The ±3% dilation limit bounds the drain at about 0.42 tick per second.
- **The loss/jitter matrix** (RTT 0/100/200/300 × jitter none/σ15/Pareto × loss none/1%/5% bursty, 20 s
  after a 6 s settle): no starvation in any loss-free cell at jitter ≤ σ 15 ms, nor in any cell with 1%
  uniform loss; 3-9 per 20 s (1-3% of ticks) with 5% bursty loss; 1-3 with Pareto spikes. Every late entry
  was a starved tick's (late = starved in every cell).

## After phase 1c (own-ship prediction)

Phase 1c draws the own ship, its shots and its exhaust from the shared ship kernel at the predicted tick
(latency-fixes.md §4.1, §4.2, §4.7, §4.8; `client/.../game/predict`). **A key now shows in the frame that
polls it or the next: own-ship I2P p50 4 ms, p95 8 ms in every cell of the matrix, at every RTT, jitter and
loss (seeds 1-3). A shot shows the same way: p50 3-4 ms, p95 8 ms.**

| seeds 1-3, 4 courses (shots: 2), 48 trials (shots: 24) per cell | phase 1b (prediction off) | phase 1c (prediction on) |
|---|---|---|
| own ship, clean, p50 / p95 | RTT + 76..92 / RTT + 108..129 | 4 / 8 ms |
| own ship, σ 15 ms jitter, p50 / p95 | RTT + 115..149 / RTT + 154..189 | 4 / 8 ms |
| own ship, Pareto spikes, p50 / p95 | RTT + 107..176 / RTT + 187..353 | 4 / 8 ms |
| own ship, 5% bursty loss, p50 / p95 | RTT + 90..205 / RTT + 201..599 | 4 / 8 ms |
| shot, clean, mean / p95 | RTT + 176.5..186.1 / RTT + 206..220 | 3.7..4.0 / 8 ms |
| shot, worst cell, mean / p95 | RTT + 410.9 / RTT + 594 | 31.7 / 8 ms |

The 4 ms is the key-poll wait: the frame order is now receive, send the input, update the game, draw (the
app's `ClientFrame` and the harness's `ClientSim`), so the entry being collected is stepped speculatively
in the same frame (lerp between the newest final tick and one step with the collected keys, by how far the
predicted clock is past it). The prediction-off column is the snapshot timeline with the same frame order.

**Exactness** (`LatencyBaselineTest` exactness runs: every course flown 60 s by an active pilot that turns,
thrusts, fires and taps the shield every 2 s, no robots, clean links; the bit-equal share of the PREDICTs
compared while the ship is in play, seeds 1-3):

| course | RTT 0 | RTT 100 | RTT 300 | predictableBounces on | correction p95 / max |
|---|---|---|---|---|---|
| walls | 100% | 100% | 100% | 100% at every RTT | 0 / 0 px |
| free flight | 99.27..100% | 100% | 99.88..100% | the same | 0 / 59 px |
| gravity well | 99.76..100% | 99.87..100% | 99.62..99.87% | the same | 0 / 98 px |
| dogfight | 99.55..100% | 99.55..99.86% | 99.6..100% | 99.6..100% | 0 / 124 px |

Every miss is contact the client cannot know: free flight's floating items (the server drains fuel and
pushes the ship in its collision phase, after the ship's kernel step), the gravity well's own bullets
curving back into the ship, the dogfight map's cannons. With robots (the motion runs, 3 robots, 6 on the
dogfight) the share drops to 56..100% and the own ship's drawn error against the server's position at the
drawn tick has p95 up to 12 px on clean links, 50 px on the dogfight at RTT 300 with loss: the predicted
ship lives up to an RTT ahead of the server, and every bump or hit there is a correction. Corrections glide
(`CorrectionSmoother`: 100 ms half-life, 30 ms near walls); a snap is asked only for a respawn, a wormhole
or a hyperjump, or when the offset passes the snapshot timeline's teleport distance (350 px). No snap was
counted in any run (excluding deaths and teleports), and the own step change p95 stays at 0.02-0.1 px.

predictableBounces needed a server fix: the per-move reseed was only in `Mover.movePlayer`, which the tick
does not call (`ShipKernel.fly` moves the ship), so with the option on the server drew from an unreseeded
source and walls dropped to 99.0-99.8%. `ShipEffects.begin` now reseeds at the fly phase, as the client
does; with the option off nothing changed (parity, trace and digest tests unchanged). Bounce ties are rare:
the option makes no measurable difference on these courses.

**Known mispredictions**, all corrected by the next PREDICT and glided out:

- contact (items, shots, cannons, ships, balls): measured above;
- devices and upkeep (emergency thrust and shield timers, phasing, autopilot, refuelling, repair, device fuel
  drain) and item damage on wall bounces: the client's `PredictEvents` leaves them to the server. The
  pilot and courses here use none of them, so they are not measured;
- a late input: when the server starves and holds the previous keys, the key lands a tick later than
  predicted (a shot then appears a tick early: up to 5 of 24 presses with bursty loss, 3 otherwise);
- not predicted at all (drawn from the snapshots): dead, paused, round delay, warping, autopilot, a PAUSE
  key, an XPilot-protocol server. A PREDICT that cannot be used (its rare part or key masks lost) is
  skipped and the ring rides on.

**Provisional shots** come from the kernel's own fire, so their spawn and velocity are exact, and move as
the server moves a SHOT (a client port of `Move_object`'s SHOT branch on the common `Segment`: gravity,
walls, shotsWallBounce, edge wrap; the server's mover is untouched). Each complete snapshot confirms them
against its own-colour SHOT records (the server already marks the receiver's own bullets, so no
`OWN_SHOTS` section was needed), and the snapshot's own bullets are not drawn meanwhile. Over 4 courses x 3
seeds x 60 s of the active pilot per link: 207-272 shots confirmed per link, unconfirmed 0.37% at RTT 0,
0% at 100 and 300 and at 100/σ15/1%, 0.93% at 200/spikes/5% bursty. The largest match distance was 0.35 px
(the wire's half pixel), so no shot was ever moved at confirmation (no adoption jump). A confirmed shot
that leaves the snapshots (out of the view, or a hit) ends there.

**Allocation**: `PredictNetAllocationTest` runs 300 s of game frames against the real server over
100/σ15/1% with 4 robots and the active pilot (prediction, replays after about 1000 corrections, the
speculative tick, provisional shots, exhaust): no frame allocates after the warm-up.

## Shots

These figures were measured before phase 1b (on the phase 0 protocol): the own-ship part of a shot then
landed by arrival time. With 1b's tagged inputs that part pays the input lead as well (above); the shot
figures under 1b and 1c are in the phase 1c section.

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
- **correction** and **exact %**: the own-ship predictor's reconciliations (`MotionMetrics.Sink`, from
  `OwnShipPredictor.Listener`): the size of each correction, 0 when bit-equal, and the bit-equal share.
  Only PREDICTs of a ship in play both in the prediction and on the server are judged. They print
  `n/a (off)` with prediction off. Since phase 1c the own and aim errors are judged after the run against
  the recorded truth, since the predicted ship is drawn ahead of the server.
- **Predicted detection** (phase 1c): a frame drawn by the predictor shows a key before any SELF of it, so
  its I2P frame is the first that draws what the key does and the baseline frame does not; a fire press is
  the first frame drawing a provisional shot, which must be the one fired in the server's fire tick
  (`invisible` otherwise).
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

The baseline is opt-in (about 90 s), so `mvn install` does not pay for it:

```
MAVEN_OPTS= mvn -q -o -pl client -am test -Platency
```

The `latency` profile skips every other test and runs `LatencyBaselineTest` once. The table is printed
and written to `client/target/latency-baseline.md` (`latency-baseline-off.md` with prediction off).
Properties:

- `-Djxpilot.latency.trials=N`: I2P trials per cell (default 48);
- `-Djxpilot.latency.seconds=S`: motion run length (default 60);
- `-Djxpilot.latency.seed=N`: default 1;
- `-Djxpilot.latency.predict=false`: the snapshot timeline (the prediction-off column), checked against the
  phase 1b bounds;
- `-Djxpilot.latency.exactSeconds=S`: exactness run length (default 60).

With prediction on (the default) it asserts phase 1c's exit criteria, checked with seeds 1, 2 and 3:

- own-ship I2P p95 at most 50 ms in every cell, never negative (measured p95 8 ms everywhere);
- shot I2P p95 at most 50 ms in every shot cell (measured 8 ms);
- motion runs (with robots): own error p95 < 15 px on clean links, < 30 px impaired, < 60 px on the
  dogfight; on clean links (not the dogfight) correction p95 < 4 px, own step change p95 < 0.5 px, no snaps;
- exactness runs: 100% bit-equal on walls with predictableBounces, at least 99% everywhere else; correction
  p95 < 4 px; no snaps;
- the harness checks as before; a provisional shot of another tick than the server's fire tick in at most
  1 in 8 presses (1 in 4 with bursty loss); at most 1 in 6 shot presses lost after the retries.

Seed 3 fails "every key shows within 2 s" in up to 2 of 48 trials in some cells with 5% bursty loss, and
all 12 shot presses of one block of free flight at RTT 300 with bursty loss, **with prediction off as well**
(the same trials): a phase 1b harness/protocol interaction under burst loss, not prediction. Prediction
off, seeds 2 and 3 also exceed 1b's impaired p50 bound (RTT + 180 ms) in some spike/bursty cells (up to
RTT + 196): 1b's bounds were set from seed 1.

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

Seed 1, prediction on (phase 1c), 48 I2P trials and 24 shot presses per cell, 60 s motion runs, then the
exactness runs. Latencies are in ms; errors and smoothness in px. Rerun with
`-Djxpilot.latency.predict=false` for the prediction-off table.

- "thrust >1px" is when the drawn position leaves the no-key path.
- "shot" columns: from the fire key to the first frame drawing the new shot (a provisional shot with
  prediction on). "shot - own" is that minus the own-ship I2P of the same press, measured with prediction
  off only. "shot trials" counts the measured presses and how many attempts were retried in their stratum.
  Shots are measured on free flight and walls only ("-" elsewhere).
- "own err" is the own ship's drawn position vs the server's at its drawn tick.
- "aim err" is a robot's drawn position vs the server's at the own ship's tick (in brackets, the number of
  robot-frames measured).
- "correction" and "exact %": the predictor's reconciliations in the motion run (with robots).

| course | RTT/jitter/loss | I2P p50 | I2P p95 | p50 - RTT | mean - RTT | turn p50 | thrust p50 | thrust >1px p50 | trials | shot mean - RTT | shot p50 | shot p95 | shot - own p50 | shot trials | own err p50/p95 | aim err p50/p95 (frames) | smooth own p95/max | smooth others p95 | snaps/min (teleports) | HUD rtt/jitter/loss | correction p95/max | exact % |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| free flight | 0/0/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/4.8 | - | 0.0 | 8/4.1/0.0 | 0.0/0.0 | 99.8 |
| free flight | 0/0/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/4.8 | - | 0.0 | 8/4.1/0.0 | 0.0/0.0 | 99.8 |
| free flight | 0/0/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/7.3 | - | 0.0 | 8/3.8/7.1 | 0.0/0.0 | 99.7 |
| free flight | 0/s15/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/2.2 | - | 0.0 | 20/14.4/0.0 | 0.0/0.4 | 99.8 |
| free flight | 0/s15/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/2.2 | - | 0.0 | 24/14.7/0.0 | 0.0/0.4 | 99.8 |
| free flight | 0/s15/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 13 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/11.8 | - | 0.0 | 17/13.2/0.0 | 0.0/0.7 | 99.7 |
| free flight | 0/spk/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/8.0 | - | 0.0 | 14/14.8/0.0 | 0.0/0.4 | 99.8 |
| free flight | 0/spk/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/8.0 | - | 0.0 | 14/14.8/0.0 | 0.0/0.4 | 99.8 |
| free flight | 0/spk/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/12.0 | - | 0.0 | 25/12.7/0.0 | 0.0/0.7 | 99.7 |
| free flight | 100/0/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 21.3/59.1 (789) | 0.02/5.8 | 0.07 | 0.0 | 100/4.1/0.0 | 0.0/12.1 | 99.8 |
| free flight | 100/0/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 6 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 22.7/61.2 (789) | 0.02/5.8 | 0.07 | 0.0 | 100/4.1/0.0 | 0.0/12.1 | 99.8 |
| free flight | 100/0/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 23.1/61.4 (789) | 0.02/12.8 | 0.07 | 0.0 | 100/4.1/0.0 | 0.0/12.1 | 99.7 |
| free flight | 100/s15/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 25.8/72.8 (789) | 0.02/5.6 | 0.08 | 0.0 | 107/17.1/0.0 | 0.0/18.1 | 99.8 |
| free flight | 100/s15/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 25.9/73.4 (789) | 0.02/5.6 | 0.08 | 0.0 | 110/17.2/0.0 | 0.0/12.1 | 99.8 |
| free flight | 100/s15/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 26.1/72.8 (790) | 0.02/15.3 | 0.07 | 0.0 | 107/17.1/0.0 | 0.0/18.1 | 99.7 |
| free flight | 100/spk/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 25.4/90.0 (797) | 0.03/10.3 | 0.08 | 0.0 | 102/7.4/0.0 | 0.0/18.1 | 99.8 |
| free flight | 100/spk/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 25.9/92.7 (797) | 0.03/10.3 | 0.08 | 0.0 | 109/7.2/0.0 | 0.0/18.1 | 99.8 |
| free flight | 100/spk/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 10 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 24.6/88.6 (789) | 0.02/15.1 | 0.08 | 0.0 | 111/6.7/0.0 | 0.0/12.1 | 99.7 |
| free flight | 200/0/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/6.5 | - | 0.0 | 200/4.1/0.0 | 0.0/1.2 | 99.9 |
| free flight | 200/0/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 9 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/13.7 | - | 0.0 | 200/4.1/0.0 | 0.0/1.6 | 99.9 |
| free flight | 200/0/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/15.1 | - | 0.0 | 200/3.9/3.6 | 0.0/1.6 | 99.9 |
| free flight | 200/s15/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 13 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/6.0 | - | 0.0 | 207/17.9/0.0 | 0.0/1.6 | 99.9 |
| free flight | 200/s15/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/6.0 | - | 0.0 | 204/17.2/0.0 | 0.0/2.0 | 99.9 |
| free flight | 200/s15/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/12.8 | - | 0.0 | 209/19.3/3.6 | 0.0/1.6 | 99.9 |
| free flight | 200/spk/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/6.8 | - | 0.0 | 207/14.1/0.0 | 0.0/2.0 | 99.9 |
| free flight | 200/spk/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/6.9 | - | 0.0 | 201/11.8/0.0 | 0.0/2.0 | 99.9 |
| free flight | 200/spk/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | -196.2 | 3 | 8 | - | 24/24 | 0.0/0.0 | -/- (0) | 0.02/8.2 | - | 0.0 | 218/10.4/3.6 | 0.0/1.6 | 99.9 |
| free flight | 300/0/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.3 | 3 | 8 | - | 21/24 (9 retried) | 0.0/0.0 | -/- (0) | 0.02/9.4 | - | 0.0 | 300/4.1/0.0 | 0.0/1.0 | 99.9 |
| free flight | 300/0/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.3 | 3 | 8 | - | 21/24 (9 retried) | 0.0/0.0 | -/- (0) | 0.02/9.4 | - | 0.0 | 300/4.1/0.0 | 0.0/1.0 | 99.9 |
| free flight | 300/0/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.3 | 3 | 8 | - | 20/24 (12 retried) | 0.0/0.0 | -/- (0) | 0.02/17.4 | - | 0.0 | 300/3.9/34.5 | 0.0/1.0 | 99.9 |
| free flight | 300/s15/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.3 | 3 | 8 | - | 20/24 (12 retried) | 0.0/0.0 | -/- (0) | 0.03/11.6 | - | 0.0 | 302/17.0/0.0 | 0.0/41.4 | 99.5 |
| free flight | 300/s15/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 9 | 48/48 | -296.3 | 3 | 8 | - | 20/24 (12 retried) | 0.0/0.0 | -/- (0) | 0.03/11.9 | - | 0.0 | 304/16.4/0.0 | 0.0/41.4 | 99.5 |
| free flight | 300/s15/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 13 | 48/48 | -296.3 | 3 | 8 | - | 20/24 (12 retried) | 0.0/0.2 | -/- (0) | 0.03/18.2 | - | 0.0 | 309/19.5/3.6 | 0.0/78.0 | 99.6 |
| free flight | 300/spk/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.3 | 3 | 8 | - | 20/24 (12 retried) | 0.0/0.0 | -/- (0) | 0.02/8.5 | - | 0.0 | 317/27.7/0.0 | 0.0/1.2 | 99.9 |
| free flight | 300/spk/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 9 | 48/48 | -296.3 | 3 | 8 | - | 20/24 (12 retried) | 0.0/0.0 | -/- (0) | 0.02/8.5 | - | 0.0 | 310/5.7/0.0 | 0.0/1.2 | 99.9 |
| free flight | 300/spk/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | -296.3 | 3 | 8 | - | 20/24 (12 retried) | 0.0/0.0 | -/- (0) | 0.02/8.5 | - | 0.0 | 308/26.4/23.3 | 0.0/1.2 | 99.9 |
| walls | 0/0/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (11 retried) | 0.0/0.0 | -/- (0) | 0.02/4.4 | - | 0.0 | 8/4.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 0/0/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (11 retried) | 0.0/0.0 | -/- (0) | 0.03/4.4 | - | 0.0 | 8/4.0/0.0 | 0.0/0.0 | 100.0 |
| walls | 0/0/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (11 retried) | 0.0/0.0 | -/- (0) | 0.02/4.4 | - | 0.0 | 8/4.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 0/s15/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 7.3/13.1 (419) | 0.02/2.6 | 0.05 | 0.0 | 18/9.8/0.0 | 0.0/0.0 | 100.0 |
| walls | 0/s15/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 7.3/13.0 (419) | 0.02/2.6 | 0.05 | 0.0 | 18/9.3/0.0 | 0.0/0.0 | 100.0 |
| walls | 0/s15/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 10 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 9.6/16.4 (419) | 0.02/7.5 | 0.05 | 0.0 | 17/7.9/0.0 | 0.0/0.0 | 100.0 |
| walls | 0/spk/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (11 retried) | 0.0/0.0 | -/- (0) | 0.02/4.5 | - | 0.0 | 16/4.8/0.0 | 0.0/0.0 | 100.0 |
| walls | 0/spk/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (11 retried) | 0.0/0.0 | -/- (0) | 0.03/4.4 | - | 0.0 | 14/4.9/3.6 | 0.0/0.0 | 100.0 |
| walls | 0/spk/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | 3.8 | 3 | 8 | - | 24/24 (11 retried) | 0.0/0.0 | -/- (0) | 0.02/4.8 | - | 0.0 | 10/4.4/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/0/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 6 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (2 retried) | 0.0/0.0 | 20.8/60.0 (4929) | 0.03/5.1 | 0.07 | 0.0 | 100/4.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/0/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 9 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (2 retried) | 0.0/0.0 | 21.8/61.8 (4927) | 0.02/5.1 | 0.07 | 0.0 | 100/4.0/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/0/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (1 retried) | 0.0/0.0 | 23.1/65.2 (4911) | 0.03/7.2 | 0.07 | 0.0 | 100/3.9/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/s15/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 25.8/74.1 (4919) | 0.02/5.5 | 0.07 | 0.0 | 103/15.3/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/s15/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 25.3/72.8 (4917) | 0.02/5.5 | 0.07 | 0.0 | 113/15.4/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/s15/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 9 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 26.3/76.1 (4916) | 0.02/5.5 | 0.07 | 0.0 | 108/17.4/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/spk/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (3 retried) | 0.0/0.0 | 24.7/92.0 (4921) | 0.02/5.1 | 0.07 | 0.0 | 105/4.4/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/spk/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (3 retried) | 0.0/0.0 | 24.3/92.7 (4913) | 0.02/5.1 | 0.07 | 0.0 | 101/7.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 100/spk/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 10 | 48/48 | -96.2 | 3 | 8 | - | 24/24 (3 retried) | 0.0/0.0 | 25.0/93.7 (4920) | 0.03/6.9 | 0.07 | 0.0 | 112/6.6/0.0 | 0.0/0.0 | 100.0 |
| walls | 200/0/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (6 retried) | 0.0/0.0 | 29.0/68.2 (5482) | 0.02/5.5 | 0.07 | 0.0 | 200/4.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 200/0/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 9 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (6 retried) | 0.0/0.0 | 30.7/70.8 (5478) | 0.02/6.4 | 0.07 | 0.0 | 200/4.1/3.6 | 0.0/0.0 | 100.0 |
| walls | 200/0/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 33.5/75.3 (5481) | 0.02/6.8 | 0.08 | 0.0 | 200/4.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 200/s15/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 33.3/79.7 (5464) | 0.02/5.7 | 0.07 | 0.0 | 206/19.6/0.0 | 0.0/0.0 | 100.0 |
| walls | 200/s15/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 33.6/81.5 (5457) | 0.02/5.7 | 0.08 | 0.0 | 216/18.6/3.6 | 0.0/0.0 | 100.0 |
| walls | 200/s15/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 35.6/81.9 (5461) | 0.02/5.7 | 0.08 | 0.0 | 217/19.3/0.0 | 0.0/0.0 | 100.0 |
| walls | 200/spk/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 34.4/85.4 (5438) | 0.02/5.3 | 0.07 | 0.0 | 207/9.0/0.0 | 0.0/0.0 | 100.0 |
| walls | 200/spk/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 34.2/89.1 (5431) | 0.02/5.3 | 0.08 | 0.0 | 213/9.2/0.0 | 0.0/0.0 | 100.0 |
| walls | 200/spk/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | -196.2 | 3 | 8 | - | 24/24 (5 retried) | 0.0/0.0 | 34.8/83.7 (5438) | 0.02/6.3 | 0.08 | 0.0 | 208/9.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 300/0/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 58.7/143.9 (2742) | 0.02/7.5 | 0.06 | 0.0 | 300/4.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 300/0/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 60.2/147.2 (2742) | 0.02/8.2 | 0.06 | 0.0 | 300/4.0/3.6 | 0.0/0.0 | 100.0 |
| walls | 300/0/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 72.1/173.9 (2742) | 0.02/7.5 | 0.06 | 0.0 | 300/4.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 300/s15/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (6 retried) | 0.0/0.0 | 92.6/159.1 (2360) | 0.02/6.4 | 0.06 | 0.0 | 310/17.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 300/s15/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (6 retried) | 0.0/0.0 | 94.0/159.6 (2369) | 0.02/6.4 | 0.06 | 0.0 | 305/17.2/3.6 | 0.0/0.0 | 100.0 |
| walls | 300/s15/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (6 retried) | 0.0/0.0 | 99.7/181.5 (2376) | 0.02/7.5 | 0.05 | 0.0 | 312/20.4/0.0 | 0.0/0.0 | 100.0 |
| walls | 300/spk/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 73.9/149.6 (2750) | 0.02/6.5 | 0.06 | 0.0 | 306/5.3/0.0 | 0.0/0.0 | 100.0 |
| walls | 300/spk/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 75.6/150.3 (2751) | 0.02/6.5 | 0.07 | 0.0 | 304/5.1/0.0 | 0.0/0.0 | 100.0 |
| walls | 300/spk/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | -296.2 | 3 | 8 | - | 24/24 (4 retried) | 0.0/0.0 | 70.1/173.5 (2742) | 0.02/6.5 | 0.07 | 0.0 | 307/4.9/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 0/0/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.00/7.1 | - | 0.0 | 8/4.1/0.0 | 0.0/0.0 | 99.9 |
| gravity well | 0/0/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.00/7.2 | - | 0.0 | 8/4.0/3.6 | 0.0/0.0 | 99.9 |
| gravity well | 0/0/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.00/11.7 | - | 0.0 | 8/4.1/7.1 | 0.0/0.0 | 99.9 |
| gravity well | 0/s15/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/3.8 | - | 0.0 | 21/9.0/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 0/s15/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/3.8 | - | 0.0 | 24/9.9/3.6 | 0.0/0.0 | 100.0 |
| gravity well | 0/s15/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 10 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.4 | - | 0.0 | 19/10.9/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 0/spk/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/11.7 | - | 0.0 | 29/7.7/0.0 | 0.0/7.8 | 99.9 |
| gravity well | 0/spk/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/11.7 | - | 0.0 | 31/7.6/3.6 | 0.0/7.8 | 99.9 |
| gravity well | 0/spk/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/11.7 | - | 0.0 | 27/7.2/7.1 | 0.0/7.8 | 99.9 |
| gravity well | 100/0/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.00/3.0 | - | 0.0 | 100/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/0/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 9 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/3.0 | - | 0.0 | 100/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/0/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/14.7 | - | 0.0 | 100/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/s15/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/6.0 | - | 0.0 | 104/16.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/s15/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/6.0 | - | 0.0 | 106/13.2/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/s15/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 9 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.5 | - | 0.0 | 115/14.7/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/spk/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/14.3 | - | 0.0 | 111/8.6/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/spk/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.9 | - | 0.0 | 104/7.7/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 100/spk/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 10 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.5 | - | 0.0 | 102/4.5/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/0/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.00/6.0 | - | 0.0 | 200/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/0/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 9 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/12.3 | - | 0.0 | 200/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/0/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.5 | - | 0.0 | 200/4.1/3.6 | 0.0/0.0 | 100.0 |
| gravity well | 200/s15/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/1.4 | - | 0.0 | 207/16.9/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/s15/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/1.4 | - | 0.0 | 205/17.4/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/s15/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.5 | - | 0.0 | 202/17.5/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/spk/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.5 | - | 0.0 | 203/8.5/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/spk/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.5 | - | 0.0 | 210/9.7/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 200/spk/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.5 | - | 0.0 | 204/10.0/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 300/0/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 87.3/96.4 (257) | 0.00/9.7 | 0.07 | 0.0 | 300/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 300/0/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 93.8/103.5 (251) | 0.01/9.7 | 0.08 | 0.0 | 300/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 300/0/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 92.2/116.0 (257) | 0.00/13.2 | 0.07 | 0.0 | 300/4.1/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 300/s15/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/3.0 | - | 0.0 | 313/19.8/0.0 | 0.0/0.7 | 99.3 |
| gravity well | 300/s15/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/3.0 | - | 0.0 | 311/18.6/0.0 | 0.0/0.7 | 99.3 |
| gravity well | 300/s15/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | -/- (0) | 0.01/13.2 | - | 0.0 | 315/14.2/20.7 | 0.0/0.7 | 99.2 |
| gravity well | 300/spk/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 95.4/105.0 (258) | 0.01/13.2 | 0.08 | 0.0 | 311/8.4/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 300/spk/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 97.8/105.1 (257) | 0.01/13.3 | 0.07 | 0.0 | 309/8.2/0.0 | 0.0/0.0 | 100.0 |
| gravity well | 300/spk/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 96.9/138.5 (283) | 0.00/13.3 | 0.08 | 0.0 | 316/23.0/0.0 | 0.0/0.0 | 100.0 |
| dogfight | 0/0/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 9.6/32.4 (11884) | 0.03/7.9 | 0.08 | 0.0 | 8/4.1/0.0 | 0.0/0.0 | 98.1 |
| dogfight | 0/0/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/0.0 | 9.8/33.6 (11881) | 0.03/7.9 | 0.09 | 0.0 | 8/4.0/0.0 | 0.0/0.0 | 98.1 |
| dogfight | 0/0/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/1.0 | 10.3/36.8 (8991) | 0.02/8.5 | 0.09 | 0.0 | 8/4.0/10.7 | 0.0/24.6 | 97.7 |
| dogfight | 0/s15/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 12.7/39.4 (17383) | 0.03/5.9 | 0.09 | 0.0 | 19/10.6/0.0 | 0.1/8.8 | 94.7 |
| dogfight | 0/s15/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.7 | 12.8/40.2 (17382) | 0.03/5.9 | 0.09 | 0.0 | 20/11.2/0.0 | 0.1/8.8 | 94.6 |
| dogfight | 0/s15/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/1.3 | 14.9/44.0 (17397) | 0.03/14.6 | 0.10 | 0.0 | 15/11.6/3.6 | 0.1/15.8 | 94.4 |
| dogfight | 0/spk/0 | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.4 | 12.8/47.4 (11870) | 0.03/11.3 | 0.09 | 0.0 | 21/5.9/0.0 | 0.0/22.5 | 98.1 |
| dogfight | 0/spk/1%u | 4 | 8 | 4 | 4.3 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.5 | 13.2/49.4 (11862) | 0.03/9.9 | 0.09 | 0.0 | 11/6.1/0.0 | 0.0/22.5 | 98.1 |
| dogfight | 0/spk/5%b | 4 | 8 | 4 | 4.3 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/1.7 | 12.1/49.7 (9010) | 0.03/11.0 | 0.09 | 0.0 | 23/7.8/10.7 | 0.0/18.4 | 97.7 |
| dogfight | 100/0/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/1.7 | 10.5/39.6 (20135) | 0.03/8.4 | 0.08 | 0.0 | 100/4.1/0.0 | 0.4/20.3 | 86.9 |
| dogfight | 100/0/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/1.8 | 10.9/42.4 (20129) | 0.03/5.1 | 0.09 | 0.0 | 100/4.1/0.0 | 0.4/20.3 | 86.8 |
| dogfight | 100/0/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/2.3 | 12.0/48.7 (20091) | 0.04/9.7 | 0.09 | 0.0 | 100/4.1/3.6 | 0.5/20.3 | 86.3 |
| dogfight | 100/s15/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.8 | 18.9/57.5 (13898) | 0.02/23.2 | 0.09 | 0.0 | 103/14.9/0.0 | 0.0/39.5 | 98.5 |
| dogfight | 100/s15/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/0.8 | 19.1/58.3 (13908) | 0.02/23.2 | 0.10 | 0.0 | 107/12.9/3.6 | 0.0/39.5 | 98.5 |
| dogfight | 100/s15/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/4.4 | 15.1/57.2 (16545) | 0.02/13.3 | 0.06 | 0.0 | 113/15.4/0.0 | 0.0/59.2 | 97.8 |
| dogfight | 100/spk/0 | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/2.1 | 13.2/51.7 (20083) | 0.04/8.9 | 0.09 | 0.0 | 105/4.2/0.0 | 0.5/20.3 | 86.7 |
| dogfight | 100/spk/1%u | 4 | 8 | -96 | -95.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/2.2 | 13.2/51.3 (20098) | 0.04/8.9 | 0.10 | 0.0 | 105/4.1/3.6 | 0.5/20.3 | 86.7 |
| dogfight | 100/spk/5%b | 4 | 8 | -96 | -95.7 | 4 | 2 | 10 | 48/48 | - | - | - | - | 0/0 | 0.0/3.5 | 14.1/53.5 (20045) | 0.04/9.4 | 0.10 | 0.0 | 107/4.1/3.6 | 0.7/26.6 | 86.2 |
| dogfight | 200/0/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/3.2 | 13.5/59.1 (12961) | 0.06/6.8 | 0.08 | 0.0 | 200/4.1/0.0 | 1.3/31.9 | 73.2 |
| dogfight | 200/0/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/3.4 | 14.2/62.5 (12956) | 0.09/8.4 | 0.08 | 0.0 | 200/4.0/3.6 | 1.3/31.9 | 73.4 |
| dogfight | 200/0/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.1/4.5 | 14.9/67.0 (12878) | 0.09/10.3 | 0.09 | 0.0 | 200/4.1/7.1 | 1.4/31.9 | 71.8 |
| dogfight | 200/s15/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/7.3 | 14.7/71.2 (22938) | 0.03/8.5 | 0.09 | 0.0 | 205/18.2/0.0 | 0.7/34.3 | 93.2 |
| dogfight | 200/s15/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/7.4 | 14.9/73.2 (22945) | 0.03/9.2 | 0.09 | 0.0 | 201/13.0/3.4 | 0.8/34.3 | 93.1 |
| dogfight | 200/s15/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/14.0 | 12.8/64.6 (22864) | 0.03/8.9 | 0.05 | 0.0 | 212/17.9/0.0 | 1.4/37.4 | 90.9 |
| dogfight | 200/spk/0 | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.1/4.8 | 15.7/74.8 (12908) | 0.08/8.6 | 0.08 | 0.0 | 215/4.3/0.0 | 1.5/42.5 | 73.4 |
| dogfight | 200/spk/1%u | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.1/4.4 | 15.6/73.2 (12906) | 0.08/8.3 | 0.09 | 0.0 | 206/4.2/3.6 | 1.4/42.5 | 73.3 |
| dogfight | 200/spk/5%b | 4 | 8 | -196 | -195.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.1/5.2 | 8.7/72.6 (10885) | 0.03/5.3 | 0.07 | 0.0 | 214/6.2/50.0 | 1.3/16.9 | 72.1 |
| dogfight | 300/0/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/20.9 | 21.0/96.1 (13233) | 0.06/13.7 | 0.08 | 0.0 | 300/4.1/0.0 | 0.6/48.6 | 93.7 |
| dogfight | 300/0/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/22.1 | 21.6/98.8 (13221) | 0.06/16.2 | 0.08 | 0.0 | 300/4.1/3.6 | 0.7/48.6 | 93.7 |
| dogfight | 300/0/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/24.3 | 23.2/125.2 (7039) | 0.03/16.7 | 0.09 | 0.0 | 300/4.1/10.7 | 1.1/72.9 | 92.5 |
| dogfight | 300/s15/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 13 | 48/48 | - | - | - | - | 0/0 | 0.0/12.0 | 14.6/94.3 (23134) | 0.08/10.2 | 0.09 | 0.0 | 311/14.4/0.0 | 1.3/67.4 | 89.0 |
| dogfight | 300/s15/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 14 | 48/48 | - | - | - | - | 0/0 | 0.0/12.1 | 14.6/94.7 (23127) | 0.07/9.9 | 0.09 | 0.0 | 312/13.2/0.0 | 1.8/67.4 | 89.0 |
| dogfight | 300/s15/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 13 | 48/48 | - | - | - | - | 0/0 | 0.0/8.9 | 9.0/65.0 (22138) | 0.03/5.2 | 0.07 | 0.0 | 296/16.2/0.0 | 0.7/44.9 | 92.0 |
| dogfight | 300/spk/0 | 4 | 8 | -296 | -295.7 | 4 | 2 | 6 | 48/48 | - | - | - | - | 0/0 | 0.0/28.9 | 23.5/124.8 (13206) | 0.07/17.4 | 0.09 | 0.0 | 307/5.7/0.0 | 0.8/85.1 | 93.6 |
| dogfight | 300/spk/1%u | 4 | 8 | -296 | -295.7 | 4 | 2 | 9 | 48/48 | - | - | - | - | 0/0 | 0.0/28.9 | 23.8/127.7 (13275) | 0.07/17.3 | 0.09 | 0.0 | 305/6.4/0.0 | 0.9/72.9 | 93.6 |
| dogfight | 300/spk/5%b | 4 | 8 | -296 | -295.7 | 4 | 2 | 5 | 48/48 | - | - | - | - | 0/0 | 0.0/25.5 | 23.2/136.3 (7042) | 0.03/16.7 | 0.10 | 0.0 | 311/4.8/10.7 | 1.1/72.9 | 92.4 |

| course | RTT | predictableBounces | reconciliations | exact % | correction p95/max | own err p95 | snaps/min |
|---|---:|---|---:|---:|---:|---:|---:|
| free flight | 0 | off | 551 | 99.27 | 0.00/0.0 | 1.57 | 0.0 |
| free flight | 0 | on | 551 | 99.27 | 0.00/0.0 | 1.57 | 0.0 |
| free flight | 100 | off | 794 | 100.00 | 0.00/0.0 | 0.11 | 0.0 |
| free flight | 100 | on | 794 | 100.00 | 0.00/0.0 | 0.11 | 0.0 |
| free flight | 300 | off | 791 | 100.00 | 0.00/0.0 | 0.01 | 0.0 |
| free flight | 300 | on | 791 | 100.00 | 0.00/0.0 | 0.01 | 0.0 |
| walls | 0 | off | 839 | 100.00 | 0.00/0.0 | 0.00 | 0.0 |
| walls | 0 | on | 839 | 100.00 | 0.00/0.0 | 0.00 | 0.0 |
| walls | 100 | off | 838 | 100.00 | 0.00/0.0 | 0.12 | 0.0 |
| walls | 100 | on | 838 | 100.00 | 0.00/0.0 | 0.12 | 0.0 |
| walls | 300 | off | 835 | 100.00 | 0.00/0.0 | 0.01 | 0.0 |
| walls | 300 | on | 835 | 100.00 | 0.00/0.0 | 0.01 | 0.0 |
| gravity well | 0 | off | 751 | 100.00 | 0.00/0.0 | 0.00 | 0.0 |
| gravity well | 0 | on | 751 | 100.00 | 0.00/0.0 | 0.00 | 0.0 |
| gravity well | 100 | off | 794 | 99.87 | 0.00/21.5 | 0.47 | 0.0 |
| gravity well | 100 | on | 794 | 99.87 | 0.00/21.5 | 0.47 | 0.0 |
| gravity well | 300 | off | 791 | 99.87 | 0.00/21.1 | 0.16 | 0.0 |
| gravity well | 300 | on | 791 | 99.87 | 0.00/21.1 | 0.16 | 0.0 |
| dogfight | 0 | off | 619 | 99.68 | 0.00/0.0 | 0.00 | 0.0 |
| dogfight | 0 | on | 663 | 99.85 | 0.00/0.0 | 0.00 | 0.0 |
| dogfight | 100 | off | 662 | 99.55 | 0.00/71.0 | 0.89 | 0.0 |
| dogfight | 100 | on | 662 | 99.85 | 0.00/75.3 | 0.53 | 0.0 |
| dogfight | 300 | off | 703 | 99.72 | 0.00/100.3 | 0.42 | 0.0 |
| dogfight | 300 | on | 703 | 99.86 | 0.00/124.1 | 0.19 | 0.0 |
