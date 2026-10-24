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
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Ships: item hooks (player.c Init_player, Go_home, Player_death_reset, Delete_player; update.c)")
class ShipsItemsTest {
    Simulation s;
    World w;

    Player setup(String extra) {
        s = SimFixture.sim(FRAME + extra, 30, 30, "____");
        w = s.world;
        return SimFixture.ready(s, "P", 0, 350, 350);
    }

    static int it(Item i) {
        return i.ordinal();
    }

    @Test
    @DisplayName("initItemState: locks, modifiers, banks, timers and visibility start clean; the sensor range is set")
    void initItemState() {
        Player p = setup("");
        Player q = SimFixture.ready(s, "Q", 0, 500, 500);
        p.tank = true;
        p.lockTagged = Player.LOCK_PLAYER;
        p.lockPlId = 7;
        p.lockDistance = 9;
        p.mods = 5;
        p.modBank[3] = 5;
        p.lockBank[2] = 4;
        p.missileRack = 2;
        p.forceVisible = 3;
        p.updateVisibility = true;
        p.numPulses = 2;
        p.ecmCount = 2;
        p.emergencyThrustLeft = p.emergencyThrustMax = 4;
        p.emergencyShieldLeft = p.emergencyShieldMax = 4;
        p.phasingLeft = p.phasingMax = 4;
        p.loseItem = 3;
        p.loseItemState = 2;
        p.tractorIsPressor = true;
        p.canSee[q.slot] = false;
        q.canSee[p.slot] = false;
        p.sensorRange = 0;
        s.ships.initItemState(p);
        assertAll(
            () -> assertFalse(p.tank, "not a tank"),
            () -> assertEquals(0, p.lockTagged, "no lock"),
            () -> assertEquals(0, p.lockPlId, "lock id 0 (C)"),
            () -> assertEquals(0f, p.lockDistance, "lock distance 0"),
            () -> assertEquals(0, p.mods, "no modifiers"),
            () -> assertEquals(0, p.modBank[3], "empty modifier banks"),
            () -> assertEquals(-1, p.lockBank[2], "empty lock banks (NOT_CONNECTED)"),
            () -> assertEquals(0, p.missileRack, "first missile rack"),
            () -> assertEquals(0, p.forceVisible, "not forced visible"),
            () -> assertFalse(p.updateVisibility, "no visibility request"),
            () -> assertEquals(0, p.numPulses, "no pulses"),
            () -> assertEquals(0, p.ecmCount, "no ECMs fired"),
            () -> assertEquals(0, p.emergencyThrustLeft + p.emergencyThrustMax + p.emergencyShieldLeft
                + p.emergencyShieldMax + p.phasingLeft + p.phasingMax, "no device timers"),
            () -> assertEquals(0, p.loseItem, "lose item 0"),
            () -> assertEquals(0, p.loseItemState, "lose item state 0"),
            () -> assertFalse(p.tractorIsPressor, "not a pressor"),
            () -> assertTrue(p.canSee[q.slot], "it sees everybody"),
            () -> assertTrue(q.canSee[p.slot], "everybody sees it"),
            () -> assertTrue(p.sensorRange > 0, "the sensor range is computed"));
    }

