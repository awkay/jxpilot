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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

class ParticlesTest {
    static final Palette P = Palette.xpilotDefault();

    @Test
    void fourLevelsCoolEveryEightFramesOrFourForBlueOwners() {
        assertAll(
            () -> assertEquals(0, Particles.level(7, false, 4), "life 7 >> 3 = 0"),
            () -> assertEquals(1, Particles.level(8, false, 4), "life 8 >> 3 = 1"),
            () -> assertEquals(3, Particles.level(31, false, 4), "life 31 >> 3 = 3"),
            () -> assertEquals(3, Particles.level(80, false, 4), "capped at levels - 1"),
            () -> assertEquals(1, Particles.level(4, true, 4), "blue: life 4 >> 2 = 1"),
            () -> assertEquals(0, Particles.level(3, true, 4), "blue: life 3 >> 2 = 0"),
            () -> assertEquals(3, Particles.level(12, true, 4), "blue: life 12 >> 2 = 3"),
            () -> assertEquals(2, Particles.level(23, false, 3), "3 levels use the same shifts: 23 >> 3 = 2"),
            () -> assertEquals(2, Particles.level(80, false, 3), "capped at 2"));
    }

    @Test
    void moreThanFourLevelsCoolTwiceAsSlowly() {
        assertAll(
            () -> assertEquals(2, Particles.level(8, false, 6), "life 8 >> 2 = 2"),
            () -> assertEquals(1, Particles.level(7, false, 6), "life 7 >> 2 = 1"),
            () -> assertEquals(4, Particles.level(8, true, 6), "blue: life 8 >> 1 = 4"),
            () -> assertEquals(3, Particles.level(7, true, 6), "blue: 7 >> 1 = 3"),
            () -> assertEquals(5, Particles.level(100, true, 6), "capped at 5"),
            () -> assertEquals(4, Particles.level(19, false, 5), "5 levels: 19 >> 2 = 4, the cap"));
    }

    @Test
    void fewerThanThreeLevelsAndDeadParticlesAreLevelZero() {
        assertAll(
            () -> assertEquals(0, Particles.level(50, false, 2), "2 levels: no temperature"),
            () -> assertEquals(0, Particles.level(0, false, 4), "no life left"),
            () -> assertEquals(0, Particles.level(-8, true, 4), "negative life"));
    }

    @Test
    void levelsMapThroughSparkColoursInterleavedPastFour() {
        int[] six = {10, 11, 12, 13, 14, 15};
        assertAll(
            () -> assertEquals(7, Particles.colorIndex(2, PaintConfig.SPARK_COLORS), "4 colours: level 2 is sparkColors[2] = 7"),
            () -> assertEquals(5, Particles.colorIndex(0, PaintConfig.SPARK_COLORS), "level 0 is 5"),
            () -> assertEquals(10, Particles.colorIndex(0, six), "6 colours: level 0 -> index 0"),
            () -> assertEquals(14, Particles.colorIndex(1, six), "level 1 -> (1 << 2) | 0 = 4"),
            () -> assertEquals(11, Particles.colorIndex(2, six), "level 2 -> 0 | 1 = 1"),
            () -> assertEquals(15, Particles.colorIndex(3, six), "level 3 -> 4 | 1 = 5"));
    }

    @Test
    void defaultsGoFromDarkRedWhenColdToRedWhenHot() {
        assertAll(
            () -> assertEquals(5, Particles.colorIndex(0, false), "cold: palette 5 (#992200)"),
            () -> assertEquals(6, Particles.colorIndex(8, false), "palette 6"),
            () -> assertEquals(7, Particles.colorIndex(16, false), "palette 7"),
            () -> assertEquals(3, Particles.colorIndex(40, false), "hot: palette 3 (red)"),
            () -> assertEquals(3, Particles.colorIndex(12, true), "blue owners heat faster"));
    }

    @Test
    void sparkIsATwoPixelSquareHalfAPixelUpAndLeft() {
        VertexBatch b = batch();
        Particles.spark(b, P, 10, 20, 6, 1);
        Particles.spark(b, P, 10, 20, 3, 2);
        Particles.spark(b, P, 10, 20, 3, 0.5);
        assertAll(
            () -> assertEquals(List.of("9.5,20.5/2", "9.75,20.25/4", "9,21/1"), points(b),
                "zoom 1: half a pixel up-left; zoom 2: WINSCALE(2) = 4 px from the world corner (9, 21), centre 1.5 px "
                    + "= 0.75 units in; zoom 0.5: 1 px at the corner"),
            () -> assertEquals(P.color(6), b.pointColor(0), "in its palette colour"),
            () -> assertEquals(P.color(3), b.pointColor(1), "per spark"));
    }

    @Test
    void sparkRandIsTheProbabilityTimes128Rounded() {
        assertAll(
            () -> assertEquals(64, Particles.sparkRand(0.5), "default 0.5: 64 (C (int)(0.5 * 128 + 0.5))"),
            () -> assertEquals(128, Particles.sparkRand(1), "1: always"),
            () -> assertEquals(0, Particles.sparkRand(0), "0: never"),
            () -> assertEquals(13, Particles.sparkRand(0.1), "0.1: 12.8 + 0.5 = 13"),
            () -> assertEquals(128, Particles.sparkRand(3), "limited to 1"),
            () -> assertEquals(0, Particles.sparkRand(-1), "limited to 0"));
    }

    @Test
    void sparkShownIsADeterministicHashBelowSparkRand() {
        int shown = 0;
        int changed = 0;
        for (int i = 0; i < 1000; i++) {
            shown += Particles.sparkShown(i, 7, 64) ? 1 : 0;
            changed += Particles.sparkShown(i, 7, 64) != Particles.sparkShown(i, 8, 64) ? 1 : 0;
        }
        int count = shown;
        int flips = changed;
        boolean always = true;
        boolean never = false;
        for (int i = 0; i < 200; i++) {
            always &= Particles.sparkShown(i, i * 3L, 128);
            never |= Particles.sparkShown(i, i * 3L, 0);
        }
        boolean all = always;
        boolean none = !never;
        assertAll(
            () -> assertEquals(Particles.sparkShown(5, 9, 64), Particles.sparkShown(5, 9, 64), "same inputs, same answer"),
            () -> assertTrue(count > 430 && count < 570, "about half of 1000 particles show at 64/128: " + count),
            () -> assertTrue(flips > 380 && flips < 620, "about half change from one frame to the next: " + flips),
            () -> assertTrue(all, "spark_rand 128: every particle shows"),
            () -> assertTrue(none, "spark_rand 0: none shows"));
    }
}
