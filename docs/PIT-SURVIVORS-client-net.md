# PIT survivors: client `net` and `game` packages (branch client-net)

Run: PIT 1.30.1 on `com.fulcrologic.jxpilot.client.net.*` and `com.fulcrologic.jxpilot.client.game.*`
(`UdpTransport` and `ClientSim` excluded as edge/main code; `*NetAllocationTest*` and `*UdpLoopbackTest*`
excluded as tests):

```
MAVEN_OPTS= mvn -o -pl common,client test-compile org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses='com.fulcrologic.jxpilot.client.net.*,com.fulcrologic.jxpilot.client.game.*' \
  -DtargetTests='com.fulcrologic.jxpilot.client.*' -DfailWhenNoMutations=false
```

**Result: 1813 mutations, 1732 killed (95%), test strength 95%.** 81 survivors (1 without coverage),
grouped below. Line numbers refer to the commit that adds this file. (The full run reported 1729 killed;
the last three, TalkLine:200, ParticlePool:219 and ClientWorld:348, were killed afterwards by new
assertions and verified one by one.)

The review round killed the four mutants the previous list had wrongly or weakly justified:
ClientConnection resend from t > 0 (`ClientConnectionTest.connectResendLaterStart`), the fifth-tick ring
recycle (`ClientWorldReviewTest.Gaps.fifthTickRecycle`), the teleport distance using both axes
(`diagonalTeleport`) and the newest SELF across the ring wrap (`selfAcrossWrap`).

## Known deviation (not a mutant)

Race/timing mode is out of scope for now: the scoreboard keeps the score order in timing mode, marks the
first row best and leaves the race column blank (`Scoreboard.appendRace` is the hook). C sorts by race
progress, which the protocol does not carry yet. Documented in the `Scoreboard` class comment.

## Equivalent mutants

