# JXPilot wire protocol (version 2)

Our own UDP protocol (no XPilot client compatibility). All code lives in
`com.fulcrologic.jxpilot.common.net`; everything below has an allocation-free codec there.

Version 2 (`Protocol.VERSION`, latency phase 1b, docs/latency-fixes.md §4.3/§4.4) tags every input with the
tick it is for, adds the input slack and starvation to the snapshot header, the owner's PREDICT
sections and the kernel world transfer (KERNEL_CONFIG, KERNEL_CHUNK, KERNEL_STATE). A version 1 peer's CONNECT gets
REJECT VERSION (see "Other versions" below). The C-server path (`client.xp`, the XPilot protocol) is untouched.

- Byte order: big-endian (Java `ByteBuffer` default).
- Max datagram payload: **1200 bytes** (`Protocol.MAX_PACKET`). Default port 15345.
- Strings: `u8` or `u16` length + Latin-1 bytes; chars > 255 become `?`. Writers truncate to the
  length field and to the space left in the buffer, so a long string never throws. Ship shapes are
  capped at 512 chars on decode (`Msg.MAX_SHAPE_LEN`, C `2*MSG_LEN`, the size of C's shape buffers), so
  a canonical shape (`ShipShape.toWireString()`, C `Convert_ship_2_string` buf + ext, each under 256)
  always fits.
- `NO_ID = 0xFFFF`, `NO_TEAM = 0xFF`.

## Datagram header (8 bytes, every packet) — `PacketHeader`

| off | type | field |
|---|---|---|
| 0 | u16 | magic `0x4A58` ("JX") |
| 2 | u8  | protocol version (2) |
| 3 | u8  | packet type (`PacketType`) |
| 4 | u16 | session id (0 until ACCEPT) |
| 6 | u16 | reliable ack: next reliable sequence the sender expects from the peer (cumulative) |

Every packet carries the reliable ack, so INPUT (client, every frame) and SNAPSHOT (server) acknowledge
reliable traffic for free. An idle side sends `ACK` (header only) when `ReliableChannel.consumeAckPending()`.

**Other versions.** A peer drops every datagram whose version byte is not its own, except this one rule, so a
player of another release learns why nothing happens. Every version so far has this header and the REJECT body
(`u32 nonce, u8 reason, str8 text`). A server or lobby answers a CONNECT (a lobby also a LOBBY_HELLO) whose
version byte differs with REJECT VERSION written with **the peer's** version byte, echoing its nonce, never larger
than the request: "client too old: protocol v2" to an older peer, "server too old: protocol v2" to a newer one
(27 characters: a typical v1.1.x CONNECT, with its name, real name and host name, has room for them; a shorter
one gets the text cut, like any REJECT). A client that is connecting (or in a lobby) accepts a REJECT of any version
byte whose nonce is its CONNECT's and shows "server runs an older (newer) JXPilot protocol (vN)". A v1.1.x client
shows the server's text.

## Packet types

| id | name | dir | body |
|---|---|---|---|
| 1 | CONNECT | C→S | `Msg.Connect`: u32 nonce, u8 team (0-9 or 255), str8 name, str16 shape, then optionally str8 real name and str8 host name (C `Connect_param` real/host; the JXPilot client sends the login name, or `--user`, and the local host name; absent or cut short reads as empty). Resent every 250 ms until answered. The shape is the ship the player wears for the whole session (C takes PKT_SHAPE only at login); the JXPilot client sends it in C's canonical form (`ShipShape.toWireString`, C `Convert_ship_2_string`). The server applies C's shape limits (`Parse_shape_str`); an empty or invalid shape gives the built-in ship, and without `allowShipShapes` everyone wears `defaultShipShape`. |
| 2 | ACCEPT | S→C | `Msg.Accept`: u32 nonce, u16 playerId. Header session = new random session id. Re-sent for duplicate CONNECTs and on a backoff schedule until the session is verified (below). |
| 3 | REJECT | S→C | `Msg.Reject`: u32 nonce, u8 reason, str8 text. Header session 0. Never larger than the CONNECT it answers (the text is cut to fit). Reasons: 1 FULL (no slot after kicking a robot, else paused players; or the requested team is full), 2 VERSION, 3 BAD_NAME (empty, or already in use: compared without case after trailing blanks are cut and the name is clamped to 16 Latin-1 chars), 4 OTHER (team play with `teamAssign: no` and no valid team requested, or no team can be picked; C contact.c `Check_queue`), 5 LOCKED ("game locked": an operator locked the game with `/lock 1`, C `E_GAME_LOCKED`; checked first), 6 QUEUED (the game is full: the player waits in the login queue, C contact.c; the text is "queued at position N" and a u16 position (1 = next) follows it). A queued client keeps its place by resending its CONNECT (same nonce) every second; each is answered with QUEUED and the current position, a place nobody asks for in 30 seconds is dropped, and when a base frees up the first waiting player is admitted (a slot is reserved for 30 seconds) and its next CONNECT is accepted. Players also wait while others are queued, the queue holds 20, two per address. 7 LOBBY (a lobby server, see "Lobby" below: this address is in no game yet; the client asks for the map list and sends CONNECT again after choosing; the text is cut to fit like any REJECT). |
| 4 | RELIABLE | both | reliable channel body (below). |
| 5 | INPUT | C→S | input with redundancy (below). |
| 6 | SNAPSHOT | S→C | world snapshot (below). |
| 7 | DISCONNECT | both | `Msg.Disconnect`: u8 reason (0 quit, 1 timeout, 2 kicked, 3 shutdown), str8 text. Send 3 times; also timeout after 10 s of silence. The server never sends it to an unverified session. After a gameDuration game over the server stops 30 * FPS frames later and every peer gets reason 3 (C `End_game`). The server kicks (reason 2) a player paused longer than `maxPauseTime` ("auto-kicked: paused too long"), paused players when a newcomer needs room ("no pause with full game" / "no pause with full team", C `Kick_paused_players`), and a player READY finds no base for ("no free base"). |
| 8 | ACK | both | empty. |
| 9/10 | PING/PONG | both | `Msg.Ping`: u32 id, u64 nanos; PONG echoes exactly these 12 bytes (anything after them is ignored). The JXPilot client sends a PING every second while playing (its send time in the nanos) and takes the PONG echoing its newest PING as an RTT sample (the HUD's net diagnostics line). |
| 11 | LOBBY_HELLO | C→S | `LobbyMsg.Hello`: u32 nonce, zero padded so the whole datagram is at least 64 bytes. See "Lobby". |
| 12 | LOBBY_INFO | S→C | `LobbyMsg.Info`: u32 nonce (of the HELLO), u64 cookie, str8 lobby name (at most 43 characters: the datagram is never larger than 64 bytes). |
| 13 | LOBBY_LIST_REQUEST | C→S | `LobbyMsg.ListRequest`: u64 cookie, u32 version of the list the client holds (0 = none). |
| 14 | LOBBY_LIST | S→C | `LobbyMsg.ListPage`: one page of the map list. |
| 15 | LOBBY_SELECT | C→S | `LobbyMsg.Select`: u64 cookie, u16 map id. |
| 16 | LOBBY_JOIN | S→C | `LobbyMsg.Join`: u16 map id, u8 result, str8 text. |

## Connection flow

1. Client sends CONNECT (repeat until ACCEPT/REJECT).
   - A CONNECT whose header has the JX magic but another protocol version gets `REJECT` with reason
     VERSION (if it is at least as large as a bare 14-byte REJECT).
   - More than `maxClientsPerIP` sessions (default 2, as C; an IPv4 address or an IPv6 /64 counts as one source;
     0 = unlimited) get `REJECT` LOCKED with the text "too many players from your address" (C
     `Check_max_clients_per_IP` answers E_GAME_LOCKED).
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
   members in team play, then the `KERNEL_STATE` differences (if any) and the `KERNEL_CONFIG` blob as
   `KERNEL_CHUNK`s (protocol v2). The client's own `PLAYER_JOIN` (with `FLAG_SELF`) comes when READY puts it
   in the game.
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
   it do not hold snapshots back); the client ignores snapshots until it has the map. The client's first
   PLAYING flush sends a PING (the initial input lead needs the RTT); its inputs start with the first
   snapshot.

Liveness and limits (server side, all in time units, independent of the tick rate):
- A verified peer is dropped (DISCONNECT timeout) after 10 s without any packet, **or** when its oldest
  unacknowledged reliable message is older than 10 s, **or** when more than 256 reliable messages are
  waiting in its overflow queue: a peer that sends but never acknowledges cannot grow server memory.
- Packets carrying a known session id are rate limited per session (`sessionPacketRate` 400/s, burst
  `sessionPacketBurst` 800), so many players behind one NAT do not share a budget. Everything else
  (CONNECT, strays, garbage) is limited per source (`ipPacketRate` 400/s, burst `ipPacketBurst` 800; a
  CONNECT that creates a session costs 100 more), keyed by a secret 64-bit hash of the IPv4 address or
  IPv6 /64. The JXPilot client sends about two INPUTs per tick plus one per key change (INPUT below).

## Lobby (lobby servers)

A lobby server (`ServerMain --lobby`, `docs/LOBBY-SPEC.md`) offers many maps on **one UDP port**. It runs the games
of the maps in-process and routes each client to the game it chose, by the client's source address and port, so
the clients and the games need nothing but this port. The lobby messages exist only in the native protocol; a
game server that is not a lobby never sends them and drops them as malformed.

### Flow

1. The client sends CONNECT as for any server. A lobby answers a CONNECT from an address it does not route with
   `REJECT` reason **LOBBY** (7), carrying the CONNECT's nonce (and a text no longer than fits, so the reply is never
   larger than the request). A client that does not know lobbies shows the text as a refusal.
