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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RobotItems: the robot's view of the item world and its item actions")
class RobotItemsTest {
    static Simulation sim(String extra) {
        return RobotFixture.sim(extra, RobotFixture.open(40, 40, "___"));
    }

    @Test
    @DisplayName("answers read the player, the item table, the objects and the pulses")
    void answers() {
        Simulation s = sim("maxMines: 7\n");
        Player p = RobotFixture.robot(s, "R", 300, 300);
        Player q = RobotFixture.human(s, "H", 400, 300);
        RobotItems it = new RobotItems(s);
        p.items[Item.MINE.ordinal()] = 4;
        p.have = Has.SHIELD | Has.CLOAKING_DEVICE;
        p.canSee[q.slot] = false;
        p.numPulses = 2;
        p.numTanks = 3;
        p.emergencyShieldLeft = 5;
        int h = s.world.objects.alloc();
        s.world.objects.info[h] = Item.ECM.ordinal();
        s.world.objects.status[h] = Obj.RANDOM_ITEM;
        Pulses ps = s.world.pulses;
        int i = ps.add();
        ps.x[i] = 12;
        ps.y[i] = 34;
        ps.dir[i] = 56;
        ps.owner[i] = q.id;
        ps.refl[i] = true;
        boolean see = it.canSee(p, q);
        p.phasingLeft = 1;
        int both = it.activeDefensives(p);
        assertAll(
            () -> assertEquals(4, it.count(p, Item.MINE), "item count"),
            () -> assertEquals(Has.SHIELD | Has.CLOAKING_DEVICE, it.have(p), "every ability"),
            () -> assertFalse(see, "visibility table"),
            () -> assertEquals(2, it.numPulses(p), "own pulses"),
            () -> assertEquals(3, it.numTanks(p), "tanks"),
            () -> assertEquals(7, it.limit(Item.MINE), "the item limit (maxMines)"),
            () -> assertEquals(2, both, "running emergency shield and phasing count"),
            () -> assertEquals(Item.ECM.ordinal(), it.itemType(h), "item kind"),
            () -> assertTrue(it.randomItem(h), "random item flag"),
            () -> assertEquals(1, it.pulseCount(), "pulses"),
            () -> assertEquals(12.0, it.pulseX(0), "pulse x"),
            () -> assertEquals(34.0, it.pulseY(0), "pulse y"),
            () -> assertEquals(56, it.pulseDir(0), "pulse dir"),
            () -> assertEquals(q.id, it.pulseOwner(0), "pulse owner"),
            () -> assertTrue(it.pulseReflected(0), "reflected"),
            () -> assertEquals(-1, it.towedBall(p), "no towed ball (balls, targets, asteroids: RobotModesTest)"));
    }

    @Test
    @DisplayName("actions reach the simulation: mines, missiles, ECM, devices, hyperjump, sensors")
    void actions() {
        Simulation s = sim("");
        Player p = RobotFixture.robot(s, "R", 300, 300);
        RobotItems it = new RobotItems(s);
        p.items[Item.MINE.ordinal()] = 3;
        p.items[Item.MISSILE.ordinal()] = 2;
        p.items[Item.ECM.ordinal()] = 1;
        p.items[Item.HYPERJUMP.ordinal()] = 1;
        p.items[Item.EMERGENCY_SHIELD.ordinal()] = 1;
        p.items[Item.EMERGENCY_THRUST.ordinal()] = 1;
        p.items[Item.DEFLECTOR.ordinal()] = 1;
        p.have |= Has.EMERGENCY_SHIELD | Has.EMERGENCY_THRUST | Has.DEFLECTOR;
        double fuel0 = p.fuel;
        it.placeMine(p, false);
        int dropped = s.world.objects.count();
        it.placeMine(p, true);
        int thrown = s.world.objects.count();
        it.fireMissile(p, Obj.TORPEDO, 0);
        it.fireEcm(p);
        it.emergencyShield(p);
        it.emergencyThrust(p);
        it.deflector(p);
        double beforeJump = p.fuel;
        it.hyperjump(p);
        p.sensorRange = 0;
        it.sensorRange(p);
        it.detachBall(p);
        assertAll(
            () -> assertEquals(1, dropped, "a dropped mine"),
            () -> assertEquals(2, thrown, "and a thrown one"),
            () -> assertEquals(1, p.items[Item.MINE.ordinal()], "two mines used"),
            () -> assertEquals(1, p.items[Item.MISSILE.ordinal()], "a torpedo fired"),
            () -> assertEquals(1, s.world.ecms.count(), "an ECM blast"),
            () -> assertEquals(Has.EMERGENCY_SHIELD | Has.EMERGENCY_THRUST | Has.DEFLECTOR,
                p.used & (Has.EMERGENCY_SHIELD | Has.EMERGENCY_THRUST | Has.DEFLECTOR), "devices on"),
            () -> assertEquals(0, p.items[Item.HYPERJUMP.ordinal()], "the hyperjump is used"),
            () -> assertEquals(beforeJump - 60 * 256, p.fuel, "for 60 fuel units"),
            () -> assertEquals(Obj.WARPING, p.status & Obj.WARPING, "the ship warps"),
            () -> assertTrue(p.sensorRange > 0, "sensor range computed"),
            () -> assertTrue(fuel0 > p.fuel, "the weapons cost fuel"),
            () -> assertEquals(0, p.used & Has.CONNECTOR, "no connector after detaching"));
    }

