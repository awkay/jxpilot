# Latency fixes: a plan for playing JXPilot over 150-300 ms RTT

Status: design written 2026-10-23 against `main` at `31e9f40`. Phase 0 (measurement) is done; its
measured figures replace the first estimates in §1, §2.1, §4.1, §5 and §8. See
[latency-measurements.md](latency-measurements.md).

Target: friends on opposite sides of the planet, 150-300 ms round trip time (RTT), with jitter and some
loss, playing on a JXPilot server hosted somewhere in the middle.

## 1. The answer in brief

**Today a key press shows up on your own ship after about RTT + 44 ms** (measured; see
[latency-measurements.md](latency-measurements.md)). At 200 ms RTT that is about 244 ms. This plan first
estimated RTT + 135 ms, of which 91 ms was interpolating the own ship like the others. Commit `d722c79`
(F1) landed after the plan was written: it draws the own ship from the newest SELF, dead-reckoned to the
present, so that term is gone. What is left is the round trip, the wait for the next 71 ms tick (36 ms on
average) and a render frame at each end. That is about what the C client gets on the same link, drawn at
vsync instead of at 14 frames a second.

**The fix with the largest gain is client-side prediction of your own ship, with server reconciliation.**
XPilot suits it unusually well:

- The physics is Newtonian and per-frame. The server runs at only ~14 Hz, so replays are tiny.
- The gravity field and walls come from the map, which the client already has bit-for-bit.
- The server's ship movement code uses no randomness except to break ties between equally good wall
  bounces.
- Java has strict IEEE floating point (Java 17+), and our trig is table-based with `StrictMath`, so the
  same kernel gives bit-identical results on every JVM.

Prediction makes your own ship, and later your own shots and exhaust, respond in about 10-45 ms at any
RTT.

What it takes:

1. **Extract the ship-movement kernel into `common`.** This covers turning, thrust, mass, gravity,
   `Move_player`, `Move_segment` and the bounce rules. Both the server and the client run it. It has to
   stay bit-exact with C, and the `tools/xpilot-ref` traces gate that.
2. **Protocol v2.** Inputs are tagged with the tick they are for. The server buffers them per tick. A
   new section carries the exact ship state and which input tick it reflects.
3. **A client predictor.** It keeps a ring of predicted ticks, replays them on each snapshot and
   smooths visual corrections. It renders a speculative partial tick, so the 71 ms tick does not add
   latency.

**Things that will not get fixed, and are not worth trying to:**

- other players are always seen at least RTT/2 late;
- being shot, rammed, tractored or pulled by a ball can only be corrected after the fact;
- server-side rewind ("lag compensation") is unfair and contrary to dodge-based XPilot play, because
  shots are slow projectiles that live for seconds.

The best tool for aiming at other players is to dead-reckon them to the present. Because the physics
is known, this is accurate for coasting ships and errs by about ½·a·t² for maneuvering ones.

**Rough total:** about 6-9 weeks of agent-driven work for phases 0-3 (§8), with phase 1 (~3-4 weeks)
delivering most of the felt improvement. Effort figures in this document are estimates, not
measurements.

## 2. Where the latency comes from today

Code references are to this worktree. T is one server tick, which is one C frame:
T = 1000/framesPerSecond ms. That is 71 ms at the default 14 fps and 83 ms at 12.

### 2.1 Own ship, input to photon (Java server, Java client)

| Stage | Time | Where |
|---|---|---|
| Key poll → INPUT sent | 0-8 ms (polled each render frame at 120 Hz; sent at once on a change) | `ClientFrame.java:118`, `ClientConnection.sendInput` |
| Uplink | RTT/2 | |
| Wait for the next server tick | 0-T, average T/2 ≈ 36 ms. Every mask received is queued and applied at the next tick | `ServerNet.onInput` → `SimGameHost.input` → `applyQueuedInput` |
| Simulate and send the snapshot | < 1 ms. The snapshot goes out right after the tick | `Server.runLoop` → `ServerNet.tick` |
| Downlink | RTT/2 | |
| Wait for the next render frame to draw it | 0-8 ms. The own ship is drawn from the newest SELF, dead-reckoned to the present (F1, `d722c79`); there is no interpolation delay for it | `ClientWorld.predictSelf` |
| Render and present | ≈ 5-10 ms (not in the measurement) | |
| **Total** | **≈ RTT + 44 ms** mean, measured; RTT + 0..88 ms from tick and frame phase, plus jitter | |

| RTT | JXPilot today (measured mean, p95) | C client on a C server (estimate: no interpolation, 14 fps drawing) |
|---|---|---|
| 0 (LAN) | ≈ 43 ms, 77 ms | ≈ 45 ms |
| 100 ms | ≈ 143 ms, 177 ms | ≈ 145 ms |
| 200 ms | ≈ 243 ms, 277 ms | ≈ 245 ms |
| 300 ms | ≈ 343 ms, 377 ms | ≈ 345 ms |

Measured headless on the real server and client through the link emulator: see
[latency-measurements.md](latency-measurements.md) for the method and the tables with jitter and loss.
This section first estimated RTT + 135 ms. That was right for the code it was written against (`31e9f40`),
which interpolated the own ship 1.28 T ≈ 91 ms in the past like every other ship. `d722c79` (F1) replaced
that with prediction from the newest SELF before this plan's phase 0 ran. Other ships, shots and other
objects are still interpolated (§2.2).

### 2.2 Other ships

The client draws other ships at `serverTickNow - 1.28 T`. `TickClock` estimates `serverTickNow` from
snapshot arrival times, so the estimate already includes the downlink delay. The drawn image of another
ship is therefore about RTT/2 + 91 ms old. Once your own ship is predicted, it runs ahead of the server
by the input lead (≈ RTT/2 + a jitter margin). The gap between "my ship" and "their ship" in game time
is then about **RTT + 110 ms**:

| RTT | Time gap own→others | Ticks at 14 Hz | Position error of a ship coasting at 10 px/tick |
|---|---|---|---|
| 100 ms | ≈ 210 ms | 2.9 | ≈ 29 px |
| 200 ms | ≈ 310 ms | 4.3 | ≈ 43 px |
| 300 ms | ≈ 410 ms | 5.7 | ≈ 57 px |

For scale, a ship is 32 px across (`SHIP_SZ` 16 is the radius) and shots fly at 21 px/tick plus the
shooter's velocity.

### 2.3 What C XPilot does about lag

Essentially nothing beyond robustness:

- **Input:** the client sends the whole key vector with a change counter (`Send_keyboard`), and resends
  it every frame until the frame header acks it (`key_ack` in `Receive_start`). It measures
  "packet lag" from those acks only for display (`Net_lag_measurement`).
