# JXPilot — Spec

A JVM reimplementation of XPilot 4.5.5 (C source at `/Users/tonykay/Downloads/xpilot-4.5.5`).
Gameplay should feel like XPilot. Wire protocol is our own (no C client compatibility).
Must load the original block-based `.xp` map files unchanged (copies in `maps/`).

## Reading the C source
Source files are Latin-1. The shell `grep` is a wrapper that skips "binary" files —
use `command grep -a ...` or `rg -a`. Key files:
- Map/config parsing: `src/server/map.c`, `src/server/option.c`, `src/server/cmdline.c` (option table + defaults), `doc/README.MAPS`, `doc/README.MAPS2`
- Physics/motion: `src/server/update.c`, `src/server/walls.c`, `src/server/collision.c`, `src/server/player.c`, `src/server/shot.c`, `src/server/item.c`, `src/server/cannon.c`, `src/server/robot.c`, `src/server/robotdef.c`
- Constants: `src/common/const.h`, `src/server/serverconst.h`, `src/common/rules.h`, `src/server/object.h`, `src/server/map.h`
- Ship shapes: `src/common/shipshape.c`
- Client look & feel: `src/client/paint*.c`, `src/client/guimap.c`, `src/client/guiobjects.c`, `src/client/painthud.c`, `src/client/default.c` (keys)

## Stack
- Java 25, Maven multi-module (`common`, `server`, `client`), JUnit 5. Build: `mvn -q -o install` (drop `-o` if deps missing).
- Client graphics: LWJGL 3.3.6 — GLFW window + OpenGL 3.3 core profile (forward-compatible; macOS needs `-XstartOnFirstThread`), STB TrueType for text.
- Packages: `com.fulcrologic.jxpilot.{common,server,client}` plus sub-packages as needed.

## Performance / GC rules (hard requirements for hot paths: sim tick, net encode/decode, render frame)
- Zero steady-state allocation per tick/frame. No `new`, boxing, varargs, lambdas capturing state, iterators/for-each over collections, streams, `String.format`, or string concatenation in hot paths.
- Game objects live in preallocated pools (fixed-capacity arrays of mutable objects or struct-of-arrays), addressed by int index; free-list reuse. Capacity limits from options (e.g. `maxPlayerShots`).
- Networking uses preallocated direct `ByteBuffer`s and NIO `DatagramChannel`.
- Rendering: one dynamic VBO batch (lines + triangles) filled from an off-heap `FloatBuffer` (`MemoryUtil.memAllocFloat`), flushed with few draw calls. Map walls built once into a static VBO.
- Text that changes each frame (scores, HUD numbers) is rendered from char/int without creating Strings (write digits directly).
- Every module provides an allocation regression test where meaningful: run N steady-state iterations, assert `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()` delta == 0 (allow warm-up first).
- Recommended runtime flags: `-XX:+UseZGC` (generational is default in 25).

## Time model
- Server simulation runs at a fixed `simHz` = 60 ticks/s.
- Original XPilot physics are per-frame at `framesPerSecond` (map option, default 14 if absent). We preserve game *feel* by scaling: each sim tick advances `dt = gameFPS / simHz` original-frames. All per-frame quantities (velocity add, lifetimes in ticks, fire repeat, fuel use, etc.) are multiplied by `dt`; lifetimes are kept as float "original frames remaining".
- Client renders at vsync, interpolating object positions between the two most recent snapshots (render ~1 snapshot interval behind). Own ship may be extrapolated.

## Coordinates
- `BLOCK_SZ = 35` pixels. World size = mapWidth*35 x mapHeight*35. Map row 0 in the file is the TOP of the world; world y increases upward (as in XPilot), so file row r maps to block y = mapHeight-1-r.
- `edgeWrap` maps wrap toroidally; otherwise world edges are walls.
- Angles: XPilot uses RES=128 headings. We use float radians internally; ship heading quantization not required.