    @Test
    @DisplayName("the random-item and reflected answers are false when the flags are clear")
    void clearFlags() {
        Simulation s = sim("");
        RobotItems it = new RobotItems(s);
        int h = s.world.objects.alloc();
        s.world.objects.status[h] = Obj.GRAVITY;
        int i = s.world.pulses.add();
        s.world.pulses.refl[i] = false;
        assertAll(
            () -> assertFalse(it.randomItem(h), "an item without RANDOM_ITEM is not random"),
            () -> assertFalse(it.pulseReflected(i), "an unreflected pulse is not reflected"));
    }

    @Test
    @DisplayName("placeMine drops a still mine, or throws a GRAVITY (moving) mine (C Place_mine / Place_moving_mine)")
    void mineKinds() {
        Simulation s = sim("minMineSpeed: 0\n");
        Player p = RobotFixture.robot(s, "R", 300, 300);
        RobotItems it = new RobotItems(s);
        p.items[Item.MINE.ordinal()] = 2;
        it.placeMine(p, false);
        int dropped = s.world.objects.active[0];
        it.placeMine(p, true);
        int thrown = s.world.objects.active[1];
        assertAll(
            () -> assertEquals(0, s.world.objects.status[dropped] & Obj.GRAVITY, "the dropped mine is not a moving mine"),
            () -> assertEquals(Obj.GRAVITY, s.world.objects.status[thrown] & Obj.GRAVITY,
                "the thrown mine is a GRAVITY mine (C Place_moving_mine)"));
    }

    @Test
    @DisplayName("transporter with nobody in range spends the item and its fuel; detachTank drops a tank;"
        + " detachBall clears only the connector")
    void transporterTankBall() {
        Simulation s = sim("");
        Player p = RobotFixture.robot(s, "R", 300, 300);
        RobotItems it = new RobotItems(s);
        p.items[Item.TRANSPORTER.ordinal()] = 1;
        double fuel0 = p.fuel;
        it.transporter(p);
        double fuel1 = p.fuel;
        s.tanks.add(p, 0);
        int players = s.world.numPlayers;
        it.detachTank(p);
        p.used = Has.CONNECTOR | Has.SHIELD;
        it.detachBall(p);
        assertAll(
            () -> assertEquals(0, p.items[Item.TRANSPORTER.ordinal()], "the transporter is used (C Do_transporter)"),
            () -> assertTrue(fuel1 < fuel0, "the transporter costs fuel"),
            () -> assertEquals(0, p.numTanks, "the tank leaves the ship"),
            () -> assertEquals(players + 1, s.world.numPlayers, "as a tank pseudo player"),
            () -> assertEquals(Has.SHIELD, p.used, "detaching the ball clears the connector and keeps the shield"));
    }
}