- **Server:** applies a keyboard packet on arrival (`Receive_keyboard` → `Handle_keyboard`). It allows
  one keyboard update per datagram ("no macros").
- **No prediction, interpolation or rewind:** the client draws each frame as received. The server sends
  integer positions, truncated integer velocities for the own ship only, and no velocities or ids for
  shots.

Every technique below is therefore a JXPilot extension. On a C server it can only be done
approximately (§6).

## 3. Hazards specific to this game

| Hazard | Why it matters | Consequence for the plan |
|---|---|---|
| **Very low tick rate** (12-14 Hz, T ≈ 71-83 ms) | Each tick of interpolation or input wait costs ~71 ms, five times what it costs a 60 Hz game. | Prediction gains more here than in most games. Replays are tiny (RTT 300 ms ≈ 5 ticks). Render a speculative partial tick so the tick quantum itself does not add latency (§4.1). |
| **Tick rate = game speed** | In 4.5.5 every speed is per frame. There is no `gameSpeed`, so a higher framesPerSecond is a faster game. | A higher internal tick rate means fractional dt, which the SPEC already rejected after three fidelity reviews (§4.11). |
| **Gravity, wall bounce, friction blocks** | The ship's path depends on the map's gravity field (`WorldMap.gravityX/Y`, in `common`), walls, and the state of targets and cannons. | The client has all of it: identical config text, and TARGET/CANNON sections. The kernel can run it exactly (§4.4). |
| **Wall tie-breaking uses the shared RNG** | `Mover.movePlayer:358` and `Segment.resolveBounce:839` call `w.rfrac()` to pick among equally good bounces. The client cannot reproduce the server's world RNG stream. | Occasional mispredicted corner bounces get corrected. An opt-in server option can draw those choices from a per-(tick, player) RNG (§4.4). |
| **Mass and fuel** | Thrust acceleration is power/mass, and mass includes fuel and items. | Send the exact mass and fuel in the prediction state. Refuel and pickups between snapshots cause small, self-correcting errors. |
| **Turn inertia** | `floatDir`, `turnVel`, `oldTurnVel`, `turnAcc` and `turnResistance` are all floats, and SELF sends only the heading. | The prediction state must carry them (§4.3). |
| **Balls** | A connector spring couples ship and ball, and other players' sparks push the ball (`ballSparkCollisions`). | The ball is only partly predictable (§4.10). |
| **Camera = own ship** | The view is centred on the own ship, so every own-ship correction shifts the whole screen. | Smoothing matters more than in third-person games (§4.2). |
| **C parity** | The server must stay frame-for-frame identical to C, up to RNG sequences. | The kernel extraction is a pure refactor gated by the xpilot-ref traces. Input tick tagging changes only *which* tick an input lands on, which in C also depends on arrival time. |
| **Cross-JVM determinism** | Java 17+ (JEP 306) makes `+ - * /`, `sqrt` and casts bit-identical everywhere. `Math.sin/cos/atan2/hypot/pow` may differ by an ulp between JVMs and CPUs (intrinsics). | The kernel uses `Trig` tables built with `StrictMath` and no other `Math` transcendentals. Today only robot code calls `Math.hypot`, and robots stay on the server. Add a test that fails if the kernel package references `java.lang.Math` transcendentals. Fixed point is not needed: positions are already int clicks (`Click`, 64/px), and velocities are C-style floats. |

## 4. Techniques

Each technique below states what it fixes and what it does not, the perceived gain at 100, 200 and 300
ms RTT, the cost, the risks and limits, and a tier:

- **DO FIRST**: phase 1.
- **LATER**: phases 2-4.
- **DON'T**: not worth doing, at least for now.

### 4.1 Own-ship prediction with reconciliation and replay (DO FIRST)

**How it works here:**

- The client runs its own tick clock *ahead* of the server by the input lead (§4.3).
- Each local tick k:
  - record the key mask for k;
  - send it tagged with k;
  - step the kernel (§4.4) from predicted state k-1 to k, and keep it in a ring of 32 ticks (2.3 s).
- When a snapshot for server tick T arrives, it carries the exact ship state after T and confirms that
  the server applied input k = T. The client compares that state with the predicted ring entry T:
  - **bit-equal** (the common case): nothing to do;
  - **different**: re-seed from the server state and replay ticks T+1..present with the recorded inputs.
    The visual difference goes to the smoother (§4.2).
- **Speculative partial tick.** The tick quantum is 71 ms, so do not interpolate between predicted ticks
  (that adds a whole tick). Each render frame, draw
  `lerp(pred[k], step(pred[k], currentKeys), α)`, where α is the fraction of local tick k elapsed. A key
  change re-runs that one step, so its effect starts on the next drawn frame and ramps over the rest of
  the tick. The step is one ship and costs microseconds.
- **No prediction** while dead, paused, counting down to respawn, in the round delay, warping,
  hyperjumping or being transported. The snapshot timeline is used as today.

**Fixes:** the own ship's turn, thrust, recoil, gravity and wall bounces respond at once. The camera
follows the predicted ship, so the whole view feels immediate.

**Does not fix:** others' positions (§4.6), forces from others (§4.10), or a server event you have not
heard about yet. Example: you are shot. The predicted ship keeps flying for about RTT, then the
explosion appears where the server killed you.

**Perceived own-ship latency:**

| RTT | Today (measured) | With prediction and the speculative step | Prediction without the speculative step |
|---|---|---|---|
| 100 ms | ≈ 143 ms | ≈ 10-45 ms | ≈ 115 ms |
| 200 ms | ≈ 243 ms | ≈ 10-45 ms | ≈ 115 ms |
| 300 ms | ≈ 343 ms | ≈ 10-45 ms | ≈ 115 ms |

**Cost:** about 800-1200 lines plus tests in `client` (`game/predict/`: `OwnShipPredictor`,
`PredictedClock`, `CorrectionSmoother`). It also needs the kernel (§4.4) and protocol v2 (§4.3). It must
be allocation-free (SPEC GC rules): the ring holds preallocated kernel state structs.

**Risks and limits:**

- Mispredictions show up as corrections. Their size is bounded by RTT × the unmodelled force.
- Thrust sparks, HUD fuel and the own-ship painter must read the predicted state, not the snapshot
  state. Otherwise there are two ships.
- **View culling:** the server culls around *its* ship position, and the predicted view runs ahead by up
  to lead × speed (≈ 6 ticks × 30 px = 180 px at high speed). `SnapshotBuilder` should add
  `|v| · leadTicks` to its view half-size, which costs a few lines. *(As built in 1b: the view is extended by
  v · lead only on the sides the ship flies towards, the lead clamped to 8 ticks and the extension to 160 px.
  Measured, owner in a 12-robot arena fight at 20 px/tick: 386 B mean at lead 0, 396 B at lead 4, 400 B at
  lead 8 and beyond; before the clamp lead 14 gave 494 B.)*

