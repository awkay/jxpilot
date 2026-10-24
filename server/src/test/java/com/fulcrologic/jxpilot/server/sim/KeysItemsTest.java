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
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Keys: item keys (event.c Handle_keyboard)")
class KeysItemsTest {
    Simulation s;
    World w;
    Keys k;

    Player setup(String extra) {
        s = SimFixture.sim(FRAME + extra, 30, 30, "____");
        w = s.world;
        k = s.keys;
        w.roundDelay = 0;
        Player p = SimFixture.ready(s, "P", 0, 350, 350);
        p.keysCleared = false;
        p.prevKeys = 0;
        p.lastReceivedKeys = 0;
        return p;
    }

    static int it(Item i) {
        return i.ordinal();
    }

    /** Press {@code key} alone (from no keys), then release everything. */
    void tap(Player p, ControlKey key) {
        k.apply(p, key.mask());
        k.apply(p, 0);
    }

    int count(int type) {
        int n = 0;
        Objects o = w.objects;
        for (int i = 0; i < o.count(); i++) {
            if ((o.type[o.active[i]] & type) != 0) {
                n++;
            }
        }
        return n;
    }

    @Nested
    @DisplayName("device keys")
    class Devices {
        @Test
        @DisplayName("TOGGLE_AUTOPILOT, EMERGENCY_THRUST, EMERGENCY_SHIELD and PHASING toggle with the ability only")
        void toggles() {
            Player p = setup("");
            List<String> got = new ArrayList<>();
            ControlKey[] keys = {ControlKey.TOGGLE_AUTOPILOT, ControlKey.EMERGENCY_THRUST, ControlKey.EMERGENCY_SHIELD,
                ControlKey.PHASING};
            int[] bits = {Has.AUTOPILOT, Has.EMERGENCY_THRUST, Has.EMERGENCY_SHIELD, Has.PHASING_DEVICE};
            Item[] items = {Item.AUTOPILOT, Item.EMERGENCY_THRUST, Item.EMERGENCY_SHIELD, Item.PHASING};
            for (int i = 0; i < keys.length; i++) {
                p.items[it(items[i])] = 3;
                p.have &= ~bits[i];
                k.pressed(p, keys[i]);
                boolean without = (p.used & bits[i]) != 0;
                p.have |= bits[i];
                k.pressed(p, keys[i]);
                boolean on = (p.used & bits[i]) != 0;
                k.pressed(p, keys[i]);
                boolean off = (p.used & bits[i]) != 0;
                got.add(keys[i] + " " + without + " " + on + " " + off);
            }
            assertEquals(List.of("TOGGLE_AUTOPILOT false true false", "EMERGENCY_THRUST false true false",
                "EMERGENCY_SHIELD false true false", "PHASING false true false"), got,
                "without the ability nothing; with it the first press turns it on, the next off");
        }

        @Test
        @DisplayName("CLOAK and DEFLECTOR toggle while an item is left")
        void itemToggles() {
            Player p = setup("");
            p.items[it(Item.CLOAK)] = 0;
            k.pressed(p, ControlKey.CLOAK);
            boolean noCloak = (p.used & Has.CLOAKING_DEVICE) != 0;
            p.items[it(Item.CLOAK)] = 1;
            p.have |= Has.CLOAKING_DEVICE;
            k.pressed(p, ControlKey.CLOAK);
            boolean cloaked = (p.used & Has.CLOAKING_DEVICE) != 0;
            k.pressed(p, ControlKey.CLOAK);
            boolean uncloaked = (p.used & Has.CLOAKING_DEVICE) != 0;
            p.items[it(Item.DEFLECTOR)] = 0;
            k.pressed(p, ControlKey.DEFLECTOR);
            boolean noDefl = (p.used & Has.DEFLECTOR) != 0;
            p.items[it(Item.DEFLECTOR)] = 1;
            k.pressed(p, ControlKey.DEFLECTOR);
            boolean defl = (p.used & Has.DEFLECTOR) != 0;
            k.pressed(p, ControlKey.DEFLECTOR);
            assertAll(
                () -> assertFalse(noCloak, "no cloak item: CLOAK does nothing"),
                () -> assertTrue(cloaked, "CLOAK cloaks"),
                () -> assertFalse(uncloaked, "CLOAK again uncloaks"),
                () -> assertFalse(noDefl, "no deflector item: DEFLECTOR does nothing"),
                () -> assertTrue(defl, "DEFLECTOR turns it on"),
                () -> assertEquals(0, p.used & Has.DEFLECTOR, "DEFLECTOR again turns it off"));
        }

