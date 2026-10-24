# Playing on an original XPilot 4.5.5 server

The JXPilot client can join an original C XPilot 4.5.5 server (`xpilots`). Only the client speaks the
C protocol; the JXPilot server never does. The code is in `com.fulcrologic.jxpilot.client.xp`.

## Choosing the protocol

```java
ConnectTarget t = ConnectTarget.parse("xp://host:15345", null);   // or ("host:15345", "xpilot")
GameConnection c = GameConnections.open(t, clientGame, "Nick", Protocol.NO_TEAM, null, seed);
// per frame: c.update(now); game.frame(now, dt); c.sendInput(keys, 0, now); c.flush(now);
```

- `xp://host[:port]` uses the XPilot protocol. `jx://host[:port]` uses the native protocol.
- A plain `host[:port]` follows `--protocol xpilot|jxpilot`. Without the flag it is native.
- Both protocols listen on port 15345 by default.
- `GameConnection` is the protocol-neutral API: start, update, sendInput, flush, sendChat,
  disconnect, state, status and counters. `NativeGameConnection` wraps `ClientConnection`.
  `XpConnection` speaks the C protocol.
- Both connections feed the same `ConnectionListener`, normally `ClientGame`, so the painters, HUD and
  input code are shared.

## How it works

`XpConnection` ports the C client (`client/netclient.c`, contact code in `client/textinterface.c`):

1. CONTACT to the contact port. The reply magic carries the protocol version, which is the lower of
   the server's and ours (0x4501). Servers older than 0x4203 are refused.
2. ENTER_QUEUE (nick, display, host, team), repeated every 2 seconds until an ENTER_GAME reply gives
   the login port. ENTER_GAME itself cannot be used directly: the 4.5.5 server would then connect back
   to the client's host *name*. As in C, the real name is `$XPILOTUSER` or the login name, the host is
   `$XPILOTHOST` or the local host name, and the display is `$DISPLAY` or `:0.0`. The `user`, `host`
   and `display` resources override them, and C's `Fix_real_name` / `Fix_host_name` clean them. There
   is no limit on queueing while the server keeps answering; 12 seconds without a reply ends it.
3. PKT_VERIFY to the login port. The reliable stream answers with REPLY(VERIFY) and MAGIC.
4. The setup arrives on the reliable stream: a header and the run-length compressed block map.
   `XpMap` turns it back into `.xp` text and builds a `WorldMap`. The C numbers of cannons, fuel
   stations, targets and bases (x-major scan order) are mapped to the `WorldMap` list indices.
5. The client sends shape (first 5 tries), PKT_PLAY, power settings (×256), PKT_DISPLAY (view size,
   4 debris colours, sparkProb 1), a PKT_MOTD request, the four PKT_MODIFIERBANK (the
   `modifierBank1..4` resources), PKT_REQUEST_AUDIO (on while sound is on) and the frame rate. It
   repeats them until REPLY(PLAY).
6. Frames follow. `XpFrameDecoder` decodes every C frame into an `XpFrame`. `XpTranslator` then
   encodes it as one native SNAPSHOT body with one tick per C frame, so `ClientWorld` interpolates
   at the server's framesPerSecond. Reliable packets become native PLAYER_JOIN, SCORE, MESSAGE,
   BASE_OWNER and other messages.

Keyboard state goes out as `PKT_KEYBOARD` (change number plus a 9-byte vector; bit = `keys_t`) when
it changes. While the server has not acked the latest change, it is resent at most once per received
frame. After 5 seconds of frames without any send, it goes out again as a keep-alive. Each datagram
carries at most one keyboard packet: the server kicks clients for "macros". Chat goes out as
`PKT_TALK` and is resent every 2 frames until `PKT_TALK_ACK` arrives. Map-object updates are acked
with PKT_ACK_FUEL, PKT_ACK_CANNON and PKT_ACK_TARGET, as C does. Under pointer control the frame's
horizontal mouse movement goes out as `PKT_POINTER_MOVE` (C `Send_pointer_move`, servers above 0x3201).

Socket errors (an ICMP port unreachable, a network that went away) count as "nothing received" or
"not sent", as C's `Sockbuf_read`/`Sockbuf_flush` treat them; the retry limits and timers end the
connection. While verifying, receiving the setup or starting, 20 seconds of silence fail the login
(C `Net_setup`: 10 reads of 2 seconds). While playing, every 10 seconds of silence adds C's "No
response from server" to the messages, and the client keeps waiting as C does; the player leaves with QUIT,
the window close or Command-Q.

Truncated or unknown packets are counted and the rest of the datagram is dropped, as C does. A broken
reliable stream (an unknown type, or a string over its limit) is fatal in C. It closes the connection
with a status line and never throws.

## Packet coverage