**Testing:** the kernel bit-equality oracle (§4.4). A headless predictor on the lossy-link emulator
(§7) must hold "zero corrections" on a no-contact course at RTT 300, jitter 30 and loss 5%, and give
bounded corrections otherwise. Run PIT on `predict/`.

### 4.2 Error smoothing and blending (DO FIRST, part of 4.1)

When a replay moves the predicted ship, keep the drawn ship where it was and record a visual offset
(position, plus heading via `XMath.angleDiff`). Decay the offset exponentially with a half-life of about
80-120 ms, a tunable. Snap instead when the offset is larger than ~3 ship lengths, larger than
`ClientWorld.TELEPORT_PX`, or comes from a respawn, wormhole or hyperjump. Decay the camera with the
ship. A camera that snaps while the ship glides, or the reverse, reads as a stutter.

- **Fixes:** turns small corrections (shoves, others' exhaust pushing you, corner tie-breaks) into
  invisible drifts.
- **Does not fix:** large corrections. A death snap is the honest thing to show.
- **Gain:** no latency gain; it is what makes prediction look good.
- **Cost:** ~150 lines plus tests.
- **Risk:** an offset that is too long-lived makes the ship feel floaty near walls. Decay faster when
  the corrected path touches a wall: crossing a wall with a visual offset looks like clipping.

### 4.3 Input tick-tagging, server input buffer, jitter buffer and clock sync (DO FIRST)

**Why it is required:** exact prediction needs the server to apply input k at tick k. Today the server
does something else:

- It applies every mask that arrived before the tick, even several of them (`SimGameHost.input` queues
  up to 16, and `applyQueuedInput` applies all of them).
- A mask sent once is held until the next one.

So the tick an input lands on depends on arrival jitter, and the client cannot know it. With 20 ms of
jitter and 71 ms ticks, roughly a quarter of key changes would land one tick early or late. Each would
be a misprediction of one tick of thrust or turn: ~2 px/tick² of thrust, or several degrees of heading.

**Design (protocol v2, `Protocol.VERSION` 2):**

- **INPUT** becomes per tick: `u32 newestTick, u8 count`, then `count ×` (`u64 keys, u64 taps,
  i16 aimTurn`), newest first.
  - It is sent once per local tick, and also at once on a change (which re-sends the current tick's
    entry).
  - `taps` holds keys that went down and up within the tick, so a quick fire tap is not lost.
    `Keys.apply` is called with `keys | taps`, then with `keys`, which reproduces C's edge semantics.
  - Redundancy stays at "everything newer than the last acked tick, at most 8": 8 ticks ≈ 570 ms of
    burst loss covered.
- **Server input buffer:** a per-player ring keyed by tick (32 entries). This replaces the per-tick queue
  in `SimGameHost`. At tick T the server applies entry T.
  - **Missing entry:** repeat the last mask (keys are usually held) and count a *starvation*.
  - **Entry for a past tick:** drop it and count it *late*.
  - **Entry more than 1 s ahead:** drop it (bounds memory and abuse).
- **Snapshot header:** `lastInputSeq` becomes `inputTick` (the tick of the input applied in this
  snapshot's tick, which is T itself, or the held one on starvation). Add an `i8 inputSlack` in 1/16
  tick: the smallest margin by which inputs arrived ahead of their tick over the last second.
- **Lead control (clock sync):**
  - The client sets its initial lead from PING/PONG RTT: `ceil((RTT/2 + 30 ms)/T) + 1` ticks.
  - It then steers by *time dilation*: its predicted clock runs at simHz × (1 ± 3%), the same slew limit
    `TickClock` already uses, aiming for `inputSlack` ≈ 0.5 tick plus 2σ of jitter.
  - On a starvation report it jumps up one tick at once and drifts back down slowly. The asymmetry is
    deliberate: running out of input costs more than an extra few ms of lead.
  - No absolute clock sync is needed. `TickClock` (arrival-based) keeps driving the render time for
    others, and the lead loop drives the predicted clock.

**Fixes:** exact prediction (it removes jitter from the tick an input lands on). It also smooths how
others see you: a 20 ms jitter spike no longer makes your input slip a tick.

**Does not fix:** latency on its own. Others now see your inputs `slack` later (≈ 0.5 T + jitter margin),
which is about the same as today's average T/2 wait for the next tick.

**Gain:** indirect, through prediction accuracy. With 20-50 ms of jitter it is the difference between
"almost always exact" and "corrects visibly every few key presses".

**Cost:**

- `common/net`: `InputHistory` and `InputReader` v2, the header field and `Msg` docs: ~200 lines.
- `server`: `InputBuffer`, `ServerNet.onInput`, `SimGameHost`, slack measurement: ~300 lines.
- `client`: `ClientConnection.sendInput` becomes tick driven, plus `PredictedClock`: ~300 lines.
- Docs: SPEC "Time model" and "Client input" bullets, PROTOCOL INPUT and header sections.

**Risks:**

- Robots are unaffected.
- **C parity:** the server's per-frame processing is unchanged. Only the landing tick of a human input
  changes, which C leaves to arrival time anyway. The "every edge counts" rule collapses to "a key tapped
  at most once per tick". C allows several keyboard datagrams per frame, but 14 taps a second is beyond
  human play.

**Testing:**

- unit tests of the buffer (in-order, reordered, duplicated, late, far-future and missing entries);
- tests of the lead controller against the link emulator with scripted RTT steps (100→300→100 ms): it
  must converge in < 2 s with no starvation after convergence;
- the loss and jitter matrix (§7).

### 4.4 Shared deterministic ship kernel in `common`: wall collision and bounce prediction (DO FIRST, the hard part)

**What moves:** the subset of the server that decides an own ship's next state from its state, its keys
and static map data.

- `Ships.update`'s physics path: limits, turn, `thrustAcceleration`, `setMass`, gravity, refuel fuel
  accounting.
- `Keys`' movement-relevant edges, and the shot-fire *math* only: the nose position, velocity and recoil
  from `Shots.fire`.
- `Mover.movePlayer`, `Mover.turnPlayer` and `wallBounce`.
- All of `Segment` (`Move_segment`, `Bounce_edge`, `Bounce_wall`).
- `MoveState`, `Click`, `Trig`, `WallDist`, and the parts of `World` and `Params` they read (click
  sizes, wrap, wall distance, the map, and target, cannon and treasure blocking state).

**Correction (phase 1a/1b as built):** refuel fuel accounting did *not* move. Device drain and timers
(phasing, cloak, emergency shield and thrust, deflector cost), refuel and tank moves stay in the server's
upkeep (`Ships.update` outside `ShipKernel.step`), so the predictor mispredicts fuel, mass and the `used`
bits they change until the next PREDICT corrects them. Phase 1c measures how much (§8); moving them into the
kernel is a later decision. The kernel's world comes to the client as the KERNEL_CONFIG blob (the kernel
options and the map blocks the client's map hides) and KERNEL_STATE (cannon phasing, target teams, changed
blocks, temporary wormholes); the frame clock and roundDelay ride in PREDICT.

In total that is roughly 2,000-2,500 lines moved and ~300 lines of new seams.

**Seams:**

- The kernel owns a `ShipBody` struct: clicks x/y, vx/vy, floatDir, dir, turnVel, oldTurnVel,
  turnAcc, mass, fuel, status and used bits, item counts that affect physics, and lastWallTouch.
  `ShipBody` also owns its own wire codec, so the wire can never lag behind the kernel's state. *(As built
  in 1b: the PREDICT section is 66 bytes every snapshot, a 63-byte core plus the applied input's hash and
  the rare and kernel versions; PREDICT_ROUND, 2 bytes, the round delay while one runs; PREDICT_RARE, 64
  bytes, carries the frame-clock offset, engine settings, masses, team, wormhole destination, modifiers and
  item counts when they changed in the last 8 snapshots and every 14th; PREDICT_KEYS, 16 bytes, the key masks
  when they differ from the applied input. The tanks are not sent: the kernel only adds to the current tank's fuel.
  See PROTOCOL.md.)*