        @Test
        @DisplayName("HYPERJUMP warps, TRANSPORTER steals, ECM fires a blast")
        void oneShots() {
            Player p = setup("");
            p.items[it(Item.HYPERJUMP)] = 1;
            k.pressed(p, ControlKey.HYPERJUMP);
            boolean warping = (p.status & Obj.WARPING) != 0;
            Player v = SimFixture.ready(s, "V", 0, 400, 350);
            java.util.Arrays.fill(v.items, 0);
            v.items[it(Item.MINE)] = 1;
            v.fuel = 0;
            v.tankFuel[0] = 0;
            p.items[it(Item.TRANSPORTER)] = 1;
            k.pressed(p, ControlKey.TRANSPORTER);
            p.items[it(Item.ECM)] = 1;
            k.pressed(p, ControlKey.ECM);
            assertAll(
                () -> assertTrue(warping, "HYPERJUMP: the ship warps"),
                () -> assertEquals(0, p.items[it(Item.HYPERJUMP)], "HYPERJUMP: the hyperjump is used"),
                () -> assertEquals(1, p.items[it(Item.MINE)], "TRANSPORTER: the victim's mine is stolen"),
                () -> assertEquals(1, w.ecms.count(), "ECM: an ECM blast"),
                () -> assertEquals(0, p.items[it(Item.ECM)], "ECM: the ECM is used"));
        }

        @Test
        @DisplayName("TRACTOR_BEAM / PRESSOR_BEAM switch the beam on with the ability; releasing either ends it")
        void beams() {
            Player p = setup("");
            p.have &= ~Has.TRACTOR_BEAM;
            k.pressed(p, ControlKey.TRACTOR_BEAM);
            boolean without = (p.used & Has.TRACTOR_BEAM) != 0;
            p.have |= Has.TRACTOR_BEAM;
            k.pressed(p, ControlKey.PRESSOR_BEAM);
            boolean pressor = p.tractorIsPressor;
            boolean onP = (p.used & Has.TRACTOR_BEAM) != 0;
            k.released(p, ControlKey.PRESSOR_BEAM);
            boolean offP = (p.used & Has.TRACTOR_BEAM) != 0;
            k.pressed(p, ControlKey.TRACTOR_BEAM);
            boolean tractor = p.tractorIsPressor;
            boolean onT = (p.used & Has.TRACTOR_BEAM) != 0;
            k.released(p, ControlKey.TRACTOR_BEAM);
            assertAll(
                () -> assertFalse(without, "without the ability nothing happens"),
                () -> assertTrue(pressor, "PRESSOR_BEAM makes a pressor"),
                () -> assertTrue(onP, "PRESSOR_BEAM turns the beam on"),
                () -> assertFalse(offP, "releasing it turns the beam off"),
                () -> assertFalse(tractor, "TRACTOR_BEAM makes a tractor"),
                () -> assertTrue(onT, "TRACTOR_BEAM turns the beam on"),
                () -> assertEquals(0, p.used & Has.TRACTOR_BEAM, "releasing it turns the beam off"));
        }
    }

    @Nested
    @DisplayName("weapon and item keys")
    class Weapons {
        @Test
        @DisplayName("FIRE_LASER needs lasers and no shield; releasing it stops")
        void laser() {
            Player p = setup("");
            p.items[it(Item.LASER)] = 0;
            k.pressed(p, ControlKey.FIRE_LASER);
            boolean none = (p.used & Has.LASER) != 0;
            p.items[it(Item.LASER)] = 1;
            p.used |= Has.SHIELD;
            k.pressed(p, ControlKey.FIRE_LASER);
            boolean shielded = (p.used & Has.LASER) != 0;
            p.used &= ~Has.SHIELD;
            k.pressed(p, ControlKey.FIRE_LASER);
            boolean on = (p.used & Has.LASER) != 0;
            k.released(p, ControlKey.FIRE_LASER);
            assertAll(
                () -> assertFalse(none, "no lasers: nothing"),
                () -> assertFalse(shielded, "shielded: nothing"),
                () -> assertTrue(on, "with a laser and no shield the laser is on"),
                () -> assertEquals(0, p.used & Has.LASER, "releasing it turns the laser off"));
        }