    @Test
    @DisplayName("refuel: REFUEL_RATE goes into each tank in turn (tanks + 1 per frame); the current tank is kept")
    void refuelTanks() {
        String[] rows = SimFixture.open(10, 10, "_");
        char[] r = rows[5].toCharArray();
        r[5] = '#';
        rows[5] = new String(r);
        s = com.fulcrologic.jxpilot.server.TestWorlds.sim(FRAME, rows);
        w = s.world;
        Player p = SimFixture.ready(s, "P", 0, 0, 0);
        var f = w.map.fuels().get(0);
        p.initPixels(f.x(), f.y());
        s.tanks.initFuel(p, 100 * 256);
        s.tanks.add(p, 10 * 256);
        s.tanks.add(p, 20 * 256);
        p.currentTank = 1;
        w.fuelLevel[0] = 5 * 256 * 2 + 640;
        s.ships.startRefuel(p);
        double t0 = p.tankFuel[0];
        double t1 = p.tankFuel[1];
        double t2 = p.tankFuel[2];
        double sum = p.fuel;
        s.ships.refuel(p);
        assertAll(
            () -> assertEquals(t1 + 1280, p.tankFuel[1], "the current tank (1) gets 1280 first"),
            () -> assertEquals(t2 + 1280, p.tankFuel[2], "then tank 2 gets 1280"),
            () -> assertEquals(t0 + 640, p.tankFuel[0], "then the main tank gets the station's last 640"),
            () -> assertEquals(sum + 3200, p.fuel, "the sum grows by all of it"),
            () -> assertEquals(0.0, w.fuelLevel[0], "the station is empty"),
            () -> assertEquals(0, p.used & Has.REFUEL, "an empty station stops refuelling"),
            () -> assertEquals(1, p.currentTank, "the current tank is restored"));
    }

    @Test
    @DisplayName("a full station fills every tank once per frame: numTanks + 1 transfers")
    void refuelPlenty() {
        String[] rows = SimFixture.open(10, 10, "_");
        char[] r = rows[5].toCharArray();
        r[5] = '#';
        rows[5] = new String(r);
        s = com.fulcrologic.jxpilot.server.TestWorlds.sim(FRAME, rows);
        w = s.world;
        Player p = SimFixture.ready(s, "P", 0, 0, 0);
        var f = w.map.fuels().get(0);
        p.initPixels(f.x(), f.y());
        s.tanks.initFuel(p, 100 * 256);
        s.tanks.add(p, 10 * 256);
        w.fuelLevel[0] = 100 * 256;
        s.ships.startRefuel(p);
        double sum = p.fuel;
        s.ships.refuel(p);
        assertAll(
            () -> assertEquals(sum + 2 * 1280, p.fuel, "two tanks: two transfers of 1280"),
            () -> assertEquals(100.0 * 256 - 2 * 1280, w.fuelLevel[0], "the station gives 2560"),
            () -> assertTrue((p.used & Has.REFUEL) != 0, "refuelling goes on"));
    }

    @Test
    @DisplayName("update runs the device timers, device use, device drain and the sensor range each frame")
    void updateHooks() {
        Player p = setup("");
        w.roundDelay = 0;
        p.have |= Has.PHASING_DEVICE;
        p.used |= Has.PHASING_DEVICE | Has.LASER;
        p.items[it(Item.LASER)] = 0;
        p.phasingLeft = 10;
        p.sensorRange = 0;
        double fuel = p.fuel;
        s.ships.update(p);
        assertAll(
            () -> assertEquals(9, p.phasingLeft, "the phase runs down (Devices.timers)"),
            () -> assertEquals(0, p.used & Has.LASER, "a laser without items goes off (Devices.use)"),
            () -> assertEquals(102.0, fuel - p.fuel, "phasing costs 102 fuel (Devices.drain)"),
            () -> assertTrue(p.sensorRange > 0, "the sensor range is computed"));
    }

    @Test
    @DisplayName("a slot reused by a new player starts with clean item state")
    void reusedSlot() {
        Player p = setup("");
        int slot = p.slot;
        p.lockBank[0] = 3;
        p.mods = 9;
        p.phasingLeft = 4;
        s.ships.leave(p);
        Player q = s.ships.join("Q", 0);
        assertAll(
            () -> assertEquals(slot, q.slot, "the slot is reused"),
            () -> assertEquals(-1, q.lockBank[0], "lock banks are empty"),
            () -> assertEquals(0, q.mods, "no modifiers"),
            () -> assertEquals(0, q.phasingLeft, "no phase"));
    }