- Side effects go out through a small `KernelEvents` interface: crashed (with type), bounced, fired
  (with shot parameters), fuel spent.
  - The server implementation calls `Crashes.playerCrashed`, `Shots.fire`, sparks and scoring as today.
  - The client implementation records predicted events (§4.7, §4.8). A predicted crash freezes
    prediction at the crash point until the server rules on it.
- Wall tie-break randomness comes through an `IntSupplier`-style source.
  - The server passes `w.rfrac`, which keeps C RNG parity.
  - The client passes a fixed choice.
  - An opt-in server option, `predictableBounces` (a JXPilot deviation, default off, listed in
    `DEVIATIONS-server.md`), would draw those two choices from `Rng.seed(tick, playerId)`. Corner
    bounces then predict exactly as well. It is off for xpilot-ref trace runs because it changes the
    RNG stream.

**Fixes:** wall bounces, sliding along walls, turning blocked by a wall, gravity wells and friction
blocks all predict exactly. Without this, every wall touch would be a visible correction, and XPilot
is a game of flying close to walls.

**Does not fix:** anything involving other objects (§4.10).

**Gain:** what makes §4.1 exact near walls. Without it, prediction would be "inertial extrapolation",
wrong at every bounce by roughly speed × RTT.

**Cost:** about 1-1.5 weeks including the parity proof. It touches `server/sim`, `server/world` and a
new `common/sim` package.

**Risks:**

- **C parity regressions** in the most fidelity-reviewed code. Mitigate it this way:
  - a pure-move refactor first, with no behaviour change, in its own commit;
  - the xpilot-ref traces and `Digest` before and after must be bit-identical on all 19 maps;
  - the existing server sim tests move with the code;
  - a C-to-Java fidelity re-review of the moved files (CLAUDE.md).
- **Code duplication must be zero.** A client-side copy of `Segment` would drift. The only acceptable
  form is one kernel that both sides call.

**Testing:**

- a **differential test**: run the full server sim and the kernel side by side over random inputs on
  every map (walls, gravity, wrap, friction). After each tick, assert the kernel's `ShipBody` equals the
  server player's fields bit for bit, except where `KernelEvents` reported an interaction the kernel
  cannot know about;
- a test that the kernel package references no `java.lang.Math` transcendentals;
- an allocation test of 10k kernel steps.

### 4.5 Clock sync (covered by 4.3)

This is listed separately because it was asked about. JXPilot needs two clocks, and both exist or are
specified:

- The **render clock for others** (`TickClock`) estimates which tick is arriving now, smoothed, with
  resync and a 3% slew. Keep it.
- The **predicted clock** (§4.3) runs ahead of it and is steered by the server's `inputSlack` feedback.

NTP-style absolute sync is unnecessary: only tick offsets matter, and the slack loop measures the one
offset that matters (uplink) directly. PING/PONG supplies RTT for the initial lead and the HUD.

**Tier:** DO FIRST, as part of 4.3. It adds no separate cost.

### 4.6 Other players: interpolation vs. extrapolation and dead reckoning; adaptive interpolation delay

**Today:** others are interpolated between the two snapshots around `serverTickNow - 1.28 T`. When the
later snapshot is missing, they are extrapolated linearly from velocity for up to 2 intervals.

**Option A, adaptive interpolation delay (LATER, cheap).** Measure snapshot inter-arrival jitter (p95)
and loss on the client, and set `delayTicks = interval + max(5 ms, p95 jitter)`. If loss is above ~2%,
use 2 intervals. `ClientWorld.setInterpolationDelayTicks` already exists.

- **Fixes:** fewer extrapolation glitches on jittery links, and ~10-15 ms less delay on clean ones.
- **Gain:** ≈ 0-15 ms on the others' staleness, the same at any RTT. It is mostly a smoothness feature.
- **Cost:** ~150 lines plus tests, client only.

**Option B, physics dead reckoning to the predicted present (LATER, behind a client option, decide by
A/B).** XPilot ships are Newtonian bodies, and the client knows almost every force on them:

- gravity, from the map;
- thrust direction, from the heading;
- whether they are thrusting, from the `THRUSTING` flag;
- power and afterburners, from `PLAYER_THRUST`;
- mass, if a byte is added to SHIP.

So instead of drawing others 1.28 T in the past, draw them at the *own ship's predicted time*. Advance
their newest snapshot state by the gap with the kernel's free-flight step (gravity plus constant thrust;
no walls for others, or walls with the kernel, which is exact if they hold their keys). Corrections
blend with the §4.2 smoother.

- **Fixes:** aiming. Shots, ships and your own ship all live in one consistent time.

  | RTT | Gap closed | Error left for a coasting ship | Error left for one that changed thrust or turn at the worst moment (a ≈ 2 px/tick²) |
  |---|---|---|---|
  | 100 ms | ≈ 210 ms | ≈ 0 | ≈ 9 px |
  | 200 ms | ≈ 310 ms | ≈ 0 | ≈ 19 px |
  | 300 ms | ≈ 410 ms | ≈ 0 | ≈ 33 px |

  Today's interpolated view errs by v × gap for *every* moving ship: 29-57 px at 10 px/tick.
- **Does not fix:** sudden maneuvers, which still pop and get smoothed, or collisions, which still
  resolve late.
- **Risks:** ships look "rubbery" when players juke. Remote ships near walls might be drawn inside a
  wall if walls are not traced, so trace them with the kernel.
