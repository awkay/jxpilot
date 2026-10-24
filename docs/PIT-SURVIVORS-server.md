# PIT surviving mutants — `server`

Run: `MAVEN_OPTS= mvn -o -pl server test-compile org.pitest:pitest-maven:mutationCoverage` (run `test-compile` in the same
command so PIT does not use stale test classes). `AllocationTest` and `TickBenchmark` are excluded from PIT.

## `server.sim` after the ship kernel extraction (latency phase 1a)

Run with `targetClasses` narrowed to `com.fulcrologic.jxpilot.server.sim.*` (all server tests): **6337 of 6790 mutants
detected (93.3%)**: 6302 killed, 35 timed out; 422 survived, 31 without coverage. Per class: Alliances 142/158,
Asteroids 309/348, Balls 278/309, CannonWeapons 321/345, Cannons 98/100, Collisions 445/469, Crashes 97/97, Debris
164/182, Devices 473/499, Digest 75/75, Ecm 174/181, Items 440/473, Keys 193/205, Lasers 309/334, Locks 198/204, Mines
210/220, Missiles 423/443, Mover 83/92, ObjPhysics 113/115, Race 188/213, RandomPilot 7/8, Rounds 343/366, Scoring
47/51, **ShipEffects 31/31**, Ships 517/549, ShotNames 16/27, Shots 98/104, Simulation 131/139, Sounds 21/23, Tanks
138/153, Targets 160/176, Texts 0/1, Wreckage 95/100. (The cannon branch of `Shots.fire` got a test after this run:
`ShipEffectsTest.CannonShots`.)

The kernel's code (Move_player, Turn_player, Segment, turning, thrust, mass, key edges, bullets) is in `common.sim`;
its survivors are in `PIT-SURVIVORS.md`. Survivors in the server code the extraction touched:

| file:line | mutator | mutated expression | why |
|---|---|---|---|
| Keys.java:71 (2) | BooleanReturns | `pressed` delegate's return | the delegate (kept for the server's key tests) is only called by tests that do not read its result; the kernel's `ShipKeys.pressed` result is tested in `ShipKeysTest`. |
| Shots.java:100 | Boundary | cannon `count() >= capacity` → `>` | the pool never holds more than its capacity, so `>` is reached only at equality, where `alloc` would fail. |
| Simulation.java:214 | Boundary | autofire `k < numPlayers` → `<=` | **not killed**: also runs autofire for `order[numPlayers]`, a slot outside the game (or slot 0 before anyone left, already handled this frame, whose repeat rate blocks a second shot). |
| Mover.java:68, 92-94, 160, 166 | as above | `moveObject` | the `Mover.java` object rows of the table below, at their new line numbers. |
| Mover.java:189, 190 (2) | Negate, PrimitiveReturns | `ballTeam` | a ball's target team only matters with `targetTeamCollision` off and a ball owner on the target's team; no ball test sets that up (unchanged code). |

## Earlier rounds

Result (S1 fix round): **3613 of 3824 mutants detected (94.5%)**: 3581 killed, 32 timed out; 194 survived, 17 without
coverage.

| class | detected / total |
|---|---|
| Cannons | 87 / 88 |
| Click | 11 / 12 |
| Collisions | 346 / 359 |
| Crashes | 74 / 75 |
| Debris | 100 / 112 |
| Digest | 72 / 75 |
| Events | 20 / 20 |
| Grid | 78 / 79 |
| Keys | 104 / 106 |
| Mover | 385 / 423 |
| Objects | 21 / 21 |
| Params | 70 / 72 |
| Player | 18 / 20 |
| RandomPilot | 7 / 8 |
| Rounds | 289 / 301 |
| Scoring | 31 / 35 |
| Segment | 707 / 770 |
| ServerSim | 14 / 22 |
| ServerSim$Args | 12 / 12 |
| ShipGeom | 14 / 14 |
| Ships | 472 / 495 |
| ShotHash | 92 / 92 |
| Shots | 82 / 83 |
| Simulation | 109 / 111 |
| SnapshotBuilder | 184 / 195 |
| Trig | 6 / 6 |
| WallDist | 81 / 89 |
| World | 127 / 129 |

