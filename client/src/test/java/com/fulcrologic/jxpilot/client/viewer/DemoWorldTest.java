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
package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.paint.Particles;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ShipPainter;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DemoWorldTest {
    static DemoWorld demo(boolean wrap) {
        return new DemoWorld(14, 2000, 1000, wrap, 1000, 500, 7, 32, 2000, 5000);
    }

    @Test
    void fillsTheRequestedCountsAndOneOfEveryOtherKind() {
        SceneData s = new SceneData();
        DemoWorld d = demo(true);
        d.update(1.5);
        d.fill(s);
        assertAll(
            () -> assertEquals(32, s.shipCount, "32 ships"),
            () -> assertEquals(2000, s.shotCount, "2000 shots"),
            () -> assertEquals(5000, s.particleCount, "5000 particles"),
            () -> assertEquals(Item.COUNT, s.itemCount, "all 21 items"),
            () -> assertTrue(s.mineCount > 0 && s.ballCount > 0 && s.missileCount > 0 && s.laserCount > 0
                && s.wreckCount > 0 && s.asteroidCount > 0 && s.ecmCount > 0 && s.pausedCount > 0 && s.linkCount > 0,
                "every other kind is present"),
            () -> assertEquals(0, s.selfId, "ship 0 is self"),
            () -> assertEquals(21, s.loops, "1.5 s at 14 frames per second"),
            () -> assertEquals(21, d.loops(), "loops accessor"),
            () -> assertEquals(1.5, d.time(), "time accessor"));
    }

    @Test
    void loopsRunAtTheMapsFramesPerSecondAndTheCountdownsFollowThem() {
        SceneData s = new SceneData();
        DemoWorld d = new DemoWorld(30, 2000, 1000, false, 1000, 500, 7, 4, 10, 10);
        d.setCountdowns(50, 40);
        d.update(1.5);
        d.fill(s);
        SceneData later = new SceneData();
        d.update(1);
        d.fill(later);
        assertAll(
            () -> assertEquals(45, s.loops, "1.5 s at the map's 30 frames per second"),
            () -> assertEquals(30, s.fps, "the scene's fps is the map's"),
            () -> assertEquals(30, d.fps(), "fps accessor"),
            () -> assertEquals(5, s.roundDelay, "round delay 50 - 45 frames left"),
            () -> assertEquals(Palette.RED, s.shipColor[0], "ships turn red while a round is pending"),
            () -> assertEquals(0, s.damaged, "damage 40 frames: over by frame 45"),
            () -> assertEquals(0, later.roundDelay, "after the delay: 0, never negative"),
            () -> assertEquals(Palette.WHITE, later.shipColor[0], "and white again"));
    }

    @Test
    void damageCountsDownFromTheStart() {
        SceneData s = new SceneData();
        DemoWorld d = demo(false);
        d.setCountdowns(0, 40);
        d.update(1);
        d.fill(s);
        assertAll(
            () -> assertEquals(26, s.damaged, "40 - 14 frames"),
            () -> assertEquals(0, s.roundDelay, "no round delay"));
    }

    @Test
    void sameSeedSameScene() {
        SceneData a = new SceneData();
        SceneData b = new SceneData();
        DemoWorld da = demo(true);
        DemoWorld db = demo(true);
        da.update(2);
        db.update(2);
        da.fill(a);
        db.fill(b);
        assertAll(
            () -> assertEquals(a.shotX[1234], b.shotX[1234], "shot positions repeat"),
            () -> assertEquals(a.particleY[4321], b.particleY[4321], "particle positions repeat"),
            () -> assertEquals(a.shipHeading[5], b.shipHeading[5], "ship headings repeat"));
    }

    @Test
    void shipsLooksAndColoursCycleByIndex() {
        SceneData s = new SceneData();
        demo(true).fill(s);
        assertAll(
            () -> assertEquals(0, DemoWorld.shipFlags(0), "0: plain"),
            () -> assertEquals(ShipPainter.SHIELD, DemoWorld.shipFlags(1), "1: shield"),
            () -> assertEquals(ShipPainter.CLOAKED, DemoWorld.shipFlags(2), "2: cloaked"),
            () -> assertEquals(ShipPainter.PHASING, DemoWorld.shipFlags(3), "3: phasing"),
            () -> assertEquals(ShipPainter.DEFLECTOR, DemoWorld.shipFlags(4), "4: deflector"),
            () -> assertEquals(ShipPainter.SHIELD | ShipPainter.EMERGENCY_SHIELD, DemoWorld.shipFlags(13), "13 = 5 mod 8"),
            () -> assertEquals(ShipPainter.SHIELD | ShipPainter.EMERGENCY_SHIELD | ShipPainter.DEFLECTOR, DemoWorld.shipFlags(6), "6: all"),
            () -> assertEquals(0, DemoWorld.shipFlags(7), "7: plain"),
            () -> assertEquals(Palette.WHITE, s.shipColor[0], "self white"),
            () -> assertEquals(Palette.BLUE, s.shipColor[1], "every third from 1 is a friend"),
            () -> assertEquals(Palette.WHITE, s.shipColor[2], "others white"),
            () -> assertTrue(s.shipLocked[5], "ship 5 is locked"),
            () -> assertEquals("pilot05", s.shipName[5], "named"),
            () -> assertEquals("self", s.shipName[0], "self is named too (the painter skips it)"));
    }

    @Test
    void wrappingKeepsEverythingInsideTheWorld() {
        SceneData s = new SceneData();
        DemoWorld d = demo(true);
        d.update(7.3);
        d.fill(s);
        boolean inside = true;
        for (int i = 0; i < s.shotCount; i++) {
            inside &= s.shotX[i] >= 0 && s.shotX[i] < 2000 && s.shotY[i] >= 0 && s.shotY[i] < 1000;
        }
        for (int i = 0; i < s.particleCount; i++) {
            inside &= s.particleX[i] >= 0 && s.particleX[i] < 2000 && s.particleY[i] >= 0 && s.particleY[i] < 1000;
        }
        boolean in = inside;
        assertAll(
            () -> assertTrue(in, "every shot and particle is inside a wrapping world"),
            () -> assertEquals(s.shipX[3], d.shipX(3), "ship accessors match the scene"),
            () -> assertEquals(s.shipY[3], d.shipY(3), "y too"));
    }

    @Test
    void shipsMoveAlongTheirHeading() {
        DemoWorld d = demo(false);
        d.update(3);
        float x0 = d.shipX(4);
        float y0 = d.shipY(4);
        double h = d.shipHeading(4);
        d.update(0.001);
        double dx = d.shipX(4) - x0;
        double dy = d.shipY(4) - y0;
        double moved = Math.atan2(dy, dx);
        double diff = Math.abs(Math.IEEEremainder(moved - h, 2 * Math.PI));
        assertTrue(diff < 0.05, "a ship moves (very nearly) along its heading: off by " + diff);
    }

    @Test
    void particlesCoolAsTheyAge() {
        SceneData s = new SceneData();
        demo(true).fill(s);
        boolean hot = false;
        boolean cold = false;
        for (int i = 0; i < s.particleCount; i++) {
            hot |= s.particleColor[i] == Particles.colorIndex(40, false);
            cold |= s.particleColor[i] == Particles.colorIndex(1, false);
        }
        boolean h = hot;
        boolean c = cold;
        assertAll(
            () -> assertTrue(h, "some particles are hot (red)"),
            () -> assertTrue(c, "some are cold (dark red)"));
    }

    @Test
    void aSingleShipDemoHasNoNamedMines() {
        SceneData s = new SceneData();
        new DemoWorld(14, 2000, 1000, false, 1000, 500, 1, 1, 10, 10).fill(s);
        boolean anyName = false;
        for (int i = 0; i < s.mineCount; i++) {
            anyName |= s.mineName[i] != null;
        }
        boolean named = anyName;
        assertAll(
            () -> assertFalse(named, "no other pilots to own mines"),
            () -> assertEquals(1, s.shipCount, "one ship"),
            () -> assertEquals(0, s.ballCount, "no ball carriers"),
            () -> assertEquals(1, s.missileCount, "one missile per ship (up to 16)"));
    }
}
