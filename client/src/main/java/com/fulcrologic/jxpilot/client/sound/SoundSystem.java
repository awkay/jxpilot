/*
 * JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
 * Copyright (C) 2026 Tony Kay and the JXPilot contributors.
 *
 * Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
 * Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
 * independent derivative port: the original XPilot authors were not involved
 * in it and do not endorse it. Ported to Java and modified starting in 2026;
 * see the git history for the changes.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package com.fulcrologic.jxpilot.client.sound;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Builds the client's {@link SoundPlayer} (C {@code audioInit}): opens the audio device, loads
 * {@code sounds/sounds.map} and every clip it names from the classpath, and falls back to a silent player
 * (the game runs without sound) when sound is off, {@code maxVolume} is 0, there is no device, the OpenAL
 * library cannot load, or a clip cannot be read.
 */
public final class SoundSystem {
    /** Classpath folder of the clips and the map. */
    public static final String DIR = "/sounds/";
    /** The sound map resource. */
    public static final String MAP = DIR + "sounds.map";

    /** Opens the device; may return null (reason in {@code why[0]}) or throw (e.g. no native library). */
    @FunctionalInterface
    public interface Opener {
        AudioDevice open(String[] why) throws Throwable;
    }

    private SoundSystem() {}

    /** The real device: OpenAL's default output with {@link SoundPlayer#VOICES} voices. */
    public static AudioDevice openAl(String[] why) {
        return OpenAlDevice.open(null, SoundPlayer.VOICES, why);
    }

    /**
     * BEHAVIOR: sound off or {@code maxVolume} 0: a silent player, without touching the opener (C prints
     * "maxVolume is 0: no sound.").
     * BEHAVIOR: an opener that returns null or throws gives a silent player and a log line with the reason.
     * BEHAVIOR: otherwise every clip of the map is loaded into the device and the player plays on it at
     * {@code maxVolume}; a clip that cannot be read or parsed closes the device and gives a silent player.
     */
    public static SoundPlayer open(boolean enabled, int maxVolume, Opener opener, PrintStream log) {
        return open(enabled, maxVolume, opener, log, DIR);
    }

    /** {@link #open(boolean, int, Opener, PrintStream)} with the map and clips in classpath folder {@code dir}. */
    static SoundPlayer open(boolean enabled, int maxVolume, Opener opener, PrintStream log, String dir) {
        if (!enabled || maxVolume <= 0) {
            log.println(enabled ? "maxVolume is 0: no sound." : "sound off.");
            return silent(maxVolume);
        }
        String[] why = {"unknown reason"};
        AudioDevice dev;
        try {
            dev = opener.open(why);
        } catch (Throwable t) {
            dev = null;
            why[0] = t.toString();
        }
        if (dev == null) {
            log.println("no sound: " + why[0]);
            return silent(maxVolume);
        }
        try {
            SoundMap map = SoundMap.parse(text(dir + "sounds.map"));
            int[] handles = new int[map.clipCount()];
            for (int c = 0; c < handles.length; c++) {
                handles[c] = dev.load(Wav.parse(bytes(dir + map.clipName(c))));
            }
            SoundPlayer p = new SoundPlayer(dev, map, handles);
            p.setMaxVolume(maxVolume);
            return p;
        } catch (IOException | RuntimeException e) {
            dev.close();
            log.println("no sound: " + e.getMessage());
            return silent(maxVolume);
        }
    }

    private static SoundPlayer silent(int maxVolume) {
        SoundPlayer p = new SoundPlayer(new AudioDevice.Silent(SoundPlayer.VOICES), SoundMap.empty(), new int[0]);
        p.setMaxVolume(maxVolume);
        return p;
    }

    static String text(String resource) throws IOException {
        return new String(raw(resource), StandardCharsets.UTF_8);
    }

    /** A resource in a direct buffer (OpenAL reads clip data from native memory). */
    static ByteBuffer bytes(String resource) throws IOException {
        byte[] b = raw(resource);
        return ByteBuffer.allocateDirect(b.length).put(b).flip();
    }

    private static byte[] raw(String resource) throws IOException {
        try (InputStream in = SoundSystem.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }
            return in.readAllBytes();
        }
    }
}