| Class:line | Code | Why equivalent |
|---|---|---|
| ClientWorld:316, 328 | `tick - newestTick > 0`, `tick - o.tick <= 0` | Equal ticks were already handled by `frameFor` (same tick in the ring). |
| ClientWorld:373, 595, 598, 701 | `f.tick - x.tick` comparisons in the ring | Ticks in the ring are distinct, so `<`/`<=` and `>`/`>=` cannot differ. |
| ClientWorld:384, 472 | `n > 0` for SELF / SELF_ITEMS | The writer drops empty sections, so a reader section always has count >= 1. |
| ClientWorld:434, 445 | `i < n` loops for TARGET / TREASURE | `<=` reads one record past the section (the next section's bytes); the index is then out of range and ignored. |
| ClientWorld:547, 548 | `slot >= 0 ?` engine offset | Slot 0 joined with the default shape has the default engine offset. |
| ClientWorld:616, 675, 690 (x2) | `j < a.ships`, output caps | Frames and outputs share the capacity; `<=` touches a slot that is never read. |
| ClientWorld:663 | `ticks < 0 ? 0` | At exactly 0 both branches return 0. |
| ClientConnection:275, 461, 539, 544 | receive loop bound, `read(...) < 0`, tick and sequence comparisons | Loop bound 256 is never reached; `read` returns -1 or >= 0; equal ticks/sequences are no-ops either way. |
| ClientConnection:390 | `nextInputDue - nowNanos <= 0` | At exact equality both schedules send at the next frame. |
| ClientGame:235 | target vs treasure list size | The fixture maps have one target and one treasure, so both sizes are 1; the bounds check itself is killed (`ClientGameTest.otherReasons`). |
| ClientGame:261 | `i < world.shipCount()` | `<=` reads a stale output slot whose id never matches a ship that is not listed. |
| CharBuf:79, 186 | `truncate` `n < len`; `v < 0 && q != 0` | `truncate(len)` is a no-op either way; `v == 0` gives `q == 0`. |
| ParticlePool:168 | `n < requested` | At equality the count is unchanged either way. |
| ScoreObjects:105 | `decimals > 0` | showScoreDecimals is never negative; 0 is tested. |
| ScoreObjects:119 | `count[i] > 0` | A count of exactly 0 is inactive; ageing it is not observable (it is reset by the next add). |
| Hud:213, 215 (x2), 216 | `winScale` `v < 1`, `v > 32767`, `n < 0` | Equal values give the same result on both branches; `n == 0` returned earlier. |
| ShipIndex:53, 54, 64, 78, 84, 85 (x4) | generation step, wrap check, probe direction, hash function | Any generation change clears the table; probing backwards or with another (even constant) hash still finds every key (tests cover collisions and a full table); only speed changes. |
| InputState:79 (x2), 120 (x2), KeyBindings:71, 84, 94, 107, 112 | `g >= 0 && g < SIZE` | `KeySyms.glfwKey` returns -1 or a valid GLFW code; GLFW never delivers codes above GLFW_KEY_LAST. |
| InputState:124 | `(m & FIRE_KEYS) != 0` to `\|` | The branch also needs SHIELD clear with the shields up; with autoShield that only happens while a fire key is held, so releasing a non-fire key then still finds a fire key and does not raise the shield. |
| Endpoint:52 | `close < 0` | `indexOf` returns -1 or an index >= 1. |
| TalkLine:240 | history loop bound | The loop always returns or wraps fully within HISTORY steps. |
| GameEvents:82 (x2), 84, 86 | `total - size() + i` | Modulo the capacity, `±2*size` is the same slot when size equals the capacity (the only case where the ring has wrapped). |
| Scoreboard:145, 198 (x2) | insertion-sort shifts | The shifted slots hold values that coincidentally equal the correct result in the tested orders; team and player order are asserted exactly. |
| Scoreboard:151 (x2) | `timing && n > 0` | With n == 0 there are no rows to mark best. |
| Scoreboard:175, 185 | team bounds / `teamPlaying[t]++` | Team 10 is never sent; the playing count only gates `!= 0`. |
| Scoreboard:222, 224 | `>` vs `>=` in team comparison | Equal values fall to the next criterion or keep insertion order either way. |
| Scoreboard:88 | `teamVersion++` | `--` still changes the version, which is all `update` compares. |
| ThrustSparkEmitter.resDir (verified by hand, not a PIT mutant) | `floor(h * RES/2pi + 0.5)` vs `dirFromU16(angleToU16(h))` | The two agree for every heading the server sends; the test asserts both forms. |

## Covered but not asserted (acceptable)

| Class:line | Code | Note |
|---|---|---|
| ShipIndex:55 | `Arrays.fill` when the generation wraps | Needs 2^32 clears (no coverage). |
| ClientWorld:748, 810 | `configured()`, `selfRespawnFrames()` | Trivial getters. |
| ClientConnection:243, 496 | `text.setLength(0)` before "quit", `packetsReceived++` on REJECT | Cosmetic / counter. |
| Hud:130, 307 (x2) | `lastFuel >= 0`; gauge blink boundaries at exact multiples | HUD timing cosmetics at exact integer phases. |
| TalkLine:147 (x2) | `deleteAt` arraycopy length | An off-by-one length copies one stale char beyond the truncated end, which is never shown. |

## client-fixes run (R9)

`MAVEN_OPTS= mvn -o -pl client -am test-compile org.pitest:pitest-maven:mutationCoverage -Dthreads=8` (whole
reactor; module configs): common 2434/2526 (96%), server 11233/12275 (92%), client 7422/7897 (94%, test strength
94%, line coverage 99%). Killed afterwards with new tests, each break-checked by hand:

- `Tracker` grid search (was 72%): `TrackerTest.matchesBruteForce` compares the grid search with a brute-force
  search over all pairs on 3000 random worlds. It found two real misses, now fixed: a world that is not a whole
  number of cells had a narrow last cell, so a candidate across the wrap seam could be outside the searched
  cells; and without wrap a prediction outside the world wrapped to the far side's cell (now clamped to the edge
  cell). Remaining `search` boundary mutants only widen or shift the span by a cell that holds nothing new.
