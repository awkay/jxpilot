# xpilot-ref: headless reference harness for the original XPilot 4.5.5

## Purpose

This harness builds and runs the original C XPilot 4.5.5 server (`xpilots`)
and X11 client (`xpilot`) in a Linux Docker container, with no display
needed. You can use it to:

- run a reference server with robots and read its log;
- take screenshots and contact sheets of the real client on any map, with
  scripted key input;
- record a per-tick numeric physics trace from the real server.

Its job is to produce reference material for comparison with JXPilot. It
does not compare anything itself.

## Requirements

- Docker with BuildKit, which is the default in Docker Desktop. The build
  uses `--build-context`.
- An unpacked `xpilot-4.5.5` source tree. The default is
  `/Users/tonykay/Downloads/xpilot-4.5.5`; override it with `XPILOT_SRC`.
- bash, awk and mktemp on the host. macOS has no `timeout`, so the scripts
  set their own host-side deadline.
- About 400 MB of disk for the image.

The image is built for the host architecture (arm64 on Apple Silicon). To
build for x86_64 under emulation, set `PLATFORM=linux/amd64`. This is
untested.

## Files

| File | What it is |
|---|---|
| `Dockerfile` | Two stages. The builder runs Imake, applies the patches and runs `make install`. The runtime stage has Xvfb, xdotool, xwd and ImageMagick. |
| `patches/0001-client-rename-getline.patch` | The only change needed to build. |
| `patches/0002-server-physics-trace.patch` | Opt-in trace and seed hooks. Inert unless their environment variables are set. |
| `patches/0003-server-wreckage-trace.patch` | Opt-in wreckage trace (`XPILOT_TRACE_WRECK`). Inert unless set. |
| `patches/0004-server-object-trace.patch` | Opt-in object, laser pulse, ECM and player item trace. Inert unless `XPILOT_TRACE_OBJS` is set. |
| `patches/0005-server-mode-trace.patch` | Opt-in trace of balls, asteroids, wreckage, race progress, treasures and targets. Inert unless `XPILOT_TRACE_MODES` is set. |
| `build.sh` | Builds image `xpilot-ref:4.5.5`. |
| `server.sh` | Runs a headless server and prints its log. |
| `screenshot.sh` | Runs server and client on Xvfb and saves one PNG. |
| `frames.sh` | Saves N PNGs at an interval, plus a labelled contact sheet. |
| `trace.sh` | Runs server and client with scripted keys and saves the XPTRACE lines. |
| `scenario-host.sh`, `lib.sh` | Host-side plumbing: deadline, container naming, output copying. |
| `container/scenario.sh` | Runs inside the container: Xvfb, then server, then client, then keys, then capture. |
| `samples/` | Reference outputs, listed below. |

Environment variables used by all scripts:

- `XPILOT_SRC`: the source tree, used only by `build.sh`.
- `XPREF_IMAGE`: the image tag. Default `xpilot-ref:4.5.5`.
- `XPREF_CNAME_PREFIX`: container name prefix. Default `xpilot-ref`. Lets
  parallel users keep their containers apart, e.g. `server-items`.
- `DOCKER`: the docker CLI. The default is `docker` on PATH, then
  `/Users/tonykay/.docker/bin/docker`.

## Usage

### build.sh

```sh
tools/xpilot-ref/build.sh                     # uses XPILOT_SRC or the default path
XPILOT_SRC=~/src/xpilot-4.5.5 tools/xpilot-ref/build.sh
tools/xpilot-ref/build.sh --no-cache          # extra args go to docker build
```

### server.sh

```sh
server.sh --map NAME [--robots N] [--seconds S] [--no-raw] [--trace] [--seed N] [xpilots options...]

tools/xpilot-ref/server.sh --map default --robots 3 --seconds 10
tools/xpilot-ref/server.sh --map teamball --robots 3 +restrictRobots
tools/xpilot-ref/server.sh --map planetx --seconds 5 --trace --seed 7 -gravity -0.3
```

- `--robots N` passes `-robots N -minRobots N`. With these, the server keeps
  exactly N robots, adding one about every 2 seconds.
- `-rawMode` is added unless you pass `--no-raw`. Without it, the 4.5.5
  server neither simulates nor adds robots until a human joins.
