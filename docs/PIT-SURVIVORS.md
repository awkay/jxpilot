# PIT surviving mutants — `common`

Run: `MAVEN_OPTS= mvn -pl common org.pitest:pitest-maven:mutationCoverage` (tests run with `-ea`, failures enforced).
Result: **2392 mutants, 2327 killed (97%)**, 0 without coverage, 65 survivors. Each survivor below was checked
individually and is an *equivalent mutant*: no input reachable through the public API makes the
mutated code behave differently. `#n` is PIT's instruction index for the mutant on that line.
`ConditionalsBoundary` swaps `<`↔`<=` / `>`↔`>=`.

| file:line #idx | mutator | mutated expression | why equivalent |
|---|---|---|---|
| CNum.java:46 #166 | ConditionalsBoundary | `s.charAt(i) > 127` → `>= 127` | char 127 (DEL) is not a digit, so `d < 0` already stops the scan. |
| CNum.java:50 #186 | ConditionalsBoundary | `v > 4·MAX` → `>=` | at equality the clamp assigns the same value. |
| ConfigParser.java:121 #39 | PrimitiveReturns | `return c` ('\n') → `return 0` | the only caller returns on both `'\n'` and `0`; the newline was already counted. |
| ConfigTransfer.java:62 #71 | VoidMethodCall | `Deflater.end()` removed | frees native memory early only; output is identical (GC finalizes it). |
| ConfigTransfer.java:145 #68 | VoidMethodCall | `Inflater.end()` removed | same: resource release only. |
| DebrisBurst.java:83 #124 | ConditionalsBoundary | `life*speed > hyp` → `>=` | at equality the clamp sets `life = hyp/speed`, which equals `life`. |
| GravityField.java:122 #234 | ConditionalsBoundary | `dx < 0` → `<= 0` (sign of fx) | at dx = 0, `tabX[0][ay] = 0`, so `fx * vx` is 0 whatever fx's sign. |
| GravityField.java:136 #319 | ConditionalsBoundary | `dy < 0` → `<= 0` (sign of fy) | at dy = 0, `tabY[ax][0] = 0`, so `fy * vy` is 0. |
| InputHistory.java:33 #36 | ConditionalsBoundary | `stored < 8` → `<= 8` | `stored` only caps the entry count, and `write` also caps it at 8. |
| MapLoader.java:150 #122 | ConditionalsBoundary | `atEnd = s >= n` → `s > n` | with `s == n` the char is already `'\n'` (s < n false); the extra `s++` only moves s further past the end. |
| Options.java:204 #19 | ConditionalsBoundary | `comma < 0` → `<= 0` | a value starting with ',' fails `strtol` for x anyway, so it is rejected either way. |
| Options.java:343 #28 | ConditionalsBoundary | `a.length() > 1` → `>= 1` | a bare "-"/"+" then looks up the name "", finds nothing and is returned as a leftover arg, the same as before. |
| Options.java:451 #43 | ConditionalsBoundary | `indexOf('\n') >= 0` → `> 0` | a leading '\n' is already caught by the leading-whitespace test. |
| Options.java:451 #47 | ConditionalsBoundary | `indexOf('\r') >= 0` → `> 0` | a leading '\r' is already caught by the leading-whitespace test. |
| ReliableChannel.java:307 #68 | ConditionalsBoundary | `t < minRto` → `<=` | at equality both branches yield minRto. |
| ReliableChannel.java:307 #78 | ConditionalsBoundary | `t > maxRto` → `>=` | at equality both branches yield maxRto. |
| ReliableChannel.java:343 #42 | ConditionalsBoundary | `remaining < 4` → `<= 4` | with exactly 4 bytes left, any length ≥ 1 exceeds the remainder and the next check returns -1 anyway. |
| Section.java:95 #4 | ConditionalsBoundary | `type >= 0` → `> 0` | type 0 is unassigned, `SIZES[0] == 0`, so both give 0. |
| ShipShape.java:166 #121 | ConditionalsBoundary | `a > 100000` → `>=` | at equality the clamp assigns the same value. |
| ShipShape.java:263 #177 | ConditionalsBoundary | `colon < 0` → `<= 0` | colon ≥ p ≥ 1 (index after a '('), so 0 never occurs. |
| ShipShape.java:268 #203 | ConditionalsBoundary | `close < 0` → `<= 0` | close > colon ≥ 1, so 0 never occurs. |
| ShipShape.java:269 #224 | ConditionalsBoundary | `close < 0` → `<= 0` | same as above. |
| ShipShape.java:304 #419 | ConditionalsBoundary | `q < 0` → `<= 0` | q = indexOf('(', q+1) ≥ 1, so 0 never occurs. |
| ShipShape.java:334 #690 | ConditionalsBoundary | default left light `x <= max.x` → `<` | only differs when y and x both tie, i.e. duplicate points with identical coordinates. |
| ShipShape.java:343 #761 | ConditionalsBoundary | default right light `x <= max.x` → `<` | same: ties on both coordinates are identical points. |
| ShipShape.java:352 #813 | ConditionalsBoundary | default engine `x < max.x` → `<=` | the engine is `(x, 0)`; a later point with the same x gives the same engine. |
| ShipShape.java:469 #21 | ConditionalsBoundary | outer pair loop `i < n` → `<=` | at i = n the inner loop (j from n+1 while j < n) does not run. |
| ShipShape.java:487 #143 | ConditionalsBoundary | outer projection loop `i < n` → `<=` | same: the inner loop is empty. |
| ShipShape.java:507 #66 | ConditionalsBoundary | x-major test `|dx| >= |dy|` → `>` | differs only on 45° edges, where x- and y-major rasterization mark the same exact cells (`(dy*(x-x0))/dx` is exact when |dx| = |dy|). The diamond test pins this. |
| ShipShape.java:508 #70 | ConditionalsBoundary | `dx > 0` → `>=` | in the x-major branch dx = 0 implies dy = 0 (zero-length edge), so the loop is empty. |
| ShipShape.java:511 #109 | ConditionalsBoundary | `x < hiX` → `<=` | the extra iteration rasterizes the far vertex itself, exactly, and it is already marked. |
| ShipShape.java:515 #146 | ConditionalsBoundary | `dy > 0` → `>=` | in the y-major branch |dy| > |dx| ≥ 0, so dy ≠ 0. |
| ShipShape.java:518 #185 | ConditionalsBoundary | `y < hiY` → `<=` | the extra iteration marks the far vertex, which is already marked. |
| ShipShape.java:540 #427 | ConditionalsBoundary | flood bound `c[0] >= -15` → `> -15` | every border cell (x = ±15 or y = ±15) is seeded before the flood, so a border neighbour is never 0; skipping it changes nothing. |
| ShipShape.java:540 #432 | ConditionalsBoundary | `c[0] <= 15` → `< 15` | same reason (border column x = 15 is pre-seeded). |
| ShipShape.java:540 #437 | ConditionalsBoundary | `c[1] >= -15` → `> -15` | same reason (border row y = -15 is pre-seeded). |
| ShipShape.java:540 #442 | ConditionalsBoundary | `c[1] <= 15` → `< 15` | same reason (border row y = 15 is pre-seeded). |
| SnapshotWriter.java:139 #5 | ConditionalsBoundary | `sectionPos >= 0` → `> 0` | an open section starts after the 15-byte snapshot header, so sectionPos is never 0. |
| SnapshotWriter.java:173 #5 | ConditionalsBoundary | `sectionPos < 0` → `<= 0` | same: sectionPos is −1 or ≥ 15. |
| ThrustSparks.java:70 #59 | ConditionalsBoundary | `maxLife > shotLife` → `>=` | at equality the cap assigns the same value (`min(minLife, shotLife)` is unchanged since minLife = 3 ≤ shotLife). |
| ThrustSparks.java:78 #114 | ConditionalsBoundary | `perTick > 0` → `>= 0` | with perTick = 0 the formula gives `((-1·n) >> 4) + 1 = 0` for every n in 1..15. |
| WallGeometry.java:149 #201 | ConditionalsBoundary | treasure `oy <= r` → `< r` | at oy = r the circle test reduces to `(ox-r)² ≤ r²`, true for every ox in [0, 35]. |
| WallGeometry.java:161 #299 | ConditionalsBoundary | cannon `sx <= S/2` → `<` | at sx = S/2 both halves' inequalities are `3sy ≤ 35`. |
| WallGeometry.java:290 #10 | ConditionalsBoundary | grow when `n+k > len` → `>=` | grows one add earlier; contents are identical. |
| WallGeometry.java:291 #20 | Math | `len * 2` → `len / 2` | `max(len/2, n+k)` still grows to the needed size; contents identical (only more copies). |
| WallGeometry.java:291 #24 | Math | `n + k` → `n - k` | `max(len*2, n-k) = len*2 ≥ n+k` whenever growth is triggered; contents identical. |
| Wire.java:25 #14 | ConditionalsBoundary | `v < 0` → `<=` | at v = 0 both branches yield 0. |
| Wire.java:25 #21 | ConditionalsBoundary | `v > 0xFFFF` → `>=` | at v = 65535 both branches yield 65535. |
| Wire.java:57 #14 | ConditionalsBoundary | `q < 0` → `<=` | at 0 both yield 0. |
| Wire.java:57 #21 | ConditionalsBoundary | `q > 255` → `>=` | at 255 both yield 255. |
| Wire.java:73 #13 | ConditionalsBoundary | `q < -127` → `<=` | at -127 both yield -127. |
| Wire.java:73 #20 | ConditionalsBoundary | `q > 127` → `>=` | at 127 both yield 127. |
| Wire.java:79 #14 | ConditionalsBoundary | `q < 0` → `<=` | at 0 both yield 0. |
| Wire.java:79 #21 | ConditionalsBoundary | `q > 0xFFFF` → `>=` | at 65535 both yield 65535. |
| Wire.java:85 #15 | ConditionalsBoundary | `q < -32768` → `<=` | at -32768 both yield -32768. |
| Wire.java:85 #22 | ConditionalsBoundary | `q > 32767` → `>=` | at 32767 both yield 32767. |
| Wire.java:106 #14 | ConditionalsBoundary | `v < 0` → `<=` | at 0 both yield 0. |
| Wire.java:106 #21 | ConditionalsBoundary | `v > 0xFFFF` → `>=` | at 65535 both yield 65535. |
| Wire.java:164 #6 | ConditionalsBoundary | `length > max` → `>=` | at equality `setLength(max)` is a no-op. |
| XMath.java:21 #6 | ConditionalsBoundary | fast path `v >= 0` → `> 0` | v = 0 takes the modulo path: `0 % size = 0`, the same result. |
| XMath.java:25 #27 | ConditionalsBoundary | `v < 0` → `<= 0` | v = 0 becomes `size`, which the next line maps back to 0. |
| XMath.java:113 #6 | ConditionalsBoundary | `v < lo` → `<=` | at v = lo both yield lo. |
| XMath.java:113 #14 | ConditionalsBoundary | `v > hi` → `>=` | at v = hi both yield hi. |
| XMath.java:118 #5 | ConditionalsBoundary | `v < lo` → `<=` | at v = lo both yield lo. |
| XMath.java:118 #12 | ConditionalsBoundary | `v > hi` → `>=` | at v = hi both yield hi. |

