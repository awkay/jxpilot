# PIT surviving mutants — `server`

Run: `MAVEN_OPTS= mvn -o -pl server test-compile org.pitest:pitest-maven:mutationCoverage` (run `test-compile` in the same
command so PIT does not use stale test classes). `AllocationTest` and `TickBenchmark` are excluded from PIT.

Result: **3073 of 3256 mutants killed (94.4%)**, 183 survivors.

| class | killed / total |
|---|---|
| Cannons | 87 / 88 |
| Click | 11 / 12 |
| Collisions | 332 / 342 |
| Crashes | 71 / 72 |
| Debris | 114 / 123 |
| Digest | 38 / 41 |
| Events | 20 / 20 |
| Grid | 78 / 79 |
| Keys | 72 / 73 |
| Mover | 401 / 442 |
| Objects | 21 / 21 |
| Params | 70 / 72 |
| Player | 18 / 20 |
| RandomPilot | 7 / 8 |
| Scoring | 30 / 34 |
| Segment | 707 / 770 |
| ServerSim | 14 / 21 |
| ServerSim$Args | 9 / 9 |
| ShipGeom | 14 / 14 |
| Ships | 430 / 448 |
| Shots | 82 / 83 |
| Simulation | 80 / 82 |
| SnapshotBuilder | 151 / 156 |
| Trig | 7 / 7 |
| WallDist | 81 / 89 |
| World | 128 / 130 |

`ConditionalsBoundary` swaps `<`↔`<=` / `>`↔`>=`. `(n)` is the number of mutants on that line. Survivors are equivalent
(no input reachable through the API behaves differently) unless marked **not killed** (a real difference that no test
catches, with the reason it is left) or **unreachable** (no coverage: code that cannot be reached in S1).

## Wall movement: Segment, Mover, WallDist, Grid, Objects, Click (SegmentOracleTest compares Segment with literal C transcriptions)

| file:line (n) | mutator | mutated expression | why |
|---|---|---|---|
| Click.java:53 | Boundary | `d < 0` → `<=` | at d = 0 both branches give 0. |
| Grid.java:134 | Boundary | outer `count < max` → `<=` | the inner loop's `count < max` still stops adding. |
| Mover.java:89, 470 (2) | Boundary | block clamp `b < 0` → `<= 0` | b = 0 gives 0 either way. |
| Mover.java:113 | Boundary | `dist > 2` → `>=` | at walldist 2 the free-flight limit is 0, so only a zero-speed object takes the path; same result. |
| Mover.java:114-117 (6) | Math/Boundary | free-flight test arithmetic | each only makes the test stricter (falls back to tracing, same result) or changes it for |v| ≤ 1 px, far below the 17 px minimum safe distance. |
| Mover.java:183, 189 (2) | Boundary | post-trace wrap `pos < 0` → `<= 0` | 0 becomes the width and the following `>= width` maps it back to 0. |
| Mover.java:312, 351, 381, 412, 417, 440, 608 (7) | Boundary | hull loops `i < n` → `<=` | touch the spare preallocated `hull[n]`, which never crashes or bounces (todo 0); `rx[n]`, `ry[n]` stay 0. |
| Mover.java:335, 336 (2) | Boundary | sign `todo < 0` → `<=` | the sign only changes for todo 0, which it multiplies. |
| Mover.java:339 (2), 342 | Boundary | chunk major-axis ties | at equality both branches compute the same done. |
| Mover.java:344, 346 (2) | Math | secondary-axis `todo*maxcl` → `/` | that axis's done becomes 0 and stays in todo; moved later, same final position. |
| Mover.java:360, 362, 365, 367 (4) | Boundary | chunk wrap at exactly 0 / width | every consumer (Segment entry, `wrapXClick`) wraps once more. |
| Mover.java:413, 414 (2) | Math | `todo/vel` → `todo*vel` | scales every point's ratio by the same positive factor per axis; the worst point is unchanged. |
| Mover.java:426 (2) | Boundary, Increment | player `++nothingDone >= 5` | needs 5 consecutive zero-progress hull iterations; no ship configuration reaches it. |
| Mover.java:495 | Boundary | `maxSpeed < 100` → `<=` | at 100 both give 100. |
| Mover.java:564 | Boundary | `dir >= wallDir` → `>` | equal headings give delta 0 in both branches. |
| Mover.java:612 | Boundary | `newDir > dir` → `>=` | equal headings returned earlier. |
| Mover.java:654 (2) | Boundary, Increment | turn `++nothingDone >= 5` | needs 5 edge bounces in one turn step; the tolerated single bounce is tested. |
| Mover.java:660 | Math | `(doneX \| doneY) != 0` → `&` | **not killed**: drops a single-axis position update; only matters if one hull point crosses two block boundaries within one turn step (steps are ≤ 3 px). |
| Mover.java:683, 685, 690 (3) | Boundary | keepInside at exactly 22 px / 0 | hull points are within ±15 px, so nothing is outside at 22 px; at 0 the push assigns the same value. |
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

