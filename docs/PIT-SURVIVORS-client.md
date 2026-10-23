# PIT surviving mutants — `client` (render, map, viewer, paint)

Run: `MAVEN_OPTS= mvn -o -pl client test-compile org.pitest:pitest-maven:mutationCoverage`. `AllocationTest` is excluded
from PIT, and so are `DemoWorld` and `DemoHud` (fake demo choreography).

Result after the paint-fidelity fixes (P1-P12): **2567 of 2633 mutants killed (97%)**, test strength 98%, line coverage
99%. One more test (stale-slot reads) was added afterwards; it kills the `ScenePainter` loop-bound survivors listed under
"killed since".

## Survivors in code added or changed by the paint fixes

| class / method | mutant | why it survives |
|---|---|---|
| `WinScale.of` | `n < 0` → `<=` (twice), `v < 1` → `<=`, `v > MAX` → `>=` | equivalent: n = 0 returns earlier; at v = 1 or v = MAX both branches give the same value |
| `Particles.sparkShown` | 6 mutants of the mixing steps (`^` → `&`, `>>>` → `<<`) | a weaker mix is still a deterministic pseudo-random bit; the tests pin determinism, the 0/128 extremes and the ~50% rates, not the hash itself |
| `ScenePainter.drawArcs` | ECM cull radius `size / 2` → `size * 2` | culling slack only: a larger radius draws nothing extra on screen |
| `HudPainter.lockShipShown` | `<=` → `<` | equivalent: `10 / zoom` never equals `15 + 1e-9` exactly |
| `RadarImage.build` | `width >= 256` → `> 256` | equivalent at exactly 256 blocks: both walks map block i to radar pixel i |

Killed since (by `ScenePainterTest.aReusedSceneDrawsOnlyThisFramesEntries`): `ScenePainter` `shipFills`, `texts` and
`shipLines` loop bounds `i < count` → `i <= count`. These loops would read stale slots of a reused `SceneData`.

## Older survivors (unchanged code, same as before the fixes)

`ScenePainter.diagonal` hash mixing (5, same argument as `sparkShown`), `ScenePainter.visible` and the asteroid cull
radius (culling slack), `HudPainter.paintLockShip` rotation (`cos(96 RES)` is about 1e-16, so `*` / `/` and `+` / `-`
truncate to the same pixels), `HudPainter.wrapHalf` / `scoreObjectVisible` boundaries, `RadarPainter.self` /
`checkpoint` / `square` wrap boundaries, `VertexBatch.unorm16`, `Camera` boundaries, `Dashes.edge` epsilon
boundaries, `NumberText`, `CFormat`, `ItemBitmaps`, `ViewerOverlay.wrap01`, `MapSymbols.fuelCoversCentre`,
`ShipPainter.lights`, `RadarImage.Walker.cell`.
