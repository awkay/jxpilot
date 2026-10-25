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
- **Revised 2026-10-23.** The original design ran the simulation at a fixed 60 Hz with
  `dt = gameFPS/60`. Three C-fidelity reviews found a whole class of fractional-frame bugs:
  resting wall contact, autofire rate, turn sparks and fuel, shove credit, afterburner spark split,
  coriolis/friction scaling and input granularity.
- **The server simulates at the map's `framesPerSecond`** (default 14). One tick = one C frame,
  and `dt` is exactly 1. The dt-scaling code may remain, but must be exact (a no-op) at dt = 1.
  The tick rate comes from Options, and nothing may hard-code 60 Hz.
- **Snapshots:** the server snapshots every tick by default (divisor 1). Radar is sent every 3rd
  snapshot unless configured otherwise.
- **Client rendering:** the client renders at vsync and interpolates between the two most recent
  snapshots, rendering about one snapshot interval behind (`TickClock`, from snapshot arrivals). The own
  ship (and the camera) is not interpolated: it is dead-reckoned from the newest SELF to the estimated
  server tick with its velocity and an acceleration estimate, and a new SELF's prediction error is blended
  out over a few render frames (60 ms time constant; snap on death and teleport; `ClientWorld.predictSelf`).
  Own-ship prediction with reconciliation (latency-fixes.md §4.1, phase 1c) will replace that path; its
  inputs exist since phase 1b: the owner's PREDICT sections (`KernelSync.body()`), the server's kernel world
  (`KernelSync.world()`) and the predicted clock.
- **Predicted clock:** the client also runs `PredictedClock`, the tick its input is for: ahead of the
  server by the input lead, so each input reaches the server before its tick runs. It starts at
  `ceil((RTT/2 + 30 ms)/T) + 1` ticks over the server's present (the first PING goes out at once), settles
  the excess once after a second of feedback, then holds the server-reported input slack near
  0.5 tick + 2 × jitter by time dilation (±3%), jumping up at once on a starvation report.
- **Client input:** one entry per predicted tick (protocol v2): the input collected while the predicted
  clock runs from N-1 to N is entry N, sent when the clock reaches N, with an early copy on a key change,
  a repeat half a tick later and up to 8 redundant older entries; `taps` keep a key pressed and released
  within a tick. The server applies entry N in tick N (`InputBuffer`); a missing entry holds the last keys
  (a starvation, reported back). C applies input at the frame after it arrives instead (a JXPilot
  deviation, DEVIATIONS-server.md).
- **Goal:** with the same map, options and inputs, the Java server matches the C server frame for
  frame, up to RNG sequences. This is verified against `tools/xpilot-ref` traces.

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
common:  map parsing & model, Options, math utils, ship shape(s), protocol (message ids + codecs over ByteBuffer),
         the own-ship kernel (common.sim: ShipKernel over ShipBody/ShipWorld/KernelEvents; server and client run it)