- The server gets SIGINT after `--seconds`.
- Any other argument goes to `xpilots` unchanged.

### screenshot.sh

```sh
screenshot.sh --map NAME --out FILE.png [--robots N] [--after S] [--size WxH]
              [--keys "ACTIONS"] [--name NICK] [--seed N]
              [--server-opt OPT]... [--client-opt OPT]... [--keep-logs DIR]

tools/xpilot-ref/screenshot.sh --map default --robots 3 --after 8 --out /tmp/default.png
tools/xpilot-ref/screenshot.sh --map default --robots 0 --after 2 \
    --keys "down:Shift_L sleep:2" --out /tmp/thrust.png        # thrust held for 2s, still held at capture
tools/xpilot-ref/screenshot.sh --map teamball --server-opt +restrictRobots --out /tmp/tb.png
tools/xpilot-ref/screenshot.sh --map default --size 800x600 --out /tmp/small.png
```

- The `--after S` countdown starts once the server log shows the human ship
  placed on a base. This is about 3 to 4 seconds after start, because the
  client window appears before login and map transfer finish.
- `--keys` takes xdotool actions separated by spaces:
  - `down:KEY` and `up:KEY` press and release a key;
  - `key:KEY` taps a key;
  - `sleep:SECS` waits;
  - `type:TEXT` types text.

  Keys use XTEST after the client window has been given focus. Any key left
  down is still held during the capture.
- Default client keys: thrust is `Shift_L` or `Shift_R`, turning is `a` and
  `s`, and fire is `Return`. The full list is in `xpilot -help` or
  `src/client/default.c`.
- `--server-opt` and `--client-opt` each pass one argument word. Repeat the
  flag for an option that takes a value, for example
  `--server-opt -gravity --server-opt -0.5`.
- A run takes about 9 to 16 seconds of wall time with `--after 8`. Most of
  that is the wait.

### frames.sh

This script is for reviewing motion. Reviewers can read images but not
video.

```sh
frames.sh --map NAME --out-dir DIR [--frames N] [--interval S] [--tile CxR] [screenshot.sh options]

tools/xpilot-ref/frames.sh --map default --robots 0 --seed 1 --after 1 \
    --keys "down:Shift_L" --frames 8 --interval 0.25 --out-dir /tmp/thrust-frames
```

The output is `frame_001.png` … `frame_NNN.png` at full size, plus
`contact.png`. The contact sheet uses ImageMagick `montage`, half-size
tiles, 4 columns by default, with each tile labelled by its frame name. For
image viewers that limit size, downscale it with `sips -Z 1400 contact.png`.
Each capture takes 0.1 to 0.2 seconds, so frames are a little further apart
than `--interval`.

### trace.sh

This script gives numeric physics output from the original server. It
relies on patch 0002.

```sh
trace.sh --map NAME --out FILE.txt [--after S] [--keys "ACTIONS"] [--robots N]
         [--all-players] [--objs] [--modes] [--map-file F] [--seed N] [--png FILE.png]
         [--server-opt OPT]...

tools/xpilot-ref/trace.sh --map default --seed 42 --after 1 \
    --keys "down:Shift_L sleep:1 up:Shift_L sleep:1" --out /tmp/thrust-trace.txt
```

- The default is 0 robots, and only the human player is traced.
  `--all-players` traces robots too.
- Each server update tick prints one line for each traced player:

  ```
  XPTRACE_HDR loop frame id robot x y cx cy vx vy ax ay dir float_dir turnvel turnacc power mass fuel thrusting status name
  XPTRACE 60 15 1 0 4252 1459 272160 93396 -4.4e-16 -1.1635 -3.3e-16 -1.8010 96 96 0 0 45 24.98 255226 1 0x809 Refpilot
  ```

- What the columns mean:
  - `loop` is `main_loops`, the server tick counter.
  - `frame` is `frame_loops`, which only counts ticks where a human is
    present.
  - `x` and `y` are in pixels. `cx` and `cy` are in clicks, where 1 pixel
    is 64 clicks.
  - `vx`/`vy` and `ax`/`ay` are the `pl->vel` and `pl->acc` values.
  - `dir` runs from 0 to 127, where 128 is a full turn.
  - `fuel` is `pl->fuel.sum`, in fixed point.
  - `thrusting` is the THRUSTING status bit.
  - `name` is the last field and can contain spaces, as robot names do
    (for example "Mad Max").
