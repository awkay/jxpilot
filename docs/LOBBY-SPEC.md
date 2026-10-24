# Lobby mode

Status: requested by the user on 2026-10-23; implemented on branch `lobby` (see "As built" at the end).

## User requirements
- **Purpose:** one server left running on a small VM (e.g. a Linode) that friends can connect to whenever
  they want to play.
- **Advertising:** the server lists the maps it has. A client that connects can ask to play one of them.
  If that map isn't running, the server starts it; if it is, the client joins it.
- **When a map's last player leaves:** that map quits, but the server itself keeps running.
- **Lobby overlay:** a client that connects to a lobby server first sees a startup overlay listing the
  maps. Each map shows:
  - an active indicator;
  - the current number of human players.
- **Sorting:** active maps first, then alphabetical.
- **Selection:**
  - each map has a single-key selector, 0-9, then a-z, then A-Z, which caps the list at 62 maps;
  - also up/down to move through the list and Enter to choose.
  - Lead decision: provide BOTH. The scrollable list with arrows and Enter is the main way to choose, and
    the 62 one-key selectors are shown next to the names as shortcuts. A list longer than 62 entries still
    works through scrolling.
- **Compatibility:** lobby mode is a new command-line option. Existing CLI usage, with one map per
  server, is unchanged.

## Design notes so far
- **One process, many maps:**
  - Server instances are already self-contained: no mutable statics, a per-World RNG, per-instance Options,
    loop thread and close(). See the multi-instance analysis in conversation.
  - The lobby runs a map as an in-process Server/SimGameHost instance, started on demand.
  - It stops the instance when the last human leaves (C quit-when-empty semantics, applied per map).
- **One UDP port:**
  - Prefer to keep the whole service on one public UDP port, which is easier to firewall.
  - The lobby owns the socket and routes each session to its map instance after selection. The
    alternative is a per-map port handed to the client.
  - **Decided: one port, routed by the lobby** (reasons under "Port decision").
- **Protocol:** new lobby messages, all in the native protocol only. C servers have no lobby, and the
  client.xp path is unaffected.
  - map list with title, author, active, humans and max players;
  - select map;
  - start/join result;
  - list updates while in the lobby.
- **Robots:** each running map keeps its own robot settings, from the map, the defaults and lobby-level
  overrides.
- **Configuration:** which maps are offered. Default to all bundled maps, plus a maps directory and
  per-map option overrides in a config file.
- **Docs:**
  - a guide section, "Running an always-on lobby server";
  - the systemd unit example for a VM.

## Port decision: one public port, routed by the lobby

Chosen after reading the server's net layer (`Server`, `ServerNet`, `UdpTransport`). The question was whether the
net layer can be shared or demultiplexed cleanly. It can, with a small, local change:

- `ServerNet` already knows nothing about sockets: datagrams come in through `onDatagram(channel, from, data, now)`
  and go out through the `Datagrams` interface (`send(channel, to, data)`), and a peer is keyed by
  (channel, source address). Its sessions are random ids it hands out itself. So a game can sit behind any
  object that feeds it datagrams and carries its replies.
- `Server`'s loop needed only a transport with `poll`, `wakeup`, `send`, `close` and a receiver. That is the new
  `NetTransport` interface (`UdpTransport` implements it unchanged). `Server.startHosted(options, settings,
  transport)` builds a game over any `NetTransport` with nothing bound.
- The lobby's socket thread owns the one `UdpTransport`. For an address it routes, it copies the datagram into the
  game's `RoutedTransport` (a fixed pool of buffers behind a blocking queue, so no allocation per datagram), and the
  game's own loop thread polls it. Replies go straight back out of the lobby's socket by the channel the client
  arrived on (`UdpTransport.send` is now safe from several threads: it reads an immutable per-channel table), so the
  "reply from the address the client contacted" rule of SPEC.md still holds with one socket per local address.
- Routing by source address and port needs no change to the games or to the wire format of a game session: after
  the lobby routed an address, its CONNECT reaches the game as if it had contacted it directly.

The per-map-port fallback would have meant a port range in the firewall, a port pool and the lobby telling clients
ports (which must be reachable and, behind NAT, are one more thing to forward), and every game still binding its own
sockets and rescanning interfaces. Routing costs one hash lookup per datagram and a copy.

Costs of the choice, accepted: a routed datagram is copied once more; the lobby's thread is a bottleneck for all
games' inbound traffic (a game server is single-threaded on I/O anyway, and the copy is a 1200 byte memcpy); a
client's route lives by its source address and port, so a client that rebinds to another port mid-game would be
sent back to the lobby (the same already ends a session on a plain game server).

## As built

Server (`server/.../lobby`, `server/.../net`):
- `ServerMain --lobby` (or `lobby: yes` in the positional config file) runs `LobbyServer`; every other command line
  is unchanged. New options `lobby`, `lobbyConfig`, `lobbyMapsDir`, `lobbyBundled`, `lobbyExclude`, `lobbyName`
  (command line / config file only, never from a map).
- `LobbyCatalog`: bundled maps (by default) plus `.xp`/`.map` files of the maps directories (a directory file
  replaces a bundled map of the same name), minus excluded names; each map is parsed for title, author, size and
  bases (= max players); a map that does not load or has no bases is reported and skipped. Ids are alphabetical
  positions.
- `LobbyConfig` / `LobbyOptionSources`: the config file is server-wide options before the first `[section]` and
  `[mapname]` sections whose options override the map; each game's `Options` are built by `Server.readOptions`
  with those extra sources (`OptionHooks`), so the command line still wins.
- `LobbyRegistry`: starts a map's game on a worker thread on the first select, joins the running one otherwise,
  reaps games that ended (their `Server` quit because the last human left, C's `quitReason`, unchanged), and tracks
  the list and its version. `LobbyServer` is the socket thread: the lobby protocol (docs/PROTOCOL.md "Lobby"),
  routes, list pushes, rate limits.
- `GameHost.humanCount()` (new, default 0; `SimGameHost` counts non-robot, non-tank players) is published by each
  game's loop as `Server.humans()` for the list.
- A game started by the lobby always quits when empty, whatever `noQuit` says in its options, and keeps the 5 minute
  no-show timeout.

Client (`client/.../net`, `game/hud`, `app`):
- `ConnectionState.LOBBY` and `LobbyClient` (HELLO / LIST_REQUEST / SELECT with resends); `ClientConnection` enters
  the state on REJECT LOBBY and restarts the handshake after JOIN OK. The `xp/` package (C server protocol) is
  untouched apart from a default method on `GameConnection`.
- `LobbyPanel` is the overlay (list, `*` indicator, humans, `[k]` shortcuts, cursor bar, scrolling); `ClientFrame`
  routes keys and characters to it while the lobby is up and lays it out in the key-help overlay's draw list.
  `--lobby-map NAME` picks a map by itself.

Deviations from, and decisions inside, the requirements above:
- Shortcut keys follow the rows as listed (display order). Because active maps come first, they move when the
  order changes. The alternative (keys fixed to the alphabetical position) was rejected because the list then
  reads out of order (`[c] [a] [b]`).
- A client that is in a game does not return to the list; changing maps means reconnecting. (Switching maps in
  game is a possible later addition: the protocol already lets a routed address that asks for the list again leave
  its route.)
- The list is sent whole on every change (about 20 maps: 2-3 datagrams), not as deltas.
- JOIN has no FULL result: a full map queues or refuses the player itself, as on any server.