server:  World (pools), Simulation.tick(), rules, robots, UDP server (ServerNet), headless main `ServerMain --map default --port 15345` (maps bundled as resources)
client:  GLFW window, Renderer (batch, fonts, map VBO), HUD/radar/messages, input→ control bitmask, ClientNet, `ClientMain [--connect host:port | --local --map ...]`
```
`--local` starts an embedded server thread in-process and connects over loopback (same code path, no special casing).
The server module provides it through common's `LocalServer.Provider` (ServiceLoader), so the client has no
compile-time dependency on server code. The server's `GameHost` is `server.host.SimGameHost` (the real
simulation); `StubGameHost` remains only for network tests.

## Server network binding (required, configurable)
Target deployments: a VPN (e.g. a `utunN` WireGuard/corporate tunnel, `10.x` addresses, or
Tailscale `100.x`), a LAN, and a public internet host (IPv4 and/or IPv6).
- Options (settable via CLI or the server config file, never from a map, like `contactPort`):
  `port` (XPilot `contactPort`, default 15345), `bind` (repeatable / comma-list) and `bindWait`.
- `bind` selectors:
  - an IP literal (`10.224.0.16`, `::1`, `0.0.0.0`, `::`);
  - a hostname (resolved at startup);
  - an interface name (`utun4`, `en0`, `tailscale0`);
  - a CIDR (`10.224.0.0/22`, `100.64.0.0/10`), meaning every local address inside it. This
    survives VPN interfaces renumbering (`utun4` becoming `utun5`);
  - `loopback`;
  - `all` (the default).
- **Reply from the address the client contacted.** On multi-homed hosts a wildcard UDP socket
  may send replies with a different source IP (LAN instead of VPN), and clients drop them.
  Java NIO has no IP_PKTINFO. So `all`, interface and CIDR selectors resolve to concrete
  addresses and open **one DatagramChannel per local address**. A peer is pinned to the channel
  it first arrived on, and all replies go out through that channel. A literal `0.0.0.0`/`::`
  bind is allowed only as an explicit opt-in, with a startup warning.
- Selector-based binds rescan interfaces every few seconds. They bind newly appeared matching
  addresses (VPN comes up) and close vanished ones (VPN drops). Peers on a vanished address
  time out normally.
- Startup logs each bound `address:port` with its interface name, so the operator can tell
  players what to connect to. A selector that matches nothing is a startup error, unless
  `bindWait` is set, in which case the server waits for a matching interface (e.g. the VPN
  comes up later).
- Pure parts (selector parsing, CIDR matching, choosing addresses from an interface list,
  diffing on rescan) take an injected interface snapshot and are unit tested. Only the
  `NetworkInterface` enumeration and channel opening are at the edge. There is one loopback
  integration test with two bound addresses (`127.0.0.1` and `127.0.0.2`, or `::1`) that
  proves replies leave from the contacted address.
- Payload stays ≤ 1200 bytes, so it fits inside VPN/IPv6 path MTUs without fragmentation.
- Client: `--connect host[:port]` (IPv6 as `[addr]:port`), and optionally `--bind` for the
  local source address. `--local` runs the embedded server bound to `loopback` only.

## Protocol (our own, UDP)
- Client→server: CONNECT(name, team pref), INPUT(seq, tick ack, 32/64-bit key bitmask, ...), CHAT(text), DISCONNECT, ACK. Inputs are sent every client frame and include the last few inputs for loss tolerance.
- Server→client: WELCOME(player id, options digest, map — sent reliably in chunks; client parses the same .xp text with the common parser), SNAPSHOT per server tick or every 2nd (configurable): tick number, your ship state (full precision), then view-culled objects (ships, shots, debris/sparks, items, mines, missiles, balls, cannons state, fuel levels, base/target damage), score board deltas, messages. Quantize positions to ints/shorts where it's lossless enough.
- Small reliable-ordered channel (seq+ack+resend) for chat, player join/leave, score events, map transfer. Keep it simple.
- Max datagram 1200 bytes payload; snapshots split by priority if needed (own ship + nearest first).

## Feature milestones
**M1 — playable core:** map loading/render (all wall shapes, fuel, bases, cannons, gravity markers, wormholes, targets, treasures as visible symbols), ship thrust/turn/gravity/wrap, wall collision (crash or bounce per options), shields, fuel use & refuel at fuel stations, firing shots, player/shot collisions, death/explosion debris & thrust sparks, respawn at base, cannons firing, wormholes, gravity sources, scores, chat, robots with simple AI, radar, HUD (fuel, score, messages, scoreboard), team play basics.
**M2:** items (pickup/drop: extra shots, afterburner, cloak, sensor, transporter, rear shot, missiles, mines, ECM, lasers, emergency thrust/shield, tractor beam, autopilot, deflector, hyperjump, phasing, mirror, armor, tanks), balls/treasures & team scoring, targets, checkpoints/race mode, asteroids.
**M3:** polish: configurable keys file; sound only if cheap (e.g. a handful of short clips via the JDK's javax.sound or OpenAL from LWJGL, triggered by events the client already receives).

**Goal and scope.** The goal is a playable game: server, client, maps and robots. These are OUT of scope and must not be ported or planned:
- the map editor (`src/mapedit`);
- replay/recording (`src/replay`, `xp-replay`);
- the metaserver and server browser;
- the `contrib` tools;
- the X11/NT/OS2 platform layers;
- the talk-macro and widget/config-menu systems, beyond what playing needs: in-game chat and key bindings.
When a feature's value to play is unclear, the lead decides; ask rather than build.

## Default controls (from XPilot)
A/S turn left/right, Shift thrust, Return fire, Space shield, Tab? (check `default.c` for full list), M talk, Esc quit. Allow config file `~/.jxpilot/keys` later.

## Definition of done per task
`mvn -q install` from repo root passes, including allocation tests. Testing follows
`docs/TESTING.md` (mandatory): every behavior enumerated and tested with labelled assertions,
each test verified by breaking the behavior, PIT mutation score ≥ 85% on logic packages. Code has brief Javadoc on public types. No TODO stubs left silently—list gaps in the agent's final report.