        @Test
        @DisplayName("missile, mine and detonate keys go to their weapons")
        void dispatch() {
            Player p = setup("");
            p.items[it(Item.MISSILE)] = 5;
            p.items[it(Item.MINE)] = 5;
            k.pressed(p, ControlKey.FIRE_TORPEDO);
            int torpedoes = count(Obj.TORPEDO);
            k.pressed(p, ControlKey.FIRE_HEAT);
            int heats = count(Obj.HEAT_SHOT);
            k.pressed(p, ControlKey.DROP_MINE);
            int mines = count(Obj.MINE);
            k.pressed(p, ControlKey.DETACH_MINE);
            int mines2 = count(Obj.MINE);
            int missilesLeft = p.items[it(Item.MISSILE)];
            k.pressed(p, ControlKey.FIRE_MISSILE);
            int smartNoLock = count(Obj.SMART_SHOT);
            k.pressed(p, ControlKey.DETONATE_MINES);
            int dead = 0;
            for (int i = 0; i < w.objects.count(); i++) {
                int h = w.objects.active[i];
                if ((w.objects.type[h] & Obj.MINE) != 0 && w.objects.life[h] == 0) {
                    dead++;
                }
            }
            int detonated = dead;
            assertAll(
                () -> assertEquals(1, torpedoes, "FIRE_TORPEDO fires a torpedo"),
                () -> assertEquals(1, heats, "FIRE_HEAT fires a heat-seeker"),
                () -> assertEquals(1, mines, "DROP_MINE drops a mine"),
                () -> assertEquals(2, mines2, "DETACH_MINE throws one"),
                () -> assertEquals(3, missilesLeft, "two missiles are used"),
                () -> assertEquals(0, smartNoLock, "FIRE_MISSILE without a lock fires nothing"),
                () -> assertEquals(1, detonated, "DETONATE_MINES explodes the closest own mine (life 0)"));
        }

        @Test
        @DisplayName("SELECT_ITEM picks the next item to lose (release: state 1); LOSE_ITEM loses it")
        void selectLose() {
            Player p = setup("loseItemDestroys: yes\n");
            java.util.Arrays.fill(p.items, 0);
            p.items[it(Item.MINE)] = 2;
            p.loseItem = 0;
            k.pressed(p, ControlKey.SELECT_ITEM);
            int selected = p.loseItem;
            int state = p.loseItemState;
            k.released(p, ControlKey.SELECT_ITEM);
            int released = p.loseItemState;
            k.pressed(p, ControlKey.LOSE_ITEM);
            assertAll(
                () -> assertEquals(it(Item.MINE), selected, "the only item owned is selected"),
                () -> assertEquals(2, state, "key down: state 2"),
                () -> assertEquals(1, released, "key up: state 1"),
                () -> assertEquals(1, p.items[it(Item.MINE)], "LOSE_ITEM loses one mine"));
        }

        @Test
        @DisplayName("tank keys select and detach tanks")
        void tanks() {
            Player p = setup("");
            s.tanks.add(p, 100 * 256);
            s.tanks.add(p, 100 * 256);
            k.pressed(p, ControlKey.TANK_NEXT);
            int next = p.currentTank;
            k.pressed(p, ControlKey.TANK_PREV);
            k.pressed(p, ControlKey.TANK_PREV);
            int prev = p.currentTank;
            k.pressed(p, ControlKey.TANK_DETACH);
            assertAll(
                () -> assertEquals(1, next, "TANK_NEXT: 0 -> 1"),
                () -> assertEquals(2, prev, "TANK_PREV twice: 1 -> 0 -> 2 (wraps)"),
                () -> assertEquals(1, p.numTanks, "TANK_DETACH releases a tank"));
        }
    }

    @Nested
    @DisplayName("modifiers (event.c:623-725)")
    class Modifiers {
        String run(boolean allowed, ControlKey key, int times) {
            Player p = setup("");
            ItemParams ip = w.items;
            ip.allowNukes = allowed;
            ip.allowClusters = allowed;
            ip.allowModifiers = allowed;
            ip.allowLaserModifiers = allowed;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < times; i++) {
                k.pressed(p, key);
                sb.append(p.mods).append(i < times - 1 ? "," : "");
            }
            return sb.toString();
        }

