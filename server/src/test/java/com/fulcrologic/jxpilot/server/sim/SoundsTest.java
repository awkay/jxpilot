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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static com.fulcrologic.jxpilot.server.sim.SoundSitesTest.heard;
import static com.fulcrologic.jxpilot.server.sim.SoundSitesTest.quiet;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Sounds (saudio.c)")
class SoundsTest {
    @Nested
    @DisplayName("volume and range (sound_play_sensors, sound_range)")
    class Math {
        @Test
        @DisplayName("100 at the listener, linear in the larger axis distance, at least 10 in range, 0 beyond")
        void volume() {
            assertAll(
                () -> assertEquals(100, Sounds.volume(0, 0, 525), "full volume at the listener"),
                () -> assertEquals(50, Sounds.volume(262.5, 0, 525), "half the range is half volume"),
                () -> assertEquals(50, Sounds.volume(100, 262.5, 525), "the larger axis distance counts (y)"),
                () -> assertEquals(50, Sounds.volume(262.5, 100, 525), "the larger axis distance counts (x)"),
                () -> assertEquals(93, Sounds.volume(35, 0, 525), "93.33 truncates to 93"),
                () -> assertEquals(10, Sounds.volume(500, 0, 525), "4.8 is raised to the minimum 10"),
                () -> assertEquals(10, Sounds.volume(525, 525, 525), "exactly at the range edge is heard at 10"),
                () -> assertEquals(0, Sounds.volume(525.5, 0, 525), "beyond the range in x is not heard"),
                () -> assertEquals(0, Sounds.volume(0, 525.5, 525), "beyond the range in y is not heard"));
        }

        @Test
        @DisplayName("the range is 15 blocks plus half that per sensor")
        void range() {
            Player p = new Player(0);
            double none = Sounds.range(p);
            p.items[Item.SENSOR.ordinal()] = 2;
            assertAll(
                () -> assertEquals(525.0, none, "15 * 35 px without sensors"),
                () -> assertEquals(1050.0, Sounds.range(p), "two sensors double it"));
        }
    }

    @Nested
    @DisplayName("who hears")
    class Listeners {
        @Test
        @DisplayName("play: humans in range at their volume; out of range and robots nothing; no wrapping")
        void sensors() {
            Simulation s = sim(FRAME + "edgeWrap: yes\n", 40, 40, "_____");
            Player near = ready(s, "Near", 0, 100, 100);
            Player far = ready(s, "Far", 0, 700, 100);
            Player seam = ready(s, "Seam", 0, 1380, 100);
            Player sensor = ready(s, "Sensor", 0, 700, 110);
            sensor.items[Item.SENSOR.ordinal()] = 1;
            Player robot = s.ships.join("Robo", 0, true);
            robot.initPixels(100, 100);
            quiet(near, far, seam, sensor, robot);
            Sounds.play(s.world, 100, 100, SoundEvent.FIRE_SHOT);
            assertAll(
                () -> assertEquals(List.of("FIRE_SHOT:100"), heard(near), "a player at the sound hears it at 100"),
                () -> assertEquals(List.of(), heard(far), "600 px is beyond the default 525 px"),
                () -> assertEquals(List.of(), heard(seam), "120 px away across the wrap seam is still 1280 px away, as C"),
                // range 787.5: 100 - 100*600/787.5 = 23.8
                () -> assertEquals(List.of("FIRE_SHOT:23"), heard(sensor), "a sensor extends the range"),
                () -> assertEquals(List.of(), heard(robot), "robots hear nothing"));
        }

        @Test
        @DisplayName("playAll and playPlayer: full volume for humans, never robots")
        void allAndOne() {
            Simulation s = sim(FRAME, 40, 40, "___");
            Player a = ready(s, "A", 0, 100, 100);
            Player b = ready(s, "B", 0, 1300, 1300);
            Player robot = s.ships.join("Robo", 0, true);
            quiet(a, b, robot);
            Sounds.playAll(s.world, SoundEvent.TEAM_WIN);
            Sounds.playPlayer(a, SoundEvent.CLOAK);
            Sounds.playPlayer(robot, SoundEvent.CLOAK);
            assertAll(
                () -> assertEquals(List.of("TEAM_WIN:100", "CLOAK:100"), heard(a), "everyone gets playAll; one player its own"),
                () -> assertEquals(List.of("TEAM_WIN:100"), heard(b), "playAll ignores distance"),
                () -> assertEquals(List.of(), heard(robot), "robots get neither"));
        }
    }
}