## Ships, keys, scoring, world

| file:line (n) | mutator | mutated expression | why |
|---|---|---|---|
| Ships.java:100 | VoidMethodCall | `removePlayer` after `pickStartPos` fails | **unreachable**: the chosen team has free bases (outside team play there is a slot per base). |
| Ships.java:132 | Boundary | `k < objects.count()` → `<=` | reads one stale `active[]` entry; the pool is never full here. |
| Ships.java:163 | Boundary | `teamBaseCount > 0` → `>= 0` | with 0 bases `members < 0` is false anyway. |
| Ships.java:167 | Negate | reserved robot team | **unreachable**: `pickTeam` gives the reserved team 0 free bases. |
| Ships.java:216 | Boundary | `teamPlaying < least` → `<=` | same minimum. |
| Ships.java:353, 366 (2) | TrueReturn | final `return false` of `pickStartPos` | **unreachable**: `numFree > 0` guarantees a pick. |
| Ships.java:357 | Boundary | `i < nb` → `<=` | the loop returns at the pick. |
| Ships.java:424 | Boundary | `damaged > 0` → `>= 0` | `max(0, 0 - dt) = 0`. |
| Ships.java:487 | Boundary | `sparks > 0` → `>= 0` | `turnThrust(pl, 0)` makes nothing. |
| Ships.java:496 (2) | Boundary | clamp bounds | at equality both give the bound. |
| Ships.java:552 | Boundary | `floatDir < 0` → `<= 0` | 0 → 128 → wrapped back to 0. |
| Ships.java:578 | Boundary | refuel `> 90.0` → `>=` | integer ship pixel vs .5 station centre: the distance is never exactly 90. |
| Ships.java:613 | Math | `(used & REFUEL) == 0` → `\|` | `l < 1e9` alone selects the first station. |
| Ships.java:689 | Boundary | `2t <= T` → `<` | both branches give m = 2/t at t = T/2. |
| Ships.java:704 | Math | `status \|= KILLED` → `&` | `kill()` clears KILLED immediately after. |
| Ships.java:729 | Boundary | `rfrac() < 0.10f` → `<=` | needs rfrac exactly 0.1f. |
| Keys.java:208 | Boundary | base loop `i < size` → `<=` | the ship is on a BASE block, so a base matches before the end. |
| World.java:167 | Boundary | `id <= 0` → `< 0` | `slotOfId[0]` is always -1. |
| World.java:290 | Boundary | `b < 0` → `<= 0` | b = 0 gives 0. |
| Params.java:343 (2) | Boundary | clamp bounds | at equality both give the bound. |
| Scoring.java:58 | Boundary | `loser > winner` → `>=` | at equality 20 - 10 = 10 = t. |
| Scoring.java:93 | Boundary | `members > 0` → `>= 0` | with 0 members no player has that team. |
| Scoring.java:113, 116 (2) | Boundary | `score > 0` → `>= 0` | 0 becomes -0.0; adding -0.0 changes nothing. |
| Player.java:174 (2) | Boundary | `addFuel` clamp | at equality both give the bound. |