`ConditionalsBoundary` swaps `<`↔`<=` / `>`↔`>=`. `(n)` is the number of mutants on that line. Survivors are equivalent
(no input reachable through the API behaves differently) unless marked **not killed** (a real difference that no test
catches, with the reason it is left) or **unreachable** (no coverage: code that cannot be reached in S1). A "stale
entry" is the slot just past the live part of a preallocated array (`order[numPlayers]` is a preallocated Player that
is not in the game; `active[count]` is a freed object handle, which `alloc` re-initialises).

## Wall movement: Segment, Mover, WallDist, Grid, Click (SegmentOracleTest compares Segment with literal C transcriptions)

**Moved (latency phase 1a):** Segment, WallDist, Click and the player half of Mover (Move_player, Turn_player, wall
bounce, now `ShipMover`) are in `common.sim` with their tests; their current survivors are in `PIT-SURVIVORS.md`
(`common.sim`). The Segment, WallDist, Click and player Mover rows below are kept for history (pre-move line numbers);
the `Mover.java` rows for `moveObject` (75, 99-101, 163, 169) still apply, at shifted line numbers.

| file:line (n) | mutator | mutated expression | why |
|---|---|---|---|
| Click.java:53 | Boundary | `d < 0` → `<=` | at d = 0 both branches give 0. |
| Grid.java:134 | Boundary | outer `count < max` → `<=` | the inner loop's `count < max` still stops adding. |
| Mover.java:75, 432 (2) | Boundary | block clamp `b < 0` → `<= 0` | b = 0 gives 0 either way. |
| Mover.java:99 | Boundary | `dist > 2` → `>=` | at walldist 2 the free-flight limit is 0, so only a zero-speed object takes the path; same result. |
| Mover.java:100, 101 (3) | Math/Boundary | free-flight test arithmetic | each only makes the test stricter (falls back to tracing, same result) or changes it for |v| ≤ 1 px, far below the 17 px minimum safe distance. |
| Mover.java:163, 169 (2) | Boundary | post-trace wrap `pos < 0` → `<= 0` | 0 becomes the width and the following `>= width` maps it back to 0. |
| Mover.java:275 | Boundary | `i > 0` → `>= 0` | hull[0] is the template `mi` itself; copying it onto itself changes nothing. |
| Mover.java:298, 299 (2) | Boundary | sign `todo < 0` → `<=` | the sign only changes for todo 0, which it multiplies. |
| Mover.java:302 (2), 305 | Boundary | chunk major-axis ties | at equality both branches compute the same done. |
| Mover.java:307, 309 (2) | Math | secondary-axis `todo*maxcl` → `/` | that axis's done becomes 0 and stays in todo; moved later, same final position. |
| Mover.java:314, 344, 375, 380, 403, 570 (6) | Boundary | hull loops `i < n` → `<=` | touch the spare preallocated `hull[n]`, which never crashes or bounces (todo 0); `rx[n]`, `ry[n]` stay 0. |
| Mover.java:323, 325, 328, 330 (4) | Boundary | chunk wrap at exactly 0 / width | every consumer (Segment entry, `wrapXClick`) wraps once more. |
| Mover.java:376, 377 (2) | Math | `todo/vel` → `todo*vel` | scales every point's ratio by the same positive factor per axis; the worst point is unchanged. |
| Mover.java:389 (2) | Boundary, Increment | player `++nothingDone >= 5` | needs 5 consecutive zero-progress hull iterations; no ship configuration reaches it. |
| Mover.java:457 | Boundary | `maxSpeed < 100` → `<=` | at 100 both give 100. |
| Mover.java:526 | Boundary | `dir >= wallDir` → `>` | equal headings give delta 0 in both branches. |
| Mover.java:574 | Boundary | `newDir > dir` → `>=` | equal headings returned earlier. |
| Mover.java:616 (2) | Boundary, Increment | turn `++nothingDone >= 5` | needs 5 edge bounces in one turn step; the tolerated single bounce is tested. |
| Mover.java:645, 647, 652 (3) | Boundary | keepInside at exactly 22 px / 0 | hull points are within ±15 px, so nothing is outside at 22 px; at 0 the push assigns the same value. |
| Segment.java:209 (2), 212 | Boundary | walldist fast-path ties | same done from either branch. |
| Segment.java:266, 273, 285, 292 (4) | Boundary | `offset+todo < 0` / `> BC` | the leave value is identical; the extra needAdjust has ratio 1 on that axis. |
| Segment.java:306, 308 (2) | Boundary | `wxr > wyr` ties | at equality the adjusted value equals the unadjusted one. |
| Segment.java:354 | Boundary | `last < wormholes.size()` → `<=` | `last == size` is never stored. |
| Segment.java:401 (2) | Boundary | treasure `offsetY > r` | every in-block point with y == r is in or on the ball and crashes anyway. |
| Segment.java:438, 447, 530, 576, 630, 638 (6) | Boundary | `vel < 0` → `<=` on an offset == BC face | offset == BC only arises with vel < 0. |
| Segment.java:457, 460 (2) | Negate | `offsetX == BC` in the "tricky" slide test | **unreachable**: offset BC with todo 0 already set a bounce flag. |
| Segment.java:471, 472, 480, 525, 526, 534, 580, 587, 588, 634, 641, 642 (14) | Boundary/Math/Negate | diagonal apex flags | equivalent when velocity is parallel to todo (all callers): the hypotenuse computation that follows gives delta 0 and the same diagonal bounce. |
| Segment.java:493, 547, 601, 655 (4) | Boundary | diagonal x/y-major tie | |w| = 1, so the truncations are exact and both branches agree. |
| Segment.java:705 (5) | Boundary, TrueReturn | `inMap(pl.bx, pl.by)` | a ship whose hull point is in a wormhole always has an in-map centre block. |
| Segment.java:825 | Math | RU apex `vx+vy <= 0` → `vx-vy <= 0` | that branch needs vx < 0 and vy ≥ 0, where both are true. |
| Segment.java:840 | Boundary | `bit <= wallBounce` → `<` | with 2+ bits set the highest bit is below wallBounce. |
| Segment.java:893, 894 (4) | Math | `m * sign` → `m / sign` | sign is ±1. |
| Segment.java:900, 901, 908 (3) | Boundary | cannon half / start-on-edge | a start exactly on an edge line gives a zero move, the same crash. |
| Segment.java:904 (4), 911 (5) | Boundary/Math/Negate | cannon early "clear" test | defers to the exact intersection (full delta when nothing is hit) or still needs both ends above an edge line the triangle lies below. |
| Segment.java:918 | Boundary | cannon x/y-major tie | |w| = 1, exact truncation. |
| Segment.java:924, 936, 959 (4) | Boundary | `dir*a >= 0` / apex row `<= S/3` | `>= 0` side equivalent (a zero candidate only for a start on the edge, already a crash); the apex-row side is **not killed**: differs only for a hit in the last row of the 3-click-wide cannon tip. |
| WallDist.java:87 | Boundary | `dist > 1` → `>=` | seeded values are 0 or maxdist ≥ 2. |
| WallDist.java:110 | Boundary | `mindist >= 255` → `>` | maxdist ≤ 255, nothing is lowered from 255. |
| WallDist.java:115, 120 (4) | Boundary | no-wrap neighbour range at 0 / w | only border blocks, already ≤ 2. |
| WallDist.java:125, 130 (2) | Boundary | `>` / `<` → `>=` / `<=` | only re-enqueues equal distances (a random Dijkstra oracle agrees). |

