# Sound plan (optional, M3)

Status: researched 2026-10-23. Not implemented. Add it only once the game is playable, and only if it
stays cheap (see SPEC.md).

## Sources (all CC0; FSF lists CC0 as GPL-compatible)
- **Primary:** Juhani Junkala, "The Essential Retro Video Game Sound Effects Collection [512 sounds]".
  - Source: <https://opengameart.org/content/512-sound-effects-8-bit-style>.
  - Format: 16-bit 44.1 kHz mono WAV, a consistent chiptune style.
  - License: the pack's `INFO.txt` says "These sound effects have been released under CC0 creative commons license."
  - Zip sha256: `6d4519229c6e2c9e09502cd4967940052f68664140549a467615b56bdfd1d031`.
- **Thrust loop:** Kenney "Sci-Fi Sounds" `thrusterFire_004.ogg`.
  - Source: <https://kenney.nl/assets/sci-fi-sounds>, CC0.
  - Zip sha256: `119340f351a5098ad814f78719438c0da355a9ce8a4c8a3af6a8d48aa3d49e04`.
  - Processing: start at 0.3 s, cut a 2.0 s loop, then crossfade the first 0.25 s with an equal-power curve, which measured click-free. Normalize its level to the Junkala set.
- **Fallback:** Kenney Sci-Fi Sounds + Digital Audio + Music Jingles (all CC0, OGG, so they need stb_vorbis).
- **Last resort:** jsfxr (Unlicense). Commit each sound's parameters plus a render script, so the sounds are reproducible and our own work.
- **Never use** the historical XPilot sound packs (`lib/sounds`, `sounds-simpsons`). They were ripped from films and TV.

## Event → clip mapping (22 clips, about 1.3 MB as WAV)
Junkala paths are relative to the pack root. The picks were chosen by file name and length without listening, so do an ear pass and swap within the same folder as needed. The likeliest swaps are `beam`, `drop`, `draw` and `shot`.

| Clip | Source | Events |
|---|---|---|
| shot.wav | Weapons/Lasers/sfx_wpn_laser8.wav | fire_shot |
| cannon.wav | Weapons/Cannon/sfx_wpn_cannon4.wav | cannon_fire |
| laser.wav | Weapons/Lasers/sfx_wpn_laser5.wav | fire_laser, fire_heat_shot |
| missile.wav | Weapons/Grenade Whistles/sfx_wpn_missilelaunch.wav | fire_smart_shot, fire_torpedo, nuke_launch |
| explode_small.wav | Explosions/Short/sfx_exp_short_hard2.wav | object_explosion, mine_explosion, player_hit_mine |
| explode_large.wav | Explosions/Medium Length/sfx_exp_medium3.wav | player_explosion, cannon_explosion, explode_ball, destroy_ball, destroy_target, player_roasted, player_shot_themself, player_ran_over_player, player_hit_cannon |
| explode_nuke.wav | Explosions/Long/sfx_exp_long1.wav | nuke_explosion |
| hit.wav | General Sounds/Simple Damage Sounds/sfx_damage_hit3.wav | player_eat_* (all), player_hit_cannonfire |
| bounce.wav | General Sounds/Impacts/sfx_sounds_impact5.wav | player_hit_wall, player_bounced, player_hit_player |
| pickup.wav | General Sounds/Positive Sounds/sfx_sounds_powerup3.wav | all *_pickup events |
| drop.wav | General Sounds/Interactions/sfx_sounds_interaction7.wav | drop_mine, drop_moving_mine, tank_detach, drop_ball |
| ball_connect.wav | General Sounds/Coins/sfx_coin_double1.wav | connect_ball |
| warp.wav | Movement/Portals and Transitions/sfx_movement_portal5.wav | worm_hole, transporter_success, cloak |
| fail.wav | General Sounds/Negative Sounds/sfx_sounds_error1.wav | transporter_fail, declare_war |
| beam.wav | General Sounds/Weird Sounds/sfx_sound_mechanicalnoise4.wav | tractor_beam, pressor_beam, ecm |
| toggle_on.wav | General Sounds/Pause Sounds/sfx_sounds_pause1_in.wav | autopilot_on, emergency_thrust_on |
| toggle_off.wav | General Sounds/Pause Sounds/sfx_sounds_pause1_out.wav | autopilot_off, emergency_thrust_off |
| blip.wav | General Sounds/Simple Bleeps/sfx_sounds_Blip7.wav | change_home |
| win.wav | General Sounds/Fanfares/sfx_sounds_fanfare1.wav | player_win, team_win |
| draw.wav | General Sounds/Negative Sounds/sfx_sounds_negative2.wav | player_draw, team_draw |
| start.wav | General Sounds/Weird Sounds/sfx_sound_poweron.wav | start |
| thrust_loop.wav | Kenney thrusterFire_004 (processed as above) | thrust |

## Compliance checklist when implementing
- Put the clips in `client/src/main/resources/sounds/` and the event mapping in `sounds.map` (`event<TAB>file`).
- Add `LICENSE-sounds.txt` next to them. For each clip, record:
  - the original pack path;
  - the author;
  - CC0, with the deed URL;
  - the source URL;
  - the download date;
  - the pack's sha256;
  - any modifications, e.g. the thrust-loop processing.
- Add rows for both sources to README.adoc's third-party table.
- Playback: LWJGL OpenAL (in the LWJGL BOM) or javax.sound. Preload all clips at startup. A fixed voice pool means zero allocation per event.