## Map format (common module)
Config/map file grammar per `doc/README.MAPS`: `name: value`, case-insensitive names with internal whitespace ignored, `#` comments, `\multiline: DELIM`, `\override:` prefix. Files are Latin-1. Map data chars per `src/server/map.c` (`Grok_map` area): walls `x` (full), `a s w q` (diagonal triangles — check exact orientation in map.c), `r d f c` (cannons facing), `#` fuel, `*`/`^` treasure/empty treasure (team digits nearby), `!` target, `%` item concentrator, `&` asteroid concentrator, `$` base orientation hints, `_` and `0-9` bases (digit = team), `+ - > <` gravity (pos/neg/clockwise/anticlockwise), `i m j k` current/friction-ish (check), `@ ( )` wormholes (normal/in/out), `A-Z` checkpoints, `.`/space empty, `z`/others: check map.c. Also decorations if present. Unknown chars → empty, with a warning.
All 19 maps in `maps/` must parse; a test asserts each loads with plausible counts (bases > 0, etc.).

## Options
An `Options` object holding typed values with defaults from `cmdline.c` for the options we support; populated from map file (and optional server config/CLI). Unknown options are retained as strings and ignored.

## Architecture
```
common:  map parsing & model, Options, math utils, ship shape(s), protocol (message ids + codecs over ByteBuffer)
server:  World (pools), Simulation.tick(), rules, robots, UDP server (ServerNet), headless main `ServerMain --map maps/default.xp --port 15345`
client:  GLFW window, Renderer (batch, fonts, map VBO), HUD/radar/messages, input→ control bitmask, ClientNet, `ClientMain [--connect host:port | --local --map ...]`
```
`--local` starts an embedded server thread in-process and connects over loopback (same code path, no special casing).

## Protocol (our own, UDP)
- Client→server: CONNECT(name, team pref), INPUT(seq, tick ack, 32/64-bit key bitmask, ...), CHAT(text), DISCONNECT, ACK. Inputs are sent every client frame and include the last few inputs for loss tolerance.
- Server→client: WELCOME(player id, options digest, map — sent reliably in chunks; client parses the same .xp text with the common parser), SNAPSHOT per server tick or every 2nd (configurable): tick number, your ship state (full precision), then view-culled objects (ships, shots, debris/sparks, items, mines, missiles, balls, cannons state, fuel levels, base/target damage), score board deltas, messages. Quantize positions to ints/shorts where it's lossless enough.
- Small reliable-ordered channel (seq+ack+resend) for chat, player join/leave, score events, map transfer. Keep it simple.
- Max datagram 1200 bytes payload; snapshots split by priority if needed (own ship + nearest first).

## Feature milestones
**M1 — playable core:** map loading/render (all wall shapes, fuel, bases, cannons, gravity markers, wormholes, targets, treasures as visible symbols), ship thrust/turn/gravity/wrap, wall collision (crash or bounce per options), shields, fuel use & refuel at fuel stations, firing shots, player/shot collisions, death/explosion debris & thrust sparks, respawn at base, cannons firing, wormholes, gravity sources, scores, chat, robots with simple AI, radar, HUD (fuel, score, messages, scoreboard), team play basics.
**M2:** items (pickup/drop: extra shots, afterburner, cloak, sensor, transporter, rear shot, missiles, mines, ECM, lasers, emergency thrust/shield, tractor beam, autopilot, deflector, hyperjump, phasing, mirror, armor, tanks), balls/treasures & team scoring, targets, checkpoints/race mode, asteroids.
**M3:** polish: configurable keys file, sound (optional), server browser.

## Default controls (from XPilot)
A/S turn left/right, Shift thrust, Return fire, Space shield, Tab? (check `default.c` for full list), M talk, Esc quit. Allow config file `~/.jxpilot/keys` later.

## Definition of done per task
`mvn -q install` from repo root passes, including allocation tests. Testing follows
`docs/TESTING.md` (mandatory): every behavior enumerated and tested with labelled assertions,
each test verified by breaking the behavior, PIT mutation score ≥ 85% on logic packages. Code has brief Javadoc on public types. No TODO stubs left silently—list gaps in the agent's final report.