- Lines are printed right after `Update_objects()` in `Main_loop()`.
- `samples/thrust-trace.txt` is a real example: the ship sits on its base,
  thrusts for 12 ticks at 12 FPS, then coasts.
- Key timing is wall-clock, through X and the network, so thrust can start
  and stop one tick earlier or later from run to run. To compare traces,
  line them up on the first `thrusting=1` tick, not on the loop number.
- `--seed N` sets `XPILOT_SEED`, which fixes the server's random number
  generator. With the same seed, robot names and start positions repeat.
  For example, seed 42 on default.xp always puts the ship at 4252,1452.

`--objs` sets `XPILOT_TRACE_OBJS` and needs an image built with patch 0004
(every image built from this directory has it). Each tick then also prints,
after the XPTRACE lines: one `XPOBJ` line per object in `Obj[]` except
sparks and debris, one `XPPULSE` line per laser pulse, one `XPECM` line per
active ECM, and one `XPITEMS` line per traced player (items, used/have
bits, fuel tanks, device timers, lock). The column lists are in the
`*_HDR` lines at the top of the output and in
`server/src/test/resources/ctrace/items/README.txt`.

```sh
XPREF_IMAGE=xpilot-ref:items tools/xpilot-ref/build.sh
XPREF_IMAGE=xpilot-ref:items tools/xpilot-ref/trace.sh --objs --map default --seed 42 \
    --after 1 --server-opt -initialMissiles --server-opt 4 \
    --keys "sleep:0.5 down:quoteright sleep:0.15 up:quoteright sleep:2" --out /tmp/torp.txt
```

`--modes` sets `XPILOT_TRACE_MODES` and needs an image built with patch 0005.
Each tick then also prints

```
XPMOBJ loop frame ptr type cx cy prevx prevy vx vy life id team status mass extra...
XPPL2  loop frame id used have ball check round time best_lap last_lap last_lap_time mychar
XPTRS  loop frame index have destroyed
XPTGT  loop frame index damage dead_time
XPTEAM loop frame team treasures_left treasures_destroyed score
```

for every ball, asteroid and wreckage object (balls: `owner treasure length`; asteroids and wreckage:
`size rotation turnspeed info`), every player, treasure, target and team with treasures. `ptr` is the C
object's address: an object keeps it while it lives. `--map-file FILE` runs a map from the host (it is
copied next to the outputs). The C server also reads `lib/defaults` (gravity -0.25, random items), so pin
those options in such a map. Example (the ball connector trace of `ModeTraceTest`):

```sh
XPREF_IMAGE=xpilot-ref:server-modes tools/xpilot-ref/trace.sh \
    --map-file server/src/test/resources/ctrace/ball-tow.xp --seed 42 --after 1 --modes \
    --keys "down:Control_L down:Shift_L sleep:0.6 up:Shift_L sleep:3 up:Control_L sleep:0.5" --out /tmp/ball.txt
```

You can also run the server in raw mode with only robots and trace them:
`server.sh --trace --seed 1 ...`.

## Samples (all checked by eye)

| File | Contents |
|---|---|
| `samples/default.png` | `default.xp` ("The Newbie Globe") with 3 robots and seed 1: ship on its base, HUD, radar, score list and messages. |
| `samples/teamball.png` | `teamball.xp` with 3 robots and `+restrictRobots`: team score list, balls, team bases. |
| `samples/planetx.png` | `planetx.xp` with 3 robots. |
| `samples/thrust.png` | `default.xp` with `Shift_L` held for 2 seconds: exhaust particles, ship away from its base. |
| `samples/thrust-contact.png` | 8 frames 0.25 s apart with thrust held, downscaled to 1400 px wide. |
| `samples/thrust-trace.txt` | Trace from the `trace.sh` example above. |

## Timings (M-series Mac, arm64, Docker Desktop 29.8)