- **Cost:** ~500 lines in `client`, plus an optional `u8 mass` byte in SHIP. Records can grow with
  stride compatibility.

**Recommendation:** ship option A in phase 3. Build B in phase 3 as an option, playtest it, and make it
the default only if the friends prefer it. The numbers suggest they will at 200+ ms.

### 4.7 Predicted shots and projectiles (LATER, phase 2)

**How it works here:**

- When the predictor's tick k fires (a fire edge, autofire, or `fireRepeatRate` from the kernel's
  `Keys`), the kernel's fire math produces the exact spawn position and velocity, plus the recoil
  already applied to the ship.
- The client draws a provisional shot keyed by (k, ordinal) and steps it each tick with the kernel's
  object mover. Shots feel gravity (`Obj.GRAVITY`) and may wall-bounce (`shotsWallBounce`).
- C sends no shot ids, and our SHOT records have none, so reconciliation needs a new small `OWN_SHOTS`
  section sent only to the owner: `u32 spawnTick, u8 ordinal, rel x, rel y`.
  - If the server confirms the shot, the provisional one adopts the server's.
  - If the server never confirms it (no fuel, `maxPlayerShots` reached, stunned, already dead), it fades
    out within one RTT.
  - Own shots are skipped in the anonymous SHOT section's association for the owner, so they are not
    drawn twice.
  - *(1b built OWN_SHOTS (section 26, 6 B per own bullet) and then dropped it: the 1c predictor confirms a
    provisional shot by matching the own-colour bullets of the SHOT section by position; measured worst
    match 0.35 px, 0.8% unconfirmed, without the section's ~300 B per fight snapshot. The shot-motion
    options go to the client in KERNEL_CONFIG instead.)*
- **Timeline:** provisional shots live in predicted time, like the ship that fired them. With option
  4.6-B everything else does too, and nothing more is needed. Without it, your shot visibly overtakes
  an enemy that is drawn ~RTT + 110 ms in the past. Read §4.9 before "fixing" that on the server side.

**Fixes:** firing feels immediate.

| RTT | Today, the shot appears after (measured mean, p95) | With prediction |
|---|---|---|
| 100 ms | ≈ 238 ms, 267 ms | ≈ 10-45 ms |
| 200 ms | ≈ 338 ms, 367 ms | ≈ 10-45 ms |
| 300 ms | ≈ 438 ms, 467 ms | ≈ 10-45 ms |

Measured (latency-measurements.md, "Shots"): RTT + 138 ms on average, 95 ms more than the own ship's
response to the same press. The fire lands on the same tick as a turn would, and the new shot is in the
same snapshot as the SELF that shows its recoil. But shots are drawn on the interpolated timeline with
the other objects (`ClientWorld.drawShots` at the render tick, 1.28 T ≈ 91 ms behind), unlike the own ship
since F1 (§2.1): 11-12 render frames after the own ship. Drawing own shots on the own ship's timeline,
even before predicting them, would take about 92 ms off at any RTT.

**Does not fix:** hits. The server decides them, and you learn of a hit about RTT + interpolation later.

**Cost:** ~600 lines (client predictor objects, the `OWN_SHOTS` section, server emission). Missiles,
mines and lasers can follow the same pattern later; missiles steer, so they are only provisional at
spawn.

**Risk:** fire-condition mismatches, such as shot-count caps or fuel at the margin, show as a shot that
appears and fades. These are rare and harmless.

### 4.8 Predicted local effects: sparks, sounds, muzzle (LATER, phase 2, cheap after 4.1)

- **Thrust sparks:** `ThrustSparks.computeWire` is already seeded by `(tick, shipId)` and fed wire-
  quantized state, so it is deterministic. Generate the own ship's exhaust from the *predicted* state at
  the predicted tick, and skip the own ship in `ClientWorld`'s snapshot spark generation. When the
  prediction is right, the sparks are identical to the ones everyone else sees.
- **Sounds** (not implemented; `docs/SOUND-PLAN.md`): play `fire_shot`, the thrust loop and toggles
  from predicted local events. Play damage, explosions and pickups from server events (§4.14).
- **Shield and cloak** rendering from the predicted key state.

| | |
|---|---|
| **Gain** | The same ≈ 300-400 ms off the felt response to thrust and fire at 200-300 ms RTT. Exhaust is the main "did my key work" cue in XPilot. |
| **Cost** | ~200 lines. |
| **Risk** | None beyond §4.1. |

### 4.9 Lag compensation and server-side rewind for hit detection (DON'T)

Rewind (the server checks a hit against where the target was *as the shooter saw it*) suits hitscan
weapons. XPilot shots are slow, physical, long-lived projectiles: 21 px/tick plus ship velocity, and
`shotLife` 60 ticks (≈ 4.3 s). They can be dodged, shielded (Space) and pushed by sparks. Rewind here
would mean one of two things:

- **Rewinding the victim for the shot's whole flight:** the victim dies to shots they saw miss or saw
  their shield block, about RTT + 110 ms (up to ~6 ticks) after dodging.
- **Fast-forwarding new shots by the shooter's latency** ("favor the shooter"): shots pop into
  existence ~100 px in front of the shooter on everyone else's screen. Collisions over the skipped path
  also have to be checked against history.

Both are unfair to the target and change the game's core dodge-and-shield skill. They also add trust in
client-reported time, which a lag switch can exploit. Even lasers (pulses) are fast projectiles, not
hitscan.

**Instead:** spawn shots at the exact tick the shooter predicted (§4.3 guarantees it), and render others
in the present (§4.6-B) so aim is honest. There is no gain worth the cost at any RTT.

### 4.10 Interactions that cannot be predicted well

The kernel predicts what a ship does to itself. Forces from others arrive one RTT late, whatever we do.

| Interaction | What the player sees with prediction | Mitigation |
|---|---|---|
| Shot and killed | The ship flies on ≈ RTT, then the explosion appears at the server's position (BURST) | Snap on DEAD. The explosion is drawn at the server position, which is the truth. |
| Shot, debris or spark impact on shields (`Collisions.deltaMv`, inelastic) | Small velocity correction | Smoothing. |
| Others' exhaust sparks pushing you | Tiny continuous corrections in close fights | Smoothing. These are below a pixel per tick each. |
| Ship-ship collision or bounce, shoves into walls | Pass-through, then a correction or a crash | Smoothing or snap. Predicting it needs 4.6-B and is still a guess, so don't. |
| Ball connector (ball and treasure carrying) | Spring force from a ball that others and sparks also push | Phase 3: the kernel models the connector with the ball as a second body from the last BALL record (BALL needs velocity added, +4 B). Otherwise use a stronger blend while connected. Measure it before investing. |
| Tractor or pressor beams from others, deflector | An unmodelled force for seconds | Smoothing. On large repeated corrections, temporarily lower the prediction horizon (blend toward the server). |
| Wormhole, hyperjump, transporter | Random or remote destination | Freeze prediction at the entry and snap when the server shows the result. |
| Autopilot item | The server steers the ship | Either suspend prediction while it is active, or put the autopilot in the kernel (it is deterministic, but large). Suspend first. |
| ECM, stun (`damaged`, `stunned`) | Controls ignored | The flags are in the prediction state. Fine once the snapshot arrives. |
| Refuel, pickups, tank detach | Mass and fuel change | Small errors; the next snapshot corrects them. |
| Ambiguous corner bounces (RNG) | An occasional wrong bounce | Smoothing, or the opt-in `predictableBounces` option (§4.4). |

