# Server: known deviations from C XPilot 4.5.5

The simulation runs one tick per C frame at the map's `framesPerSecond` and uses C's float (`DFLOAT`)
arithmetic, so with the same map, options and inputs it matches the C server frame for frame. The golden
traces in `server/src/test/resources/ctrace/` check that bit for bit (`CTraceTest`; `ModeTraceTest` for the ball
connector, race checkpoints and lap times, and asteroid motion). The deviations below
are deliberate.

| Area | C | JXPilot | Why |
|---|---|---|---|
| Point-source gravity (`Compute_global_gravity`) | wraps the y offset with `WRAP_DX` (the world width) | wraps y with the world height | C bug; kept fixed (lead decision S11). Only maps with `gravityPoint` on a non-square wrapping world differ. |
| `in_range_acd` across the seam | four fixed shifts; misses objects on opposite sides of the seam that did not cross it | shifts by the closest world copy | C bug; results differ only for end points exactly half a world apart. |
| Random numbers | Mersenne Twister `rfrac()`, static `keep_rand` in `Thrust()`, extra `rfrac()` draws (cloak visibility) | one seeded xorshift per world; thrust sparks and bursts seeded from (tick, id) so clients can replay them | Different sequences by design; everything random differs in detail, nothing else. |
| Key input | every key event of a client packet | the client sends its key mask; the server handles the edges between consecutive masks once per tick (= C frame) | Our protocol. A press and release inside one tick is lost. |
| Keys after `Go_home` | `last_keyv`/`prev_keyv` cleared; the next keyboard packet (sent on a key change) brings the full state | held keys are ignored until the client's mask changes, then all held keys count as pressed | Same effect (lead decision S9). |
| Default engine settings | `Init_player` 45/30/0.12; the client then sends its own settings | 45/35/0.12 (alternate 35/25/0.12), the C client's defaults, until SETTINGS arrives | Lead decision S2. The C client sends settings as 1/256 fixed point, so a C server holds 30/256 = 0.1171875 for 0.12; our SETTINGS carries floats. |
| Score | `DFLOAT` | double | Not physics; scores agree to float display precision. |
| `teamShareScore` when a player leaves | `TEAM_SCORE(team, -score)` runs after `NumPlayers--` but before the array swap, so the last player in the array misses the re-share | the leaver is removed first, then every remaining member gets the new share | C ordering bug. |
| `Init_player` limited-lives "too late" check | uses the slot's stale `team` | runs after the team is chosen | Lead decision S10. |
| Rounds: `gameDuration` | each frame sends the seconds left only when they change (else the round time), and `End_game` kicks every player with "shutting down: ..." | the same wall-clock timer, log line, game over and 30 * FPS shutdown (`Rounds`, `SimGameHost`, `Server`); TIMERS carries the seconds left in every snapshot while the timer runs, and the shutdown ends every connection with DISCONNECT (shutdown) | The native protocol sends HUD timers as state, not as one-off packets. |
| Ball life | `LONG_MAX`, counted down every frame; "loose for N frames" is `LONG_MAX - life` | 2^52 (exact in a double), the same countdown and message | `Objects.life` is a double. |
| Connector reach (`pl->ball`) | a plain pointer: after the ball is deleted the slot may hold another object, and C can then "connect" to it | the handle is checked to still be a live ball | C undefined behaviour. |
| Temporary wormholes | unlimited (`realloc`); `remove_temp_wormhole` leaves the moved wormhole's `itemID` stale | at most 64 at a time; the moved wormhole's object index is updated | Fixed pool; C bug. |
| Race respawn and asteroid placement | `rfrac()` draws (Mersenne Twister) | the world RNG (xorshift) | As "Random numbers" above. |
| `Give_best_player_bonus` with nobody eligible | reads an unset array entry | announces nothing | C undefined behaviour. |
| Login messages (`Handle_login`) | "Welcome to ...", "X (realname) has entered ..." | the network layer (`SimGameHost`) sets them in C's place (before the round-delay message), without the real name: "X has entered ..." / "X (team N) has entered ..." | Our CONNECT carries no real name. |
| Login name check (`Check_names`) | nick must start with 'A'..'Z' (the C client capitalizes it), else E_INVAL | any non-empty name; duplicates are refused case-insensitively | Clients other than C's do not capitalize. |
| Chat (`Handle_talk`) | "god:" lines go to the server admin log; partial names also match the real name | "god:" lines go to `adminMessageFileName` when it is set (the entry has no real name, host, address or display); names only | No real names on our protocol; C's default log file is LIBDIR/log, ours is none. |
| CHANGE_TEAM / SHAPE messages | team change and ship shape change while playing | ignored | Not needed for play yet. |
| Paused ships on the wire | `Send_paused(x, y, count)` | a SHIP record with the PAUSED flag (no count) | Our protocol. |
| Floating point | GCC may fuse multiply-adds (arm64 `fmadd`) | separate float operations | Matters only for products of non-trivial floats added together (coriolis, local gravity tables); the golden traces show no difference. |
| Robot hooks of kills (`Robot_war`, `Robot_reset_war`) | called inside the collision code, in the frame of the kill | the robot manager runs them from the KILL/CRASH events at the start of the next frame, before any robot decides (the shove's random pusher is still drawn in `Player_crash`, in C's place) | The robot manager reacts to sim events instead of hooks in the sim. `Robot_war`'s own `rfrac()` is drawn one frame later. |
| Defaults file (`parseDefaultsFile`) | `LIBDIR/defaults`; a missing file is skipped silently | XPilot's `lib/defaults`, bundled in the jar; `--defaults FILE` replaces it, `--defaults none` skips it, and a named file that cannot be read stops the server with an error | No install directory. A typo in a file you named should not quietly give you different physics. |
| Password file (`passwordFileName`, `password`) | read after the defaults file; gives operator privileges | not read | No operator commands yet (C command.c). |
| Wreckage on the wire (frame.c:802-807) | sent only when the client's spark probability is not 0 or wreckage is deadly; the shape's high bit marks deadly wreckage | always sent; shape is `info & 0x7F`, no deadly bit | Our client has no spark-probability setting and does not draw deadly wreckage differently. |
| `noQuit` | the server exits when the last human leaves, unless `-noQuit` | the server keeps running | Pending a lead decision: quitting would surprise dedicated-server users and the embedded `--local` server. |
| `denyHosts` separators | splits the list on `,;: \t\n`; IPv4 only | splits on commas, semicolons and whitespace; IPv4 and IPv6, `addr/prefix` or `addr/dotted.mask` | ':' is part of IPv6 addresses. |
| `serverHost` | the contact socket binds to that host; it is also reported to the meta server | used as the bind selector when `--bind` is not given | No meta server. |
| `logRobots` line | `"DD Mon HH:MM:SS NAME (n, realname) starts at startpos B."` on stdout | the same text without the time stamp, on the server log (stderr) | Matches the rest of our server log. |
| Robot war on shoves (walls.c `Player_crash`) | `Robot_war` with a random pusher (`rfrac() * num_pushers`) | war on the last pusher, the killer of the KILL event the shove produces (no extra `rfrac()` draw) | The robot manager reacts to sim events; only the RNG sequence differs. |
| Missile start outside the world (`Fire_general_shot`, no edgeWrap) | the allocated object is left with stale position and velocity | the object is freed | C bug: a ghost missile; only near the edge of non-wrapping maps. |
| Random parts of the frame code (`Frame_shots`: random items, confused mines' friendliness) | world `rfrac()` per client per frame | a snapshot-local RNG seeded from the tick and viewer | Snapshots must not change the simulation's random sequence. |
| Radar blinking of mines, missiles and nukes (`Frame_radar`) | shown or hidden by frame number | always shown | Our radar goes out every few snapshots, so frame-number blinking would alias. |
| HUD lock visibility (`Frame_status` sets LOCK_VISIBLE) | per connected client when its frame is built, with the client's view size | for every human at the end of the sim tick, with a 1024x768 view for the `inview` test | Keeps the simulation independent of snapshot timing; only matters with playersOnRadar off. |
| Visibility of a new player | stale `canSee` entries of the slot until the next visibility update | everyone sees and is seen at join until the first update | Avoids an invisible first frame. |
| ECM reprogramming a robot | `Send_seek` tells every client | the robot goes to war; no message | Our protocol has no seek message. |
| Snapshot rate of paused / game-over players (frame.c:1275-1294) | without allowViewing, paused players get every 4th frame and game-over players every 2nd | every snapshot | A bandwidth saving only; our snapshot rate is set by the network layer. |
| Modifier banks | the C client sends its configured banks at login (PKT_MODIFIERBANK) | banks start empty; REPROGRAM + LOAD_MODIFIERS fills them in game | Our protocol has no bank message. |
| Lose-item selection HUD (`Send_loseitem`) | sent while selecting | not sent | Our protocol; the client shows the item counts. |
| Deflector object lookup | C's cells are kept current by every move | the spatial grid is rebuilt for each deflector (and cannon danger) query | Same objects in the same order. |
| Lasers hitting asteroids, ECM detaching balls | in the laser and ECM code | hooks only (`Ecm.ballDetacher`); asteroids are not simulated by this code | Balls and asteroids belong to their own ports. |
| Rare item code paths | allocate nothing | the first run of some rare message branches resolves C string literals once (a few bytes) | JVM string constant resolution; `ShotNames.warm()` pre-resolves the kill messages. |
