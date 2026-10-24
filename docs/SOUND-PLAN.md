# Sound plan (optional, M3)

Status: implemented 2026-10-23 (branch client-sound). The research and plan below are kept; "Implementation"
at the end records what was built and where it differs.

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

## Implementation (2026-10-23)

**Which events sound:** exactly C's. The server queues C's events per player (`server.sim.Sounds`, a port
of `saudio.c`) at the Java ports of C's `sound_play_*` call sites, and sends them in the SNAPSHOT SOUND
section (37). A C server sends them as `PKT_AUDIO`. Both reach the same client path. Wire and API:
docs/PROTOCOL.md "Sounds".
- **Server API** for new features: put one call where C calls `sound_play_*`:
  - `Sounds.play(world, x, y, SoundEvent.X)` for `sound_play_sensors`;
  - `Sounds.playAll(world, ...)`;
  - `Sounds.playPlayer(player, ...)`.

  `SoundEvent` (common) has C's names without `_SOUND`, and its ordinal equals the C index.
- **Assets:** `tools/sounds/build-sounds.sh` (downloads, verifies sha256, extracts the clips in `clips.tsv`)
  and `process_sounds.py` rebuild `client/src/main/resources/sounds/` byte for byte. They need curl, unzip,
  ffmpeg and python3. The ear-pass substitute measured these levels:
  - every clip lasts 0.047-1.73 s;
  - one-shots are peak-normalized to -6 dBFS (RMS -8.8 to -20.1);
  - the thrust loop sits at -24 dBFS RMS;
  - the loop seam step is 877, against a 4996 p99 sample step.

  A 5 ms tail fade removes end clicks.
- **Mapping:** `sounds.map` follows the table above. Events the table left out are mapped as follows:
  - deflector → beam;
  - hyperjump → warp;
  - phasing_on/off, emergency_shield_on/off → toggle_on/off;
  - asteroid_hit → hit;
  - asteroid_break → explode_small;
  - every `*_pickup` → pickup.

  refuel stays silent, because no C server plays it.
- **Playback:** LWJGL OpenAL (OpenAL Soft natives for every platform the release jar bundles). The pieces:
  - all clips are preloaded;
  - 16 fixed voices; a loop clip owns one voice;
  - a one-shot takes a free voice, else the quietest (oldest among equals);
  - a repeat of the same clip in the same frame shares a voice;
  - nothing allocates per event (`client.sound.AllocationTest`).

  The gain is C's `MIN(volume, maxVolume)/100`. The server has already attenuated the volume by distance.
- **Deviations from C:**
  - Thrust: C replays `thrust` every frame. JXPilot plays a 2 s loop instead, and holds it for 2.5 game
    frames after the last thrust event.
  - Mute key: C has no mute key; JXPilot adds one.
  - Out of range: sounds outside the sensor range are not heard, and distances do not wrap, as C. On
    wrapping maps a nearby sound across the seam is silent, as C.
- **Options:**
  - `--sound` / `--no-sound` (C resource `sound`; default on);
  - `--volume 0..255` (C `maxVolume`; default 100; 0 means no sound);
  - the key `minus` (`ClientAction.TOGGLE_SOUND`, resource keyToggleSound; C leaves it unbound) mutes and unmutes, with a "Sound off." / "Sound on." message.
- **No device:** without an audio device, if the OpenAL library cannot load, or if a clip cannot be read,
  the client logs `no sound: <reason>` and plays silently. Against a C server, `PKT_REQUEST_AUDIO` is 1
  only while sound is on.