### 4.11 Tick rate and snapshot rate (DON'T change)

- **A higher internal tick rate** (for example 60 Hz with dt = fps/60) is what the original SPEC did.
  Three C-fidelity reviews found a class of fractional-frame bugs in it, so the SPEC moved to dt = 1.
  Going back gives up C bit-exactness for a latency gain that prediction already delivers more cheaply.
- **Raising `framesPerSecond`** speeds up the game: every speed in 4.5.5 is per frame. That is a map or
  server choice for the players to make, not a latency fix.
- **The snapshot rate** already equals the tick rate (`snapshotDivisor` 1). Nothing changes between
  ticks, so more snapshots carry nothing new. Never raise the divisor on a WAN, because that adds
  interpolation delay.
- **Sub-tick input timestamps:** C has no notion of them. The `taps` mask (§4.3) keeps the only thing
  that matters, a tap shorter than a tick.
- **Upside of 14 Hz:** a predictor replay of 300 ms RTT is about 5 ship steps, and bandwidth is small
  (§4.12).

### 4.12 Bandwidth: delta compression and snapshot acking (DON'T for now)

**Measured budget** (`SnapshotTest.WireSize`):

| Scene | Size per snapshot | At 14 Hz |
|---|---|---|
| Typical | 410 B | ≈ 46 kbit/s per client |
| Fight | 1386 B | ≈ 155 kbit/s per client |

Upstream INPUT v2 with 8 redundant entries is ≈ 18 kbit/s at most. A 4-friend game peaks around
0.6 Mbit/s of server upload, which is fine for home hosting.

Delta compression against acked baselines (Quake 3 style) helps static data most. Ships and shots
move every tick, and the static sections (CANNON, FUEL, TARGET) are already only bytes, so the saving
is modest. It would also add baseline bookkeeping per client and make loss handling worse (a lost
baseline). On long paths the problem is loss, not bandwidth. Revisit it only for 16+ players on
constrained uplinks. `ackTick` in INPUT already exists if it is ever needed.

### 4.13 Loss handling

| Loss | Today | Change | Tier |
|---|---|---|---|
| INPUT | 8× redundancy | Kept per tick (§4.3); starvation feedback grows the lead | DO FIRST |
| SNAPSHOT (others) | One tick of linear extrapolation, at most 2 intervals, then freeze | Adaptive delay (§4.6-A). With 4.6-B, raise the extrapolation cap to ≈ RTT + 2 intervals, because physics extrapolation stays accurate much longer than linear | LATER |
| SNAPSHOT (own ship) | Interpolation hole | Prediction ignores gaps; the next snapshot reconciles | DO FIRST |
| BURST (explosions) | "A lost snapshot loses its bursts" (PROTOCOL.md): at 2-5% loss, 2-5% of explosions never appear | Re-send bursts with `age ≤ 1` in the next snapshot. The client dedupes by seed. +27 B per burst, ~50 lines | LATER (phase 2, cheap) |
| Reliable events | RFC 6298 RTO; a resend costs about 1 RTT | Fine. Align presentation with the timeline (§4.14) | LATER |
| Bursty loss (Wi-Fi, intercontinental) | | Test with Gilbert-Elliott bursts, not uniform loss (§7) | — |

### 4.14 Event timeline alignment (LATER, cheap)

Reliable GAME_EVENT and SCORE messages (kills, crashes, score popups) travel on a different channel from
snapshots. Today they show up when they arrive, while the explosion is drawn when the render time
reaches its tick. The two can be a tick or more apart, and at 300 ms with a resend, one RTT apart. Add a
tick field to GAME_EVENT and SCORE_OBJECT in v2, and present them (message line, popup, future sound)
when the matching timeline reaches that tick:

- the render time, for events about others;
- the predicted time is never right for server verdicts, so use the render time for verdicts about you
  too.

This costs ~100 lines. It is cosmetic, but it makes "I died because of that" legible.

### 4.15 Cheating and trust

The server stays fully authoritative. Clients send only keys.

- **Prediction** grants no authority: a hacked predictor only lies to its own screen.
- **Tick tags** can only *delay* your own input. The server rejects past ticks and caps the future at
  1 s, so no rewind exists to abuse, and a "lag switch" gains nothing.
- **Time dilation** cannot speed up a ship: the server consumes exactly one entry per tick.
- **Dead reckoning** of others uses only data already sent. The view-cull margin (§4.1) reveals at most
  ~180 px more. XPilot has no line-of-sight occlusion, so there is little to leak. The whole-world radar
  already leaks more.
- **What would weaken trust:** rewind (§4.9) and client-reported hit claims. Neither is recommended.
- **Hidden map information (a trade-off taken in phase 1b).** The own-ship kernel needs the real gravity
  field, wormholes and friction blocks, and the kernel options, to predict exactly. So every client gets the
  kernel config blob (PROTOCOL.md KERNEL_CONFIG): the blocks `Packet_map` hides with gravityVisible,
  wormholeVisible, itemConcentratorVisible, asteroidConcentratorVisible and blockFrictionVisible off, and
  the kernel options (`KernelConfig.OPTIONS`), visible or not, with their live `/set` updates. The stock
  client still draws the masked map and shows only the visible options; a modified client could show the
  hidden ones. What leaks is map layout and physics, not other players' state, and an attentive player
  learns most of it by flying anyway (gravity pulls the ship). Withholding it would make every gravity block
  and hidden wormhole a visible correction under prediction. A server that wants the old secrecy has no
  switch for it yet; adding one would mean sending a masked kernel world and accepting those corrections.

For a friends server this is low priority, and the plan does not make it worse.

### 4.16 Relay or region hosting (DO NOW, operations only)

- **Each player's own-ship feel:** with prediction it no longer depends on RTT, apart from correction
  sizes.
- **How stale others look:** their uplink + your downlink + the interpolation delay. That is roughly the
  average of the two players' RTTs plus 1.3 T.