## Ships, keys, scoring, rounds, world

| file:line (n) | mutator | mutated expression | why |
|---|---|---|---|
| Ships.java:137 | TrueReturn | `return false` after `pickStartPos` fails | **unreachable**: the chosen team has free bases (outside team play there is a slot per base). |
| Ships.java:172 | VoidMethodCall | `removePlayer` when `join`'s spawn fails | **unreachable** for the same reason. |
| Ships.java:201 | Math | leave loop start `count() - 1` → `+ 1` | visits two stale `active[]` entries first: freed handles, re-initialised by `alloc`. |
| Ships.java:225, 432 (2) | Boundary | `k < numPlayers` → `<=` | reads one stale `order` entry: a preallocated player that is not in the game. |
| Ships.java:272 | Boundary | `teamBaseCount > 0` → `>= 0` | with 0 bases `members < 0` is false anyway. |
| Ships.java:276 | Negate | reserved robot team | **unreachable**: `pickTeam` gives the reserved team 0 free bases. |
| Ships.java:325 | Boundary | `teamPlaying < least` → `<=` | same minimum. |
| Ships.java:479, 492 (2) | TrueReturn | final `return false` of `pickStartPos` | **unreachable**: `numFree > 0` guarantees a pick. |
| Ships.java:483 | Boundary | `i < nb` → `<=` | the loop returns at the pick. |
| Ships.java:605 | Boundary | `damaged > 0` → `>= 0` | at 0 the countdown gives 0 again. |
| Ships.java:665, 669, 670 (3) | Math | `(status & GRAVITY) != 0` → `\|`; the no-gravity branch | **unreachable**: every ship has GRAVITY (C sets it in `Init_player`). |
| Ships.java:687 (2) | Boundary | clamp bounds | at equality both give the bound. |
| Ships.java:733 | Boundary | `floatDir < 0` → `<= 0` | 0 → 128 → wrapped back to 0. |
| Ships.java:759 | Boundary | refuel `> 90.0` → `>=` | integer ship pixel vs .5 station centre: the distance is never exactly 90. |
| Ships.java:793 | Math | `(used & REFUEL) == 0` → `\|` | `l < 1e9` alone selects the first station. |
| Ships.java:871 | Boundary | `2t <= T` → `<` | both branches give m = 2/t at t = T/2. |
| Ships.java:888 | Math | `status \|= KILLED` → `&` | `kill()` clears KILLED immediately after. |
| Ships.java:912 | Boundary | `rfrac() < 0.10f` → `<=` | needs rfrac exactly 0.1f. |
| Keys.java:222 | TrueReturn | `homeBase < 0` → pause | **unreachable**: a spawned ship always has a home base. |
| Keys.java:289 | Boundary | base loop `i < size` → `<=` | the ship is on a BASE block, so a base matches before the end. |
| Rounds.java:76, 170, 206, 475 (4) | Boundary | `team < MAX_TEAMS` → `<=` | teams are 0..9; MAX_TEAMS itself never occurs (team 0 is tested). |
| Rounds.java:153 | Boundary | `aliveRobots > 1` → `>= 1` | one alive robot that is the only one alive is caught by the `alive == 1` branch first (or there is no active human). |
| Rounds.java:199, 217 (2) | Boundary | max treasures / max score `>` → `>=` | ties give the same maximum. |
| Rounds.java:403, 509, 576 (3) | Boundary | `k < numPlayers` / `k < count` → `<=` | one stale entry (a player not in the game / a freed object). |
| Rounds.java:434 | Math | `(status & GAME_OVER) != 0` → `\|` (waiting check) | mychar 'W' is only ever set together with GAME_OVER. |
| Rounds.java:612 | Boundary | `resetOnHuman > 0` → `>= 0` | at 0 `humans() <= 0` is false: the joiner is already a human in the game. |
| World.java:185 | Boundary | `id <= 0` → `< 0` | `slotOfId[0]` is always -1. |
| World.java:332 | Boundary | `b < 0` → `<= 0` | b = 0 gives 0. |
| Params.java:378 (2) | Boundary | clamp bounds | at equality both give the bound. |
| Scoring.java:60 | Boundary | `loser > winner` → `>=` | at equality 20 - 10 = 10 = t. |
| Scoring.java:100 | Boundary | `members > 0` → `>= 0` | with 0 members no player has that team. |
| Scoring.java:121, 124 (2) | Boundary | `score > 0` → `>= 0` | 0 becomes -0.0; adding -0.0 changes nothing. |
| Player.java:192 (2) | Boundary | `addFuel` clamp | at equality both give the bound. |