| Step | Time or size |
|---|---|
| `build.sh --no-cache`, base image already pulled | 26 s. The compile itself is about 15 s. |
| Image size | 385 MB |
| `screenshot.sh --after 8` | 9.5 to 16 s wall time. planetx.xp starts slowest. |
| `screenshot.sh --after 2` with a 2 s key script | about 9 s |
| `frames.sh`, 8 frames | about 6 to 9 s |
| `server.sh --seconds 9` | about 10 s |

## Distro choice and patches

### Distro: Debian bookworm (`debian:bookworm-slim`, GCC 12.2)

- On GCC 12, implicit function declarations, int-conversion and
  incompatible-pointer-types are warnings, not errors. The stock upstream
  Imake build therefore compiles with no extra flags.
- GCC 14, used by trixie and Ubuntu 24.04, makes those diagnostics errors,
  so it would need `-std=gnu89` or several `-Wno-error=` flags.
- `xutils-dev` provides `xmkmf` and imake, so the build follows the
  original `xmkmf -a && make && make install` path. Makefile.std is not
  used.
- The only failure is one glibc name clash, fixed by patch 0001.
- The full build log is inside the image at
  `/usr/local/share/xpilot-ref/make.log`.
- The remaining warnings are mainly about 30 pointer/int casts. Most are an
  int index passed through a `void *` (netserver.c) and `XPointer` data in
  the replay and map-editor tools. None of them are implicit declarations.
- The Local.config defaults are used unchanged: `PREFIX=/usr/local`,
  `CDEBUGFLAGS=-O`, WindowScaling on, no sound, no DBE.
- The server's compiled-in `LIBDIR` is `/usr/local/lib/xpilot/`. Maps are
  at `/usr/local/lib/xpilot/maps/*.xp`, so `-map default` finds
  `default.xp`.

### Patches

Each patch file has a header saying it modifies XPilot 4.5.5 under
GPL-2.0-or-later, and why.

1. `0001-client-rename-getline.patch`: renames
   `static char *getline(char*, int, FILE*)` to `xp_getline` in
   `src/client/textinterface.c`. It clashes with POSIX `getline(3)` in
   glibc's `<stdio.h>`. There is no change in behaviour.
2. `0002-server-physics-trace.patch`, in `src/server/server.c` only:
   - adds `Trace_players()`, called after `Update_objects()`. It does
     nothing unless `XPILOT_TRACE` is set. `XPILOT_TRACE_NAME` filters the
     output to one player.
   - lets `XPILOT_SEED=<n>` replace the `time*pid` seed for `seedMT()`.

   Without those environment variables, the server behaves exactly as
   upstream.
3. `0003-server-wreckage-trace.patch`, in `src/server/server.c` and
   `src/server/ship.c`: with `XPILOT_TRACE_WRECK` set, `Make_wreckage`
   prints its arguments (`XPWMAKE`) and every `rfrac()` value it draws
   (`XPWRAND`), and after each `Update_objects()` every wreckage object is
   printed (`XPWRECK`). Floats are printed with `%a`, so they are exact.
   The game and its random sequence are unchanged. Used for
   `server/src/test/resources/ctrace/wreckage-default.txt`:

   ```sh
   CDPATH= XPREF_IMAGE=xpilot-ref:4.5.5-sb2 tools/xpilot-ref/build.sh   # or rebuild the default tag
   docker run --rm --network none -e XPILOT_TRACE_WRECK=1 -e XPILOT_SEED=1 xpilot-ref:4.5.5-sb2 \
       timeout -s INT -k 5 180 xpilots -map default -robots 4 -minRobots 4 -noQuit +reportToMetaServer \
       -rawMode 2> wreck.trace
   grep -E '^XPW(MAKE|RAND|RECK) ' wreck.trace > wreckage-default.txt
   ```

   Images built before this patch lack it; rebuild with `build.sh`.
4. `0004-server-object-trace.patch`, in `src/server/server.c` only, on top of
   0003: adds `Trace_objects()`, called right after `Trace_wreckage()`. It
   does nothing unless `XPILOT_TRACE_OBJS` is set; `XPILOT_TRACE_NAME`
   filters the XPITEMS lines like the XPTRACE lines.
