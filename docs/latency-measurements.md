# Latency measurements

Phase 0 of [latency-fixes.md](latency-fixes.md) (§7, §8): the yardstick that later phases are measured
against. Everything here is measured headless, in virtual time, on the real Java server and the real
client pipeline, through an emulated lossy link.

## Headline

**Own-ship input-to-photon today is about RTT + 37-50 ms at the median, and RTT + 75-80 ms at p95,
on a clean link.** That is not the RTT + 135 ms that §2.1 of latency-fixes.md estimates.

| RTT | §2.1 estimate | Measured p50 (clean link) | Measured p95 (clean link) |
|---|---|---|---|
| 0 | ≈ 135 ms | 40 ms | 75 ms |
| 100 ms | ≈ 235 ms | 142 ms | 174 ms |
| 200 ms | ≈ 335 ms | 237 ms | 275 ms |
| 300 ms | ≈ 435 ms | 350 ms | 379 ms |

(Seed 1, 48 trials per RTT. Seeds 2 and 3 put the clean-link median between RTT + 37 and RTT + 53 ms.)

Why §2.1 is off: it was written against `31e9f40`, when the own ship was interpolated like every other
ship (1.28 ticks ≈ 91 ms behind). Commit `d722c79` (F1, "own ship predicted to the present with error
blending") landed after the doc. Since then `ClientWorld.predictSelf` draws the own ship from the
*newest* SELF, dead-reckoned to the estimated server tick, and uses the newest SELF's heading as is. So
a SELF that carries a key's effect shows on the very next render frame. The 91 ms interpolation term is
gone. What is left matches the measurement:

| Stage | Time |
|---|---|
| Key down until the next render frame polls it (120 Hz) | 0-8 ms, mean 4 |
| Uplink | RTT/2 |
| Wait for the next server tick (inputs apply at the next tick, 14 Hz) | 0-71 ms, mean 36 |
| Downlink | RTT/2 |
| Snapshot received until the next render frame draws it | 0-8 ms, mean 4 |
| **Total** | **RTT + ≈ 44 ms mean, RTT + 8..87 ms range** |

The measurement stops at the frame that draws the change. Buffer swap and display (§2.1's "render and
present", 5-10 ms) are not modelled.

The three courses give identical I2P numbers for the same link cell: I2P depends on timing (tick phase,
frame phase, link), not on where the ship flies, and the courses share their seeds. Turn and thrust come
out the same. The first tick of TURN_LEFT already moves the heading the server
sends (`dir`, 128 steps per turn) by at least one step at the default turn speed, and the first tick of
THRUST changes the velocity.

Jitter and loss move the median little but stretch the tail. With 5% bursty loss (mean burst 3 packets)
the p95 reaches RTT + 400 ms in some cells. A burst can eat the INPUT that carries the key and its
redundant copy one tick later; then the SELF that shows it is lost too, and the next one has to arrive.
Normal jitter of σ 15 ms adds about 5-15 ms to the median, from the FIFO path: a late packet holds back
the ones behind it.

## Method

`client/src/test/java/com/fulcrologic/jxpilot/client/latency/`:

- **`LatencyRig`**: one game in virtual time. The real server (`VirtualServer`: options read as
  `Server.start` reads them, `SimGameHost`, `ServerNet`) and the real client (`ClientSim`: connection,
  `ClientGame`, `ClientWorld`, `TickClock`) are joined by an `EmulatedTransport` over a `LoopbackPipe`.
  Events run in time order: uplink deliveries when due, server ticks at the map's rate (14 Hz), and
  client render frames at 120 Hz. Same seeds and inputs give the same game, frame for frame.
- **`InputToPhoton`**: a baseline game without the key, and per trial the same game with the key held
  from t0. The first server tick whose sent heading (turn) or velocity (thrust) differs from the
  baseline is where the key landed. The I2P frame is the first render frame that draws a SELF of that
  tick or later. Comparing server state, not drawn pixels, keeps the link's timing out of it: the
  trial's extra INPUT shifts the emulator's random stream for the packets behind it. Checks: every frame
  before t0 must be bit-identical to the baseline, and the I2P frame must draw a heading or velocity the
  baseline does not. Trials alternate turn and thrust, 263 ms apart (3.68 ticks, 31.6 frames), so t0
  walks across the tick and frame phases. 48 trials per cell, in 4 blocks of 12. Each block has its own
  baseline game and seed, so no trial starts more than 4.4 s after the spawn (courses crash sooner or
  later) and the blocks sample other tick phases and link draws. A trial whose ship crashes after t0,
  before the key shows, is left out and counted.
- **`MotionMetrics`**: a 60 s game with 3 robots per cell:
  - **own error**: the drawn own ship vs the server's position at its drawn tick;
  - **aim error**: a remote ship's drawn position vs the server's position at the own ship's drawn tick.
    Only robots that come into view count, so the frame count is shown and is 0 in some cells;
  - **smoothness**: the `SmoothMotionTest` step change |p(i) - 2 p(i-1) + p(i-2)| per 120 Hz frame;
  - **snaps**: own step changes above 96 px (3 ship lengths) per minute alive. Server-side teleports
    (wormholes, the "tp" count) are excluded;
  - **HUD rtt/jitter/loss**: what the net diagnostics line shows at the end of the run, from the client's
    own PING RTT, snapshot jitter and loss of the last 2 s window. This checks the HUD against the link.
    The jitter has a floor of about 4 ms on a clean link, because arrivals are stamped at the 120 Hz render
    frame that reads them. The loss is one 2 s window, so with bursty loss it jumps around.
- **Not measurable before phase 1c**: correction size and prediction exactness need the predictor's
  reconciliations. They print `n/a (1c)`. `MotionMetrics.Sink` is where the predictor reports them.
- **Courses** (`Course`): each thrusts for 0.5 s after spawning and then coasts:
  - free flight: `globe.xp` (open space, the map's gravity, wormholes);
  - walls: `tournament.xp` (a small walled box; the ship keeps bouncing);
  - gravity well: `pad.xp` (no global gravity, attractor blocks pull the ship back and forth).
- **Link scenarios** (`Scenario`), applied per direction:
  - RTT {0, 100, 200, 300} ms (RTT/2 each way);
  - jitter none, normal with σ 15 ms, or Pareto spikes on 5% of packets (scale 20 ms, shape 1.5,
    capped at 300 ms). The path is FIFO, so a late packet holds back the ones behind it;
  - loss none, 1% uniform, or 5% Gilbert loss in bursts of mean length 3.

The emulator is `common/src/test/java/com/fulcrologic/jxpilot/common/net/link/` (shared through the
`jxpilot-common` test-jar): `LinkEmulator`, `LinkDirection` and `LinkProfile`. It covers delay, normal
jitter, Pareto spikes, Gilbert-Elliott loss, reordering, duplication and a bandwidth cap with a
drop-tail queue. It is seeded, and every packet consumes the same 7 random draws whatever the profile,
so cells with the same seed share their random sequence (`0/0/0` and `0/0/1%u` differ only where a
loss hit). It allocates nothing in steady state. The decorators are `EmulatedTransport` (client
`Transport`) and `EmulatedDatagrams` (server `Datagrams`). `tools/lagproxy` runs the same emulator in
real time between real programs.

## Rerun

```
MAVEN_OPTS= mvn -q -o -pl client -am test -Dtest=LatencyBaselineTest -Dsurefire.failIfNoSpecifiedTests=false
```

It takes about 40 s. The table is printed and written to `client/target/latency-baseline.md`.
Properties: `-Djxpilot.latency.trials=N` (I2P trials per cell, default 48),
`-Djxpilot.latency.seconds=S` (motion run length, default 60), `-Djxpilot.latency.seed=N` (default 1).

`LatencyBaselineTest` asserts loose bounds around today's design, so that added own-ship latency fails.
The bounds were checked with seeds 1, 2 and 3:
- clean-link I2P p50 within RTT + 20..80 ms (measured 37..53), and p95 ≤ RTT + 110 ms (measured ≤ 81);
- every cell's p50 ≤ RTT + 120 ms (measured ≤ 67);
- p95 ≤ RTT + 200 ms without bursty loss (measured ≤ 157), and ≤ RTT + 600 ms with it (measured ≤ 415);
- no key shows sooner than one RTT;
- the HUD's RTT reads RTT..RTT + 10 ms on clean links, with 0% loss;
- own error p95 < 20 px;
- on clean links, own step change p95 < 0.5 px and no snaps;
- the harness checks: frames before t0 identical, every key shows, at most a quarter of the trials lost to
  crashes.

Phase 1c is expected to break the lower bounds on purpose. It must restate them, and record its
before/after table in its PR, as §7.4 asks.

## Baseline table

Seed 1, 48 I2P trials per cell, 60 s motion runs. Latencies are in ms; errors and smoothness in px.
"own err" is the own ship's drawn position vs the server's; "aim err" is a robot's drawn position vs
the server's at the own ship's tick (in brackets, the number of robot-frames measured).

| course | RTT/jitter/loss | I2P p50 | I2P p95 | p50 - RTT | turn p50 | thrust p50 | trials | own err p50/p95 | aim err p50/p95 (frames) | smooth own p95/max | smooth others p95 | snaps/min (teleports) | HUD rtt/jitter/loss | correction p95 | exact % |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| free flight | 0/0/0 | 40 | 75 | 40 | 38 | 40 | 48/48 | 0.2/1.0 | 11.5/17.0 (10) | 0.02/5.1 | 0.15 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/0/1%u | 40 | 75 | 40 | 38 | 40 | 48/48 | 0.2/1.0 | 11.5/17.0 (10) | 0.02/5.1 | 0.15 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/0/5%b | 43 | 92 | 43 | 41 | 43 | 48/48 | 0.2/0.7 | 12.3/17.0 (10) | 0.02/39.1 | 0.16 | 0.0 | 8/4.0/37.9 | n/a (1c) | n/a (1c) |
| free flight | 0/s15/0 | 49 | 90 | 49 | 55 | 43 | 48/48 | 0.2/0.9 | 10.8/16.7 (27) | 0.02/6.4 | 0.16 | 0.0 | 23/9.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/s15/1%u | 49 | 106 | 49 | 55 | 43 | 48/48 | 0.2/0.9 | 9.7/16.7 (30) | 0.02/6.4 | 0.14 | 0.0 | 21/9.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/s15/5%b | 58 | 99 | 58 | 67 | 58 | 48/48 | 0.2/1.3 | 9.7/16.7 (29) | 0.03/46.2 | 0.20 | 0.0 | 20/11.7/39.3 | n/a (1c) | n/a (1c) |
| free flight | 0/spk/0 | 45 | 104 | 45 | 49 | 43 | 48/48 | 0.2/1.0 | 12.3/17.0 (14) | 0.02/5.6 | 0.13 | 0.0 | 19/5.3/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/spk/1%u | 43 | 104 | 43 | 49 | 43 | 48/48 | 0.2/1.0 | 12.3/17.0 (14) | 0.02/5.6 | 0.13 | 0.0 | 28/4.8/0.0 | n/a (1c) | n/a (1c) |
| free flight | 0/spk/5%b | 49 | 158 | 49 | 49 | 43 | 48/48 | 0.2/1.4 | 12.3/17.0 (11) | 0.03/26.4 | 0.15 | 0.0 | 16/5.4/25.0 | n/a (1c) | n/a (1c) |
| free flight | 100/0/0 | 142 | 174 | 42 | 142 | 144 | 48/48 | 0.2/1.7 | 21.9/29.7 (167) | 0.03/31.3 | 0.18 | 0.0 (2 tp) | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/0/1%u | 142 | 179 | 42 | 142 | 144 | 48/48 | 0.2/2.0 | 21.9/29.7 (167) | 0.04/31.3 | 0.18 | 0.0 (2 tp) | 100/4.0/3.6 | n/a (1c) | n/a (1c) |
| free flight | 100/0/5%b | 144 | 182 | 44 | 142 | 159 | 48/48 | 0.2/3.2 | 22.3/29.7 (154) | 0.06/35.1 | 0.17 | 0.0 (2 tp) | 100/4.2/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/s15/0 | 142 | 191 | 42 | 142 | 136 | 48/48 | 0.2/2.3 | 21.9/29.7 (159) | 0.04/35.5 | 0.22 | 0.0 (2 tp) | 111/16.7/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/s15/1%u | 140 | 191 | 40 | 142 | 136 | 48/48 | 0.2/2.5 | 21.9/29.7 (158) | 0.05/35.9 | 0.20 | 0.0 (2 tp) | 111/18.5/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/s15/5%b | 142 | 215 | 42 | 148 | 136 | 48/48 | 0.2/2.8 | 22.3/29.7 (147) | 0.07/35.4 | 0.21 | 0.0 (2 tp) | 116/19.2/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/spk/0 | 153 | 211 | 53 | 142 | 159 | 48/48 | 0.2/1.9 | 21.9/29.7 (181) | 0.04/37.2 | 0.23 | 0.0 (2 tp) | 107/6.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/spk/1%u | 156 | 211 | 56 | 153 | 159 | 48/48 | 0.2/2.1 | 21.9/29.7 (181) | 0.04/50.3 | 0.24 | 0.0 (2 tp) | 109/6.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 100/spk/5%b | 159 | 274 | 59 | 153 | 159 | 48/48 | 0.2/3.0 | 22.6/29.7 (163) | 0.07/68.8 | 0.22 | 0.0 (2 tp) | 105/16.0/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/0/0 | 237 | 275 | 37 | 231 | 237 | 48/48 | 0.2/0.9 | -/- (0) | 0.02/10.3 | - | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/0/1%u | 246 | 280 | 46 | 231 | 249 | 48/48 | 0.2/1.1 | -/- (0) | 0.03/10.3 | - | 0.0 | 200/4.0/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/0/5%b | 237 | 280 | 37 | 231 | 237 | 48/48 | 0.2/1.1 | -/- (0) | 0.04/44.4 | - | 0.0 | 200/4.1/7.1 | n/a (1c) | n/a (1c) |
| free flight | 200/s15/0 | 241 | 290 | 41 | 245 | 233 | 48/48 | 0.2/1.0 | -/- (0) | 0.03/12.4 | - | 0.0 | 212/15.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/s15/1%u | 242 | 297 | 42 | 245 | 233 | 48/48 | 0.2/1.0 | -/- (0) | 0.03/11.9 | - | 0.0 | 214/17.9/3.6 | n/a (1c) | n/a (1c) |
| free flight | 200/s15/5%b | 242 | 297 | 42 | 245 | 233 | 48/48 | 0.2/1.8 | -/- (0) | 0.05/43.1 | - | 0.0 | 208/13.3/7.1 | n/a (1c) | n/a (1c) |
| free flight | 200/spk/0 | 249 | 280 | 49 | 254 | 237 | 48/48 | 0.2/1.0 | -/- (0) | 0.03/10.1 | - | 0.0 | 208/17.0/0.0 | n/a (1c) | n/a (1c) |
| free flight | 200/spk/1%u | 249 | 283 | 49 | 254 | 237 | 48/48 | 0.2/1.0 | -/- (0) | 0.03/10.1 | - | 0.0 | 201/14.8/3.6 | n/a (1c) | n/a (1c) |
| free flight | 200/spk/5%b | 249 | 390 | 49 | 254 | 237 | 48/48 | 0.2/1.6 | -/- (0) | 0.06/55.1 | - | 0.0 | 201/15.7/10.7 | n/a (1c) | n/a (1c) |
| free flight | 300/0/0 | 350 | 379 | 50 | 355 | 350 | 48/48 | 0.2/0.7 | -/- (0) | 0.02/6.1 | - | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/0/1%u | 350 | 379 | 50 | 355 | 350 | 48/48 | 0.2/0.7 | -/- (0) | 0.02/6.1 | - | 0.0 | 300/4.0/3.6 | n/a (1c) | n/a (1c) |
| free flight | 300/0/5%b | 353 | 396 | 53 | 358 | 350 | 48/48 | 0.2/1.2 | -/- (0) | 0.02/127.2 | - | 2.0 | 300/4.1/3.6 | n/a (1c) | n/a (1c) |
| free flight | 300/s15/0 | 342 | 389 | 42 | 342 | 336 | 48/48 | 0.2/1.2 | -/- (0) | 0.02/6.7 | - | 0.0 | 312/22.6/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/s15/1%u | 342 | 390 | 42 | 342 | 336 | 48/48 | 0.2/1.1 | -/- (0) | 0.02/6.7 | - | 0.0 | 312/22.1/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/s15/5%b | 349 | 437 | 49 | 349 | 343 | 48/48 | 0.2/1.3 | -/- (0) | 0.03/43.8 | - | 0.0 | 302/17.5/25.0 | n/a (1c) | n/a (1c) |
| free flight | 300/spk/0 | 353 | 398 | 53 | 358 | 350 | 48/48 | 0.2/0.6 | -/- (0) | 0.02/13.3 | - | 0.0 | 321/10.4/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/spk/1%u | 353 | 425 | 53 | 358 | 350 | 48/48 | 0.2/0.7 | -/- (0) | 0.02/14.0 | - | 0.0 | 317/8.7/0.0 | n/a (1c) | n/a (1c) |
| free flight | 300/spk/5%b | 355 | 468 | 55 | 358 | 353 | 48/48 | 0.2/1.5 | -/- (0) | 0.02/127.5 | - | 2.0 | 330/9.1/3.6 | n/a (1c) | n/a (1c) |
| walls | 0/0/0 | 40 | 75 | 40 | 38 | 40 | 48/48 | 0.2/1.0 | 5.6/17.8 (317) | 0.02/3.2 | 0.07 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/0/1%u | 40 | 75 | 40 | 38 | 40 | 48/48 | 0.2/1.1 | 5.7/18.3 (326) | 0.02/3.2 | 0.07 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/0/5%b | 43 | 95 | 43 | 41 | 43 | 48/48 | 0.2/8.7 | 4.2/17.1 (534) | 0.04/90.5 | 0.07 | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/s15/0 | 55 | 106 | 55 | 50 | 57 | 48/48 | 0.2/1.1 | 5.5/17.7 (1016) | 0.02/3.0 | 0.07 | 0.0 | 20/9.9/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/s15/1%u | 55 | 106 | 55 | 50 | 57 | 48/48 | 0.2/1.1 | 5.5/17.8 (1018) | 0.03/3.0 | 0.07 | 0.0 | 20/10.6/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/s15/5%b | 59 | 124 | 59 | 50 | 61 | 48/48 | 0.2/1.1 | 4.7/17.2 (1173) | 0.03/21.9 | 0.07 | 0.0 | 26/7.8/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/spk/0 | 49 | 96 | 49 | 49 | 49 | 48/48 | 0.2/1.4 | 5.4/17.7 (527) | 0.03/7.8 | 0.07 | 0.0 | 18/7.8/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/spk/1%u | 49 | 104 | 49 | 49 | 49 | 48/48 | 0.2/1.1 | 5.5/17.8 (504) | 0.03/3.1 | 0.08 | 0.0 | 18/6.8/0.0 | n/a (1c) | n/a (1c) |
| walls | 0/spk/5%b | 58 | 153 | 58 | 49 | 58 | 48/48 | 0.2/0.9 | 4.6/17.4 (704) | 0.03/21.1 | 0.08 | 0.0 | 16/6.3/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/0/0 | 142 | 174 | 42 | 142 | 144 | 48/48 | 0.2/0.7 | 3.7/6.9 (269) | 0.02/3.9 | 0.05 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/0/1%u | 142 | 174 | 42 | 142 | 144 | 48/48 | 0.2/0.8 | 3.6/6.9 (269) | 0.02/3.9 | 0.05 | 0.0 | 100/4.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/0/5%b | 142 | 179 | 42 | 142 | 144 | 48/48 | 0.2/0.9 | 3.7/6.9 (271) | 0.02/15.4 | 0.05 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/s15/0 | 142 | 191 | 42 | 142 | 142 | 48/48 | 0.2/1.1 | 3.8/6.9 (361) | 0.02/3.6 | 0.05 | 0.0 | 111/13.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/s15/1%u | 153 | 191 | 53 | 153 | 149 | 48/48 | 0.2/1.2 | 3.7/7.0 (359) | 0.03/3.6 | 0.05 | 0.0 | 112/13.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/s15/5%b | 143 | 214 | 43 | 148 | 142 | 48/48 | 0.2/1.0 | 3.7/6.9 (360) | 0.03/12.9 | 0.05 | 0.0 | 115/13.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/spk/0 | 153 | 217 | 53 | 142 | 159 | 48/48 | 0.2/0.7 | 3.6/6.9 (276) | 0.02/3.8 | 0.05 | 0.0 | 104/6.4/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/spk/1%u | 153 | 213 | 53 | 142 | 159 | 48/48 | 0.2/0.7 | 3.6/6.9 (272) | 0.02/3.8 | 0.05 | 0.0 | 104/6.4/0.0 | n/a (1c) | n/a (1c) |
| walls | 100/spk/5%b | 153 | 233 | 53 | 142 | 159 | 48/48 | 0.2/0.7 | 3.7/6.9 (275) | 0.02/14.8 | 0.05 | 0.0 | 107/5.5/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/0/0 | 237 | 275 | 37 | 231 | 237 | 48/48 | 0.2/1.1 | 9.0/22.3 (4887) | 0.02/5.0 | 0.07 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/0/1%u | 237 | 280 | 37 | 231 | 237 | 48/48 | 0.2/1.1 | 9.0/22.3 (4887) | 0.02/5.0 | 0.07 | 0.0 | 200/4.0/3.6 | n/a (1c) | n/a (1c) |
| walls | 200/0/5%b | 237 | 280 | 37 | 231 | 237 | 48/48 | 0.2/1.2 | 8.8/22.3 (4887) | 0.03/11.2 | 0.07 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/s15/0 | 251 | 291 | 51 | 254 | 245 | 48/48 | 0.2/1.2 | 9.0/22.3 (4886) | 0.02/5.5 | 0.07 | 0.0 | 211/15.3/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/s15/1%u | 251 | 292 | 51 | 254 | 245 | 48/48 | 0.2/1.2 | 9.0/22.3 (4886) | 0.02/5.5 | 0.07 | 0.0 | 209/15.6/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/s15/5%b | 251 | 292 | 51 | 254 | 250 | 48/48 | 0.2/1.4 | 8.8/22.3 (4883) | 0.03/9.7 | 0.07 | 0.0 | 222/13.6/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/spk/0 | 252 | 327 | 52 | 254 | 249 | 48/48 | 0.2/1.2 | 9.0/22.4 (4886) | 0.03/8.1 | 0.07 | 0.0 | 212/4.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 200/spk/1%u | 253 | 312 | 53 | 254 | 249 | 48/48 | 0.2/1.1 | 9.0/22.3 (4886) | 0.03/5.2 | 0.07 | 0.0 | 210/4.5/3.6 | n/a (1c) | n/a (1c) |
| walls | 200/spk/5%b | 252 | 390 | 52 | 254 | 249 | 48/48 | 0.2/1.3 | 8.8/22.4 (4886) | 0.04/10.3 | 0.07 | 0.0 | 210/4.3/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/0/0 | 350 | 379 | 50 | 355 | 350 | 48/48 | 0.2/0.8 | 9.4/23.4 (5748) | 0.04/4.6 | 0.07 | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/0/1%u | 350 | 379 | 50 | 355 | 350 | 48/48 | 0.2/0.9 | 9.4/23.4 (5742) | 0.04/4.6 | 0.07 | 0.0 | 300/4.1/3.6 | n/a (1c) | n/a (1c) |
| walls | 300/0/5%b | 353 | 465 | 53 | 358 | 350 | 48/48 | 0.2/1.2 | 9.1/23.5 (5782) | 0.08/17.6 | 0.07 | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/s15/0 | 337 | 395 | 37 | 333 | 344 | 48/48 | 0.2/1.0 | 7.5/33.6 (3380) | 0.05/4.8 | 0.07 | 0.0 | 316/16.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/s15/1%u | 337 | 402 | 37 | 333 | 344 | 48/48 | 0.2/0.9 | 7.5/33.6 (3380) | 0.05/4.8 | 0.07 | 0.0 | 309/18.0/3.6 | n/a (1c) | n/a (1c) |
| walls | 300/s15/5%b | 344 | 412 | 44 | 345 | 336 | 48/48 | 0.2/1.8 | 7.2/33.5 (3365) | 0.08/18.5 | 0.08 | 0.0 | 319/11.4/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/spk/0 | 355 | 405 | 55 | 358 | 353 | 48/48 | 0.2/0.9 | 9.4/23.4 (5747) | 0.04/7.5 | 0.07 | 0.0 | 303/8.0/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/spk/1%u | 355 | 448 | 55 | 358 | 353 | 48/48 | 0.2/1.1 | 9.4/23.4 (5743) | 0.05/7.5 | 0.08 | 0.0 | 314/7.7/0.0 | n/a (1c) | n/a (1c) |
| walls | 300/spk/5%b | 358 | 465 | 58 | 358 | 353 | 48/48 | 0.2/1.5 | 9.1/23.5 (5791) | 0.09/17.3 | 0.08 | 0.0 | 306/6.6/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/0/0 | 40 | 75 | 40 | 38 | 40 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/0.5 | - | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/0/1%u | 40 | 75 | 40 | 38 | 40 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/0.5 | - | 0.0 | 8/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/0/5%b | 43 | 95 | 43 | 41 | 43 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/41.1 | - | 0.0 | 8/4.1/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 0/s15/0 | 55 | 106 | 55 | 50 | 57 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/0.3 | - | 0.0 | 22/9.9/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/s15/1%u | 55 | 106 | 55 | 50 | 57 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/0.3 | - | 0.0 | 20/11.0/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/s15/5%b | 59 | 124 | 59 | 50 | 61 | 48/48 | 0.0/0.6 | -/- (0) | 0.02/42.5 | - | 0.0 | 20/10.5/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 0/spk/0 | 49 | 96 | 49 | 49 | 49 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/37.5 | - | 0.0 | 18/8.5/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/spk/1%u | 49 | 104 | 49 | 49 | 49 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/37.5 | - | 0.0 | 13/7.7/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 0/spk/5%b | 58 | 153 | 58 | 49 | 58 | 48/48 | 0.0/0.8 | -/- (0) | 0.01/69.9 | - | 0.0 | 19/8.1/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 100/0/0 | 142 | 174 | 42 | 142 | 144 | 48/48 | 0.0/0.6 | 9.2/21.2 (348) | 0.01/0.9 | 0.11 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/0/1%u | 142 | 174 | 42 | 142 | 144 | 48/48 | 0.0/0.6 | 9.2/21.2 (348) | 0.01/1.0 | 0.11 | 0.0 | 100/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/0/5%b | 142 | 179 | 42 | 142 | 144 | 48/48 | 0.0/0.7 | 9.2/21.2 (348) | 0.01/67.8 | 0.11 | 0.0 | 100/4.0/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 100/s15/0 | 142 | 191 | 42 | 142 | 142 | 48/48 | 0.0/0.6 | -/- (0) | 0.02/0.5 | - | 0.0 | 109/14.2/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/s15/1%u | 153 | 191 | 53 | 153 | 149 | 48/48 | 0.0/0.6 | -/- (0) | 0.02/3.2 | - | 0.0 | 107/14.6/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/s15/5%b | 143 | 214 | 43 | 148 | 142 | 48/48 | 0.0/0.7 | -/- (0) | 0.02/52.9 | - | 0.0 | 106/13.9/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/spk/0 | 153 | 217 | 53 | 142 | 159 | 48/48 | 0.0/0.6 | 9.1/21.2 (367) | 0.01/24.3 | 0.09 | 0.0 | 134/9.5/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 100/spk/1%u | 153 | 213 | 53 | 142 | 159 | 48/48 | 0.0/0.7 | 9.1/21.2 (366) | 0.01/24.9 | 0.10 | 0.0 | 134/8.7/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 100/spk/5%b | 153 | 233 | 53 | 142 | 159 | 48/48 | 0.0/0.7 | 9.0/21.2 (359) | 0.02/67.1 | 0.10 | 0.0 | 131/8.6/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/0/0 | 237 | 275 | 37 | 231 | 237 | 48/48 | 0.0/0.7 | 20.3/24.4 (514) | 0.01/1.2 | 0.13 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/0/1%u | 237 | 280 | 37 | 231 | 237 | 48/48 | 0.0/0.7 | 20.3/24.4 (514) | 0.01/1.2 | 0.13 | 0.0 | 200/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/0/5%b | 237 | 280 | 37 | 231 | 237 | 48/48 | 0.0/0.8 | 20.3/24.4 (514) | 0.01/56.0 | 0.13 | 0.0 | 200/4.0/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 200/s15/0 | 251 | 291 | 51 | 254 | 245 | 48/48 | 0.0/0.7 | 20.3/24.4 (513) | 0.02/1.1 | 0.12 | 0.0 | 219/16.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/s15/1%u | 251 | 292 | 51 | 254 | 245 | 48/48 | 0.0/0.8 | 20.3/24.4 (514) | 0.02/2.8 | 0.14 | 0.0 | 209/14.7/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/s15/5%b | 251 | 292 | 51 | 254 | 250 | 48/48 | 0.0/0.8 | 20.3/24.4 (514) | 0.03/75.8 | 0.12 | 0.0 | 215/20.6/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 200/spk/0 | 252 | 327 | 52 | 254 | 249 | 48/48 | 0.0/0.7 | 20.3/24.4 (514) | 0.02/24.0 | 0.14 | 0.0 | 221/7.7/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/spk/1%u | 253 | 312 | 53 | 254 | 249 | 48/48 | 0.0/0.7 | 20.3/24.4 (514) | 0.02/25.7 | 0.14 | 0.0 | 213/6.8/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 200/spk/5%b | 252 | 390 | 52 | 254 | 249 | 48/48 | 0.0/0.8 | 20.3/24.4 (514) | 0.02/71.3 | 0.12 | 0.0 | 215/15.3/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 300/0/0 | 350 | 379 | 50 | 355 | 350 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/0.4 | - | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/0/1%u | 350 | 379 | 50 | 355 | 350 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/0.4 | - | 0.0 | 300/4.1/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/0/5%b | 353 | 465 | 53 | 358 | 350 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/80.0 | - | 0.0 | 300/4.1/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 300/s15/0 | 337 | 395 | 37 | 333 | 344 | 48/48 | 0.0/0.6 | 12.9/24.6 (2391) | 0.02/0.9 | 0.10 | 0.0 | 316/14.4/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/s15/1%u | 337 | 402 | 37 | 333 | 344 | 48/48 | 0.0/0.6 | 12.9/24.6 (2391) | 0.02/1.3 | 0.10 | 0.0 | 311/13.7/3.6 | n/a (1c) | n/a (1c) |
| gravity well | 300/s15/5%b | 344 | 412 | 44 | 345 | 336 | 48/48 | 0.0/0.6 | 12.2/24.8 (2390) | 0.02/67.2 | 0.11 | 0.0 | 313/22.0/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/spk/0 | 355 | 405 | 55 | 358 | 353 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/31.8 | - | 0.0 | 332/10.3/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/spk/1%u | 355 | 448 | 55 | 358 | 353 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/32.0 | - | 0.0 | 324/9.9/0.0 | n/a (1c) | n/a (1c) |
| gravity well | 300/spk/5%b | 358 | 465 | 58 | 358 | 353 | 48/48 | 0.0/0.6 | -/- (0) | 0.01/66.3 | - | 0.0 | 309/8.3/0.0 | n/a (1c) | n/a (1c) |