- `ObjectLists` lines and keys: `ObjectListsTest.Edges` (exact reach, sum over both ends in both orders, ties,
  x reach, the shared `taken` reset between connectors and transporters, missile class keys vs ids, y
  extrapolation).
- `KeyBindings`: `InputTest.Bindings.setAndFunctionsOf`.
- `ClientWorld.predictSelf` / `newestSelfTick`: `selfPredictedOutOfOrder`, `selfBlended2d`.
- `Records.Self` / `Records.Timers` appended-field guards: `SnapshotTest.ShortRecords.cutField`; `ShipShape`
  cache LRU: `MathTest.ShapeCache.lru`; `SnapshotBuilder.writeRefuel` station index bound: `Refuel.beams`.

Equivalent survivors left: `SnapshotBuilder.writeSelf` `count > 0` → `>= 0` (0 either way); `KeyBindings`
`glfwKey >= 0` → `> 0` (no GLFW key 0) and the `<init>`/`rebuild` loop bounds; `ObjectLists.key` `f >= 0`
(id fields are 4 and 5).

## Latency phase 1b fix round: PredictedClock, KernelSync, ClientConnection

PIT on the three classes with the `client.net` tests (`*AllocationTest*` excluded): PredictedClock **127/136
(93%)**, KernelSync **141/160 (88%)**, ClientConnection **311/341 (91%)**. Killed afterwards (verified by
hand): ClientConnection's `clock.sent(...)` per final entry (`KernelFeed.finalsRecordAdjustment`: a report
covering entries sent before a jump reads the jump), the later-part path into KernelSync
(`KernelFeed.laterParts`), and `KernelSync.stateKnown` → false. Survivors in 1b code:

| file:line | mutator | why acceptable |
|---|---|---|
| PredictedClock.java:306 | `e > DEADBAND` → `>=` (settle) | at exactly the band edge the settle moves by half a band or the dilation is 0: within the deadband either way. |
| PredictedClock.java:341, 343 (6) | the guard-rail bounds | equality cases (a lead of exactly 0 or simHz - 1) re-initialize to the same lead; the arithmetic mutants only matter at those edges. Old code, unchanged since 1b. |
| PredictedClock.java:170, 354 | `max(0, initial - tick)`, `dt > 0` | 0 at equality either way; old code. |
| ClientConnection.java:503 | `slack >= REPEAT_SKIP_SLACK` → `>` | equality at exactly 1.25 ticks of estimate only shifts which of two equal choices is taken. |
| ClientConnection.java:481, 764, 779, 792, 796 | boundaries and negations of the tick comparisons | ticks are equal only for the same snapshot or entry, which the next branch handles the same way; the newest-tick and ack paths are covered end to end by `PredictRoundTripTest` and `LeadControlTest` (latency suite, outside this PIT run). |
| ClientConnection.java:305, 848, 872 | `clock.reset`, `kernel.onConfig` removed | the next WELCOME / session resets them again; covered end to end by the latency suite. |
| KernelSync.java:130, 138, 152 | `clearState`/`fill`/`setWorld` removed | reset is followed by a new KERNEL_CONFIG (rebuild) in every real session; the reader's world is set again on config. |
| KernelSync.java:276, 283, 291, 313, 331, 349-355 | growth and bounds boundaries | capacity growth only, or indices that equal a bound only beyond the map (ignored either way). |
| KernelSync.java:470 | `roundValue > 0` → `>= 0` | PREDICT_ROUND clamps to 1..65535: 0 never arrives. |
| KernelSync.java:174, 241, 255, 296, 318 | no coverage in this run | the KERNEL_CHUNK completion and world-wormhole paths are exercised by `KernelSyncNetTest` (latency suite). |