Notes
- `AllocationTest` is excluded from PIT (it measures thread allocation, which PIT instrumentation can
  disturb). It runs in its own surefire execution with `-XX:-DoEscapeAnalysis`, and it also exercises
  the overflow-buffer pool that the functional tests check with `pooledBuffers()`.

## `common.sim` — the ship kernel (latency phase 1a)

Run as above (the kernel is part of `common`). Result: **1903 mutants in `com.fulcrologic.jxpilot.common.sim`,
1784 detected (93.7%)**, 116 survived, 3 without coverage; the whole module 4613 / 4848 (95.2%). Per class: Click 11/12,
KernelWorld 36/36, Mods 53/54, Segment 709/774, ShipBody 152/154, ShipGeom 28/30, ShipKernel 135/138, ShipKeys
102/102, ShipMover 311/343, ShipParams 11/13, ShipShooter 106/108, ShipWorld 43/44, Trig 6/6, WallDist 81/89.

Segment, WallDist, Click and the player half of Mover (now ShipMover) moved here from `server`; their survivors are the
ones `PIT-SURVIVORS-server.md` justified before the move (same code, new line numbers), listed again by category.

### New kernel code

| file:line | mutator | mutated expression | why equivalent |
|---|---|---|---|
| ShipKernel.java:103 (2) | Boundary | `limits` clamp `v > hi` / `v < lo` | at equality both branches give the bound. |
| ShipKernel.java:234 | Boundary | `while (floatDir < 0)` → `<= 0` | 0 becomes RES, and the following `>= RES` loop maps it back to 0. |
| ShipParams.java:137 (2) | Boundary | `clamp` bounds | at equality both give the bound. |
| ShipWorld.java:159 | Boundary | `clampBlock` `b < 0` → `<= 0` | b = 0 gives 0 either way. |
| ShipShooter.java:96 | Math | rear dir `dir + RES/2 + ...` → `dir - RES/2 + ...` | `Trig.mod` of a value RES apart: +RES/2 and -RES/2 are the same heading. |
| ShipShooter.java:172 | Boundary | `fuel < SHOT_FUEL_NEEDED` → `<=` | fuel changes by whole units (`addFuel` truncates), so it is never exactly 51.2. |
| ShipBody.java:174, 176 (2) | Boundary | `addFuel` clamp | at equality both give the bound. |
| ShipGeom.java:85, 92 (2) | Boundary, VoidMethodCall (no coverage) | no-rack fallback `missileRackX.length > 0` | **unreachable**: `ShipShape` always has a rack (the parser and the default ship add one at the main gun). |
| Mods.java:62 | Math | `warhead`: `m >>> 0` → `m << 0` | the warhead shift is 0. |
| Click.java:53 | Boundary | `roundHalfAway` `d < 0` → `<=` | at d = 0 both branches give 0. |

