#!/usr/bin/env bash
# Regenerates the guide's gallery pictures (docs/guide/images/gallery/) with the real client drawing
# code. Needs a display with OpenGL 3.3 (it opens a window briefly), JDK 25, Maven and ffmpeg.
#
#   docs/guide/tools/capture-gallery.sh            (from anywhere in the repository)
#
# It builds the dist module's test classes in-reactor, runs GalleryCapture on the fixture map
# docs/guide/gallery/features.xp and race.xp (scenes and crops are in dist/src/test/java/.../gallery/GalleryShots.java),
# writes still pictures as PNG and turns frame sequences into looping GIFs. The run ends by itself.
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
du -sh "$out"