## Combat: Collisions, Cannons, Debris, Shots, Crashes (CollisionsTest compares `inRange` with a literal C `in_range_acd` port on 20 000 random cases)

| file:line | mutator | mutated expression | why |
|---|---|---|---|
| Cannons.java:79 | Boundary | `rfrac*16 < dt` → `<=` | differs only for rfrac exactly dt/16 (2^-24 per check). |
| Collisions.java:76, 83, 90, 97 (4) | Boundary | `p1x > p2x` etc. → `>=` | only reached when the two differ by more than half a world, so they cannot be equal. |
| Collisions.java:108, 111, 116, 119 (4) | Boundary | seam fix `ex > ww/2` etc. | points exactly half a world apart: both copies are equally far; which one is used is arbitrary. |
| Collisions.java:133 | Boundary | `top < 0` → `<= 0` | top 0 means the closest point is the end point, which already failed. |
| Collisions.java:286 | Boundary | `k < n` → `<=` | the extra grid result is a stale handle out of range of this ship or already life 0. |
| Crashes.java:189 | Math | `pushCount++` → `--` | every count flips sign with the total: `cnt/total` and the average are unchanged. |
| Debris.java:105, 109, 112, 116, 119, 122, 130 (7) | Boundary | Make_debris clamps | at equality the assignment gives the same value (also after 1/16 quantization). |
| Debris.java:259, 263 (2) | Boundary | `count > 0` → `>= 0` | expanding 0 particles makes nothing. |
| Shots.java:173 | Boundary | `--shots <= 0` → `< 0` | at 0 it sets 0 again. |

## Orchestration, snapshots, tools

| file:line (n) | mutator | mutated expression | why |
|---|---|---|---|
| Digest.java:46 | ConditionalsBoundary | `i < 8` → `<= 8` | `v >>> 64` is `v` in Java, so the extra round hashes byte 0 again: still deterministic and field-sensitive. |
| Digest.java:82 | VoidMethodCall | `add(o.count())` removed | every live object also adds six fields, so the object count still changes the digest. |
| Digest.java:83 | ConditionalsBoundary | `k < count` → `<=` | hashes the stale handle after the last live one: deterministic, so equal worlds still agree. |
| RandomPilot.java:58 | ConditionalsBoundary | `r < p` → `<=` | `nextFloat` hitting a flip probability exactly does not happen for these constants (24-bit fractions). |
| ServerSim.java:85-93 | (5 mutants) | `main`: usage print, `System.exit`, file parsing, µs/tick print | the CLI shell (excluded from the threshold like other `main` classes); `run` is tested. |
| ServerSim.java:101 | Math | team `i % 2` → `i * 2` | an absent team falls back to the team with most free bases, so the same players join. |
| Simulation.java:139 | ConditionalsBoundary | `numPlayers <= 0` → `< 0` | with 0 players the regeneration is `(int)(0 * 15.36) * dt = 0`. |
| Simulation.java:174 | ConditionalsBoundary | `c > 0` → `>= 0` | at 0 the countdown becomes `max(0, -dt) = 0`. |
| SnapshotBuilder.java:150 | ConditionalsBoundary | `curType >= 0` → `> 0` | section types start at 1; "no section" is -1. |
| SnapshotBuilder.java:176 | BooleanTrueReturnVals | second `beginSection` failure | unreachable with equal-sized buffers: a fresh part always has room for one record of any section. |
| SnapshotBuilder.java:227 | ConditionalsBoundary | `count > 0` → `>= 0` | `ceil(0) = 0`, the same value. |
| SnapshotBuilder.java:286 | ConditionalsBoundary | `i < n` → `<=` | the extra entry's variant is -1 (every processed entry is marked), so nothing is written. |
| SnapshotBuilder.java:291 | ConditionalsBoundary | `j < n` → `<=` | same: the stale entry beyond `n` is marked -1. |