5. `0005-server-mode-trace.patch`, in `src/server/server.c` only, on top of
   0004: adds `Trace_modes()`, called right after `Trace_objects()`, which prints the
   XPMOBJ/XPPL2/XPTRS/XPTGT/XPTEAM lines when `XPILOT_TRACE_MODES` is set
   (`trace.sh --modes`). Inert otherwise.

## Known quirks

- **Window size.** The client's built-in default is 1026x768
  (`DEF_TOP_WIDTH` = 1024+2), and the source documents 1282x1024 as the
  maximum. `--size` sets both the Xvfb screen and `-geometry WxH+0+0`.
  There is no window manager, so captures (`xwd -id`) contain only the
  client window: radar, buttons, score list and the play area. They have
  no title bar.
- **Fonts.** The client asks for Times (`-*-times-*-*-*--18-*`, used for
  the HUD and messages), Courier for the MOTD and fixed fonts for the score
  list. These come from `xfonts-75dpi`, `xfonts-100dpi` and `xfonts-base`,
  and the italic Times in the samples is the real font. ImageMagick
  `montage` aborts (SIGABRT) unless it is given an explicit font, so the
  harness passes `-font DejaVu-Sans` from `fonts-dejavu-core`.
- **X resources.** The client class is `XPilot`. It reads `~/.xpilotrc`
  (or `$XPILOTRC`), `$XUSERFILESEARCHPATH`, `$XENVIRONMENT` and the X
  server's `RESOURCE_MANAGER` property. None of these exist in the
  container, so compiled-in defaults apply. Use `--client-opt` to change
  any client option, for example `--client-opt -showHUD --client-opt no`.
- **Boolean options.** The server turns a boolean on with `-option` and off
  with `+option`. Passing `-opt no` is wrong: the server reports "Unknown
  option 'no'". The client takes `-opt yes|no` for options that have an
  argument.
- **Robots on teamball.** On team maps, `restrictRobots` (on by default)
  puts robots only on `robotTeam`. On teamball that team has no bases, so
  no robots appear. Pass `+restrictRobots`.
- **No network.** Containers run with `--network none`, and the scripts
  pass `+reportToMetaServer`, so nothing reaches meta.xpilot.org. Client
  and server talk over loopback inside one container.
- **Nick capitalisation.** The server capitalises the first letter of the
  nick: `refpilot` becomes `Refpilot`. `trace.sh` handles this for
  `XPILOT_TRACE_NAME`.
- **Robots join slowly.** Robots join about 2 seconds apart, so with
  `--robots 3`, use `--after` of 6 seconds or more if all three should be
  on the score list.
- **xdotool and focus.** Without a window manager, `xdotool --sync` waits
  about 18 seconds for events that never come. The harness uses plain
  `windowfocus` followed by a 0.1 second sleep.
- **Floating point.** This is an arm64 build, and `DFLOAT` is `float` in
  4.5.5. Low-order float digits could differ from an x86 build.
- **Untagged images.** A rebuild leaves the previous `xpilot-ref:4.5.5`
  image untagged (`<none>`). Clean up only by image ID, after checking with
  `docker image ls`. Never prune globally.

## Safety conventions

- Every container is named `<prefix>-<kind>-<pid>-<rand>` (prefix
  `$XPREF_CNAME_PREFIX`, default `xpilot-ref`) and runs with `--rm`.
- There are two timeouts:
  - inside the container, `timeout` wraps the server or scenario;
  - on the host, a deadline stops that container by name and then kills
    the docker CLI process that the script started, by PID.
- Nothing kills by pattern.
- The only image created is `xpilot-ref:4.5.5`.

## GPL provenance

XPilot is Copyright (C) 1991-2010 by Bjørn Stabell, Ken Ronny Schouten,
Bert Gijsbers, Dick Balaska and contributors. It is licensed under
GPL-2.0-or-later.

- This directory does **not** contain the XPilot source. The source is
  read at build time from the path in `XPILOT_SRC`, using the Docker named
  build context `xpsrc`.
- The files in `patches/` are derivative works of XPilot 4.5.5 and are
  distributed under GPL-2.0-or-later.
- The built image contains GPL binaries and data such as maps. If you
  distribute it, make the corresponding source available: the upstream
  tarball plus these patches.
- The harness scripts and the Dockerfile do not contain XPilot code.
