# JXPilot wire protocol (version 1)

Our own UDP protocol (no XPilot client compatibility). All code lives in
`com.fulcrologic.jxpilot.common.net`; everything below has an allocation-free codec there.

- Byte order: big-endian (Java `ByteBuffer` default).
- Max datagram payload: **1200 bytes** (`Protocol.MAX_PACKET`). Default port 15345.
- Strings: `u8` or `u16` length + Latin-1 bytes; chars > 255 become `?`. Writers truncate to the
  length field and to the space left in the buffer, so a long string never throws. Ship shapes are
  capped at 256 chars on decode (`Msg.MAX_SHAPE_LEN`, C `MSG_LEN`).
- `NO_ID = 0xFFFF`, `NO_TEAM = 0xFF`.

## Datagram header (8 bytes, every packet) — `PacketHeader`

| off | type | field |
|---|---|---|
| 0 | u16 | magic `0x4A58` ("JX") |
| 2 | u8  | protocol version (1) |
| 3 | u8  | packet type (`PacketType`) |
| 4 | u16 | session id (0 until ACCEPT) |
| 6 | u16 | reliable ack: next reliable sequence the sender expects from the peer (cumulative) |

Every packet carries the reliable ack, so INPUT (client, every frame) and SNAPSHOT (server) acknowledge
reliable traffic for free. An idle side sends `ACK` (header only) when `ReliableChannel.consumeAckPending()`.

## Packet types

| id | name | dir | body |
|---|---|---|---|
| 1 | CONNECT | C→S | `Msg.Connect`: u32 nonce, u8 team (0-9 or 255), str8 name, str16 shape. Resent every 250 ms until answered. |
| 2 | ACCEPT | S→C | `Msg.Accept`: u32 nonce, u16 playerId. Header session = new random session id. Re-sent for duplicate CONNECTs and on a backoff schedule until the session is verified (below). |
| 3 | REJECT | S→C | `Msg.Reject`: u32 nonce, u8 reason, str8 text. Header session 0. Never larger than the CONNECT it answers (the text is cut to fit). Reasons: 1 FULL (no slot after kicking a robot, else paused players; or the requested team is full), 2 VERSION, 3 BAD_NAME (empty, or already in use: compared without case after trailing blanks are cut and the name is clamped to 16 Latin-1 chars), 4 OTHER (team play with `teamAssign: no` and no valid team requested, or no team can be picked; C contact.c `Check_queue`). |
| 4 | RELIABLE | both | reliable channel body (below). |
| 5 | INPUT | C→S | input with redundancy (below). |
| 6 | SNAPSHOT | S→C | world snapshot (below). |
| 7 | DISCONNECT | both | `Msg.Disconnect`: u8 reason (0 quit, 1 timeout, 2 kicked, 3 shutdown), str8 text. Send 3 times; also timeout after 10 s of silence. The server never sends it to an unverified session. After a gameDuration game over the server stops 30 * FPS frames later and every peer gets reason 3 (C `End_game`). The server kicks (reason 2) a player paused longer than `maxPauseTime` ("auto-kicked: paused too long"), paused players when a newcomer needs room ("no pause with full game" / "no pause with full team", C `Kick_paused_players`), and a player READY finds no base for ("no free base"). |
| 8 | ACK | both | empty. |
| 9/10 | PING/PONG | both | `Msg.Ping`: u32 id, u64 nanos; PONG echoes exactly these 12 bytes (anything after them is ignored). |

## Connection flow

1. Client sends CONNECT (repeat until ACCEPT/REJECT).
   - A CONNECT whose header has the JX magic but another protocol version gets `REJECT` with reason
     VERSION (if it is at least as large as a bare 14-byte REJECT).
   - More than `maxClientsPerIP` sessions (default 4; an IPv4 address or an IPv6 /64 counts as one source;
     0 = unlimited) get `REJECT` FULL.
   - A CONNECT with a new nonce from an address that already has a session replaces it, but the old
     session is dropped only after the new join succeeded.