    @Nested
    @DisplayName("deathReset (C Player_death_reset)")
    class DeathReset {
        @Test
        @DisplayName("items go back to the initial counts; device timers, ECMs, forced visibility, lock distance reset")
        void items() {
            Player p = setup("");
            w.params.initialItems[it(Item.MINE)] = 2;
            p.items[it(Item.MINE)] = 7;
            p.items[it(Item.CLOAK)] = 3;
            p.forceVisible = 5;
            p.ecmCount = 4;
            p.emergencyThrustLeft = p.emergencyThrustMax = 4;
            p.emergencyShieldLeft = p.emergencyShieldMax = 4;
            p.phasingLeft = p.phasingMax = 4;
            p.lockDistance = 9;
            p.have |= Has.CLOAKING_DEVICE | Has.DEFLECTOR;
            p.used |= Has.CLOAKING_DEVICE | Has.DEFLECTOR | Has.EMERGENCY_SHIELD;
            s.ships.deathReset(p);
            assertAll(
                () -> assertEquals(2, p.items[it(Item.MINE)], "mines back to initialMines"),
                () -> assertEquals(0, p.items[it(Item.CLOAK)], "cloaks back to 0"),
                () -> assertEquals(0, p.forceVisible, "not forced visible"),
                () -> assertEquals(0, p.ecmCount, "no ECMs fired"),
                () -> assertEquals(0, p.emergencyThrustLeft + p.emergencyThrustMax + p.emergencyShieldLeft
                    + p.emergencyShieldMax + p.phasingLeft + p.phasingMax, "no device timers"),
                () -> assertEquals(0f, p.lockDistance, "lock distance 0"),
                () -> assertEquals(w.params.defHave, p.have, "abilities back to DEF_HAVE"),
                () -> assertEquals(0, p.used & (Has.CLOAKING_DEVICE | Has.DEFLECTOR | Has.EMERGENCY_SHIELD),
                    "devices in use stop (USED_KILL)"));
        }

        @Test
        @DisplayName("an autopilot (or a hover pause) is switched off first, restoring the saved settings")
        void autopilot() {
            Player p = setup("");
            p.power = 45;
            s.devices.autopilot(p, true);
            s.ships.deathReset(p);
            Player q = setup("");
            q.status |= Obj.HOVERPAUSE;
            q.autoPowerS = 33;
            q.power = 30;
            s.ships.deathReset(q);
            Player r = setup("");
            r.autoPowerS = 33;
            r.power = 30;
            s.ships.deathReset(r);
            assertAll(
                () -> assertEquals(0, p.used & Has.AUTOPILOT, "the autopilot is off"),
                () -> assertEquals(45f, p.power, "the saved power is back"),
                () -> assertEquals(0, q.status & Obj.HOVERPAUSE, "the hover pause ends"),
                () -> assertEquals(33f, q.power, "and the autopilot's saved power is restored"),
                () -> assertEquals(30f, r.power, "without autopilot or hover pause the power is kept"));
        }

        @Test
        @DisplayName("a dying tank leaves the game")
        void tank() {
            Player p = setup("");
            s.tanks.add(p, 50 * 256);
            s.tanks.detach(p);
            Player t = null;
            for (int k = 0; k < w.numPlayers; k++) {
                if (w.playerAt(k).tank) {
                    t = w.playerAt(k);
                }
            }
            Player tank = t;
            int before = w.numPlayers;
            s.ships.deathReset(tank);
            assertAll(
                () -> assertTrue(tank != null, "the tank was released"),
                () -> assertEquals(before - 1, w.numPlayers, "the tank is gone"),
                () -> assertFalse(tank.inUse, "its slot is free"));
        }
    }

