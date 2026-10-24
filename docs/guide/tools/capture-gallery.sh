#!/usr/bin/env bash
# Regenerates the guide's gallery pictures (docs/guide/images/gallery/) with the real client drawing
# code. Needs a display with OpenGL 3.3 (it opens a window briefly), JDK 25, Maven and ffmpeg.
#
#   docs/guide/tools/capture-gallery.sh            (from anywhere in the repository)
#
# It builds the dist module's test classes in-reactor, runs GalleryCapture on the fixture map
# docs/guide/gallery/features.xp and race.xp (scenes and crops are in dist/src/test/java/.../gallery/GalleryShots.java),
# writes still pictures as PNG and turns frame sequences into looping GIFs. Then it runs the real client
# (the client's DevRun harness: GameApp against its scripted server on features.xp) a few times, with the
# button bar, MENU, CONFIG and ABOUT scripted and with look options set, and crops its screenshots for
# the screen, panel, window and look pictures. Every run ends by itself.
set -euo pipefail

unset CDPATH
root=$(cd "$(dirname "$0")/../../.." && pwd)
out="$root/docs/guide/images/gallery"
work="$root/dist/target/gallery"
frames="$work/frames"
cpfile="$work/classpath.txt"

rm -rf "$work" "$out"
mkdir -p "$work" "$out"
cd "$root"
MAVEN_OPTS= mvn -q -o -pl dist -am test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile="$cpfile" > "$work/mvn.log" 2>&1 \
  || { cat "$work/mvn.log"; exit 1; }

# headless AWT: ImageIO must not start the AWT toolkit, which deadlocks with GLFW on the macOS main thread
jvm=(-Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow)
case "$(uname -s)" in Darwin) jvm+=(-XstartOnFirstThread) ;; esac

java "${jvm[@]}" -cp "dist/target/test-classes:dist/target/classes:$(cat "$cpfile")" \
  com.fulcrologic.jxpilot.dist.gallery.GalleryCapture \
  "$out" "$frames" docs/guide/gallery/features.xp docs/guide/gallery/race.xp

# One looping GIF per frame sequence, at the game's 14 frames per second, with an optimised palette.
for dir in "$frames"/*/; do
  name=$(basename "$dir")
  ffmpeg -v error -y -framerate 14 -i "$dir/f%03d.png" \
    -filter_complex "[0:v]split[a][b];[a]palettegen=max_colors=32:reserve_transparent=0[p];[b][p]paletteuse=dither=none" \
    -loop 0 "$out/$name.gif"
  echo "wrote $name.gif"
done

# The real client: DevRun ([client options] --out DIR --start F --every N --count K --script NAME).
cp="client/target/test-classes:dist/target/test-classes:$(cat "$cpfile")"
devrun() {
  local dir="$work/app/$1"
  shift
  java "${jvm[@]}" -cp "$cp" com.fulcrologic.jxpilot.client.app.DevRun \
    --scripted-server docs/guide/gallery/features.xp --no-sound --name Pilot --out "$dir" "$@" \
    > "$dir.log" 2>&1 || { cat "$dir.log"; exit 1; }
}
mkdir -p "$work/app"
# shot-00 frame 30: the plain game; shot-01 frame 70: the talk line open with a typed line
devrun plain --script talk --start 30 --every 40 --count 2
# frames 35 MENU held over CONFIG, 45 CONFIG page 1, 55 page 2, 65 page 2 (still), 75 ABOUT over CONFIG
devrun ui --script ui --start 35 --every 10 --count 5
devrun look --script none --start 60 --every 10 --count 1 --clock yes --packetSizeMeter yes \
  --packetLossMeter yes --packetDropMeter yes --packetLagMeter yes --texturedWalls yes --texturedObjects yes
devrun border --script none --start 60 --every 10 --count 1 --scale-factor 1.5
devrun colours --script none --start 60 --every 10 --count 1 --color2 SteelBlue --color4 orange \
  --color3 HotPink

# crop SHOT NAME X Y W H: a region in logical pixels of the 1280x800 window (any pixel ratio)
crop() {
  ffmpeg -v error -y -i "$work/app/$1.png" \
    -vf "crop=w=iw*$5/1280:h=ih*$6/800:x=iw*$3/1280:y=ih*$4/800" "$out/$2.png"
  echo "wrote $2.png"
}
cp "$work/app/plain/shot-00.png" "$out/screen.png"
crop plain/shot-00 radar 0 0 256 190
crop plain/shot-00 button-bar 0 190 256 28
crop plain/shot-00 score-list 0 190 256 140
crop plain/shot-01 talk-line 296 584 944 40
crop plain/shot-01 messages 262 752 290 44
crop ui/shot-00 menu 0 190 256 150
crop ui/shot-01 config-1 0 190 256 610
crop ui/shot-02 config-2 0 190 256 610
crop ui/shot-04 about 464 44 610 712
crop look/shot-00 look-panel 0 0 256 800
crop look/shot-00 look-textures 620 50 380 540
crop look/shot-00 look-meters 1010 70 270 90
crop border/shot-00 visible-border 256 0 1024 800
crop colours/shot-00 look-colours 540 50 560 560
du -sh "$out"
