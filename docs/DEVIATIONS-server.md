# Server: known deviations from C XPilot 4.5.5

The simulation runs one tick per C frame at the map's `framesPerSecond` and uses C's float (`DFLOAT`)
arithmetic, so with the same map, options and inputs it matches the C server frame for frame. The golden
traces in `server/src/test/resources/ctrace/` check that bit for bit (`CTraceTest`). The deviations below
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
| Rounds | race mode (`Race_game_over`) and treasure destruction | not ported (S1 has no race scoring or treasures; hover pause and the autopilot are ported with the items); timing maps play without rounds | Scope. Team rounds end by lives or by `maxRoundTime`. |
| `Give_best_player_bonus` with nobody eligible | reads an unset array entry | announces nothing | C undefined behaviour. |
| Login messages (`Handle_login`) | "Welcome to ...", "X (realname) has entered ..." | the network layer (`SimGameHost`) sets them in C's place (before the round-delay message), without the real name: "X has entered ..." / "X (team N) has entered ..." | Our CONNECT carries no real name. |
| Login name check (`Check_names`) | nick must start with 'A'..'Z' (the C client capitalizes it), else E_INVAL | any non-empty name; duplicates are refused case-insensitively | Clients other than C's do not capitalize. |
| Chat (`Handle_talk`) | "god:" lines go to the server admin log; partial names also match the real name | "god:" lines are dropped; names only | No admin log, no real names. |
| CHANGE_TEAM / SHAPE messages | team change and ship shape change while playing | ignored | Not needed for play yet. |
| Paused ships on the wire | `Send_paused(x, y, count)` | a SHIP record with the PAUSED flag (no count) | Our protocol. |
| Floating point | GCC may fuse multiply-adds (arm64 `fmadd`) | separate float operations | Matters only for products of non-trivial floats added together (coriolis, local gravity tables); the golden traces show no difference. |
| Robot war on shoves (walls.c `Player_crash`) | `Robot_war` with a random pusher (`rfrac() * num_pushers`) | war on the last pusher, the killer of the KILL event the shove produces (no extra `rfrac()` draw) | The robot manager reacts to sim events; only the RNG sequence differs. |
| Missile start outside the world (`Fire_general_shot`, no edgeWrap) | the allocated object is left with stale position and velocity | the object is freed | C bug: a ghost missile; only near the edge of non-wrapping maps. |
| Random parts of the frame code (`Frame_shots`: random items, confused mines' friendliness) | world `rfrac()` per client per frame | a snapshot-local RNG seeded from the tick and viewer | Snapshots must not change the simulation's random sequence. |
| Radar blinking of mines, missiles and nukes (`Frame_radar`) | shown or hidden by frame number | always shown | Our radar goes out every few snapshots, so frame-number blinking would alias. |
| HUD lock visibility (`Frame_status` sets LOCK_VISIBLE) | per connected client when its frame is built, with the client's view size | for every human at the end of the sim tick, with a 1024x768 view for the `inview` test | Keeps the simulation independent of snapshot timing; only matters with playersOnRadar off. |
| Hyperjump with wormTime | makes a pair of temporary wormholes (`add_temp_wormholes`) | no temporary wormholes; the jump itself is C's | Not ported yet. |
| Visibility of a new player | stale `canSee` entries of the slot until the next visibility update | everyone sees and is seen at join until the first update | Avoids an invisible first frame. |
| ECM reprogramming a robot | `Send_seek` tells every client | the robot goes to war; no message | Our protocol has no seek message. |
| Modifier banks | the C client sends its configured banks at login (PKT_MODIFIERBANK) | banks start empty; REPROGRAM + LOAD_MODIFIERS fills them in game | Our protocol has no bank message. |
| Lose-item selection HUD (`Send_loseitem`) | sent while selecting | not sent | Our protocol; the client shows the item counts. |
| Deflector object lookup | C's cells are kept current by every move | the spatial grid is rebuilt for each deflector (and cannon danger) query | Same objects in the same order. |
| Lasers hitting asteroids, ECM detaching balls | in the laser and ECM code | hooks only (`Ecm.ballDetacher`); asteroids are not simulated by this code | Balls and asteroids belong to their own ports. |
| Rare item code paths | allocate nothing | the first run of some rare message branches resolves C string literals once (a few bytes) | JVM string constant resolution; `ShotNames.warm()` pre-resolves the kill messages. |
