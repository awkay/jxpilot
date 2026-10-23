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