## Combat: Collisions, Cannons, Debris, Shots, Crashes (CollisionsTest compares `inRange` with a literal C `in_range_acd` port on 20 000 random cases)

| file:line (n) | mutator | mutated expression | why |
|---|---|---|---|
| Cannons.java:76 | Boundary | `rfrac*16 < 1` → `<=` | differs only for rfrac exactly 1/16 (2^-24 per check). |
| Collisions.java:74, 81, 88, 95 (4) | Boundary | `p1x > p2x` etc. → `>=` | only reached when the two differ by more than half a world, so they cannot be equal. |
| Collisions.java:106, 109, 114, 117 (4) | Boundary | seam fix `ex > ww/2` etc. | points exactly half a world apart: both copies are equally far; which one is used is arbitrary. |
| Collisions.java:131 | Boundary | `top < 0` → `<= 0` | top 0 means the closest point is the end point, which already failed. |
| Collisions.java:150, 154 (2) | Math | `farApart`: a subtraction inside a move length → addition | only raises the threshold, so fewer pairs are rejected early; `inRange` then gives the same answer (the shortcut is conservative; every lowering mutant is killed). |
| Collisions.java:300 | Boundary | `k < n` → `<=` | the extra grid result is a stale handle out of range of this ship or already life 0. |
| Collisions.java:444 | Math | `drain * 1.0f` → `/ 1.0f` | identical. |
| Crashes.java:218 | Math | `pushCount++` → `--` | every count flips sign with the total: `cnt/total` and the average are unchanged. |
| Debris.java:96 (2) | Boundary | `fx >= pixelWidth` / `fy >= pixelHeight` → `>` | an origin exactly on the far edge is rejected by the second bounds check after quantization (line 105); x = 0 / y = 0 are tested. |
| Debris.java:105, 108, 112, 115, 121, 124, 127, 135 (8) | Boundary | Make_debris clamps | at equality the assignment gives the same value (also after half-pixel quantization). |
| Debris.java:261, 265 (2) | Boundary | `count > 0` → `>= 0` | expanding 0 particles makes nothing. |
| Shots.java:176 | Boundary | `--shots <= 0` → `< 0` | at 0 it sets 0 again. |