        @Test
        @DisplayName("each toggle steps its field (wrapping at its maximum) when its rule allows it")
        void toggles() {
            int v = 1 << 4;
            int mi = 1 << 6;
            int sp = 1 << 8;
            int pw = 1 << 10;
            int la = 1 << 12;
            int nu = 1 << 2;
            assertAll(
                () -> assertEquals(nu + "," + 3 * nu + ",0", run(true, ControlKey.TOGGLE_NUCLEAR, 3),
                    "nuclear: none -> nuclear -> full nuclear -> none"),
                () -> assertEquals("1,0", run(true, ControlKey.TOGGLE_CLUSTER, 2), "cluster flips"),
                () -> assertEquals("2,0", run(true, ControlKey.TOGGLE_IMPLOSION, 2), "implosion flips"),
                () -> assertEquals(v + "," + 2 * v + "," + 3 * v + ",0", run(true, ControlKey.TOGGLE_VELOCITY, 4),
                    "velocity 1, 2, 3, 0"),
                () -> assertEquals(mi + "," + 2 * mi + "," + 3 * mi + ",0", run(true, ControlKey.TOGGLE_MINI, 4),
                    "mini 1, 2, 3, 0"),
                () -> assertEquals(sp + "," + 2 * sp + "," + 3 * sp + ",0", run(true, ControlKey.TOGGLE_SPREAD, 4),
                    "spread 1, 2, 3, 0"),
                () -> assertEquals(pw + "," + 2 * pw + "," + 3 * pw + ",0", run(true, ControlKey.TOGGLE_POWER, 4),
                    "power 1, 2, 3, 0"),
                () -> assertEquals(la + "," + 2 * la + ",0", run(true, ControlKey.TOGGLE_LASER, 3), "laser 1, 2, 0"));
        }

        @Test
        @DisplayName("without the rule a toggle does nothing; CLEAR_MODIFIERS always clears")
        void disallowed() {
            List<String> got = new ArrayList<>();
            for (ControlKey key : new ControlKey[] {ControlKey.TOGGLE_NUCLEAR, ControlKey.TOGGLE_CLUSTER,
                ControlKey.TOGGLE_IMPLOSION, ControlKey.TOGGLE_VELOCITY, ControlKey.TOGGLE_MINI,
                ControlKey.TOGGLE_SPREAD, ControlKey.TOGGLE_POWER, ControlKey.TOGGLE_LASER}) {
                got.add(run(false, key, 1));
            }
            Player p = setup("");
            p.mods = 12345;
            k.pressed(p, ControlKey.CLEAR_MODIFIERS);
            assertAll(
                () -> assertEquals(List.of("0", "0", "0", "0", "0", "0", "0", "0"), got, "nothing changes"),
                () -> assertEquals(0, p.mods, "CLEAR_MODIFIERS clears everything"));
        }

        @Test
        @DisplayName("LOAD_MODIFIERS stores into its bank while REPROGRAM is held, else loads it filtered by the rules")
        void banks() {
            Player p = setup("");
            int mods = Obj.MOD_CLUSTER | Obj.MOD_IMPLOSION | Mods.withMini(0, 2);
            p.mods = mods;
            k.pressed(p, ControlKey.REPROGRAM);
            boolean reprogram = (p.status & Obj.REPROGRAM) != 0;
            k.pressed(p, ControlKey.LOAD_MODIFIERS_3);
            k.released(p, ControlKey.REPROGRAM);
            boolean released = (p.status & Obj.REPROGRAM) != 0;
            int bank = p.modBank[2];
            int other = p.modBank[0];
            p.mods = 0;
            w.items.allowClusters = true;
            w.items.allowModifiers = false;
            k.pressed(p, ControlKey.LOAD_MODIFIERS_3);
            assertAll(
                () -> assertTrue(reprogram, "REPROGRAM press sets the status"),
                () -> assertFalse(released, "REPROGRAM release clears it"),
                () -> assertEquals(mods, bank, "bank 3 holds the modifiers"),
                () -> assertEquals(0, other, "bank 1 is untouched"),
                () -> assertEquals(Obj.MOD_CLUSTER, p.mods, "loading keeps only what the rules allow (cluster)"));
        }