2. Server answers ACCEPT with a random 16-bit session id. The session is **unverified** (anti-reflection:
   a CONNECT's source address can be spoofed): until a packet carrying that session id arrives from the
   client's address, the server sends it nothing but ACCEPT — resent after 250, 500 and 1000 ms (4 in
   all), and on each duplicate CONNECT, which does not extend its life — and drops it silently 2 s after
   the last ACCEPT (3.75 s in all). Every ACCEPT (14 bytes) is smaller than a CONNECT (≥ 16 bytes).
   **The client must send something with the session id after ACCEPT**: the JXPilot client answers every
   ACCEPT (the first and each repeat) with a bare `ACK` carrying the session id. (It sends INPUT only once
   it plays, so INPUT cannot be the proof.)
3. On verification the server queues reliable `WELCOME`, then `DATA_CHUNK`s, then `PLAYER_JOIN`,
   `BASE_OWNER` and `SCORE` for every player already in the game, and `TEAM_SCORE` for every team with
   members in team play. The client's own `PLAYER_JOIN` (with `FLAG_SELF`) comes when READY puts it in
   the game.
4. The config blob is the server's **effective configuration as config-file text**
   (`Options.toConfigText()`: every visible map-settable option + unknown options + `mapData`),
   deflated (`ConfigTransfer.encode`). WELCOME gives the uncompressed length, compressed length and CRC32.
   The client reassembles (`ConfigTransfer.Receiver`), inflates, verifies, and parses it with the
   same `ConfigParser`/`WorldMap` code as the server, so both sides have identical maps and options.
   Values round-trip exactly: values that cannot sit on one config line use `\multiline:`, values not
   ending in a newline get the JXPilot-only `\noeol:` prefix (C's grammar cannot express e.g. `Map #2`
   or `"  padded  "` otherwise), and CR before LF is doubled because the reader folds CRLF.
   **Encode once per map load** (≈9 ms for planetx) and share the immutable `Blob`; each client gets its
   own `ConfigTransfer.Sender`. Never encode per client or on the simulation thread.
   A receiver rejects announced lengths outside 0..`ConfigTransfer.MAX_LENGTH` (8 MB); the client treats
   that like a CRC failure (bad config: DISCONNECT and close).
5. Client sends reliable `READY`; the server spawns the player (until then it holds a reserved slot and is
   invisible to everyone; its view centre is a base of its team). The game runs (the simulation ticks,
   robots play) only while a human is in it or with `rawMode`, as C's main loop. Snapshots start once the client has
   acknowledged every reliable message up to and including the initial state (game events queued after
   it do not hold snapshots back); the client ignores snapshots until it has the map.

Liveness and limits (server side, all in time units, independent of the tick rate):
- A verified peer is dropped (DISCONNECT timeout) after 10 s without any packet, **or** when its oldest
  unacknowledged reliable message is older than 10 s, **or** when more than 256 reliable messages are
  waiting in its overflow queue: a peer that sends but never acknowledges cannot grow server memory.
- Packets carrying a known session id are rate limited per session (`sessionPacketRate` 400/s, burst
  `sessionPacketBurst` 800), so many players behind one NAT do not share a budget. Everything else
  (CONNECT, strays, garbage) is limited per source (`ipPacketRate` 400/s, burst `ipPacketBurst` 800; a
  CONNECT that creates a session costs 100 more), keyed by a secret 64-bit hash of the IPv4 address or
  IPv6 /64. Clients should send INPUT on every key change and otherwise at about the tick rate.

## Reliable channel — `ReliableChannel`

Ordered, reliable, small messages (chat, joins, scores, map transfer). One instance per peer.

RELIABLE body: `u8 count`, then `count ×` (`u16 seq`, `u16 len`, `len` bytes); the first byte of each
message is its `ReliableType`.

- Sender: `begin(type)` → write payload → `end()`; `Msg.*.send(ch)` does this and aborts the message
  if writing throws, so the channel never wedges. Strings are truncated to the slot, so a long chat
  line cannot overflow it. When the window is full, messages wait in an **overflow queue** (pooled
  buffers, order preserved) and move into the window as acks arrive — callers never see "window full".
  `write(buf, now)` packs new messages and messages unacked for ≥ the resend timeout, oldest first,
  ≤255 per body. `onAck(ack, now)` releases acknowledged messages.