    @Test
    @DisplayName("goHome: playerStartsShielded raises the deflector too when the ship has one; visibility restarts")
    void goHome() {
        Player p = setup("playerStartsShielded: yes\n");
        Player q = SimFixture.ready(s, "Q", 0, 500, 500);
        p.items[it(Item.DEFLECTOR)] = 1;
        p.have |= Has.DEFLECTOR;
        p.updateVisibility = false;
        p.visLastChange[q.slot] = 9;
        q.visLastChange[p.slot] = 9;
        s.ships.goHome(p);
        boolean deflector = (p.used & Has.DEFLECTOR) != 0;
        q.items[it(Item.DEFLECTOR)] = 1;
        q.have &= ~Has.DEFLECTOR;
        s.ships.goHome(q);
        Player r = setup("playerStartsShielded: no\n");
        r.items[it(Item.DEFLECTOR)] = 1;
        r.have |= Has.DEFLECTOR;
        s.ships.goHome(r);
        assertAll(
            () -> assertTrue(deflector, "a ship with a deflector starts with it on"),
            () -> assertTrue(p.updateVisibility, "visibility is to be updated"),
            () -> assertEquals(0L, p.visLastChange[q.slot], "its visibility of others restarts"),
            () -> assertEquals(0L, q.visLastChange[p.slot], "others' visibility of it restarts"),
            () -> assertEquals(0, q.used & Has.DEFLECTOR, "without the ability no deflector"),
            () -> assertEquals(0, r.used & Has.DEFLECTOR, "without playerStartsShielded no deflector"));
    }

    @Test
    @DisplayName("warp with no wormhole (hyperjump): to a random space position, WARPED, forced visible 15 more frames")
    void hyperjumpWarp() {
        Player p = setup("");
        p.status |= Obj.WARPING;
        p.wormHoleHit = -1;
        p.vx = 2;
        p.forceVisible = 3;
        w.rng.seed(8);
        Rng ref = new Rng(8);
        float x = (int) ((double) ref.nextFloat() * w.pixelWidth);
        float y = (int) ((double) ref.nextFloat() * w.pixelHeight);
        s.ships.warp(p);
        assertAll(
            () -> assertEquals((int) x, p.px, "x is the first random position (all space)"),
            () -> assertEquals((int) y, p.py, "y is the first random position"),
            () -> assertEquals(p.px, p.prevX, "the previous position is the new one"),
            () -> assertEquals(-2, p.wormHoleDest, "the destination is -2 (hyperjump)"),
            () -> assertEquals(18, p.forceVisible, "forced visible 15 more frames"),
            () -> assertEquals(0, p.status & Obj.WARPING, "no longer warping"),
            () -> assertTrue((p.status & Obj.WARPED) != 0, "warped"),
            () -> assertEquals(2f, p.vx, "the velocity is kept (WORM_BRAKE_FACTOR 1)"));
    }

    @Nested
    @DisplayName("leave (C Delete_player): item cleanup")
    class Leave {
        @Test
        @DisplayName("own mines and mines it owns die without keepShots; pulses, locks, tractor beams and banks go")
        void cleanup() {
            Player p = setup("");
            Player q = SimFixture.ready(s, "Q", 0, 500, 500);
            Player r = SimFixture.ready(s, "R", 0, 600, 600);
            Objects o = w.objects;
            int own = SimFixture.obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.mineOwner[own] = p.id;
            int adopted = SimFixture.obj(w, Obj.MINE, q.id, 120, 100, 0, 0, 30, 100);
            o.mineOwner[adopted] = p.id;
            int others = SimFixture.obj(w, Obj.MINE, q.id, 140, 100, 0, 0, 30, 100);
            o.mineOwner[others] = q.id;
            s.lasers.fireGeneral(p, p.team, 350, 350, 0, 0);
            int pulses = w.pulses.count();
            q.lockTagged = Player.LOCK_PLAYER | Player.LOCK_VISIBLE;
            q.lockPlId = p.id;
            q.used |= Has.TRACTOR_BEAM;
            q.lockBank[1] = p.id;
            r.lockTagged = Player.LOCK_PLAYER;
            r.lockPlId = q.id;
            r.used |= Has.TRACTOR_BEAM;
            r.lockBank[0] = q.id;
            Player u = SimFixture.ready(s, "U", 0, 650, 650);
            u.lockTagged = 0;
            u.lockPlId = p.id;
            u.used |= Has.TRACTOR_BEAM;
            s.ships.leave(p);
            assertAll(
                () -> assertEquals(1, pulses, "the leaver had a pulse"),
                () -> assertEquals(0, w.pulses.count(), "its pulses go"),
                () -> assertEquals(0.0, o.life[own], "its own mine dies"),
                () -> assertEquals(0f, o.mass[own], "massless, so it does not explode"),
                () -> assertEquals(Obj.NO_ID, o.owner[own], "and loses its owner"),
                () -> assertEquals(Obj.NO_ID, o.mineOwner[own], "and its mine owner"),
                () -> assertEquals(0.0, o.life[adopted], "a mine it owns (another's object) dies"),
                () -> assertEquals(Obj.NO_ID, o.mineOwner[adopted], "and loses its mine owner"),
                () -> assertEquals(q.id, o.owner[adopted], "but keeps its object owner"),
                () -> assertEquals(100.0, o.life[others], "other mines live on"),
                () -> assertEquals(0, q.lockTagged & (Player.LOCK_PLAYER | Player.LOCK_VISIBLE), "locks on it go"),
                () -> assertEquals(0, q.used & Has.TRACTOR_BEAM, "with their tractor beam"),
                () -> assertEquals(-1, q.lockBank[1], "lock banks naming it are cleared"),
                () -> assertEquals(Player.LOCK_PLAYER, r.lockTagged, "a lock on someone else stays"),
                () -> assertTrue((r.used & Has.TRACTOR_BEAM) != 0, "with its tractor beam"),
                () -> assertEquals(q.id, r.lockBank[0], "other banks stay"),
                () -> assertTrue((u.used & Has.TRACTOR_BEAM) != 0, "a ship without a lock keeps its tractor beam"));
        }

