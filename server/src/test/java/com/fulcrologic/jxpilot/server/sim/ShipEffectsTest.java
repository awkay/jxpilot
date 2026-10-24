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
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.ShipKernel;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ShipEffects}: each kernel event runs the server code C runs at that point. */
@DisplayName("ShipEffects: the server's side of the ship kernel")
class ShipEffectsTest {
    /** Wormholes (0, 1), targets (0, 1), a fuel station and two bases. */
    static final String[] ROWS = {
        "xxxxxxxxxx",
        "x  @   @ x",
        "x !    ! x",
        "x   #    x",
        "x _ _    x",
        "xxxxxxxxxx"
    };

    Simulation s;
    World w;
    ShipEffects fx;
    Player p;

    void setup() {
        s = TestWorlds.sim("framesPerSecond: 60\ngravity: 0", ROWS);
        w = s.world;
        fx = new ShipEffects(w, s);
        p = TestWorlds.join(s, "p", 0);
        p.used &= ~Has.SHIELD;
        p.audio.clear();
    }

    int sounds(SoundEvent e) {
        int n = 0;
        for (int i = 0; i < p.audio.count(); i++) {
            n += p.audio.event(i) == e.ordinal() ? 1 : 0;
        }
        return n;
    }

    @Nested
    @DisplayName("keys")
    class Keys {
        @Test
        @DisplayName("other keys go to the server's Keys and its answer is returned; autopilot off; THRUST event on a power change")
        void keys() {
            setup();
            boolean sd = fx.keyPressed(p, ControlKey.SELF_DESTRUCT);
            int sdStatus = p.status & Obj.SELF_DESTRUCT;
            int sdCount = p.count;
            p.used |= Has.REFUEL;
            fx.keyReleased(p, ControlKey.REFUEL);
            int refuel = p.used & Has.REFUEL;
            s.ships.pause(p, true);
            p.count = 0;
            boolean unpause = fx.keyPressed(p, ControlKey.PAUSE);
            p.autoPowerS = 33;
            p.used |= Has.AUTOPILOT;
            fx.autopilotOff(p);
            long before = w.events.seq();
            fx.powerChanged(p);
            int last = Events.index(w.events.seq() - 1);
            assertAll(
                () -> assertFalse(sd, "self-destruct does not end key handling"),
                () -> assertEquals(Obj.SELF_DESTRUCT, sdStatus, "it starts the self-destruct"),
                () -> assertEquals(150, sdCount, "with C's 150-frame count"),
                () -> assertEquals(0, refuel, "releasing REFUEL stops refuelling"),
                () -> assertTrue(unpause, "an unpause that leaves the ship playing ends key handling (true)"),
                () -> assertEquals(0, p.used & Has.AUTOPILOT, "autopilotOff switches the autopilot off"),
                () -> assertEquals(33f, p.power, "restoring the saved power"),
                () -> assertEquals(before + 1, w.events.seq(), "powerChanged emits one event"),
                () -> assertEquals(Events.THRUST, w.events.kind[last], "a THRUST event"),
                () -> assertEquals(p.id, w.events.a[last], "for the player"),
                () -> assertEquals(33f, w.events.value[last], "with its power"));
        }
    }

    @Nested
    @DisplayName("shots")
    class Shots {
        @Test
        @DisplayName("shotRoom is whether the pool has room; shot makes the bullet object (or C's unpositioned one) with the sound")
        void shots() {
            setup();
            boolean room = fx.shotRoom();
            int h = fx.shot(p, 100.5f, 80.25f, 16, 3.5f, -1.25f, false);
            int sound = sounds(SoundEvent.FIRE_SHOT);
            int n = w.objects.count();
            int out = fx.shot(p, -5, 80, 16, 0, 0, true);
            while (w.objects.count() < w.objects.capacity) {
                w.objects.alloc();
            }
            boolean full = fx.shotRoom();
            assertAll(
                () -> assertTrue(room, "an empty pool has room"),
                () -> assertTrue(h >= 0, "the bullet's handle"),
                () -> assertEquals(Obj.SHOT, w.objects.type[h], "a SHOT"),
                () -> assertEquals(p.id, w.objects.owner[h], "owned by the shooter"),
                () -> assertEquals(p.team, w.objects.team[h], "of its team"),
                () -> assertEquals(3.5f, w.objects.vx[h], "with the kernel's velocity"),
                () -> assertEquals(-1.25f, w.objects.vy[h], "both axes"),
                () -> assertEquals(16, w.objects.missileDir[h], "along dir"),
                () -> assertEquals(w.params.shotLife, w.objects.life[h], "with the shot life"),
                () -> assertEquals(w.params.shotMass, w.objects.mass[h], "and mass"),
                () -> assertEquals(1, sound, "FIRE_SHOT is played"),
                () -> assertEquals(1, n, "one object"),
                () -> assertEquals(-1, out, "outside the world: -1"),
                () -> assertFalse(full, "a full pool has no room"));
        }
    }

