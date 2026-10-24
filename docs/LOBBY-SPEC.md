# Lobby mode (queued after the parity goal)

Status: requested by the user on 2026-10-23. Not started. Implement it after full C parity lands.

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
  - To decide at design time: routing on one port vs. handing out ports.
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
