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
"""Turn the staged source clips into JXPilot's sound resources (python3 -I, stdlib only).

usage: python3 -I process_sounds.py CLIPS_TSV STAGE_DIR OUT_DIR

STAGE_DIR holds the sources as build-sounds.sh extracts them: junkala/<path> (16-bit 44.1 kHz mono
WAV) and kenney/<path>.wav (the OGG decoded by ffmpeg to the same format). For every clip:
  * Junkala clips are copied, faded out linearly over their last FADE_OUT seconds (some end on a
    non-zero sample, which clicks), then peak-normalized to PEAK_DBFS.
  * thrust_loop.wav: cut LOOP_SECONDS starting at LOOP_START, crossfade the first XFADE seconds
    with the audio that follows the cut (equal-power sin/cos curves), so the end flows into the start
    without a click; then normalized to LOOP_RMS_DBFS RMS (a continuous sound sits under the one-shots).
Prints a report of every output clip: duration, peak and RMS in dBFS, and the gain applied.
Deterministic: the same inputs give byte-identical outputs.
"""
import array
import math
import os
import sys
import wave

RATE = 44100
PEAK_DBFS = -6.0
LOOP_START = 0.3
LOOP_SECONDS = 2.0
XFADE = 0.25
LOOP_RMS_DBFS = -24.0
FADE_OUT = 0.005
FULL = 32767.0


def read_wav(path):
    with wave.open(path, 'rb') as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1 or w.getframerate() != RATE:
            sys.exit('%s: need 16-bit mono %d Hz, got %d-bit %d ch %d Hz' % (
                path, RATE, 8 * w.getsampwidth(), w.getnchannels(), w.getframerate()))
        data = array.array('h')
        data.frombytes(w.readframes(w.getnframes()))
        if sys.byteorder == 'big':
            data.byteswap()
        return [float(s) for s in data]


def write_wav(path, samples):
    data = array.array('h', [max(-32768, min(32767, int(round(s)))) for s in samples])
    if sys.byteorder == 'big':
        data.byteswap()
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


def peak(samples):
    return max(abs(s) for s in samples) if samples else 0.0


def rms(samples):
    return math.sqrt(sum(s * s for s in samples) / len(samples)) if samples else 0.0


def dbfs(level):
    return 20 * math.log10(level / FULL) if level > 0 else float('-inf')


def fade_out(samples):
    n = min(len(samples), int(round(FADE_OUT * RATE)))
    for i in range(n):
        samples[len(samples) - n + i] *= (n - 1 - i) / n
    return samples


def make_loop(src):
    """LOOP_SECONDS from LOOP_START; the first XFADE seconds are mixed with what follows the cut."""
    start = int(round(LOOP_START * RATE))
    n = int(round(LOOP_SECONDS * RATE))
    x = int(round(XFADE * RATE))
    if start + n + x > len(src):
        sys.exit('thrust source too short: %d samples' % len(src))
    out = src[start:start + n]
    for i in range(x):
        t = (i + 0.5) / x
        out[i] = src[start + i] * math.sin(t * math.pi / 2) + src[start + n + i] * math.cos(t * math.pi / 2)
    return out


def main(argv):
    if len(argv) != 4:
        sys.exit(__doc__)
    clips, stage, out_dir = argv[1], argv[2], argv[3]
    os.makedirs(out_dir, exist_ok=True)
    print('%-18s %7s %8s %8s %8s' % ('clip', 'seconds', 'peak', 'rms', 'gain'))
    with open(clips, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            clip, pack, path = line.split('\t')
            if pack == 'junkala':
                samples = fade_out(read_wav(os.path.join(stage, 'junkala', path)))
                gain = 10 ** (PEAK_DBFS / 20) * FULL / peak(samples)
            elif pack == 'kenney':
                samples = make_loop(read_wav(os.path.join(stage, 'kenney', path + '.wav')))
                gain = 10 ** (LOOP_RMS_DBFS / 20) * FULL / rms(samples)
            else:
                sys.exit('unknown pack ' + pack)
            samples = [s * gain for s in samples]
            if peak(samples) > FULL:
                sys.exit('%s clips after gain %.2f' % (clip, gain))
            write_wav(os.path.join(out_dir, clip), samples)
            print('%-18s %7.3f %8.1f %8.1f %+7.1fdB' % (
                clip, len(samples) / RATE, dbfs(peak(samples)), dbfs(rms(samples)), 20 * math.log10(gain)))


if __name__ == '__main__':
    main(sys.argv)
