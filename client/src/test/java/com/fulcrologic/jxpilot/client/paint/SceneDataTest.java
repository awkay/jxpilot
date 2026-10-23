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

import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneDataTest {
    static final ShipShape SHIP = ShipShape.defaultShip();

    @Test
    void addersStoreEveryFieldAndRefuseWhenFull() {
        SceneData s = new SceneData(1, 1, 1, 1);
        assertAll(
            () -> assertTrue(s.addShip(1, 2, 0.5, SHIP, 7, 3, 2, true, "n"), "first ship fits"),
            () -> assertFalse(s.addShip(0, 0, 0, SHIP, 8, 0, 1, false, null), "second does not"),
            () -> assertEquals(1, s.shipCount, "one ship"),
            () -> assertEquals(2f, s.shipY[0], "y"),
            () -> assertEquals(0.5, s.shipHeading[0], "heading"),
            () -> assertSame(SHIP, s.shipShape[0], "shape"),
            () -> assertEquals(7, s.shipId[0], "id"),
            () -> assertEquals(3, s.shipFlags[0], "flags"),
            () -> assertEquals(2, s.shipColor[0], "colour"),
            () -> assertTrue(s.shipLocked[0], "locked"),
            () -> assertEquals("n", s.shipName[0], "name"),
            () -> assertTrue(s.addShot(3, 4, 2, true) && !s.addShot(0, 0, 0, false), "one shot"),
            () -> assertTrue(s.shotTeam[0] && s.shotColor[0] == 2 && s.shotX[0] == 3, "shot fields"),
            () -> assertTrue(s.addParticle(5, 6, 7) && !s.addParticle(0, 0, 0), "one particle"),
            () -> assertEquals(7, s.particleColor[0], "particle colour"));
    }

    @Test
    void everyOtherKindStoresItsFields() {
        SceneData s = new SceneData(1, 1, 1, 1);
        assertAll(
            () -> assertTrue(s.addMissile(1, 2, 15, 32) && !s.addMissile(0, 0, 0, 0), "missile, then full"),
            () -> assertEquals(32, s.missileDir[0], "missile dir"),
            () -> assertEquals(15f, s.missileLen[0], "missile len"),
            () -> assertTrue(s.addLaser(1, 2, 50, 64, 3) && !s.addLaser(0, 0, 0, 0, 0), "laser"),
            () -> assertEquals(3, s.laserColor[0], "laser colour"),
            () -> assertEquals(50f, s.laserLen[0], "laser len"),
            () -> assertTrue(s.addMine(1, 2, true, "m") && !s.addMine(0, 0, false, null), "mine"),
            () -> assertTrue(s.mineTeam[0] && "m".equals(s.mineName[0]), "mine fields"),
            () -> assertTrue(s.addBall(8, 9) && !s.addBall(0, 0), "ball"),
            () -> assertEquals(9f, s.ballY[0], "ball y"),
            () -> assertTrue(s.addItem(1, 2, 20) && !s.addItem(0, 0, 0), "item"),
            () -> assertEquals(20, s.itemType[0], "item type"),
            () -> assertTrue(s.addWreck(1, 2, true, 2, 64, 200) && !s.addWreck(0, 0, false, 0, 0, 0), "wreck"),
            () -> assertTrue(s.wreckDeadly[0] && s.wreckType[0] == 2 && s.wreckRot[0] == 64 && s.wreckSize[0] == 200, "wreck fields"),
            () -> assertTrue(s.addAsteroid(1, 2, 1, 5, 3) && !s.addAsteroid(0, 0, 0, 0, 0), "asteroid"),
            () -> assertTrue(s.asteroidType[0] == 1 && s.asteroidRot[0] == 5 && s.asteroidSize[0] == 3, "asteroid fields"),
            () -> assertTrue(s.addEcm(1, 2, 40) && !s.addEcm(0, 0, 0), "ecm"),
            () -> assertEquals(40f, s.ecmSize[0], "ecm size"),
            () -> assertTrue(s.addPaused(1, 2, 9) && !s.addPaused(0, 0, 0), "paused"),
            () -> assertEquals(9, s.pausedCountdown[0], "countdown"),
            () -> assertTrue(s.addLink(1, 2, 3, 4, SceneData.LINK_TRACTOR) && !s.addLink(0, 0, 0, 0, 0), "link"),
            () -> assertTrue(s.linkX2[0] == 3 && s.linkY2[0] == 4 && s.linkKind[0] == SceneData.LINK_TRACTOR, "link fields"),
            () -> assertEquals(1f, s.mineX[0] + s.itemX[0] + s.wreckX[0] + s.asteroidX[0] + s.ecmX[0] + s.pausedX[0]
                + s.linkX1[0] + s.laserX[0] + s.missileX[0] - 8, "x stored for every kind"),
            () -> assertEquals(2f, s.mineY[0] + s.itemY[0] + s.wreckY[0] + s.asteroidY[0] + s.ecmY[0] + s.pausedY[0]
                + s.linkY1[0] + s.laserY[0] + s.missileY[0] - 16, "y stored for every kind"));
    }

    @Test
    void clearEmptiesEveryKindButKeepsFrameAndSelf() {
        SceneData s = new SceneData();
        s.loops = 9;
        s.selfId = 4;
        s.addShip(0, 0, 0, SHIP, 1, 0, 1, false, null);
        s.addShot(0, 0, 1, false);
        s.addParticle(0, 0, 1);
        s.addMissile(0, 0, 1, 0);
        s.addLaser(0, 0, 1, 0, 1);
        s.addMine(0, 0, false, null);
        s.addBall(0, 0);
        s.addItem(0, 0, 0);
        s.addWreck(0, 0, false, 0, 0, 1);
        s.addAsteroid(0, 0, 0, 0, 1);
        s.addEcm(0, 0, 1);
        s.addPaused(0, 0, 0);
        s.addLink(0, 0, 1, 1, 0);
        s.clear();
        assertAll(
            () -> assertEquals(0, s.shipCount + s.shotCount + s.particleCount + s.missileCount + s.laserCount + s.mineCount
                + s.ballCount + s.itemCount + s.wreckCount + s.asteroidCount + s.ecmCount + s.pausedCount + s.linkCount,
                "every count is 0"),
            () -> assertEquals(9, s.loops, "loops kept"),
            () -> assertEquals(4, s.selfId, "self kept"),
            () -> assertEquals(64, s.maxShips, "default capacity: 64 ships"),
            () -> assertEquals(4096, s.maxShots, "4096 shots"),
            () -> assertEquals(16384, s.maxParticles, "16384 particles"),
            () -> assertEquals(256, s.maxLinks, "256 of the rest"));
    }
}