### Moved code (ShipMover = Move_player / Turn_player, Segment, WallDist)

| file:line | mutator | mutated expression | why equivalent |
|---|---|---|---|
| ShipMover.java:81 | Boundary | block clamp `b < 0` → `<= 0` | b = 0 gives 0 either way. |
| ShipMover.java:170 | Boundary | `i > 0` → `>= 0` | hull[0] is the template `mi` itself; copying it onto itself changes nothing. |
| ShipMover.java:193, 194 (2) | Boundary | chunk sign `todo < 0` → `<=` | the sign only changes for todo 0, which it multiplies. |
| ShipMover.java:197 (2), 200 | Boundary | chunk major-axis ties | at equality both branches compute the same done. |
| ShipMover.java:202, 204 (2) | Math | secondary-axis `todo*maxcl` → `/` | that axis's done becomes 0 and stays in todo; moved later, same final position. |
| ShipMover.java:209, 239, 270, 275, 298, 463 (6) | Boundary | hull loops `i < n` → `<=` (`i = 1` loops: `<` of `n`) | touch the spare preallocated `hull[n]`, which never crashes or bounces (todo 0); `rx[n]`, `ry[n]` stay 0. |
| ShipMover.java:218, 220, 223, 225 (4) | Boundary | chunk wrap at exactly 0 / width | every consumer (Segment entry, `wrapXClick`) wraps once more. |
| ShipMover.java:271, 272 (2) | Math | `todo/vel` → `todo*vel` | scales every point's ratio by the same positive factor per axis; the worst point is unchanged. |
| ShipMover.java:284 (2) | Boundary, Increment | player `++nothingDone >= 5` | needs 5 consecutive zero-progress hull iterations; no ship configuration reaches it. |
| ShipMover.java:348 | Boundary | `maxSpeed < 100` → `<=` | at 100 both give 100. |
| ShipMover.java:418 | Boundary | `dir >= wallDir` → `>` | equal headings give delta 0 in both branches. |
| ShipMover.java:467 | Boundary | `newDir > dir` → `>=` | equal headings returned earlier. |
| ShipMover.java:509 (2) | Boundary, Increment | turn `++nothingDone >= 5` | needs 5 edge bounces in one turn step; the tolerated single bounce is tested. |
| ShipMover.java:515 | Math | turn trace `(doneX \| doneY) != 0` → `&` | the position only feeds the next Move_segment of the same rotation step, and a step moves a hull point less than a block, so it is traced in one segment. |
| ShipMover.java:538, 540, 545 (3) | Boundary | keepInside at exactly 22 px / 0 | hull points are within ±15 px, so nothing is outside at 22 px; at 0 the push assigns the same value. |
| Segment.java:229 (2), 232 | Boundary | walldist fast-path ties | same done from either branch. |
| Segment.java:286, 293, 305, 312 (4) | Boundary | `offset+todo < 0` / `> BC` | the leave value is identical; the extra needAdjust has ratio 1 on that axis. |
| Segment.java:326, 328 (2) | Boundary | `wxr > wyr` ties | at equality the adjusted value equals the unadjusted one. |
| Segment.java:373 | Boundary | `last < wormholeCount()` → `<=` | `last == count` is never stored. |
| Segment.java:423 (2) | Boundary | treasure `offsetY > r` | every in-block point with y == r is in or on the ball and crashes anyway. |
| Segment.java:465, 474, 557, 603, 657, 665 (6) | Boundary | `vel < 0` → `<=` on an offset == BC face | offset == BC only arises with vel < 0. |
| Segment.java:484, 487 (2, no coverage) | Negate | `offset == 0 \|\| offset == BC` in the "tricky" slide test | **unreachable**: offset 0/BC with todo 0 on that axis already set a bounce flag. |
| Segment.java:498, 499, 507, 552, 553, 561, 607, 614, 615, 661, 668, 669 (14) | Boundary/Math/Negate | diagonal apex flags | equivalent when velocity is parallel to todo (all callers): the hypotenuse computation that follows gives delta 0 and the same diagonal bounce. |
| Segment.java:520, 574, 628, 682 (4) | Boundary | diagonal x/y-major tie | \|w\| = 1, so the truncations are exact and both branches agree. |
| Segment.java:732 (5) | Boundary, TrueReturn | `inMap(pl.bx, pl.by)` | a ship whose hull point is in a wormhole always has an in-map centre block. |
| Segment.java:852 | Math | RU apex `vx+vy <= 0` → `vx-vy <= 0` | that branch needs vx < 0 and vy ≥ 0, where both are true. |
| Segment.java:867 | Boundary | `bit <= wallBounce` → `<` | with 2+ bits set the highest bit is below wallBounce. |
| Segment.java:920, 921 (4) | Math | `m * sign` → `m / sign` | sign is ±1. |
| Segment.java:927, 928, 935 (3) | Boundary | cannon half / start-on-edge | a start exactly on an edge line gives a zero move, the same crash. |
| Segment.java:931 (4), 938 (5) | Boundary/Math/Negate | cannon early "clear" test | defers to the exact intersection (full delta when nothing is hit) or still needs both ends above an edge line the triangle lies below. |
| Segment.java:945 | Boundary | cannon x/y-major tie | \|w\| = 1, exact truncation. |
| Segment.java:951, 963, 986 (3) | Boundary | `dir*a >= 0` / apex row `<= S/3` | `>= 0` side equivalent (a zero candidate only for a start on the edge, already a crash); the apex-row side is **not killed**: differs only for a hit in the last row of the 3-click-wide cannon tip. |
| WallDist.java:87 | Boundary | `dist > 1` → `>=` | seeded values are 0 or maxdist ≥ 2. |
| WallDist.java:110 | Boundary | `mindist >= 255` → `>` | maxdist ≤ 255, nothing is lowered from 255. |
| WallDist.java:115, 120 (4) | Boundary | no-wrap neighbour range at 0 / w | only border blocks, already ≤ 2. |
| WallDist.java:125, 130 (2) | Boundary | `>` / `<` → `>=` / `<=` | only re-enqueues equal distances (a random Dijkstra oracle agrees). |