- Resend timeout: adaptive, RFC 6298 (`srtt + 4·rttvar`, Karn's rule — only messages sent once are
  sampled), initial 150 ms, clamped to [50 ms, 2 s]. Each resend of the oldest unacknowledged message
  doubles it (exponential backoff), still capped at 2 s, until the next RTT sample. Messages go out only
  when the owner services the channel (the server: once per tick, at most `reliablePacketsPerSecond`
  480/s worth of datagrams per tick), so at 14 fps a resend can be up to one tick late.
- `ReliableChannel.nextSequence()`/`allAckedBefore(seq)` wait for a batch to be acknowledged and
  `unackedAgeNanos(now)` measures how long the oldest message has waited (first send).
- Receiver: delivers `seq == expected` immediately plus any buffered successors; buffers up to
  `window-1` ahead; drops older/farther. Sequences are 16-bit and wrap.
- Window: default **32** messages in flight (memory 2 × 32 × 1100 B = 70 KB per peer), configurable per
  instance. Messages ≤ 1100 bytes including the type byte. 32 × 1 KB chunks per round trip moves a
  compressed map (5-40 KB) in a few RTTs, so map transfer needs no special path.

### Reliable message types (`ReliableType`, codecs in `Msg`)

Server → client:

| id | name | body |
|---|---|---|
| 1 | WELCOME | u16 playerId, u8 team, u8 simHz, u8 snapshotHz, u8 flags (1 teamPlay, 2 edgeWrap, 4 timing), f32 gameFps, u32 configLength, u32 configCompressedLength, u32 configCrc32, str8 serverName. *(Server S1 fix round: the server simulates at the map's framesPerSecond, so simHz = gameFps; one tick is one C frame.)* |
| 2 | DATA_CHUNK | u32 offset, raw bytes (≤ 1024) to the end of the message |
| 3 | PLAYER_JOIN | u16 id, u8 team, u8 flags (1 robot, 2 tank, 4 self, 8 paused), f32 power, u8 afterburners, str8 name, str16 shape (empty = default) |
| 4 | PLAYER_LEAVE | u16 id |
| 5 | PLAYER_TEAM | u16 id, u8 team |
| 6 | SCORE | u16 id, f32 score, i16 lives (-1 = unlimited), u8 status (1 dead, 2 paused, 4 waiting, 8 out of lives). Only updates the score table: the client's HUD score popups come from SCORE_OBJECT (12), never from SCORE changes |
| 7 | TEAM_SCORE | u8 team, f32 score |
| 8 | MESSAGE | u8 kind (chat, team chat, private, server, game), u16 from (NO_ID = server), str16 text |
| 9 | BASE_OWNER | u16 baseIndex (`WorldMap.bases()`), u16 playerId. A player owns one base: the client releases any other base of that player (C `Handle_base`); playerId NO_ID releases the base (the server sends BASE_OWNER(oldBase, NO_ID) when a player leaves or changes home) |
| 10 | GAME_EVENT | u8 kind (kill, crash, spawn, round end, target/treasure destroyed, checkpoint), u16 a, u16 b, f32 value. TARGET_DESTROYED: a = the player credited, b = target index. TREASURE_DESTROYED: a = the player who cashed the ball, b = the destroyed treasure's index. CHECKPOINT: a = player, b = the next checkpoint, value = the last lap time in frames (0 before a lap completes). A ball, asteroid or (unowned) explosion kill is a KILL with a = NO_ID unless the ball had an owner. CRASH: a = the player who crashed, b = the other ship in a two-ship crash or NO_ID, value = crash sub-type `Msg.GameEvent.CRASH_*`: 1 wall, 2 target, 3 treasure, 4 cannon, 5 universe, 6 ships, 7 unknown. A shove into a wall is a KILL by the last pusher with the sub-type in value. SPAWN: a = player, b = base index. A KILL or CRASH is always sent before the SCORE_OBJECT / SCORE it causes, in the same tick |
| 11 | PLAYER_THRUST | u16 id, f32 power, u8 afterburners — sent when they change (thrust sparks are generated client-side). `afterburners` is the effective value (C forces MAX_AFTERBURNER for emergency thrust, ship.c:75-77); the server seeds with the f32 power the client receives |
| 12 | SCORE_OBJECT | f32 score (points gained, negative when lost), u16 bx, u16 by (block where they were made), str8 label (C `Send_score_object`: a player name, "[Wall]", "[Shove]", "Cannon", "[Deadliest]"...). Sent only to the player whose score changed; drives the HUD score popups (the client's ScoreObjects stack) |
| 20 | TIMING | race mode only (C `Send_timing`): u16 id, u8 check (the next checkpoint the player must pass, C `pl->check`), u16 round (laps started, 0 before the first checkpoint). Sent for every player in the initial state and whenever a player passes a checkpoint or race progress is reset. The last checkpoint passed is check - 1 (wrapping); the own player's check is the checkpoint the client highlights. Ids 20-26 are the game-mode block |

Client → server:

| id | name | body |
|---|---|---|
| 64 | READY | empty |
| 65 | CHAT | same as MESSAGE (kind/from ignored); XPilot "name:"/"team:" prefixes in the text |
| 66 | SETTINGS | f32 power, turnSpeed, turnResistance, altPower, altTurnSpeed, altTurnResistance, u8 flags (1 autoShield). The client sends it before READY on every join (C sends the settings with PKT_PLAY) and again whenever a power or turn-speed key changes them (C Send_power ...); those keys themselves are never sent |
| 67 | CHANGE_TEAM | u8 team |
| 68 | SHAPE | str16 shape |

## INPUT — `InputHistory` (client) / `InputReader` (server)

`u32 latestSeq, u32 ackTick, u8 count (≤ 8)`, then `count ×` (`u64 keys, i16 aimTurn`) newest first
(entry i has sequence `latestSeq - i`).

Send rate (`ClientConnection.sendInput`, called every render frame): an INPUT goes out at once when the key
mask changes, otherwise every 1/simHz (about the tick rate), and never two closer than 1/120 s (a change
inside that gap waits and goes out with the then-current mask; `aimTurn` of held frames is summed). A
sequence number is used only per datagram sent. Each INPUT carries every entry newer than the snapshot
header's `lastInputSeq` (the newest the server applied), at least 1 and at most 8. After a transport error
(VPN drop, ICMP port unreachable) INPUT, reliable data and ACKs pause for 250 ms.
`keys` is a `ControlKey` mask: bit = `keys_t` value − 1 (KEY_LOCK_NEXT … KEY_DEFLECTOR = 64 keys).
The server applies entries newer than the last applied sequence, oldest first, and derives press/release
edges by comparing consecutive masks. `aimTurn` is a pointer-control turn in u16 angle units (0 = none).
`ackTick` is the newest snapshot tick received.

## SNAPSHOT — `SnapshotWriter` / `SnapshotReader` / `Records`

Default rate: every simulation tick, i.e. the map's framesPerSecond (default 14 Hz; one tick per C frame)
(`Protocol.DEFAULT_SNAPSHOT_DIVISOR` = 1, server option `snapshotDivisor`; WELCOME.simHz and
WELCOME.snapshotHz tell the client).

Header (15 bytes): `u32 tick, u32 lastInputSeq, u8 part, u8 partCount, u8 flags (1 = last part),
u16 viewX, u16 viewY` — the receiver's view centre in half pixels. `tick` T is the number of the sim tick,
one C frame, whose result the snapshot shows: the number the network layer passed to `GameHost.tick(T)`
and on to the simulation, so bursts' `age` and thrust-spark seeds use the same numbers. Parts are numbered
0..63; the client treats a higher part as malformed. If a tick does not fit one datagram it is split into
self-contained parts. Sections are written SELF, SOUND, SHIP, CANNON, FUEL, TARGET, TREASURE, TEMP_WORMHOLE, TIMERS, RADAR, BURST, WRECKAGE, BALL/ASTEROID/CONNECTOR, then SHOT, which fills
the remaining room nearest first; a record that does not fit drops only itself, smaller records of later
sections are still sent.

View-relative positions: the writer encodes `wrapDelta(x - viewX)`; offsets must be within ±16383 px —
the server culls with `SnapshotWriter.inView(x, y)` and encoding anything farther throws (never
clamped). The reader (`SnapshotReader.setWorld(w, h, wrap)`) wraps decoded positions into
[0,W)×[0,H) on wrapping maps. **Client interpolation and extrapolation must use `XMath.wrapDelta`**
between snapshots: an object crossing the seam decodes from e.g. 990 to 5, which is +15 px, not −985.

RADAR is sent in every 3rd snapshot by default (`Protocol.DEFAULT_RADAR_DIVISOR`, server option `radarDivisor`).

Then sections until the end of the datagram: `u8 type, u8 recordSize, u8 variant, u16 count, records`.
Readers stride by the transmitted `recordSize`, so fields can be appended to a record later, and skip
unknown types (`SnapshotReader.known()`).

Quantization (`Wire`): **rel** = i16 half pixels relative to the view centre, wrap-aware (±16383 px);
**pos** = u16 absolute half pixels; **vel16** = i16 1/16 px per original frame; **ang** = u16 (2π/65536);
**frac** = u16 0..1.

| type | section | bytes | layout |
|---|---|---|---|
| 1 | SELF | 47 | u16 id, u16 flags, f32 x, y, vx, vy, heading, fuel, fuelMax, u16 lockId, u8 numTanks, u8 currentTank, u16 respawnFrames (absolute, full precision), u8 power, u8 turnSpeed, u8 turnResistance*255 (C PKT_SELF rounding), u16 lockDist (px, 0 = lock not shown), u8 lockDir (RES), u8 check (next checkpoint), u16 mods (weapon modifier bits: 0 nuclear, 1 full nuclear, 2 cluster, 3 implosion, 4-5 velocity, 6-7 mini, 8-9 spread, 10-11 power, 12 laser stun, 13 laser blind). Fields from power on were appended; a reader of an older 38-byte record gets 0 |
| 2 | SHIP | 14 | u16 id, u16 flags (`ShipFlags`), rel x, rel y, ang heading, vel16 vx, vel16 vy |
| 3 | SHOT | 6 | rel x, rel y, i8 vx, i8 vy (whole px/frame). **variant = harmless << 7 \| kind << 4 \| colour** *(S1 fix round: bit 7 `ShotKind.FLAG_HARMLESS` = this shot cannot hurt the receiver, C frame.c's friendly colour: team immune owner, paused owner, an unowned shot of the receiver's team in team play, or the receiver's own shot with selfImmunity; kinds are 3 bits)* |
| 4 | BURST | 27 | rel x, rel y, vel16 vx, vy, u32 seed, u8 kind, u8 colour, u16 count, u8 minDir, u8 dirSpan (RES), u16 minSpeed, maxSpeed (1/16), u16 minLife, maxLife (frames), u8 age (sim ticks since spawn) |
| 5 | CANNON | 3 | u16 index, u8 flags (dead, phasing, shield) |
| 6 | FUEL | 4 | u16 index, frac level (of 500) |
| 7 | TARGET | 5 | u16 index, u8 flags (dead), frac strength left |
| 8 | RADAR | 3 | u8 x, u8 y (1/256 of the world size, decoded to the cell centre), u8 flags (friendly, self, missile, mine, ball, nuke, asteroid, locked) — whole world |
| 9 | ITEM | 5 | rel x, rel y, u8 item (`Item`) |
| 10 | MINE | 7 | rel x, rel y, u8 flags, u16 owner |
| 11 | MISSILE | 10 | u16 id, rel x, rel y, ang heading, u8 kind (smart/heat/torpedo, 0x80 nuclear), u8 length |
| 12 | BALL | 9 | u16 id, rel x, rel y, u8 team, u16 holder |
| 13 | LASER | 9 | rel x, rel y, ang heading, u16 length, u8 colour |
| 14 | ASTEROID | 7 | rel x, rel y, u8 size, ang rotation |
| 15 | WRECKAGE | 8 | rel x, rel y, u8 shape, u8 size, ang rotation *(server-batch2: the Java server sends wreckage in view every snapshot; shape is C's `info & 0x7F`, size C's 0..255, rotation C's RES rotation in radians)* |
| 16 | ECM | 6 | rel x, rel y, u16 radius |
| 17 | CONNECTOR | 9 | rel x0, y0, x1, y1, u8 kind (0 ball, 1 tractor, 2 pressor, 3 refuel: station to ship, 4 repair: ship to target) |
| 18 | TRANSPORTER | 8 | rel x0, y0, x1, y1 |
| 19 | SELF_ITEMS | 21 | u8 count per `Item` |
| 20 | TREASURE | 3 | u16 index, u8 flags (has ball) |
| 21 | TEMP_WORMHOLE | 5 | u16 bx, u16 by, u8 type |
| 22 | TIMERS | 26 | u16 damaged (frames of ECM damage left), u16 roundDelay (frames, 0 none), i32 timeLeft (seconds, -1 none), u16 destruct (self-destruct frames left, 0 none), u16 thrustLeft, thrustMax, shieldLeft, shieldMax, phasingLeft, phasingMax (C meters; max 0 = no meter), i16 shutdownCount (frames until the server stops after a gameDuration game over, C `ShutdownServer`; -1 none), u16 shutdownDelay (C `ShutdownDelay`, 1000) — the receiver's HUD timers. Fields from destruct on were appended; an older 8-byte record reads as none |
| 37 | SOUND | 2 | u8 event (`SoundEvent` ordinal = C `*_SOUND` index, src/common/audio.h), u8 volume (10..100) — the sounds the receiver hears (below) |

M1 uses 1-8; 9-22 are defined for M2; 37 is SOUND. The game-mode sections are sent as follows (server-modes):
TARGET and TREASURE for map objects in view (target strength left, dead flag; treasure HAS_BALL while its ball
rests in it); TIMERS while any timer is active (round delay while C's `round_delay_send` runs, which lasts one
second past the delay so the client sees 0; time left = the round timer rounded up to seconds with
`maxRoundTime`; ECM damage frames); BALL (id = the server's object handle, team = the team of the ball's
treasure, holder = the connected ship or NO_ID: the client draws the ball connector from it); CONNECTOR kind
BALL only for a ship still *reaching* for a ball with its connector (C `pl->ball`); ASTEROID (size 1-4,
rotation) and WRECKAGE (shape, size 0-255, rotation); RADAR blips with FLAG_BALL (`treasuresOnRadar`) and
FLAG_ASTEROID (`asteroidsOnRadar`); TEMP_WORMHOLE for every temporary (hyperjump) wormhole, whole world,
type = `Wormhole.Type` ordinal (1 IN, 2 OUT). SHIP flags carry HAS_BALL and REPAIRING. Game-mode snapshot section ids
30-36 are reserved for future mode sections (none used yet). A BURST of one particle with no speed and no life is a
position-only point particle (C debris on the XPilot path) that the client tracks across frames. Indices refer to the `WorldMap` lists, which both sides build
identically from the same config text.

### Sounds (SOUND, C `saudio.c`)

The server keeps a queue per human player (`Player.audio`, C `pl->audio`): each event at most once, in the
order first queued, at the loudest volume queued (C `queue_audio`). Simulation code fills it through
`server.sim.Sounds`, at the places C calls `sound_play_*`:
- `Sounds.play(world, x, y, event)` (C `sound_play_sensors`): every human whose larger axis distance
  `max(|dx|, |dy|)` (pixels, not wrapped, as C) is within `range = 525 + 262.5 * sensors` px hears it at
  `(int) max(100 - 100 * dist / range, 10)`;
- `Sounds.playAll(world, event)` (C `sound_play_all`): every human at 100;
- `Sounds.playPlayer(player, event)` (C `sound_play_player`): one human at 100.

Robots never hear sounds, and a player entering the game does not hear its own START (C plays it before
the newcomer's audio request). Part 0 of the player's next snapshot carries the queue as the SOUND section right
after SELF, and the queue is then emptied (C `sound_play_queued` after the frame's objects), so each sound is
sent once; a lost snapshot loses its sounds, as a lost C frame does. A player's queue is emptied when it
enters the game (C `sound_player_init`). The client plays SOUND records only from the newest tick it has
received. On the XPilot path the client asks for audio with `PKT_REQUEST_AUDIO 1` (when its sound is on) and
turns each `PKT_AUDIO` (type, volume) into the same SOUND record.

### Sparks and debris

In C XPilot, sparks and debris are gameplay objects, not just visuals (`src/server/collision.c`,
`walls.c`): debris hitting a ship drains fuel (`2·mass·|v|`) and can kill ("succumbed to an
explosion"); sparks from other ships push you (`Delta_mv` — exhaust "blowing"); with
`sparksWallBounce` your own bounced sparks lose owner immunity and give reactive thrust; balls absorb
and are pushed by sparks (`ballSparkCollisions`); asteroids are hit by debris. So the **server keeps
simulating every particle**; on the wire:

- **Thrust sparks are not sent.** Each sim tick a ship is thrusting (`ShipFlags.THRUSTING` is set only when
  sparks are really emitted, not when cloaked or phased), server and client both call
  `ThrustSparks.computeWire(tick, shipId, x, y, engine offset at the RES heading, heading (`dirFromU16`
  of the wire heading), vx, vy, power (the f32), afterburners, shotLife, fps, hypotenuse, ...)` — a port
  of C `Thrust()` that quantizes position and velocity exactly like the wire first (`wirePos`,
  `wireVel`) — and expand the two resulting bursts with `DebrisBurst.generate`. One tick is one C frame,
  so there is no frame fraction. Power and afterburners come from PLAYER_JOIN and PLAYER_THRUST. The
  server's particles are authoritative; the client's are cosmetic. (Turn sparks, only with `turnThrust`,
  are left to the client the same way.) A client receiving snapshot T after T' generates ticks t in
  (T', T], at most the last 8 (`ClientWorld.MAX_SPARK_TICKS`), each seeded `seed(t, id)`; with one
  snapshot per tick that is exactly one tick per snapshot.
- **Spark inputs are the wire values.** `hypotenuse = (int) hypot(W, H)` (an int, as C's
  `World.hypotenuse`). SHIP records carry exactly the quantized position and velocity. SELF is full
  precision (f32), and `computeWire` quantizes it the same way; the heading comes back as
  `dir = round(heading * RES / 2pi) mod RES` (`ThrustSparkEmitter.resDir`), which is exact for SELF's
  `dir * 2pi/RES` and SHIP's `dir * 512` and agrees with `dirFromU16`.
- **Explosion debris and wall sparks are BURST records**, one per C `Make_debris` call. The server must
  generate its own particles from `Records.Burst.quantize`d parameters so both sides match, with
  `DebrisBurst.hypotenuse` = world diagonal (C's per-particle life clamp). *(S1 fix round: cluster/flak
  fragments are SHOT records of kind CLUSTER and are not also sent as a burst.)* The sink receives each
  particle's speed so the server can apply C's cluster mass `mass / (speed/shotSpeed)²`.
- Particles consumed by collisions on the server may linger briefly on clients (cosmetic). Bursts from
  ticks between snapshots ride in the next snapshot with their `age`; a lost snapshot loses its bursts.

`Rng` is a fixed xorshift32 and the cos/sin tables use `StrictMath`, so every JVM expands identically.

### Size budget (asserted in `SnapshotTest.WireSize`, sizes include the 8-byte packet header)

| scene | bytes | datagrams |
|---|---|---|
| Fight: 15 thrusting ships, 150 shots (3 colour groups), 400 debris as 5 explosion bursts, 15 radar blips | **1386** (1336 without radar) | 2 |
| Typical: 4 ships, 30 shots, 2 bursts, 8 blips | **410** | 1 |
| Radar-heavy: 32 players + 300 objects on radar (332 blips), 8 ships in view | **1184** | 1 |
| Radar-heavy with all 31 other ships in view | **1506** | 2 |

At 30 Hz the fight is ≈ 330 kbit/s per client (at the default 14 Hz tick rate ≈ 155 kbit/s). The original per-particle layout needed ~4.7 KB per snapshot.

## Extending

- New fields: append to a record and bump its `SIZE`; old readers keep working (stride).
- New sections: new type id; old readers skip it.
- New reliable messages: new `ReliableType` id; receivers ignore unknown types.
- Incompatible changes: bump `Protocol.VERSION` (header check rejects mismatches).