        @Test
        @DisplayName("when one player is left its lock goes whatever it names")
        void lastPlayer() {
            Player p = setup("");
            Player q = SimFixture.ready(s, "Q", 0, 500, 500);
            q.lockTagged = Player.LOCK_PLAYER;
            q.lockPlId = 999;
            s.ships.leave(p);
            assertEquals(0, q.lockTagged, "a lone player keeps no lock");
        }

        @Test
        @DisplayName("with keepShots mines keep living ownerless")
        void keepShots() {
            Player p = setup("keepShots: yes\n");
            Player q = SimFixture.ready(s, "Q", 0, 500, 500);
            Objects o = w.objects;
            int own = SimFixture.obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.mineOwner[own] = p.id;
            int adopted = SimFixture.obj(w, Obj.MINE, q.id, 120, 100, 0, 0, 30, 100);
            o.mineOwner[adopted] = p.id;
            s.ships.leave(p);
            assertAll(
                () -> assertEquals(100.0, o.life[own], "its mine lives"),
                () -> assertEquals(30f, o.mass[own], "with its mass"),
                () -> assertEquals(Obj.NO_ID, o.owner[own], "without owner"),
                () -> assertEquals(Obj.NO_ID, o.mineOwner[own], "and without mine owner"),
                () -> assertEquals(100.0, o.life[adopted], "the adopted mine lives"),
                () -> assertEquals(Obj.NO_ID, o.mineOwner[adopted], "and loses its mine owner"));
        }

        @Test
        @DisplayName("released tanks leave with their owner; with keepShots they stay, ownerless")
        void tanks() {
            Player p = setup("");
            s.tanks.add(p, 50 * 256);
            s.tanks.detach(p);
            int with = w.numPlayers;
            s.ships.leave(p);
            int after = w.numPlayers;
            Player q = setup("keepShots: yes\n");
            s.tanks.add(q, 50 * 256);
            s.tanks.detach(q);
            s.ships.leave(q);
            Player tank = w.numPlayers == 1 ? w.playerAt(0) : null;
            assertAll(
                () -> assertEquals(2, with, "ship and tank"),
                () -> assertEquals(0, after, "both are gone"),
                () -> assertTrue(tank != null && tank.tank, "with keepShots the tank stays"),
                () -> assertEquals(Obj.NO_ID, tank == null ? 0 : tank.lockPlId, "without an owner"));
        }
    }
}
