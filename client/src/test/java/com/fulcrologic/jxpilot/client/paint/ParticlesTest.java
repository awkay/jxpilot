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
        assertAll(
            () -> assertEquals(List.of("9.5,20.5/2", "9,21/2"), points(b), "offset by half a pixel (scaled by px)"),
            () -> assertEquals(P.color(6), b.pointColor(0), "in its palette colour"),
            () -> assertEquals(P.color(3), b.pointColor(1), "per spark"));
    }
}
