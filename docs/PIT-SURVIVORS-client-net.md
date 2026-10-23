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