- **Choose the host to minimize the *largest* player RTT** (minimax), not the average. Measure each
  friend → candidate host with `ping` or `mtr` over a few minutes, and include the jitter. A cheap
  cloud VPS in a well-peered region usually beats a home connection in the "middle": cloud backbones
  often cut 10-30% off public-internet routes and have far less jitter. This is a guess; measure it.
- **VPN overlays** (the SPEC targets WireGuard and Tailscale): make sure each path is direct. Tailscale
  falls back to DERP relays when NAT traversal fails (`tailscale ping` shows "via DERP"), which can add
  50-150 ms. Our ≤ 1200-byte payload already fits inside the tunnel MTU.
- **Server tick stability:** `JavaServerEndToEndTest` asserts p99 tick jitter < 8 ms. Run with
  `-XX:+UseZGC` as the SPEC recommends.
- **Cost:** a page in the user guide (`docs/guide`).
- **Gain:** often 20-80 ms of RTT on the worst leg, available today with no code.

### 4.17 Whole-world lockstep or rollback (GGPO style) (DON'T)

Lockstep makes every player wait for the slowest player's input, so latency is the largest RTT for
everyone. Rollback would mean re-simulating the whole world, including thousands of particles, robots
and the shared RNG, for every late input, and making the entire server sim client-side and
deterministic. That is far more than the ship kernel, for a game with up to 16+ players. Server
authority with own-entity prediction is the standard and right answer here.

## 5. Summary of techniques

| # | Technique | Gain at 100 / 200 / 300 ms RTT | Cost | Modules | Tier |
|---|---|---|---|---|---|
| 4.4 | Shared ship kernel in `common` | (enables 4.1 near walls) | 1-1.5 wk | common, server | DO FIRST |
| 4.3 | Tick-tagged input, input buffer, lead control | (enables exact 4.1) | 1 wk | common, server, client | DO FIRST |
| 4.1 | Own-ship prediction, replay, speculative tick | own ship ≈ 143/243/343 (measured) → ≈ 10-45 ms | 1-1.5 wk | client | DO FIRST |
| 4.2 | Correction smoothing | (makes 4.1 look right) | 2 d | client | DO FIRST |
| 4.1 | View-cull margin for the lead | (no pop-in at the leading edge) | < 1 d | server | DO FIRST |
| 4.7 | Predicted own shots and `OWN_SHOTS` | shot appears ≈ 238/338/438 (measured) → ≈ 10-45 ms | 1 wk | all | LATER (phase 2) |
| 4.8 | Predicted sparks, sound and shield | thrust cue as 4.1 | 2 d | client | LATER (phase 2) |
| 4.13 | Burst redundancy | no lost explosions | 1 d | server, client | LATER (phase 2) |
| 4.6-A | Adaptive interpolation delay | 0-15 ms; fewer glitches | 2 d | client | LATER (phase 3) |
| 4.6-B | Dead-reckon others to the predicted present (option) | aim gap ≈ 210/310/410 ms → 0 for coasting ships | 1-1.5 wk | client (+1 B SHIP) | LATER (phase 3, A/B) |
| 4.14 | Tick-aligned events | legibility | 1 d | all | LATER (phase 3) |
| 6 | C servers: heading prediction | aim and turn ≈ RTT+45 → ≈ 10-45 ms | 3-5 d | client/xp | LATER (phase 4) |
| 6 | C servers: position prediction | | 1-2 wk, unreliable | client/xp | DON'T |
| 4.9 | Lag compensation and rewind | | large; unfair | server | DON'T |
| 4.11 | Higher tick rate or substeps | | breaks C parity | all | DON'T |
| 4.12 | Delta compression | bandwidth only | 1-2 wk | common | DON'T (now) |
| 4.17 | Lockstep or rollback | | huge | all | DON'T |
| 4.16 | Host placement, direct VPN paths | 20-80 ms off the worst leg (a guess) | docs | — | DO NOW |

## 6. The client.xp layer (C servers)

The C server never changes, so everything has to be inferred from what it sends:

- **Own ship** (`Send_self`): integer position (rounded), *truncated* integer velocity, `dir` 0-127,
  power, turnspeed and turnresistance rounded. No `turnVel`, no sub-pixel state.
- **Physics options:** the C Setup (`XpSetup`) carries only map, mode, lives, size and fps. Gravity
  strength, `shipMass`, wall-bounce brake factors and friction are **not sent**, and the client
  cannot know them.
- **Input:** applied on arrival (`Receive_keyboard`). Tick tags are impossible. The frame header's
  `key_ack` does tell us after the fact which frame a key change landed on. The first frame whose
  `key_ack ≥ change` is the frame it was applied in, unless the frame before it was lost. So replay can
  be exact in time, but the *forecast* of the landing frame carries the jitter.
- **Shots:** positions only, with no ids and no velocities. Own-shot prediction cannot be reconciled
  except by proximity.
- **Frame rate:** C may lower the frame rate it sends to a client, a per-player FPS.

| Technique | Java server | C server |
|---|---|---|
| Own heading prediction | exact | **Good.** Heading depends only on keys, turnspeed and turnresistance (all known), and on `turnVel`. That decays by turnresistance (default 0.12) within 1-2 frames once keys are released, so key history determines it. Errors are ±1 RES step, corrected by `dir` every frame. **LATER, phase 4.** This gives most of the aiming benefit. |
| Own position prediction | exact | **Poor.** Unknown options and truncated velocity give errors of about 1 px/tick per tick of lead, plus wrong wall bounces whenever bounce options differ from the defaults. **DON'T**, or make it opt-in and experimental. |
| Own shots and sparks | exact | Sparks are cosmetic and could follow predicted heading and thrust. Shots: **DON'T**. |
| Dead reckoning of others | physics | Velocities are derived from frame differences (`XpTranslator` already does this), and gravity is unknown. Linear extrapolation only. Adaptive delay helps. |
| Input timing | tick tags | Keep C's resend-until-acked scheme (`XpKeyboard`). Respect "no macros": at most one keyboard update per datagram. |

On C servers the honest goal is heading prediction plus adaptive delay. Full prediction is a
JXPilot-server feature.

## 7. Test and measurement approach

**1. Lossy-link emulator (`LinkEmulator`, test fixture).**

- **What it wraps:** a decorator over both the client `Transport` and the server `Datagrams` interfaces.
  Both are already injectable. It runs on an **injected virtual clock**: `ServerNet` and
  `ClientConnection` already take nanos as a parameter.
- **Per-direction settings:** base delay; jitter as normal or Pareto (spikes); Gilbert-Elliott bursty
  loss (p(good→bad), p(bad→good), loss in each state); reordering; duplication; a bandwidth cap. It is
  seeded, so runs are reproducible.
