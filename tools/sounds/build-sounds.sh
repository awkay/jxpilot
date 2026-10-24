#!/bin/bash
# JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
# Copyright (C) 2026 Tony Kay and the JXPilot contributors.
#
# Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
# Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
# independent derivative port: the original XPilot authors were not involved
# in it and do not endorse it. Ported to Java and modified starting in 2026;
# see the git history for the changes.
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation; either version 2 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program; if not, see <https://www.gnu.org/licenses/>.
#
# Rebuild client/src/main/resources/sounds/*.wav from the original CC0 packs (docs/SOUND-PLAN.md).
#
# usage: tools/sounds/build-sounds.sh [OUT_DIR]
#   OUT_DIR  default: client/src/main/resources/sounds
#   WORK     scratch directory (default: a new mktemp -d); downloads go into new, empty
#            WORK/dl-junkala and WORK/dl-kenney, sources are staged in WORK/stage.
# Needs curl, unzip, shasum or sha256sum, ffmpeg (decodes the Kenney OGG) and python3.
#
# Each pack is verified against its sha256 before anything is extracted, only the clips listed in
# clips.tsv are extracted, and nothing is run from inside the download directories.
# process_sounds.py does the thrust-loop cut/crossfade and the level normalization.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out=${1:-$root/client/src/main/resources/sounds}
work=${WORK:-$(mktemp -d)}

JUNKALA_URL='https://opengameart.org/sites/default/files/The%20Essential%20Retro%20Video%20Game%20Sound%20Effects%20Collection%20%5B512%20sounds%5D.zip'
JUNKALA_SHA=6d4519229c6e2c9e09502cd4967940052f68664140549a467615b56bdfd1d031
# unzip patterns treat [ ] as wildcards, so '?' stands for each bracket.
JUNKALA_ROOT='The Essential Retro Video Game Sound Effects Collection ?512 sounds? By Juhani Junkala'
KENNEY_URL='https://kenney.nl/media/pages/assets/sci-fi-sounds/6b296f9ecf-1677589334/kenney_sci-fi-sounds.zip'
KENNEY_SHA=119340f351a5098ad814f78719438c0da355a9ce8a4c8a3af6a8d48aa3d49e04

die() { echo "build-sounds: $*" >&2; exit 1; }

sha256() {
    if command -v shasum >/dev/null; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi
}

# fetch NAME URL SHA: download into a new empty WORK/dl-NAME and verify; prints the zip path.
fetch() {
    local dir="$work/dl-$1"
    mkdir "$dir" || die "$dir already exists"
    curl -fsSL -m 600 -o "$dir/pack.zip" "$2"
    local got
    got=$(sha256 "$dir/pack.zip")
    [ "$got" = "$3" ] || die "$1: sha256 $got, expected $3"
    echo "$dir/pack.zip"
}

junkala=$(fetch junkala "$JUNKALA_URL" "$JUNKALA_SHA")
kenney=$(fetch kenney "$KENNEY_URL" "$KENNEY_SHA")
stage="$work/stage"
mkdir -p "$stage"

while IFS=$'\t' read -r clip pack path; do
    case "$clip" in ''|'#'*) continue ;; esac
    mkdir -p "$stage/$pack/$(dirname "$path")"
    case "$pack" in
        junkala) unzip -p "$junkala" "$JUNKALA_ROOT/$path" > "$stage/junkala/$path" ;;
        kenney)
            unzip -p "$kenney" "$path" > "$stage/kenney/$path"
            ffmpeg -nostdin -loglevel error -y -i "$stage/kenney/$path" -ac 1 -ar 44100 -c:a pcm_s16le \
                -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$stage/kenney/$path.wav" ;;
        *) die "unknown pack $pack" ;;
    esac
    [ -s "$stage/$pack/$path" ] || die "$clip: $path not found in the $pack pack"
done < "$here/clips.tsv"

python3 -I "$here/process_sounds.py" "$here/clips.tsv" "$stage" "$out"
echo "wrote $out (work files in $work)"