    @Nested
    @DisplayName("the player loop")
    class Loop {
        @Test
        @DisplayName("devices runs the timers and uses; upkeep drains devices and refuels; sensors sets the range")
        void loop() {
            setup();
            p.have |= Has.PHASING_DEVICE | Has.LASER;
            p.used |= Has.PHASING_DEVICE | Has.LASER;
            p.phasingLeft = 5;
            fx.devices(p);
            int phasing = p.phasingLeft;
            int laser = p.used & Has.LASER;
            setup();
            p.have |= Has.CLOAKING_DEVICE | Has.REFUEL;
            p.used |= Has.CLOAKING_DEVICE | Has.REFUEL;
            p.fs = 0;
            com.fulcrologic.jxpilot.common.map.FuelStation st = w.map.fuels().get(0);
            p.initPixels(st.x(), st.y() + 20);
            p.fuel = 1000;
            p.tankFuel[0] = 1000;
            double station = w.fuelLevel[0];
            fx.upkeep(p);
            double gained = p.fuel - 1000;
            double given = station - w.fuelLevel[0];
            p.sensorRange = 0;
            fx.sensors(p);
            assertAll(
                () -> assertEquals(4, phasing, "the phasing timer ran"),
                () -> assertEquals(0, laser, "the laser goes off without laser items"),
                () -> assertEquals(ShipKernel.REFUEL_RATE, given, "the station gave one refuel rate"),
                () -> assertEquals(given + Devices.ED_CLOAKING_DEVICE, gained, 1e-9,
                    "the ship got it, less the cloak's drain"),
                () -> assertTrue(p.sensorRange > 0, "the sensor range is computed"));
        }

        @Test
        @DisplayName("upkeep repairs the target the ship is repairing")
        void repair() {
            setup();
            com.fulcrologic.jxpilot.common.map.Target t = w.map.targets().get(0);
            p.initPixels(t.x(), t.y() - 30);
            p.have |= Has.REPAIR;
            p.used |= Has.REPAIR;
            p.repairTarget = 0;
            p.fuel = 100000;
            p.tankFuel[0] = 100000;
            w.modes.targetDamage[0] = ModeState.TARGET_DAMAGE - 5000;
            fx.upkeep(p);
            assertAll(
                () -> assertTrue(w.modes.targetDamage[0] > ModeState.TARGET_DAMAGE - 5000, "the target gained strength"),
                () -> assertEquals(100000.0 - ShipKernel.REFUEL_RATE, p.fuel, "paid with one refuel rate of fuel"));
        }

        @Test
        @DisplayName("warp sends a warping ship through its wormhole; exhaust and turn sparks make sparks")
        void warpAndSparks() {
            setup();
            p.status |= Obj.WARPING;
            p.wormHoleHit = 0;
            int x0 = p.cx;
            boolean stop = fx.warp(p);
            int status = p.status & (Obj.WARPING | Obj.WARPED);
            int moved = p.cx - x0;
            setup();
            p.status |= Obj.THRUSTING;
            fx.thrustSparks(p);
            int exhaust = w.objects.count();
            int thrustSound = sounds(SoundEvent.THRUST);
            setup();
            fx.turnSparks(p, 6);
            int turn = w.objects.count();
            assertAll(
                () -> assertFalse(stop, "a wormhole within the table does not break the player loop"),
                () -> assertEquals(Obj.WARPED, status, "the ship is WARPED, no longer WARPING"),
                () -> assertTrue(moved != 0, "and somewhere else"),
                () -> assertTrue(exhaust > 0, "thrust makes exhaust sparks (" + exhaust + ")"),
                () -> assertEquals(1, thrustSound, "and the thrust sound"),
                () -> assertEquals(6, turn, "turn sparks: as many as asked"));
        }
    }

    @Nested
    @DisplayName("moving")
    class Moving {
        @Test
        @DisplayName("a crash goes to Crashes; wallDamage damages items with the wall bounce probability")
        void crashAndDamage() {
            setup();
            MoveState m = new MoveState();
            m.start(p.cx, p.cy, 0, 0, 0, 0, 0);
            m.crash = MoveState.CRASH_WALL;
            fx.crashed(p, m, 0);
            int killed = p.status & Obj.KILLED;
            setup();
            w.items.wallBounceDestroyItemProb = 0.5f;
            p.items[Item.MINE.ordinal()] = 4;
            w.rng.seed(7);
            float f = new Rng(7).nextFloat();
            fx.wallDamage(p);
            int mines = p.items[Item.MINE.ordinal()];
            assertAll(
                () -> assertEquals(Obj.KILLED, killed, "a wall crash kills"),
                () -> assertEquals(f < 0.5f ? 2 : 4, mines, "C Item_damage: (int)(4 * 0.5 + 0.5) when rfrac < 0.5"));
        }

        @Test
        @DisplayName("bounced makes wall sparks and the bounce sound, and damages target 0 or 1 when named, none for -1")
        void bounced() {
            setup();
            fx.bounced(p, 64, 3, -1);
            int sparks = w.objects.count();
            int sound = sounds(SoundEvent.PLAYER_BOUNCED);
            int[] none = w.modes.targetDamage.clone();
            setup();
            w.params.wallBounceFuelDrainMult = 2;
            fx.bounced(p, 64, 3, 0);
            int[] zero = w.modes.targetDamage.clone();
            setup();
            fx.bounced(p, 64, 3, 1);
            int[] one = w.modes.targetDamage.clone();
            int full = ModeState.TARGET_DAMAGE;
            assertAll(
                () -> assertTrue(sparks > 0, "wall sparks are made (" + sparks + ")"),
                () -> assertEquals(1, sound, "PLAYER_BOUNCED is played"),
                () -> assertEquals(full, none[0], "no target named: target 0 intact"),
                () -> assertEquals(full, none[1], "and target 1"),
                () -> assertEquals(full - 384, zero[0],
                    "target 0 named: damaged by (long)((3 << 8) * (drain 2 / 4.0)) = 384 (walls.c:2529)"),
                () -> assertEquals(full, zero[1], "target 1 is not"),
                () -> assertTrue(one[1] < full, "target 1 named: it is damaged"),
                () -> assertEquals(full, one[0], "target 0 is not"));
        }
    }
}