        @Test
        @DisplayName("LOAD_LOCK stores the lock while REPROGRAM is held and restores it otherwise")
        void lockBanks() {
            Player p = setup("");
            Player q = SimFixture.ready(s, "Q", 0, 500, 500);
            p.lockTagged = Player.LOCK_PLAYER;
            p.lockPlId = q.id;
            p.status |= Obj.REPROGRAM;
            k.pressed(p, ControlKey.LOAD_LOCK_2);
            int stored = p.lockBank[1];
            p.status &= ~Obj.REPROGRAM;
            p.lockTagged = 0;
            p.lockPlId = 0;
            k.pressed(p, ControlKey.LOAD_LOCK_2);
            assertAll(
                () -> assertEquals(q.id, stored, "bank 2 holds the locked player"),
                () -> assertEquals(q.id, p.lockPlId, "loading locks on that player again"),
                () -> assertTrue((p.lockTagged & Player.LOCK_PLAYER) != 0, "the lock is set"));
        }
    }

    @Nested
    @DisplayName("autopilot and settings")
    class Autopilot {
        Player auto() {
            Player p = setup("");
            p.have |= Has.AUTOPILOT;
            p.power = 45;
            p.turnSpeed = 20;
            s.devices.autopilot(p, true);
            return p;
        }

        @Test
        @DisplayName("turning or thrusting (press or release) switches the autopilot off first")
        void manualControl() {
            Player p = auto();
            k.apply(p, ControlKey.TURN_LEFT.mask());
            boolean turnOff = (p.used & Has.AUTOPILOT) == 0;
            float acc = p.turnAcc;
            Player q = auto();
            k.apply(q, ControlKey.THRUST.mask());
            boolean thrustOff = (q.used & Has.AUTOPILOT) == 0;
            boolean thrusting = (q.status & Obj.THRUSTING) != 0;
            s.devices.autopilot(q, true);
            k.apply(q, 0);
            boolean releaseOff = (q.used & Has.AUTOPILOT) == 0;
            Player r = auto();
            k.apply(r, ControlKey.TURN_RIGHT.mask());
            s.devices.autopilot(r, true);
            k.apply(r, 0);
            assertAll(
                () -> assertTrue(turnOff, "TURN_LEFT turns the autopilot off"),
                () -> assertEquals(20f, acc, "and turns with the restored turnspeed"),
                () -> assertTrue(thrustOff, "THRUST turns the autopilot off"),
                () -> assertTrue(thrusting, "and thrusts"),
                () -> assertTrue(releaseOff, "releasing THRUST turns it off"),
                () -> assertEquals(45f, q.power, "with the saved power back"),
                () -> assertEquals(0, r.used & Has.AUTOPILOT, "releasing a turn key turns it off"),
                () -> assertEquals(0f, r.turnAcc, "and stops the turn"));
        }

        @Test
        @DisplayName("power, turnspeed and swap keys do nothing on autopilot or hover pause")
        void settingsBlocked() {
            List<String> got = new ArrayList<>();
            ControlKey[] keys = {ControlKey.SWAP_SETTINGS, ControlKey.INCREASE_POWER, ControlKey.DECREASE_POWER,
                ControlKey.INCREASE_TURNSPEED, ControlKey.DECREASE_TURNSPEED};
            for (ControlKey key : keys) {
                Player p = auto();
                k.pressed(p, key);
                String onAuto = p.power + "/" + p.turnSpeed;
                s.devices.autopilot(p, false);
                p.status |= Obj.HOVERPAUSE;
                k.pressed(p, key);
                String onHover = p.power + "/" + p.turnSpeed;
                p.status &= ~Obj.HOVERPAUSE;
                p.powerS = 35;
                p.turnSpeedS = 25;
                k.pressed(p, key);
                got.add(key + " " + onAuto + " " + onHover + " " + p.power + "/" + p.turnSpeed);
            }
            assertEquals(List.of("SWAP_SETTINGS 30.0/34.0 45.0/20.0 35.0/25.0",
                "INCREASE_POWER 30.0/34.0 45.0/20.0 " + (float) (45f * 1.10) + "/20.0",
                "DECREASE_POWER 30.0/34.0 45.0/20.0 " + (float) (45f * 0.90) + "/20.0",
                "INCREASE_TURNSPEED 30.0/34.0 45.0/20.0 45.0/" + (float) (20f * 1.05),
                "DECREASE_TURNSPEED 30.0/34.0 45.0/20.0 45.0/" + (float) (20f * 0.95)), got,
                "blocked on autopilot and hover pause; otherwise they change the settings");
        }
    }

    @Test
    @DisplayName("a blocked settings key does not stop the keys after it in the same frame")
    void blockedSettingsContinue() {
        Player p = setup("");
        p.have |= Has.AUTOPILOT;
        s.devices.autopilot(p, true);
        k.apply(p, ControlKey.SWAP_SETTINGS.mask() | ControlKey.INCREASE_POWER.mask()
            | ControlKey.DECREASE_POWER.mask() | ControlKey.INCREASE_TURNSPEED.mask()
            | ControlKey.DECREASE_TURNSPEED.mask() | ControlKey.THRUST.mask());
        assertAll(
            () -> assertTrue((p.status & Obj.THRUSTING) != 0, "THRUST (a later key) is handled"),
            () -> assertEquals(0, p.used & Has.AUTOPILOT, "and switches the autopilot off"));
    }

    @Test
    @DisplayName("lock keys and FIRE_MISSILE go to the locks and the missiles")
    void lockAndMissileKeys() {
        List<String> got = new ArrayList<>();
        for (ControlKey key : new ControlKey[] {ControlKey.LOCK_NEXT, ControlKey.LOCK_PREV,
            ControlKey.LOCK_NEXT_CLOSE}) {
            Player p = setup("");
            SimFixture.ready(s, "Q", 0, 400, 350);
            p.lockTagged = 0;
            k.pressed(p, key);
            got.add(key + " " + ((p.lockTagged & Player.LOCK_PLAYER) != 0));
        }
        Player p = setup("");
        boolean compass = (p.used & Has.COMPASS) != 0;
        k.pressed(p, ControlKey.TOGGLE_COMPASS);
        got.add("compass " + compass + " " + ((p.used & Has.COMPASS) != 0));
        Player q = SimFixture.ready(s, "Q", 0, 400, 350);
        p.items[it(Item.MISSILE)] = 2;
        p.lockTagged = Player.LOCK_PLAYER | Player.LOCK_VISIBLE;
        p.lockPlId = q.id;
        p.lockDistance = 50;
        s.locks.sensorRange(p);
        k.pressed(p, ControlKey.FIRE_MISSILE);
        got.add("smart " + count(Obj.SMART_SHOT));
        assertEquals(List.of("LOCK_NEXT true", "LOCK_PREV true", "LOCK_NEXT_CLOSE true", "compass true false",
            "smart 1"), got, "each key reaches its handler");
    }

    @Nested
    @DisplayName("phased keys (event.c:403-453)")
    class Phased {
        @Test
        @DisplayName("a phased ship handles only the allowed presses; releases always act")
        void filter() {
            Player p = setup("");
            p.used |= Has.PHASING_DEVICE;
            p.have |= Has.PHASING_DEVICE | Has.AUTOPILOT;
            p.items[it(Item.HYPERJUMP)] = 1;
            p.items[it(Item.LASER)] = 1;
            k.apply(p, ControlKey.FIRE_SHOT.mask());
            int shots = count(Obj.SHOT);
            k.apply(p, ControlKey.HYPERJUMP.mask());
            boolean warped = (p.status & Obj.WARPING) != 0;
            k.apply(p, ControlKey.FIRE_LASER.mask());
            boolean laser = (p.used & Has.LASER) != 0;
            p.used |= Has.LASER;
            k.apply(p, ControlKey.TOGGLE_AUTOPILOT.mask());
            boolean auto = (p.used & Has.AUTOPILOT) != 0;
            boolean laserReleased = (p.used & Has.LASER) == 0;
            k.apply(p, 0);
            k.apply(p, ControlKey.SHIELD.mask());
            assertAll(
                () -> assertEquals(0, shots, "FIRE_SHOT is ignored while phased"),
                () -> assertFalse(warped, "HYPERJUMP is ignored while phased"),
                () -> assertFalse(laser, "FIRE_LASER is ignored while phased"),
                () -> assertTrue(auto, "TOGGLE_AUTOPILOT works while phased"),
                () -> assertTrue(laserReleased, "releasing FIRE_LASER still turns the laser off"),
                () -> assertTrue((p.used & Has.SHIELD) != 0, "SHIELD works while phased"));
        }
    }

    @Nested
    @DisplayName("pauseKey (KEY_PAUSE, event.c:791-880)")
    class Pause {
        Base home;

        Player near(int dbx, int dby, float velocity) {
            Player p = setup("");
            home = w.map.bases().get(p.homeBase);
            p.initPixels((home.bx() + dbx + 0.5) * 35, (home.by() + dby + 0.5) * 35);
            p.velocity = velocity;
            return p;
        }

        @Test
        @DisplayName("on the home block and slower than 3 the ship pauses (autopilot off first)")
        void pause() {
            Player p = near(0, 0, 3);
            p.have |= Has.AUTOPILOT;
            p.power = 45;
            s.devices.autopilot(p, true);
            boolean ended = k.pauseKey(p);
            Player q = near(0, 0, 3.01f);
            k.pauseKey(q);
            assertAll(
                () -> assertTrue((p.status & Obj.PAUSE) != 0, "the ship pauses"),
                () -> assertFalse(ended, "pausing does not end key handling"),
                () -> assertEquals(0, p.used & Has.AUTOPILOT, "the autopilot is off"),
                () -> assertEquals(45f, p.power, "with the saved power back"),
                () -> assertEquals(0, q.status & Obj.PAUSE, "faster than 3 px/frame: no pause"));
        }

        @Test
        @DisplayName("the speed limit is (float)(3 + |gravity|) computed in double, as C's DFLOAT +=")
        void gravityLimit() {
            Player p = setup("gravity: -0.245\ngravityAngle: 30\n");
            home = w.map.bases().get(p.homeBase);
            p.initPixels((home.bx() + 0.5) * 35, (home.by() + 0.5) * 35);
            float gx = w.gravityX(p.bx, p.by);
            float gy = w.gravityY(p.bx, p.by);
            float cLimit = (float) (3.0f + StrictMath.hypot(gx, gy));
            float floatLimit = 3.0f + (float) StrictMath.hypot(gx, gy);
            p.velocity = cLimit;
            k.pauseKey(p);
            assertAll(
                () -> assertEquals(3.2450001f, cLimit, "C: (float)(3 + 0.24500000...) = 3.2450001"),
                () -> assertEquals(3.245f, floatLimit, "a float sum would give 3.245"),
                () -> assertTrue((p.status & Obj.PAUSE) != 0, "a ship at exactly C's limit pauses"));
        }

        @Test
        @DisplayName("within 2 blocks of home nothing happens; farther and slower than 5 the ship hover-pauses")
        void hoverStart() {
            Player p = near(2, 2, 0);
            k.pauseKey(p);
            int close = p.status & (Obj.PAUSE | Obj.HOVERPAUSE);
            Player q = near(3, 0, 5.01f);
            k.pauseKey(q);
            int fast = q.status & Obj.HOVERPAUSE;
            Player h = near(0, 3, 5);
            w.params.defHave &= ~Has.SHIELD;
            h.status |= Obj.SELF_DESTRUCT;
            h.have |= Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE | Has.SHIELD;
            h.have &= ~Has.MIRROR;
            h.used |= Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE | Has.SHOT | Has.TRACTOR_BEAM;
            h.status &= ~Obj.GRAVITY;
            h.power = 45;
            boolean ended = k.pauseKey(h);
            assertAll(
                () -> assertEquals(0, close, "2 blocks from home: neither pause"),
                () -> assertEquals(0, fast, "faster than 5 px/frame: no hover pause"),
                () -> assertTrue((h.status & Obj.HOVERPAUSE) != 0, "3 blocks from home: hover pause"),
                () -> assertFalse(ended, "hover pause does not end key handling"),
                () -> assertEquals(5 * 60, h.count, "it lasts at least 5 * FPS frames"),
                () -> assertEquals(0, h.status & Obj.SELF_DESTRUCT, "self-destruct is cancelled"),
                () -> assertEquals(0, h.used & (Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE),
                    "emergency thrust, emergency shield and phasing go off"),
                () -> assertTrue((h.status & Obj.GRAVITY) != 0, "phasing off: gravity again"),
                () -> assertTrue((h.used & Has.AUTOPILOT) != 0, "the autopilot holds the ship"),
                () -> assertEquals(30f, h.power, "on autopilot settings"),
                () -> assertEquals(0, h.used & (Has.SHOT | Has.TRACTOR_BEAM), "USED_KILL abilities stop"),
                () -> assertEquals(0, h.have & (Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD),
                    "switching off the spent emergency devices (no items, no time) drops their abilities"),
                () -> assertEquals(0, h.have & Has.MIRROR, "other abilities are not granted"),
                () -> assertEquals(0, h.have & Has.SHIELD, "without default shields the shield is lost"),
                () -> assertEquals(0, h.used & Has.SHIELD, "so shields cannot go up"));
        }

        @Test
        @DisplayName("hover pause leaves devices that are not in use alone")
        void hoverUnused() {
            Player h = near(0, 3, 0);
            w.params.defHave &= ~Has.SHIELD;
            h.have |= Has.SHIELD;
            h.have |= Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE;
            h.used &= ~(Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE);
            h.status &= ~Obj.GRAVITY;
            k.pauseKey(h);
            assertAll(
                () -> assertTrue((h.status & Obj.HOVERPAUSE) != 0, "hover-paused"),
                () -> assertEquals(Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE,
                    h.have & (Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE),
                    "unused emergency devices and phasing keep their abilities"),
                () -> assertEquals(0, h.status & Obj.GRAVITY, "phasing (off) is not switched off again"),
                () -> assertEquals(0, h.have & Has.SHIELD, "without default shields the shield ability is lost"));
        }

        @Test
        @DisplayName("hover pause raises shields when available; it ends only after its count, dropping missing shields")
        void hoverEnd() {
            Player p = near(4, 4, 0);
            w.params.defHave |= Has.SHIELD;
            p.have |= Has.SHIELD;
            p.used &= ~Has.SHIELD;
            k.pauseKey(p);
            boolean shields = (p.used & Has.SHIELD) != 0;
            p.count = 1;
            k.pauseKey(p);
            boolean still = (p.status & Obj.HOVERPAUSE) != 0;
            p.count = 0;
            p.have &= ~Has.SHIELD;
            k.pauseKey(p);
            assertAll(
                () -> assertTrue(shields, "shields go up"),
                () -> assertTrue(still, "with count left the hover pause stays"),
                () -> assertEquals(0, p.status & Obj.HOVERPAUSE, "after the count it ends"),
                () -> assertEquals(0, p.used & Has.AUTOPILOT, "the autopilot goes off"),
                () -> assertEquals(0, p.used & Has.SHIELD, "shields the ship does not have go down"));
        }

        @Test
        @DisplayName("a paused ship asks to unpause (true once playing again); a hover-paused ship cannot pause")
        void unpause() {
            Player p = near(0, 0, 0);
            k.pauseKey(p);
            p.count = 0;
            boolean ended = k.pauseKey(p);
            Player q = near(4, 4, 0);
            k.pauseKey(q);
            q.status |= Obj.PAUSE;
            boolean mixed = k.pauseKey(q);
            Player r = near(0, 0, 0);
            r.homeBase = -1;
            boolean noHome = k.pauseKey(r);
            assertAll(
                () -> assertTrue(ended, "unpausing ends key handling for the frame"),
                () -> assertEquals(0, p.status & Obj.PAUSE, "the ship is no longer paused"),
                () -> assertTrue((p.status & Obj.PLAYING) != 0, "and plays"),
                () -> assertFalse(mixed, "a hover-paused ship's pause press does nothing"),
                () -> assertTrue((q.status & Obj.HOVERPAUSE) != 0, "and the hover pause stays"),
                () -> assertFalse(noHome, "a ship without a home base cannot pause"),
                () -> assertEquals(0, r.status & (Obj.PAUSE | Obj.HOVERPAUSE), "it stays as it is"));
        }

        @Test
        @DisplayName("PAUSE through apply hover-pauses, and the autopilot then flies the ship")
        void viaApply() {
            Player p = near(5, 5, 0);
            w.frames = 10;
            k.apply(p, ControlKey.PAUSE.mask());
            k.apply(p, 0);
            p.vx = 3;
            p.turnSpeed = 34;
            s.devices.use(p);
            assertAll(
                () -> assertTrue((p.status & Obj.HOVERPAUSE) != 0, "hover-paused"),
                () -> assertTrue(p.turnSpeed != 34f, "the autopilot steers the hovering ship"));
        }
    }
}