2. The client sends `LOBBY_HELLO` (a nonce, padded to 64 bytes) every 250 ms until `LOBBY_INFO` echoes the nonce.
   INFO carries a **cookie**: the lobby's secret-keyed hash of the sender's address (an IPv4 address or an IPv6 /64,
   as `SourceKeys`), the same for every port of the address. Only a host that receives at its address can know it.
3. The client sends `LOBBY_LIST_REQUEST` with the cookie and the version of the list it holds, at once and then every
   second (keep-alive). The lobby drops requests with a wrong cookie. It answers a request whose version is not
   current with every page of the list (not more often than every 200 ms per address); one whose version is current
   with a single empty page of that version, so a silent lobby can be told from a quiet list. Whenever the list
   changes (a map starts or stops, a human count changes) the lobby pushes all pages to every address that asked in
   the last 15 seconds; a client that lost a page keeps asking with its old version and gets the list again.
4. `LOBBY_LIST` pages: `u32 version` (1 and up, bumped on every change; compared as a wrapping counter), `u16 page`,
   `u16 pageCount`, `u8 count`, then per map `u16 id` (stable while the lobby runs), `u8 flags` (1 = a game of it is
   running), `u16 humans`, `u16 maxPlayers` (the map's bases), `u16 width`, `u16 height` (blocks), `str8 name`,
   `str8 title`, `str8 author` (each cut to 60 characters). A page holds at most 1100 bytes; a list is complete
   when all pages of one version have arrived (`LobbyListAssembler`: any order, duplicates and older versions are
   ignored). The lobby sends maps in display order (running maps first, then by name ignoring case); the id, not the
   position, names a map. At most 2048 maps are offered.
5. The player chooses: `LOBBY_SELECT` with the cookie and the map id, resent every 500 ms until `LOBBY_JOIN`
   answers. The client sends no LIST_REQUEST meanwhile, not even to confirm a list the lobby pushes because the
   chosen map started (1.1.1 and older clients did, which could undo the JOIN; see the grace rule below). JOIN results: 0 OK (the address is routed to the map's
   game; send CONNECT now), 1 STARTING (the game is being started: keep asking), 2 UNKNOWN_MAP, 3 FAILED (the text
   says why; the next SELECT tries again). Starting a map loads and encodes it on a worker thread, never on the
   socket thread.
6. After OK the client sends CONNECT with a **new nonce** from the same address and port. The lobby forwards it, and
   every later datagram of that address that is not a lobby message, untouched to the game, whose connection layer
   (`ServerNet`) answers through the lobby's socket on the channel the client arrived on. From here on the
   protocol is the one above, and the game sees a client that contacted it directly. A route lasts while the address
   keeps sending: it is dropped after 20 seconds of silence and when its game ends.
7. When a game's last human leaves (C quit-when-empty, per map) the game stops: its remaining peers get
   DISCONNECT (shutdown), the routes go, and the map is idle in the list again. A client whose CONNECT then reaches
   the lobby unrouted gets REJECT LOBBY again and returns to the list. The lobby process keeps running.

A LIST_REQUEST or SELECT with the right cookie from an address that has a route means the client is back in the
lobby: the route is dropped first. Exception (grace rule): a LIST_REQUEST for a route younger than 2 seconds that
has not yet carried a game datagram is ignored (not answered, the route kept). It is a list confirm that crossed the
SELECT on the way, and dropping the route for it would bounce the client's CONNECT with REJECT LOBBY. A HELLO never
changes a route (anyone can send one for any address).

### Anti-reflection and limits

- Only HELLO and CONNECT are answered without the cookie, each with a reply no larger than the request; every
  larger reply (LIST) goes only to an address that quoted its cookie. The keep-alive page is 17 bytes.
- Datagrams of an address without a route are rate limited per source (`ipPacketRate`/`ipPacketBurst`, the same
  budget as a game server's strays); `denyHosts` applies to them too. Routed datagrams are the game's business
  (its per-session limits and `maxClientsPerIP`).
- At most 1024 addresses are kept as list subscribers; each game queues at most 512 datagrams for its loop thread and
  drops the rest.

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
| 1 | WELCOME | u16 playerId, u8 team, u8 simHz, u8 snapshotHz, u8 flags (1 teamPlay, 2 edgeWrap, 4 timing, 8 radarBlinked: radar records are already blinked; only the XPilot-protocol client sets it for itself), f32 gameFps, u32 configLength, u32 configCompressedLength, u32 configCrc32, str8 serverName. *(Server S1 fix round: the server simulates at the map's framesPerSecond, so simHz = gameFps; one tick is one C frame.)* |
| 2 | DATA_CHUNK | u32 offset, raw bytes (≤ 1024) to the end of the message |
| 3 | PLAYER_JOIN | u16 id, u8 team, u8 flags (1 robot, 2 tank, 4 self, 8 paused), f32 power, u8 afterburners, str8 name, str16 shape (C's canonical form, `Convert_ship_2_string`; empty = the built-in default ship), str8 real name, str8 host name (C `Fix_real_name` / `Fix_host_name` applied: "X" / "xxx.xxx" when the client sent none; robots and tanks get `robotRealName`/`robotHostName` and `tankRealName`/`tankHostName`; a long shape is cut so the names fit; old readers stop after the shape). A detached fuel tank is a pseudo player with flag 2, the name "NAME's tank" and the `tankShipShape` (with `allowShipShapes`, else `defaultShipShape`); it owns no base |
| 4 | PLAYER_LEAVE | u16 id |
| 5 | PLAYER_TEAM | u16 id, u8 team |
| 6 | SCORE | u16 id, f32 score, i16 lives (-1 = unlimited), u8 status (1 dead, 2 paused, 4 waiting, 8 out of lives). Only updates the score table: the client's HUD score popups come from SCORE_OBJECT (12), never from SCORE changes |
| 7 | TEAM_SCORE | u8 team, f32 score |
| 8 | MESSAGE | u8 kind (chat, team chat, private, server, game), u16 from (NO_ID = server), str16 text |
| 9 | BASE_OWNER | u16 baseIndex (`WorldMap.bases()`), u16 playerId. A player owns one base: the client releases any other base of that player (C `Handle_base`); playerId NO_ID releases the base (the server sends BASE_OWNER(oldBase, NO_ID) when a player leaves or changes home) |
| 10 | GAME_EVENT | u8 kind (kill, crash, spawn, round end, target/treasure destroyed, checkpoint), u16 a, u16 b, f32 value. TARGET_DESTROYED: a = the player credited, b = target index. TREASURE_DESTROYED: a = the player who cashed the ball, b = the destroyed treasure's index. CHECKPOINT: a = player, b = the next checkpoint, value = the last lap time in frames (0 before a lap completes). A ball, asteroid or (unowned) explosion kill is a KILL with a = NO_ID unless the ball had an owner. CRASH: a = the player who crashed, b = the other ship in a two-ship crash or NO_ID, value = crash sub-type `Msg.GameEvent.CRASH_*`: 1 wall, 2 target, 3 treasure, 4 cannon, 5 universe, 6 ships, 7 unknown. A shove into a wall is a KILL by the last pusher with the sub-type in value. SPAWN: a = player, b = base index. A KILL or CRASH is always sent before the SCORE_OBJECT / SCORE it causes, in the same tick |
| 11 | PLAYER_THRUST | u16 id, f32 power, u8 afterburners — sent when they change (thrust sparks are generated client-side). `afterburners` is the effective value (C forces MAX_AFTERBURNER for emergency thrust, ship.c:75-77); the server seeds with the f32 power the client receives |
| 12 | SCORE_OBJECT | f32 score (points gained, negative when lost), u16 bx, u16 by (block where they were made), str8 label (C `Send_score_object`: a player name, "[Wall]", "[Shove]", "Cannon", "[Deadliest]"...). Sent only to the player whose score changed; drives the HUD score popups (the client's ScoreObjects stack) |
| 13 | SEEK | u16 programmerId, u16 robotId, u16 soughtId — a player's ECM reprogrammed a robot to hunt someone (C `Send_seek`, item.c:1207), sent to everyone; the client logs "PROGRAMMER has programmed ROBOT to seek SOUGHT." (C `Handle_seek`) |
| 20 | TIMING | race mode only (C `Send_timing`): u16 id, u8 check (the next checkpoint the player must pass, C `pl->check`), u16 round (laps started, 0 before the first checkpoint). Sent for every player in the initial state and whenever a player passes a checkpoint or race progress is reset. The last checkpoint passed is check - 1 (wrapping); the own player's check is the checkpoint the client highlights. Ids 20-26 are the game-mode block |
| 28 | MOTD | u32 offset, u32 total size, bytes: a piece of the server's message of the day (C `motdFileName`, Latin-1, at most 30 KB). *(server-batch2 block 28-30.)* The server sends all pieces once, with the first event drain after the initial state, when it has a MOTD; the client shows it (C's MOTD viewer) once every piece is in. Old clients skip it |
| 31 | PLAYER_ALLIANCE | u16 id, u8 mark (Latin-1): the alliance mark of player id as this receiver sees it, C `Send_score`'s alliance character (netserver.c:1670-1678): ' ' not in an alliance, '+' in the receiver's own alliance (when `announceAlliances` is off; other alliances then show ' '), '0'-'9' the alliance number (when `announceAlliances` is on). Sent when a player's alliance changes (C resends the score then); when the receiver's own alliance changes it gets every player's mark again (its '+' marks may all have changed), and after an operator changes `announceAlliances` every player's mark goes to everyone. Also in the initial state for players in alliances (every player after a resync). The client shows it in the score list's team column outside team play and treats equal non-blank marks as friends (blue ship, friendly lock). Ids 32-33 are reserved for server-commands |
| 48 | KERNEL_CONFIG | u32 version, u32 length, u32 compressedLength, u32 crc32: a kernel config blob (`common.config.KernelConfig`) follows as KERNEL_CHUNKs, deflated like the config. Raw: `u32 textLength`, the config lines of every option the ship kernel's world depends on (`KernelConfig.OPTIONS`, including the options a player's SHOT moves by (`KernelConfig.SHOT_MOTION`: shotLife, shotsGravity, shotsWallBounce, maxObjectWallBounceSpeed, objectWallBounceBrakeFactor, objectWallBounceLifeFactor, predictableBounces, allowPlayerKilling). Each value is the one the simulation follows (`GameHost.kernelOptionValue`): allowPlayerKilling is sent as the crash masks apply it, since C applies a /set of it only at the next `Move_init` and never restores KILLING_SHOTS. Any /set re-encodes the blob, and it is a new version only when the bytes differ, visible or not), `u32 runs`, runs × (`u32 index, u16 length, chars`): the mapData characters the client's map hides (C `Packet_map`: gravity, wormholes, concentrators, friction), as a patch over the config's mapData. Sent to every connection after its initial state, and again (a new version) when an operator /sets a kernel option; a newer version abandons an unfinished older transfer. The client builds `KernelWorld.of` from it, exactly the server's kernel world (`KernelConfigParityTest`, all shipped maps). The owner thus learns hidden gravity and wormholes; see latency-fixes.md §4.15 |
| 49 | KERNEL_CHUNK | as DATA_CHUNK, for the kernel config blob |
| 50 | KERNEL_STATE | u32 tick, u8 n, n × (u8 kind, u16 a, u16 b, u16 c, i32 d): run-time state the kernel reads that the map does not give. kind 1 BLOCK (a bx, b by, c block type, d item id: cannons and targets destroyed and revived, temporary wormholes opened and closed), 2 CANNON (a index, c 1 phased), 3 TARGET (a index, d team), 4 WORM (a index of a temporary wormhole, b bx, c by, d type), 5 WORM_COUNT (d the map's plus the temporary wormholes), 6 RESET (forget earlier entries; what follows is everything that differs from the map as loaded). The server scans once per tick (after the sim tick) and sends the changes with the events, one message per scanned tick: `tick` is the net tick whose snapshot first shows the state (the world is as the entries say from that snapshot on). The reliable channel can deliver a message before or after that snapshot, or an RTO later; the client exposes the newest tick as `KernelSync.stateTick()`. A newcomer gets the differences in its initial state, tagged with the current tick, always at least one (possibly empty) message; a connection the change log (4096 entries) ran past gets RESET and the differences. Temporary wormhole blocks are tracked while they differ from the map and dropped once their restore is logged. Wormholes' last destination and countdown are not sent (their destination is random) |
| 40 | WAR | u16 robotId, u16 killerId: a robot's war target changed (C `Send_war`, the id is C's PKT_WAR); killerId NO_ID ends the war. The client keeps the target (C `war_id`, also set by SEEK, cleared when the target leaves), shows it in the score list as " (NAME)" after the robot's name and, for a new target, logs "ROBOT declares war on KILLER." (C `Handle_war`). Sent to everyone as C `Robot_war` does: when a robot declares war on its killer, and (NO_ID) when a robot kills the player it was at war with; the initial state sends it for every robot already at war |

Client → server:

| id | name | body |
|---|---|---|
| 64 | READY | empty |
| 65 | CHAT | same as MESSAGE (kind/from ignored); XPilot "name:"/"team:" prefixes in the text. A line starting with '/' is a talk command (C `Receive_talk`/`Handle_player_command`: /advance /ally /get /help /kick /lock /password /pause /queue /reset /set /team /version); replies come back as MESSAGE kind server ending in " [*Server reply*]" |
| 66 | SETTINGS | f32 power, turnSpeed, turnResistance, altPower, altTurnSpeed, altTurnResistance, u8 flags (1 autoShield). The client sends it before READY on every join (C sends the settings with PKT_PLAY) and again whenever a power or turn-speed key changes them (C Send_power ...); those keys themselves are never sent |
| 67 | CHANGE_TEAM | u8 team. Handled exactly as the talk command "/team N" (C has no team-change packet; the C client changes teams with that command) |
| 68 | SHAPE | str16 shape. Ignored: the shape comes with CONNECT (C accepts PKT_SHAPE only before the player is in the game) |
| 15 | DISPLAY | u16 viewWidth, u16 viewHeight: the world area the client shows, in world pixels (C PKT_DISPLAY's `view_width`/`view_height`: window size times scaleFactor). Sent before READY on every join and again whenever it changes (resize, scale swap). The server limits each axis to 384..1024 (C `Receive_display`, MIN/MAX_VIEW_SIZE) and uses it for the HUD lock's in-view test and for culling the receiver's snapshots; until it arrives the view is 768 x 768 (DEF_VIEW_SIZE) |
| 14 | MODIFIER_BANK | u8 bank (0..3), str8 text: one of the client's weapon modifier banks as C text (the `modifierBank1..4` resources, e.g. `FN C V2 X4 LS`; C PKT_MODIFIERBANK). The client sends all four before READY on every join (C sends them once at login, after PKT_DISPLAY). The server parses the text as C `Receive_modifier_bank` into the player's bank, before READY (kept for the spawn) or in play: nothing without allowModifiers; `N` nuclear and `FN` full nuclear need allowNukes, `C` cluster allowClusters, `LS`/`LB` stun/blind laser allowLaserModifiers (the char after `L` is always consumed); `I` implosion, `V#` velocity 0..3, `X#` mini 2..4 (stored 1..3), `Z#` spread 0..3, `B#` power 0..3 (a number out of range is the minimum); other chars are skipped, case is ignored, the text ends at a NUL. A bank above 3 is ignored; a text of 80 chars or more (C MAX_CHARS string overflow) is dropped |

DISPLAY and MODIFIER_BANK are client → server messages although their ids are below 64 (13 is SEEK).

## INPUT — `InputHistory` (client) / `InputReader` (server)

`u16 seq, u32 newestTick, u8 count (1..8)`, then `count` entries newest first (entry i is for tick
`newestTick - i`), each `u8 flags` (1 keys follow, 2 taps follow, 4 aimTurn follows; other bits are malformed)
and only those fields: `u64 keys` (absent: the next-newer entry's keys, 0 for the newest), `u64 taps` (absent: 0),
`i16 aimTurn` (absent: 0). `seq` counts the client's INPUT datagrams (u16, wrapping). Eight entries of one held
mask are 23 bytes; keys changing every tick, 79.

- `keys` is a `ControlKey` mask: bit = `keys_t` value − 1 (KEY_LOCK_NEXT … KEY_DEFLECTOR = 64 keys).
- `taps` are keys that went down and came up again within the tick, so a quick tap is not lost. The server
  applies `keys | taps`, then `keys` (`Keys.apply` twice): the tap gives its press and its release edge.
- `aimTurn` is pointer control's horizontal mouse movement in window pixels (C `PKT_POINTER_MOVE`, 0 = none),
  summed over the tick. The server applies it when the tick's entry is applied, as C `Receive_pointer_move`:
  ignored in hover pause, the autopilot switched off, `turnVel -= sign * clamp(|movement| * turnSpeed / 64)`
  with the clamp 4..64 under a turn resistance, else 0..5 * RES.

**Ticks** are the client's predicted ticks (`PredictedClock`), running ahead of the server by the input lead.
The input collected while the predicted clock runs from N-1 to N is entry N, final when the clock reaches N.

**Sending** (`ClientConnection.sendInput`, called every render frame, only while PLAYING and once a snapshot has
started the clock):

- when the predicted tick passes the last final one, the entry is set and sent at once (never held back);
- a key change within the tick sends an early copy of the next entry, as collected so far, once per tick and
  not within 1/120 s of the previous send (later changes go with the next send);
- a quarter tick after the final send the INPUT is repeated once (a second copy before the deadline:
  redundancy only helps while the tick is still ahead), unless the slack estimate is at least 1.25 ticks, when
  the next final send is that second copy;
- every INPUT carries every entry newer than the newest snapshot's `inputTick`, at least 1 and at most 8;
  ticks the clock skipped hold the previous keys;
- after a transport error (VPN drop, ICMP port unreachable) INPUT, reliable data and ACKs pause for 250 ms.

**Server** (`InputBuffer`, one per connection, a 32-entry ring by tick): every tick T, before the sim runs,
the host gets entry T (`GameHost.input(id, T, keys, taps, aim)`). A missing entry repeats the last applied
keys without taps or aim and counts a *starvation* (not before the first entry is applied). An entry for a
tick already run is dropped (counted *late* when that tick starved); one more than simHz ticks ahead is
dropped. A copy of an entry from a datagram sent before the one that wrote it (`seq`, compared modulo 2^16)
is ignored: reordering, typically an early copy arriving after the final send of the same tick, which carries
the same newest tick. A copy from the same or a later datagram replaces it (an early copy revised by the final
send). A copy arriving after its tick ran is counted late, once, when the tick starved or applied a different
copy (a final send that lost to its early copy).

The **input slack** is the smallest margin, in 1/16 tick, by which entries arrived before their tick over the
last second: the margin of the copies that delivered what the server applies (an entry's first copy, a later
copy that changes it, and the first late copy of a starved tick, negative). Repeats of what is stored do not
count: they arrive later by design.

## SNAPSHOT — `SnapshotWriter` / `SnapshotReader` / `Records`

Default rate: every simulation tick, i.e. the map's framesPerSecond (default 14 Hz; one tick per C frame)
(`Protocol.DEFAULT_SNAPSHOT_DIVISOR` = 1, server option `snapshotDivisor`; WELCOME.simHz and
WELCOME.snapshotHz tell the client).

Header (16 bytes): `u32 tick, u32 inputTick, i8 inputSlack, u8 part, u8 partCount, u8 flags, u16 viewX,
u16 viewY`.

- `inputTick`: the tick whose input entry the server applied in this tick: T itself, or on a starvation the
  tick of the held entry.
- `inputSlack`: the input slack (above) in 1/16 tick, clamped to -127..127; -128 = no arrival in the last
  second.
- `flags`: bit 0 = last part; bits 1-7 = this receiver's starvation count mod 128 (ticks that ran without its
  entry). The client compares it with the previous snapshot's: a change means its lead is too short. A
  counter rather than a flag, so a lost snapshot does not lose the report.
- `viewX`, `viewY`: the receiver's view centre in half pixels. `tick` T is the number of the sim tick,
one C frame, whose result the snapshot shows: the number the network layer passed to `GameHost.tick(T)`
and on to the simulation, so bursts' `age` and thrust-spark seeds use the same numbers. Parts are numbered
0..63; the client treats a higher part as malformed. If a tick does not fit one datagram it is split into
self-contained parts. Sections are written SELF, PREDICT, PREDICT_ROUND, PREDICT_RARE, PREDICT_KEYS, SOUND, SHIP, CANNON, FUEL, TARGET, TREASURE, TEMP_WORMHOLE, RADAR, the item sections (TIMERS first), BURST, WRECKAGE, BALL/ASTEROID/CONNECTOR, then SHOT, which fills
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
| 2 | SHIP | 14 | u16 id, u16 flags (`ShipFlags`), rel x, rel y, ang heading, vel16 vx, vel16 vy. A PAUSED ship is still, so its vx carries C's pause count (`Send_paused(.., pl->count)`: frames left, 0 when spent; vy is 0): the client blinks the P while it is above 0 and never moves the ship by it. It rides the 1/16 px/frame velocity, so counts above 2047 frames clamp |
| 3 | SHOT | 7 | rel x, rel y, then vx and vy as signed 12-bit values in 1/16 px/frame packed into 3 bytes (`[vx 11..4] [vx 3..0 \| vy 11..8] [vy 7..0]`, range -128..127.9375 px/frame; the server rounds its float velocity to the nearest 1/16, halves up, and clamps). *(Protocol v2; v1 sent i8 whole px/frame. `Records.Shot.vx16()/vxf()` give the precise value, `vx()` the whole pixels as before.)* **variant = harmless << 7 \| kind << 4 \| colour** *(S1 fix round: bit 7 `ShotKind.FLAG_HARMLESS` = this shot cannot hurt the receiver, C frame.c's friendly colour: team immune owner, paused owner, an unowned shot of the receiver's team in team play, or the receiver's own shot with selfImmunity; kinds are 3 bits: 0 SHOT, 1 CANNON_SHOT, 2 CLUSTER, 3 PULSE, 4 NUCLEAR = any shot with nuclear modifiers, which the client draws as a red X/+ teamshot when the tick has bit 1 set, C frame.c:830)* |
| 4 | BURST | 27 | rel x, rel y, vel16 vx, vy, u32 seed, u8 kind, u8 colour, u16 count, u8 minDir, u8 dirSpan (RES), u16 minSpeed, maxSpeed (1/16), u16 minLife, maxLife (frames), u8 age (sim ticks since spawn) |
| 5 | CANNON | 3 | u16 index, u8 flags (dead, phasing, shield) |
| 6 | FUEL | 4 | u16 index, frac level (of 500) |
| 7 | TARGET | 5 | u16 index, u8 flags (dead), frac strength left |
| 8 | RADAR | 3 | u8 x, u8 y (1/256 of the world size, decoded to the cell centre), u8 flags (friendly, self, missile, mine, ball, nuke, asteroid, locked) — whole world. Section variant: 0, or for a section of asteroid blips their size (C `Frame_radar` draws an asteroid size + 1 pixels; one section per size, so ordinary blips cost nothing extra). Readers that ignore the variant draw every asteroid at size 1 |
| 9 | ITEM | 5 | rel x, rel y, u8 item (`Item`) |
| 10 | MINE | 7 | rel x, rel y, u8 flags, u16 owner |
| 11 | MISSILE | 10 | u16 id, rel x, rel y, ang heading, u8 kind (smart/heat/torpedo, 0x80 nuclear), u8 length |
| 12 | BALL | 9 | u16 id, rel x, rel y, u8 team, u16 holder |
| 13 | LASER | 9 | rel x, rel y, ang heading, u16 length, u8 colour |
| 14 | ASTEROID | 7 | rel x, rel y, u8 type_size (C `Send_asteroid`: outline type << 4 \| size), ang rotation |
| 15 | WRECKAGE | 8 | rel x, rel y, u8 shape, u8 size, ang rotation *(server-batch2: the Java server sends wreckage in view every snapshot; shape is C's `info & 0x7F`, size C's 0..255, rotation C's RES rotation in radians)* |
| 16 | ECM | 6 | rel x, rel y, u16 radius |
| 17 | CONNECTOR | 9 | rel x0, y0, x1, y1, u8 kind (0 ball, 1 tractor, 2 pressor, 3 refuel: station to ship, 4 repair: ship to target) |
| 18 | TRANSPORTER | 8 | rel x0, y0, x1, y1 |
| 19 | SELF_ITEMS | 22 | u8 count per `Item` (21), u8 loseItem (the `Item` KEY_LOSE_ITEM drops, C `pl->lose_item` / PKT_LOSEITEM). loseItem was appended; an older 21-byte record reads 0 |
| 20 | TREASURE | 3 | u16 index, u8 flags (has ball) |
| 21 | TEMP_WORMHOLE | 5 | u16 bx, u16 by, u8 type |
| 22 | TIMERS | 26 | u16 damaged (frames of ECM damage left), u16 roundDelay (frames, 0 none), i32 timeLeft (seconds, -1 none), u16 destruct (self-destruct frames left, 0 none), u16 thrustLeft, thrustMax, shieldLeft, shieldMax, phasingLeft, phasingMax (C meters; max 0 = no meter), i16 shutdownCount (frames until the server stops after a gameDuration game over, C `ShutdownServer`; -1 none), u16 shutdownDelay (C `ShutdownDelay`, 1000) — the receiver's HUD timers. Fields from destruct on were appended; an older 8-byte record reads as none |
| 37 | SOUND | 2 | u8 event (`SoundEvent` ordinal = C `*_SOUND` index, src/common/audio.h), u8 volume (10..100) — the sounds the receiver hears (below) |

M1 uses 1-8; 9-22 are defined for M2; 37 is SOUND. Item sections as the server fills them (C `Frame_shots`/`Frame_ships`/`Frame_status`):

- SELF: `lockId` is the locked player when the lock and the compass are on (else NO_ID); `numTanks` and `currentTank`
  are the receiver's extra tanks and the tank fuel is taken from (0 = main); `lockDist`/`lockDir` are set while the lock
  is shown on the HUD (C LOCK_VISIBLE), `mods` are the receiver's modifiers; the AUTOPILOT flag of SELF is C's
  autopilot light (`showautopilot`: blinking 4 of 8 frames, steady once a hover pause has run out).
- TIMERS: sent every snapshot; the round delay while C's `round_delay_send` runs (one second past the delay, so the
  client sees 0); the time left: the `gameDuration` timer's wall-clock seconds while it runs, else with `maxRoundTime`
  the round time rounded up to seconds, else -1; the emergency thrust, emergency shield and phasing meters while each
  is in use, the self-destruct countdown while self-destructing; the shutdown countdown after a `gameDuration` game
  over.
- SELF_ITEMS: one record per snapshot with the receiver's item counts (TANK = its tank count) and its lose item (C sends
  PKT_LOSEITEM only while the selection is shown; the value only changes with KEY_SELECT_ITEM, so every record carries
  it and the client draws the selection box itself from its own SELECT_ITEM / LOSE_ITEM key presses, C xevent.c).
- ITEM: floating items in view; a random item (`randomItemProb`) shows a random kind each snapshot.
- MINE: flags ARMED (no longer owner-immune), FRIENDLY (C `laid_by_team`: the controller is paused, team-immune with
  the receiver, or it is the receiver's own fused mine; random for a confused identified mine), NUCLEAR, OWN (the
  receiver controls it); ownerId is the controller when `identifyMines` and the receiver is within mine-sensing range
  (SHIP_SZ + 130 px + 30 px per sensor), else NO_ID.
- MISSILE: id = owner (NO_ID unowned), kind SMART/HEAT/TORPEDO (+NUCLEAR), length 12/15/18 with `distinguishMissiles`,
  else 15.
- LASER: from the pulse start when in view, else from its end backwards; colour BLUE when harmless to the receiver
  (team immune, or its own with `selfImmunity`), else RED.
- ECM: centre and current radius (it halves every tick): C's `ecm->size`, also on the XPilot path (PKT_ECM's size
  as sent). The client draws it as C does, a circle `size` pixels across (`Gui_paint_ecm`), i.e. half the blast radius.
- CONNECTOR: tractor/pressor beams of players and cannons, three lines from the beam source to the target's first three
  hull points.
- TRANSPORTER: from the victim to the thief (or where the cannon fired it), for 5 ticks.
- RADAR: missiles and mines within the receiver's sensor range (FLAG_MISSILE/FLAG_MINE, FLAG_NUKE for nuclear ones with
  `nukesOnRadar`); players follow C's visibility rules (cloaked opponents only when seen, sensor range with
  `limitedVisibility`, the locked player blinks).
- SHIP: cloaked opponents the receiver cannot see are not sent; flags CLOAKED, PHASING, EMERGENCY_SHIELD, DEFLECTOR,
  AUTOPILOT (or hover pause), EMERGENCY_THRUST are set from the ship's state.

The game-mode sections are sent as follows (server-modes):
TARGET and TREASURE for map objects in view (target strength left, dead flag; treasure HAS_BALL while its ball
rests in it); BALL (id = the server's object handle, team = the team of the ball's
treasure, holder = the connected ship or NO_ID: the client draws the ball connector from it); CONNECTOR kind
BALL only for a ship still *reaching* for a ball with its connector (C `pl->ball`); ASTEROID (size 1-4,
rotation) and WRECKAGE (shape, size 0-255, rotation); RADAR blips with FLAG_BALL (`treasuresOnRadar`) and
FLAG_ASTEROID (`asteroidsOnRadar`) within the receiver's sensor range; TEMP_WORMHOLE for the IN end of every temporary (hyperjump) wormhole in view, only with
`wormholeVisible` (C frame.c:611-636; type = `Wormhole.Type` ordinal, so always 1). The client draws each like a map
wormhole half a block up and right of its block, as C's client does with the block centre the C server sends.
TIMING carries race progress; in timing mode the client's score list follows the race (C `Client_score_table`). SHIP flags carry HAS_BALL and REPAIRING. Game-mode snapshot section ids
30-36 are reserved for future mode sections (none used yet).
A BURST of one particle with no speed and no life is a
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

Robots never hear sounds. A player entering the game hears its own START too: C plays it to everyone in
`Handle_login`, then the newcomer's `PKT_REQUEST_AUDIO`, in the same datagram, plays it to the newcomer
(`sound_player_onoff`). Part 0 of the player's next snapshot carries the queue as the SOUND section right
after SELF, and the queue is then emptied (C `sound_play_queued` after the frame's objects), so each sound is
sent once; a lost snapshot loses its sounds, as a lost C frame does. A player's queue is emptied when it
enters the game (C `sound_player_init`). The client plays SOUND records only from the newest tick it has
received. On the XPilot path the client sends `PKT_REQUEST_AUDIO` once, with PKT_PLAY at `Net_start`: 1 when
sound is enabled at startup (a C client built with SOUND always sends 1), 0 with `--no-sound` or volume 0; the
mute key does not change it. It turns each `PKT_AUDIO` (type, volume) into the same SOUND record. Stock C
builds have SOUND off, so most C servers send no `PKT_AUDIO` at all.

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

### PREDICT, PREDICT_ROUND, PREDICT_RARE, PREDICT_KEYS (sections 23-25 and 27, owner only)

The receiving player's own ship as the ship kernel (`common.sim`) sees it, for the own-ship predictor, in part
0 of every snapshot whose view is the receiver's own ship (none while it watches another player).
`ShipBody` owns the codec, so the wire cannot lag behind what the kernel reads.

- **PREDICT** (68 bytes, every snapshot): the 63-byte core block: `i24 cx, i24 cy` (clicks), `f32 vx, vy,
  velocity, mass, floatDir, turnVel, oldTurnVel, turnAcc` (raw IEEE bits), `u8 dir`, `i32 status, have, used`,
  `u24 fuel` (C's integer fuel, 1/256 units, clamped to 0..16777215; Java fuel is always integral, asserted in
  `KernelDifferentialTest`), `u16 shotAge` (`frames - shotTime`, saturating at 65535: the fire check only needs
  "long ago"), `u16 shieldTime, stunned, shots` (clamped to 0..65535), `u8 flags` (1 prevKeys equals the
  applied input's keys, 2 lastReceivedKeys does, 4 keysCleared, 8 roundDelay is nonzero); then
  `u8 appliedHash` (xor of the 8 bytes of the key mask the server applied), `u8 rareVersion` (the
  PREDICT_RARE it refers to), `u8 kernelVersion` (the KERNEL_CONFIG version the server simulates with, mod
  256), `u16 stateTag` (the tick, mod 2^16, of the newest KERNEL_STATE message the server has queued to this
  client; ServerNet queues events before it builds the snapshot, so it covers every change up to the snapshot's
  tick). The key flags compare with the entry the server applied this tick, which the client has in its own
  input history under the header's `inputTick`; when the client's entry hashes differently (the server applied
  another copy, or held old keys), the flagged masks are unknown. The client uses PREDICT only when its rare
  block has `rareVersion`, its masks are known, its kernel world is built from `kernelVersion`, and the newest
  KERNEL_STATE it has received is tagged `stateTag` or later (`KernelSync.stateCurrent`, compared on 16 bits): a
  change still in flight, lost and awaiting its retransmit, would otherwise leave the predictor a stale world.
- **PREDICT_ROUND** (2 bytes, section 27): `u16 roundDelay` (frames, clamped to 1..65535), right after PREDICT
  when flag 8 is set. Flag 8 without it in the part leaves roundDelay unknown (PREDICT unusable).
- **PREDICT_RARE** (64 bytes): `u8 version`, `i32 frameOffset` (C `frame_loops` minus the snapshot tick:
  constant while the sim runs; the kernel's frame clock is `tick + frameOffset`, mod 2^32), then the 59-byte
  rare block: `f32 emptyMass, u24 fuelMax, f32 power, turnSpeed, turnResistance, powerS, turnSpeedS,
  turnResistanceS, u16 shotMax, i8 team, i16 wormHoleDest, u16 mods, u8 items[21]`. The version (mod 256)
  changes when the block's bytes or the frame offset change; the block is sent in the 8 snapshots after a
  change (and after the join) and in every 14th. It may follow PREDICT in the part; the client decodes the
  core's shot age against the frame clock after reading the whole part.
- **PREDICT_KEYS** (16 bytes): `u64 prevKeys, u64 lastReceivedKeys`, only when a key flag is clear.

Not sent: the tanks (the kernel only adds to the current tank's fuel, never reads a tank), pixels and blocks
(derived from the clicks), the previous position, the acceleration, the busy frame, the last wall touch and
the hull (it comes with PLAYER_JOIN). The canonical encoding (core + rare + keys) backs `ShipBody.sameState`
(`ByteBuffer.mismatch`); a round trip through the real net path is bit-equal every tick
(`PredictRoundTripTest`).

### Size budget (asserted in `SnapshotTest.WireSize`, sizes include the 8-byte packet header)

| scene | bytes | datagrams |
|---|---|---|
| Fight: 15 thrusting ships, 150 shots (3 colour groups), 400 debris as 5 explosion bursts, 15 radar blips | **1546** (1496 without radar) | 2 |
| Typical: 4 ships, 30 shots, 2 bursts, 8 blips | **450** | 1 |
| Radar-heavy: 32 players + 300 objects on radar (332 blips), 8 ships in view | **1194** | 1 |
| Radar-heavy with all 31 other ships in view | **1516** | 2 |

The owner's snapshot adds PREDICT (5 + 68 = 73 bytes): typical **523**, fight **1619**; PREDICT_RARE adds
69 bytes in the snapshots that carry it (typical 592), PREDICT_KEYS 21 when the key masks differ and
PREDICT_ROUND 7 while a round delay runs.

At 30 Hz the fight is ≈ 370 kbit/s per client (at the default 14 Hz tick rate ≈ 173 kbit/s). The original per-particle layout needed ~4.7 KB per snapshot.

## Extending

- New fields: append to a record and bump its `SIZE`; old readers keep working (stride).
- New sections: new type id; old readers skip it.
- New reliable messages: new `ReliableType` id; receivers ignore unknown types.
- Incompatible changes: bump `Protocol.VERSION` (header check rejects mismatches; version 2 is the current
  one).
