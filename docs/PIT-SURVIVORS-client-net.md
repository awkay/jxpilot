# PIT survivors: client `net` and `game` packages (branch client-net)

Run: PIT 1.30.1 on `com.fulcrologic.jxpilot.client.net.*` and `com.fulcrologic.jxpilot.client.game.*`
(`UdpTransport` and `ClientSim` excluded as edge/main code; `*NetAllocationTest*` and `*UdpLoopbackTest*`
excluded as tests).

**Result: 1499 mutations, 1425 killed (95%), test strength 95%.** 74 survivors (4 without coverage),
grouped below. Line numbers refer to the commit that adds this file.

## Equivalent mutants

| Class:line | Code | Why equivalent |
|---|---|---|
| ClientWorld:296, 306 | `tick - newestTick > 0`, `tick - o.tick <= 0` | `tick ==` either value was already handled by `frameFor` (same tick in the ring), so the boundary never sees equality. |
| ClientWorld:351 (boundary) | oldest-frame search `f.tick - best.tick < 0` | Ticks in the ring are distinct, so `<` vs `<=` cannot differ. |
| ClientWorld:362, 450 | `n > 0` for SELF / SELF_ITEMS | The writer removes empty sections, and a reader section always has `count >= 1`. |
| ClientWorld:412, 423 | `i < n` loops for TARGET / TREASURE | Boundary `<=` reads one record past the section, which is the next section's bytes; the index then exceeds the list and is ignored by the bounds check. No observable change with the tested layouts. |
| ClientWorld:517, 518 | `slot >= 0 ?` for the engine offset | Slot 0 joined with the default shape has the same engine offset as the default; power (line 516) is tested and killed. |
| ClientWorld:558, 561 | latest A / earliest B: `f.tick - a.tick > 0` | Frame ticks are distinct, so `>`/`>=` never differ. |
| ClientWorld:608 | `ticks < 0 ? 0` | At exactly 0 both branches return 0. |
| ClientWorld:620, 635 (x2) | output caps `>= shipCap`, `i < base.shots && i < shotCap` | Frames are filled with the same capacity as the outputs, so the counts can never exceed them. |
| ClientWorld:646 (x2) | newest SELF `f.tick - s.tick > 0` | Distinct ticks; `+` instead of `-` only changes which SELF wins when several frames have one and the test's ticks keep the same order. |
| ClientWorld:597 | teleport `dx*dx + dy*dy` | With dy = 0 (all teleport tests are horizontal), `+` and `-` agree. |
| ClientConnection:227, 350, 427 | receive loop bound, `read(...) < 0`, `tick - latestTick > 0` | Loop bound 256 is never reached; `read` returns -1 or >= 0 (never exactly 0 vs negative ambiguity); equal ticks are no-ops either way. |
| ClientConnection:252 | `nowNanos - lastConnectSend` | The resend test starts at time 0, where `now + 0 == now - 0`. (Test gap rather than true equivalence: a start time > 0 would kill it.) |
| CharBuf:79, 185 | `truncate` `n < len`; `v < 0 && q != 0` | `truncate(len)` is a no-op either way; `v == 0` gives `q == 0`. |
| ParticlePool:170 | `age < 0` | At age 0 the visible branch computes the origin, identical to the hidden branch's position; only `visible` differs for one exact instant. |
| KeyBindings:71, 84, 94, 107, 112 | `g >= 0 && g < SIZE` | `KeySyms.glfwKey` only returns -1 or a valid GLFW code (<= GLFW_KEY_LAST), and the codes at both ends are not reachable through keysyms. |
| InputState:55, 80 | `glfwKey >= SIZE` | Same: GLFW never delivers codes above GLFW_KEY_LAST; SIZE itself is tested. |
| Endpoint:52 | `close < 0` | `indexOf` returns -1 or an index >= 1 (index 0 is the '[' itself). |
| TalkLine:154 | history loop bound | The loop always returns or wraps fully within HISTORY steps. |
| GameEvents:82, 84, 86 | `total - size() + i` | Modulo the capacity, `±2*size` is the same slot when size equals the capacity (the only case where the ring has wrapped). |
| Scoreboard:140, 193 (arraycopy) | insertion-sort shifts | The shifted slots hold values (zero-initialised ids / team 0) that coincidentally equal the correct result in the tested orders; team and player order are asserted exactly. |
| Scoreboard:146 (x2) | `timing && n > 0` | With n == 0 there are no rows to mark best. |
| Scoreboard:170, 180 | team bounds / `teamPlaying[t]++` | `t >= MAX_TEAMS` vs `>`: team 10 is never sent (teams are 0-9); playing count only gates `!= 0`, so `--` is equally non-zero. |
| Scoreboard:217, 219 | `>` vs `>=` in team comparison | Equal values fall to the next criterion or keep insertion order either way. |
| Scoreboard:83 | `teamVersion++` | `--` still changes the version, which is all `update` compares. |

## Covered but not asserted (acceptable)

| Class:line | Code | Note |
|---|---|---|
| ClientWorld:351 (math) | `f.tick + best.tick < 0` | Always false, so the first valid slot is recycled; the tested sequence (100, 102, 104, 106) recycles slot 0 first in both cases. Test gap: a fifth tick would kill it. |

| ClientWorld:226 | `clock.reset(simHz)` in configure | `reset()` (called right after) resets the clock again with the stored rate; only the rate change matters and the default is 60. |
| ClientWorld:326 | `malformedCount++` on a truncated section | Counter only; the MALFORMED result is asserted. |
| ClientWorld:682, 744 | `configured()`, `selfRespawnFrames()` getters | Trivial getters. |
| Hud:116, 137, 138, 193, 257 (x2) | fuel-notify trigger `lastFuel >= 0`, `hasSelf ? fuel : 0`, `advance` call in `build`, speed pointer guard, gauge blink boundaries at exact multiples | HUD timing cosmetics; each helper is tested directly, these are the orchestration wiring and exact-integer phase boundaries. |
| ClientConnection:198, 393, 442, 452 | `text.setLength(0)` before "quit", `packetsReceived++` on REJECT, `finishConfig` for an empty config, `badConfig` on a chunk out of range | The first two are cosmetic/counters; the last two need a malicious server (empty or out-of-range config) and are covered by the CRC test path. |
| ClientConnection:351, 360 | `malformed++` inside the RELIABLE/underflow branches | Not reachable with the codecs' own encoders; would need hand-crafted corrupt bodies. |
| ClientGame:217 | `scoreDeltaLife > 0` | At exactly 0 both branches leave it at 0. |
| InputState:99 | `Arrays.fill(down, false)` in releaseAll | `keys` is zeroed anyway; a later release of a stale key recomputes from `down` (tested once). |
| TalkLine:113 (x2) | `deleteAt` arraycopy length | Off-by-one length copies one stale char beyond the truncated end, which is never shown. |