## Orchestration, snapshots, tools

| file:line (n) | mutator | mutated expression | why |
|---|---|---|---|
| Digest.java:46 | ConditionalsBoundary | `i < 8` → `<= 8` | `v >>> 64` is `v` in Java, so the extra round hashes byte 0 again: still deterministic and field-sensitive. |
| Digest.java:72 | VoidMethodCall | `add(o.count())` removed | every live object also adds its fields, so the object count still changes the digest. |
| Digest.java:73 | ConditionalsBoundary | `k < count` → `<=` | hashes the stale handle after the last live one: deterministic, so equal worlds still agree. |
| RandomPilot.java:58 | ConditionalsBoundary | `r < p` → `<=` | `nextFloat` hitting a flip probability exactly does not happen for these constants (24-bit fractions). |
| ServerSim.java:107-116 (7) | (no coverage) | `main`: usage print, `System.exit`, file parsing, µs/tick print | the CLI shell; `Args.parse` and `run` are tested. |
| ServerSim.java:124 | Math | team `i % 2` → `i * 2` | an absent team falls back to the team with most free bases, so the same players join. |
| Simulation.java:87, SnapshotBuilder.java:100 (2) | VoidMethodCall | `initRecordClasses()` removed | only preloads the record classes so their first use does not allocate; checked by `AllocationTest` (excluded from PIT). |
| Simulation.java:155 | ConditionalsBoundary | `numPlayers <= 0` → `< 0` | with 0 players the regeneration is `(int)(0 * 15.36) = 0`. |
| SnapshotBuilder.java:186 | ConditionalsBoundary | `curType >= 0` → `> 0` | section types start at 1; "no section" is -1. |
| SnapshotBuilder.java:204 | ConditionalsBoundary | `size >= failedSize` → `>` | a record of exactly the failed size is retried and fails again: same output. |
| SnapshotBuilder.java:216 | TrueReturn | second `beginSection` failure | **unreachable** with equal-sized buffers: a fresh part always has room for one record of any section. |
| SnapshotBuilder.java:267 | ConditionalsBoundary | `count > 0` → `>= 0` | the value sent is 0 either way. |
| SnapshotBuilder.java:345 | Math | room of the unused parts: one subtraction → addition | **not killed**: over-estimates the SHOT room of the *later* parts by a few records, so in a saturated multi-part snapshot with mixed shot variants a few of the farthest shots may replace near ones of a later variant; the first-part estimate (line 343) is pinned by `Budget.mixedVariants`. |
| SnapshotBuilder.java:357, 358 (2) | Math | `viewHalf / CELL` → `*` | the ring radius becomes the maximum (16 cells); cells outside the view only add shots that `visible()` then rejects. |
| SnapshotBuilder.java:360, 365 (2) | ConditionalsBoundary | `n < cap` → `<=` | collects one shot more than the estimated room; the estimate keeps a section header per part spare, so it still fits. |
| SnapshotBuilder.java:376 | ConditionalsBoundary | `i < n` → `<=` | the extra entry's variant is -1 or never set and its inner loop is empty. |