- **Where it lives:** a `common` test-jar, used by the `client` and `server` tests. Allocation-free
  in steady state, as all test fixtures should be.

**2. A real-network proxy for manual play (`tools/lagproxy`).**

- **What it is:** a small Java UDP proxy that runs the same emulator in real time between real clients
  and a real server. It needs no root, works on macOS, Linux and Windows, and friends can use it.
- **The rate-limit catch:** all proxied clients share one source IP, so set `maxClientsPerIP` 0 for
  tests.
- **Alternatives:** macOS `dnctl` with `pfctl` (dummynet), or Linux
  `tc qdisc add dev lo root netem delay 150ms 20ms distribution normal loss gemodel 1% 30%`.
  `SmoothMotionTest` already notes Docker-based jitter measurements.

**3. Metrics.** All are computed headless from `ClientWorld`/predictor outputs in virtual time,
printed as a table, and asserted against thresholds in tests:

| Metric | Definition | Target after phase 1 |
|---|---|---|
| **Input-to-photon (I2P)** | A scripted key change at t0. The first rendered frame (120 Hz) where the drawn own heading (turn) or velocity (thrust) differs from the no-input baseline | p95 ≤ 50 ms at every RTT |
| Shot I2P | The first frame where a new own shot is drawn | p95 ≤ 50 ms (phase 2) |
| Prediction exactness | % of reconciliations that are bit-equal on courses with no contact with others | 100% (`predictableBounces` on); ≥ 99% off |
| Correction size | p50/p95/max of the visual offset, per scenario | p95 < 4 px without contact |
| Snaps per minute | Offsets above the snap threshold, excluding death and teleport | 0 without contact |
| Input starvation / late | Server counters | 0 after convergence at jitter ≤ 30 ms |
| Others' aim error | The distance between a remote ship's drawn position and the server's position at the own predicted tick | Report only; the A/B input for 4.6-B |
| Smoothness | Frame-to-frame displacement deviation (the `SmoothMotionTest` method) | No regression |

**4. Scenario matrix:** RTT {0, 100, 200, 300} × jitter {0, σ 15 ms, Pareto spikes} × loss {0, 1%
uniform, 5% bursty}. Courses on `maps/` with walls and gravity:

- free flight;
- wall-hugging and bounces;
- a gravity well;
- turning fights against robots, which gives "unpredictable" pushes;
- ball carrying.

Each phase records a before/after table in its PR. The **A/B script** runs the same seed and inputs
with prediction off and on, and diffs the metric tables.

**5. Parity gate** (for any phase that touches `server/sim`): the xpilot-ref traces and `Digest` must be
identical before and after on all 19 maps, plus the differential kernel test (§4.4).

**6. Live diagnostics:** a HUD line, toggled by a key, showing RTT, the lead in ticks, `inputSlack`, the
interpolation delay, corrections per second, and the last correction in px. A key toggles prediction
on and off for blind A/B playtests with friends.

## 8. Phased roadmap

Effort is rough agent-days of implementation, including tests and the CLAUDE.md review loop. These are
estimates.

| Phase | Content | Effort | Tests and exit criteria |
|---|---|---|---|
| **0. Measure** | `LinkEmulator`, headless I2P harness on `ClientSim` plus the real server, metric tables, HUD net line, `tools/lagproxy`, and the hosting page in the guide | 4-6 d | Baseline tables at 0/100/200/300 ms; this is the yardstick for everything after. Done: own-ship I2P measured RTT + ≈ 44 ms (not §2's first estimate of RTT + 135; see §2.1 and latency-measurements.md) |
| **1a. Kernel** | Extract the ship kernel to `common/sim` as a pure move (`ShipBody`, `KernelEvents`, injected tie-break source). The server delegates to it | 6-8 d | xpilot-ref and `Digest` bit-identical on all maps, the differential test, the no-`Math`-transcendentals test, allocation test, PIT ≥ 85%, and a C-fidelity re-review of the moved code |
| **1b. Protocol v2 and input buffer** | Per-tick INPUT with taps, server `InputBuffer`, `inputTick`/`inputSlack` header, the `PREDICT` section, the view-cull margin, and the PROTOCOL/SPEC updates. Optionally the `predictableBounces` option | 5-7 d | Buffer unit tests (reorder, duplicate, late, missing, far future), the lead controller converging under RTT steps, starvation 0 at jitter ≤ 30 ms |
| **1c. Predictor** | `PredictedClock`, `OwnShipPredictor` (ring, reconcile, replay), the speculative partial tick, `CorrectionSmoother`, camera follow, and no prediction while dead/paused/warping | 6-9 d | I2P p95 ≤ 50 ms at 300 ms RTT; 100% bit-equal reconciliation on contact-free courses; no smoothness regression; allocation test. **The friends' playtest happens here.** |
| **2. Local feel** | Predicted own shots and `OWN_SHOTS`, own exhaust from predicted state, burst redundancy, and predicted sound hooks (if sound lands) | 6-9 d | Shot I2P p95 ≤ 50 ms; < 1% provisional shots unconfirmed in normal play; no lost explosions at 5% loss |
| **3. Others** | Adaptive interpolation delay; dead reckoning to the predicted present as an option (with an optional mass byte in SHIP and walls for others via the kernel); tick-aligned events; the ball connector in the kernel if phase 1 data shows ball play correcting badly | 7-12 d | The aim-error metric (4.6-B vs. interpolation) at 100/200/300 ms; blind A/B with friends decides the default |
| **4. C servers** (optional) | Heading prediction in the xp layer, reconciled by `dir` and `key_ack`; adaptive delay | 3-5 d | Heading I2P p95 ≤ 50 ms against the xpilot-ref C server through the emulator; heading corrections ≤ 1 RES step |

The cheapest big win is **phase 0 + phase 1** (≈ 3-4 weeks). It turns own-ship response from
≈ RTT + 44 ms (measured) into ≈ 10-45 ms, which is snappier than C XPilot on a LAN, and leaves the other
phases optional. The gain is smaller than first estimated (F1 already removed 91 ms) but still the whole
RTT: about 100-300 ms on the target links.

## 9. Decisions needed from the user or lead

1. **Protocol v2** (`Protocol.VERSION` 2: per-tick INPUT, new header fields, `PREDICT` and `OWN_SHOTS`
   sections). Clients and servers must update together. Recommended: yes.
2. **`predictableBounces`** as an opt-in JXPilot deviation (per-player RNG for the two wall tie-breaks).
   Recommended: build it, default off, and turn it on for friends' games if corner corrections bother
   anyone.
3. **The default view of others** (interpolated past vs. dead-reckoned present). Recommended: decide by
   blind A/B after phase 3. Do not decide on paper.
4. **XP-layer scope.** Recommended: heading prediction only, and only if playing on C servers matters
   to the user.
