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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The server's sound API, a port of C {@code saudio.c}. Simulation code calls one of these where C calls
 * {@code sound_play_*}; the sound lands in each hearing player's {@link Player#audio} queue and goes out in
 * that player's next snapshot (SNAPSHOT section SOUND, docs/PROTOCOL.md):
 * <ul>
 *   <li>{@link #play(World, double, double, SoundEvent)}: C {@code sound_play_sensors(x, y, sound)}, a sound at
 *       a place, heard by every player within its sensor range, quieter with distance;</li>
 *   <li>{@link #playAll(World, SoundEvent)}: C {@code sound_play_all(sound)}, everyone at full volume;</li>
 *   <li>{@link #playPlayer(Player, SoundEvent)}: C {@code sound_play_player(pl, sound)}, one player at full
 *       volume.</li>
 * </ul>
 * Robots never hear anything (C: no connection, no {@code WANT_AUDIO}). Positions are world pixels, as C's
 * {@code pos.x}. Allocation free.
 */
public final class Sounds {
    /** C {@code SOUND_DEFAULT_RANGE}: 15 blocks. */
    public static final double DEFAULT_RANGE = Const.BLOCK_SZ * 15;
    /** C {@code SOUND_RANGE_FACTOR}: each sensor adds half the default range. */
    public static final double RANGE_FACTOR = 0.5;

    private Sounds() {}

    /**
     * Initialize {@link SoundEvent} now (its constants and tables), so the first sound of a game does not
     * allocate while the JVM loads the enum lazily.
     */
    static void initClasses() {
        SoundEvent.of(0);
    }

    /**
     * C {@code sound_play_sensors}.
     * <p>
     * BEHAVIOR: every human player in the game whose {@link #volume} for the distance is positive queues the
     * sound at that volume; robots and players out of range queue nothing. Distances are plain coordinate
     * differences, without wrapping, as in C.
     */
    public static void play(World w, double x, double y, SoundEvent e) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (pl.robot) {
                continue;
            }
            // out of range is volume 0, which the queue ignores
            pl.audio.queue(e.ordinal(), volume(Math.abs(pl.px - x), Math.abs(pl.py - y), range(pl)));
        }
    }

    /** BEHAVIOR: every human player in the game queues the sound at {@link SoundEvent#MAX_VOLUME}. */
    public static void playAll(World w, SoundEvent e) {
        for (int k = 0; k < w.numPlayers; k++) {
            playPlayer(w.playerAt(k), e);
        }
    }

    /** BEHAVIOR: the player queues the sound at {@link SoundEvent#MAX_VOLUME}, unless it is a robot. */
    public static void playPlayer(Player pl, SoundEvent e) {
        if (!pl.robot) {
            pl.audio.queue(e.ordinal(), SoundEvent.MAX_VOLUME);
        }
    }

    /** BEHAVIOR: C {@code sound_range}: {@link #DEFAULT_RANGE} plus half of it per sensor item. */
    public static double range(Player pl) {
        return DEFAULT_RANGE + pl.items[Item.SENSOR.ordinal()] * DEFAULT_RANGE * RANGE_FACTOR;
    }

    /**
     * The volume of a sound {@code dx, dy} (absolute) pixels away for a hearing range (C
     * {@code sound_play_sensors}).
     * <p>
     * BEHAVIOR: 0 (not heard) when either distance exceeds the range; otherwise
     * {@code (int) max(100 - 100 * max(dx, dy) / range, 10)}: 100 at the listener, falling linearly with the
     * larger axis distance, never below {@link SoundEvent#MIN_VOLUME} at the edge.
     */
    public static int volume(double dx, double dy, double range) {
        if (dx > range || dy > range) {
            return 0;
        }
        double factor = Math.max(dx, dy) / range;
        return (int) Math.max(SoundEvent.MAX_VOLUME - SoundEvent.MAX_VOLUME * factor, SoundEvent.MIN_VOLUME);
    }
}
