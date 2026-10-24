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
| Random numbers | Mersenne Twister `rfrac()`, static `keep_rand` in `Thrust()`, extra `rfrac()` draws (robot war on shoves, cloak visibility) | one seeded xorshift per world; thrust sparks and bursts seeded from (tick, id) so clients can replay them | Different sequences by design; everything random differs in detail, nothing else. |
| Key input | every key event of a client packet | the client sends its key mask; the server handles the edges between consecutive masks once per tick (= C frame) | Our protocol. A press and release inside one tick is lost. |
| Keys after `Go_home` | `last_keyv`/`prev_keyv` cleared; the next keyboard packet (sent on a key change) brings the full state | held keys are ignored until the client's mask changes, then all held keys count as pressed | Same effect (lead decision S9). |
| Default engine settings | `Init_player` 45/30/0.12; the client then sends its own settings | 45/35/0.12 (alternate 35/25/0.12), the C client's defaults, until SETTINGS arrives | Lead decision S2. The C client sends settings as 1/256 fixed point, so a C server holds 30/256 = 0.1171875 for 0.12; our SETTINGS carries floats. |
| Score | `DFLOAT` | double | Not physics; scores agree to float display precision. |
| `teamShareScore` when a player leaves | `TEAM_SCORE(team, -score)` runs after `NumPlayers--` but before the array swap, so the last player in the array misses the re-share | the leaver is removed first, then every remaining member gets the new share | C ordering bug. |
| `Init_player` limited-lives "too late" check | uses the slot's stale `team` | runs after the team is chosen | Lead decision S10. |
| Rounds: `gameDuration` | `Frame_update` ends the game `gameDuration` minutes of wall-clock time after the server started, then shuts the server down 30 s later | counted in simulated frames from the map load (the simulation runs only while humans play, or with `rawMode`); `World.shutdownDelay` counts the 30 s, but the host does not yet stop the server | Deterministic time model. |
| Ball life | `LONG_MAX`, counted down every frame; "loose for N frames" is `LONG_MAX - life` | 2^52 (exact in a double), the same countdown and message | `Objects.life` is a double. |
| Connector reach (`pl->ball`) | a plain pointer: after the ball is deleted the slot may hold another object, and C can then "connect" to it | the handle is checked to still be a live ball | C undefined behaviour. |
| Radar balls and asteroids | only within the viewer's `sensor_range` | the whole world | Sensor range is not ported (items). |
| Wreckage on the wire | sent when the client's `sparkProb` is not 0 or with `wreckageCollisionMayKill` | always sent | `sparkProb` is a client display setting. |
| Temporary wormholes | unlimited (`realloc`); `remove_temp_wormhole` leaves the moved wormhole's `itemID` stale | at most 64 at a time; the moved wormhole's object index is updated | Fixed pool; C bug. |
| Warp of a removed temporary wormhole | `break` leaves the whole player loop for this frame | only this ship's warp ends | C bug. |
| Asteroid item drops | `Choose_random_item` + `Make_item` | an `Asteroids.ItemDrop` hook, called once per item after C's rolls; nothing drops until the item code installs it | Items are ported separately. |
| Race respawn and asteroid placement | `rfrac()` draws (Mersenne Twister) | the world RNG (xorshift) | As "Random numbers" above. |
| `Give_best_player_bonus` with nobody eligible | reads an unset array entry | announces nothing | C undefined behaviour. |
| Login messages (`Handle_login`) | "Welcome to ...", "X (realname) has entered ..." | the network layer (`SimGameHost`) sets them in C's place (before the round-delay message), without the real name: "X has entered ..." / "X (team N) has entered ..." | Our CONNECT carries no real name. |
| Login name check (`Check_names`) | nick must start with 'A'..'Z' (the C client capitalizes it), else E_INVAL | any non-empty name; duplicates are refused case-insensitively | Clients other than C's do not capitalize. |
| Chat (`Handle_talk`) | "god:" lines go to the server admin log; partial names also match the real name | "god:" lines are dropped; names only | No admin log, no real names. |
| CHANGE_TEAM / SHAPE messages | team change and ship shape change while playing | ignored | Not needed for play yet. |
| Paused ships on the wire | `Send_paused(x, y, count)` | a SHIP record with the PAUSED flag (no count) | Our protocol. |
| Floating point | GCC may fuse multiply-adds (arm64 `fmadd`) | separate float operations | Matters only for products of non-trivial floats added together (coriolis, local gravity tables); the golden traces show no difference. |
| Robot war on shoves (walls.c `Player_crash`) | `Robot_war` with a random pusher (`rfrac() * num_pushers`) | war on the last pusher, the killer of the KILL event the shove produces (no extra `rfrac()` draw) | The robot manager reacts to sim events; only the RNG sequence differs. |
