# Server: known deviations from C XPilot 4.5.5

The simulation runs one tick per C frame at the map's `framesPerSecond` and uses C's float (`DFLOAT`)
arithmetic, so with the same map, options and inputs it matches the C server frame for frame. The golden
traces in `server/src/test/resources/ctrace/` check that bit for bit (`CTraceTest`). The deviations below
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
| Rounds | race mode (`Race_game_over`), treasure destruction and hover pause | not ported (S1 has no race scoring, treasures or autopilot); timing maps play without rounds | Scope. Team rounds end by lives or by `maxRoundTime`. |
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
| `denyHosts` separators | splits the list on `,;: \t\n`; IPv4 only | splits on commas, semicolons and whitespace; IPv4 and IPv6, `addr/prefix` or `addr/dotted.mask` | ':' is part of IPv6 addresses. |
| `serverHost` | the contact socket binds to that host; it is also reported to the meta server | used as the bind selector when `--bind` is not given | No meta server. |
| `logRobots` line | `"DD Mon HH:MM:SS NAME (n, realname) starts at startpos B."` on stdout | the same text without the time stamp, on the server log (stderr) | Matches the rest of our server log. |