| C packet | Handling |
|---|---|
| START / END | frame bracket; old frames dropped (stale); keyboard ack taken; premature ack drops the frame |
| SELF (+ SELF_ITEMS, MODIFIERS) | SELF record: position, velocity, heading, fuel/max, lock, tanks, current tank; flags from own SHIP + status (DEAD if not visible, PAUSED, SELF_DESTRUCT, AUTOPILOT); SELF_ITEMS; modifiers decoded only |
| SHIP | SHIP records; shield/cloak/emergency shield/phased/deflector; velocity derived from the previous frame |
| PAUSED | SHIP with id NO_ID and PAUSED (C sends no id) |
| REFUEL | REFUELING / REPAIRING flag on the ship at the beam end / start; the client draws the beam from the nearest station (target) within 90 px, as C |
| FASTSHOT | SHOT sections per colour and harmless flag (C rule: non-team shots that are not blue are white; team shots, types DEBRIS_TYPES and up, keep their colour and, unless red (the nuclear blink), carry `ShotKind.FLAG_HARMLESS` like the native SHOT), view-area decoding; the client links shots across frames (nearest neighbour per colour, velocity-predicted) and interpolates them |
| DEBRIS (128–255) | one point BURST (1 particle, no speed, no life) per C particle; the client tracks points across frames like shots. The client asks for 4 debris colours (temperature levels, sent as colour `ClientWorld.POINT_LEVEL_BASE + level`) and sparkProb 1 (every particle every frame; the painter does the sparkle) |
| FASTRADAR / RADAR | RADAR records (friendly, ball, missile/mine, asteroid) plus own ship (C adds it too); without the own ship, the ship at the view centre (the one watched) gets a player blip (C `Handle_ship`) |
| FUEL / CANNON / TARGET | FUEL / CANNON / TARGET state (C number → WorldMap index), acked like C |
| ITEM, MINE, MISSILE, BALL, LASER, ASTEROID, WRECKAGE, ECM, CONNECTOR, TRANS | native M2 sections, modelled and drawn (balls and missiles interpolated) |
| WORMHOLE | TEMP_WORMHOLE section, drawn as C does |
| DAMAGED, ROUNDDELAY, TIME_LEFT | TIMERS section: ECM flash, round-delay countdown and red ships, HUD time left |
| LOSEITEM | SELF_ITEMS loseItem; the last value is kept over frames without one (C `lose_item`) |
| DESTRUCT, SHUTDOWN, THRUSTTIME, SHIELDTIME, PHASINGTIME | SELF_DESTRUCT flag and the TIMERS section (self-destruct, shutdown and the three HUD meters) |
| EYES | decoded; the watched ship's radar blip comes from its position (above) |
| AUDIO | SOUND section, played with the bundled clips |
| RELIABLE | C stream: in-order append, gaps and duplicates acked, overlap trimmed |
| REPLY, MAGIC, QUIT, TALK_ACK | connection control |
| PLAYER | PLAYER_JOIN (first after the play reply is the client itself: `onAccepted(id)` then FLAG_SELF); mychar R/T/P → robot/tank/paused; shape + extension; the real name and host go to `onIdentity` (ID mode) |
| SCORE | SCORE (score/100 since 0x4500); lives only with limitedLives; P/W/D → paused/waiting/out of lives |
| TEAM_SCORE | TEAM_SCORE |
| MESSAGE | MESSAGE (talk vs game decided by MessageLog from the text, as C) |
| LEAVE, WAR, SEEK | PLAYER_LEAVE, WAR and SEEK; `ClientGame` keeps the robots' war targets for the score list and adds C's client-side texts ("X left this world.", "declares war on", "has programmed"), on both protocols |
| BASE | BASE_OWNER; the player's previous base released (C `Handle_base`) |
| SCORE_OBJECT | delivered as the native SCORE_OBJECT (score / 100 since 0x4500, whole points before; block; label), so the HUD score popups match the C client |
| TIMING | TIMING: race progress (score list, next checkpoint) |
| MOTD | MOTD pieces, shown in the MOTD panel |
| STRING | decoded and ignored |

## Running the C server for the client

`client/scripts/xp-c-server.sh` runs the `xpilot-ref:4.5.5` image (built by
`tools/xpilot-ref/build.sh`) with its UDP ports published on 127.0.0.1:

```sh
client/scripts/xp-c-server.sh --map default --robots 3 --seconds 120
client/scripts/xp-c-server.sh --map teamball --robots 3 -- +restrictRobots
```

- The contact port is 15345. Player ports are pinned to 15346–15353 with `-clientPortStart` and
  `-clientPortEnd`, and all of these ports are published.
- This works on Docker Desktop for Mac. The server replies to the source address of the contact
  packet. It later `connect()`s the player socket to that address, using the source port of the first
  packet on the player port. Docker's port publishing presents every host flow from the same gateway
  address, so both checks pass.
- `-rawMode` is always on, so robots play before a human joins.
- The container is named `xpilot-ref-compat-*` and runs with `--rm`. It has an in-container
  `timeout` and a host deadline that stops only that container.

Headless check, which prints the map, the own ship, the ships, the messages and the counters:

```sh
java -cp common/target/classes:server/target/classes:client/target/classes \
  com.fulcrologic.jxpilot.client.xp.XpClientSim --connect xp://127.0.0.1:15345 --seconds 30
```

## Known gaps

- The C protocol caps the view at 1024×1024 around the ship. Objects outside it are not sent.
- C sends no velocity for shots or debris. The client links them across frames and interpolates, so
  they move smoothly; a shot or particle seen in only one frame still shows for that frame only.
- Paused ships have no id on the wire, so their shapes and names are unknown.
- Server versions 0x4203–0x44ff are supported in the decoders but were not tested live.
