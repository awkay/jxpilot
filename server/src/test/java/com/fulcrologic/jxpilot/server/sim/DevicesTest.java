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
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Devices (update.c device switches, item.c tractor/deflector/transporter/hyperjump)")
class DevicesTest {
    /** One sim tick is one C frame at 60 fps: 4 * FPS = 240 frames per device item. */
    static final int RUN = 240;

    Simulation s;
    World w;
    Devices d;

    Player setup(String extra) {
        s = SimFixture.sim(FRAME + extra, 30, 30, "______");
        w = s.world;
        d = s.devices;
        return SimFixture.ready(s, "P", 0, 350, 350);
    }

    Player other(String name, double x, double y) {
        return SimFixture.ready(s, name, 0, x, y);
    }

    static int it(Item i) {
        return i.ordinal();
    }

    /** THRUST events since {@code from} as "id afterburners power". */
    static List<String> thrust(World w, long from) {
        List<String> out = new ArrayList<>();
        Events e = w.events;
        for (long q = from; q < e.seq(); q++) {
            int i = Events.index(q);
            if (e.kind[i] == Events.THRUST) {
                out.add(e.a[i] + " " + e.b[i] + " " + e.value[i]);
            }
        }
        return out;
    }

    /** The first seed whose RNG stream satisfies {@code p} (the stream is consumed by the predicate). */
    static int seedWhere(Predicate<Rng> p) {
        for (int seed = 1; ; seed++) {
            if (p.test(new Rng(seed))) {
                return seed;
            }
        }
    }

    // ================================================================ switches

    @Nested
    @DisplayName("phasing (C Phasing)")
    class Phasing {
        @Test
        @DisplayName("turning on starts a 4*FPS phase with one item only when the last phase ran out")
        void on() {
            Player p = setup("");
            p.items[it(Item.PHASING)] = 2;
            p.used |= Has.REFUEL | Has.REPAIR | Has.TRACTOR_BEAM | Has.SHIELD;
            d.phasing(p, true);
            int left = p.phasingLeft;
            int max = p.phasingMax;
            int items = p.items[it(Item.PHASING)];
            int used = p.used;
            int status = p.status;
            p.phasingLeft = 10;
            d.phasing(p, true);
            assertAll(
                () -> assertEquals(RUN, left, "a new phase lasts 4 * FPS frames"),
                () -> assertEquals(RUN, max, "the phase maximum is 4 * FPS"),
                () -> assertEquals(1, items, "the new phase uses one phasing item"),
                () -> assertTrue((used & Has.PHASING_DEVICE) != 0, "phasing is in use"),
                () -> assertEquals(0, used & (Has.REFUEL | Has.REPAIR | Has.TRACTOR_BEAM),
                    "refuel, repair and tractor beam go off"),
                () -> assertTrue((used & Has.SHIELD) != 0, "other abilities in use stay on"),
                () -> assertEquals(0, status & Obj.GRAVITY, "a phased ship feels no gravity"),
                () -> assertEquals(10, p.phasingLeft, "a running phase is kept"),
                () -> assertEquals(1, p.items[it(Item.PHASING)], "a running phase uses no item"));
        }

        @Test
        @DisplayName("turning off restores gravity and drops the ability iff phase and items are used up")
        void off() {
            Player p = setup("");
            p.have |= Has.PHASING_DEVICE;
            p.used |= Has.PHASING_DEVICE;
            p.status &= ~Obj.GRAVITY;
            p.phasingLeft = 0;
            p.items[it(Item.PHASING)] = 0;
            d.phasing(p, false);
            int haveSpent = p.have;
            int usedSpent = p.used;
            int status = p.status;
            p.have |= Has.PHASING_DEVICE;
            p.phasingLeft = 5;
            d.phasing(p, false);
            int haveRunning = p.have;
            p.phasingLeft = 0;
            p.items[it(Item.PHASING)] = 1;
            d.phasing(p, false);
            int haveItems = p.have;
            assertAll(
                () -> assertEquals(0, usedSpent & Has.PHASING_DEVICE, "phasing goes off"),
                () -> assertTrue((status & Obj.GRAVITY) != 0, "gravity works again"),
                () -> assertEquals(0, haveSpent & Has.PHASING_DEVICE, "no phase and no item left: the ability goes"),
                () -> assertTrue((haveRunning & Has.PHASING_DEVICE) != 0, "phase time left: the ability stays"),
                () -> assertTrue((haveItems & Has.PHASING_DEVICE) != 0, "items left: the ability stays"));
        }
    }

    @Nested
    @DisplayName("cloak (C Cloak)")
    class Cloak {
        @Test
        @DisplayName("cloaking without cloakedShield drops emergency shield, deflector and shields")
        void onNoCloakedShield() {
            Player p = setup("cloakedShield: no\n");
            p.items[it(Item.CLOAK)] = 1;
            p.items[it(Item.DEFLECTOR)] = 1;
            p.items[it(Item.EMERGENCY_SHIELD)] = 1;
            p.have |= Has.EMERGENCY_SHIELD | Has.DEFLECTOR | Has.SHIELD | Has.CLOAKING_DEVICE;
            p.used |= Has.EMERGENCY_SHIELD | Has.DEFLECTOR | Has.SHIELD;
            p.updateVisibility = false;
            d.cloak(p, true);
            assertAll(
                () -> assertTrue((p.used & Has.CLOAKING_DEVICE) != 0, "the cloak is on"),
                () -> assertTrue(p.updateVisibility, "visibility is to be updated"),
                () -> assertEquals(0, p.used & Has.EMERGENCY_SHIELD, "the emergency shield goes off"),
                () -> assertEquals(0, p.used & Has.DEFLECTOR, "the deflector goes off"),
                () -> assertEquals(0, p.used & Has.SHIELD, "shields go down"),
                () -> assertEquals(0, p.have & Has.SHIELD, "shields are lost"));
        }

        @Test
        @DisplayName("cloaking turns off only the emergency shield and deflector actually in use")
        void onLeavesUnusedDevices() {
            Player p = setup("cloakedShield: no\n");
            p.items[it(Item.CLOAK)] = 1;
            p.items[it(Item.DEFLECTOR)] = 0;
            p.items[it(Item.EMERGENCY_SHIELD)] = 0;
            p.emergencyShieldLeft = 0;
            p.have |= Has.EMERGENCY_SHIELD | Has.DEFLECTOR;
            p.used &= ~(Has.EMERGENCY_SHIELD | Has.DEFLECTOR);
            d.cloak(p, true);
            assertAll(
                () -> assertTrue((p.have & Has.EMERGENCY_SHIELD) != 0, "an unused emergency shield keeps its ability"),
                () -> assertTrue((p.have & Has.DEFLECTOR) != 0, "an unused deflector keeps its ability"));
        }

        @Test
        @DisplayName("uncloaking without emergency shield or default shields gives no shields")
        void offNoShields() {
            Player p = setup("cloakedShield: no\n");
            w.params.defHave &= ~Has.SHIELD;
            p.items[it(Item.CLOAK)] = 1;
            p.have = (p.have | Has.CLOAKING_DEVICE) & ~(Has.SHIELD | Has.EMERGENCY_SHIELD);
            p.used |= Has.CLOAKING_DEVICE;
            d.cloak(p, false);
            assertAll(
                () -> assertEquals(0, p.have & Has.SHIELD, "no shields come back"),
                () -> assertEquals(0, p.used & Has.EMERGENCY_SHIELD, "no emergency shield"));
        }

        @Test
        @DisplayName("cloaking with cloakedShield keeps the shields; nothing happens without items or when cloaked")
        void onCloakedShieldAndGuards() {
            Player p = setup("cloakedShield: yes\n");
            p.items[it(Item.CLOAK)] = 1;
            p.have |= Has.SHIELD | Has.DEFLECTOR;
            p.used |= Has.SHIELD | Has.DEFLECTOR;
            d.cloak(p, true);
            int used = p.used;
            int have = p.have;
            p.updateVisibility = false;
            d.cloak(p, true);
            boolean again = p.updateVisibility;
            Player q = other("Q", 500, 500);
            q.items[it(Item.CLOAK)] = 0;
            q.updateVisibility = false;
            d.cloak(q, true);
            assertAll(
                () -> assertTrue((used & Has.CLOAKING_DEVICE) != 0, "the cloak is on"),
                () -> assertTrue((used & (Has.SHIELD | Has.DEFLECTOR)) == (Has.SHIELD | Has.DEFLECTOR),
                    "shields and deflector stay up with cloakedShield"),
                () -> assertTrue((have & Has.SHIELD) != 0, "shields are kept with cloakedShield"),
                () -> assertFalse(again, "cloaking an already cloaked ship does nothing"),
                () -> assertEquals(0, q.used & Has.CLOAKING_DEVICE, "without a cloak item the cloak stays off"),
                () -> assertFalse(q.updateVisibility, "without a cloak item visibility is not touched"));
        }

        @Test
        @DisplayName("uncloaking without cloakedShield restores emergency shield, default shields and held shields")
        void offRestores() {
            Player p = setup("cloakedShield: no\n");
            p.items[it(Item.CLOAK)] = 0;
            p.items[it(Item.EMERGENCY_SHIELD)] = 1;
            p.have = (p.have | Has.CLOAKING_DEVICE | Has.EMERGENCY_SHIELD) & ~Has.SHIELD;
            p.used = (p.used | Has.CLOAKING_DEVICE) & ~Has.SHIELD;
            p.lastReceivedKeys = ControlKey.SHIELD.mask();
            p.keysCleared = false;
            p.updateVisibility = false;
            d.cloak(p, false);
            assertAll(
                () -> assertEquals(0, p.used & Has.CLOAKING_DEVICE, "the cloak goes off"),
                () -> assertTrue(p.updateVisibility, "visibility is to be updated"),
                () -> assertEquals(0, p.have & Has.CLOAKING_DEVICE, "the last cloak item gone: the ability goes"),
                () -> assertTrue((p.used & Has.EMERGENCY_SHIELD) != 0, "the emergency shield comes back on"),
                () -> assertEquals(RUN, p.emergencyShieldLeft, "the emergency shield starts a new run"),
                () -> assertTrue((p.have & Has.SHIELD) != 0, "shields are available again"),
                () -> assertTrue((p.used & Has.SHIELD) != 0, "the held shield key raises shields"));
        }

        @Test
        @DisplayName("uncloaking: default shields return, keys only count when held, items keep the ability")
        void offVariants() {
            Player p = setup("cloakedShield: no\n");
            w.params.defHave |= Has.SHIELD;
            p.items[it(Item.CLOAK)] = 2;
            p.have = (p.have | Has.CLOAKING_DEVICE) & ~(Has.SHIELD | Has.EMERGENCY_SHIELD);
            p.used = (p.used | Has.CLOAKING_DEVICE) & ~Has.SHIELD;
            p.lastReceivedKeys = ControlKey.SHIELD.mask();
            p.keysCleared = true;
            d.cloak(p, false);
            int have = p.have;
            int used = p.used;
            p.updateVisibility = false;
            p.keysCleared = false;
            p.lastReceivedKeys = 0;
            d.cloak(p, false);
            boolean vis = p.updateVisibility;
            Player q = setup("cloakedShield: yes\n");
            w.params.defHave |= Has.SHIELD;
            q.items[it(Item.CLOAK)] = 1;
            q.have = (q.have | Has.CLOAKING_DEVICE) & ~Has.SHIELD;
            q.used |= Has.CLOAKING_DEVICE;
            q.lastReceivedKeys = ControlKey.SHIELD.mask();
            q.keysCleared = false;
            d.cloak(q, false);
            assertAll(
                () -> assertTrue((have & Has.CLOAKING_DEVICE) != 0, "cloak items left: the ability stays"),
                () -> assertTrue((have & Has.SHIELD) != 0, "default shields come back"),
                () -> assertEquals(0, used & Has.SHIELD, "a shield key from before Go_home does not count"),
                () -> assertEquals(0, used & Has.EMERGENCY_SHIELD, "no emergency shield without the ability"),
                () -> assertFalse(vis, "uncloaking an uncloaked ship does not touch visibility"),
                () -> assertEquals(0, q.have & Has.SHIELD, "with cloakedShield shields are not restored"),
                () -> assertEquals(0, q.used & Has.SHIELD, "with cloakedShield the shield key is not looked at"));
        }
    }

    @Nested
    @DisplayName("deflector (C Deflector)")
    class Deflector {
        @Test
        @DisplayName("it goes on with an item, unless cloaked without cloakedShield")
        void on() {
            Player p = setup("cloakedShield: no\n");
            p.items[it(Item.DEFLECTOR)] = 1;
            d.deflector(p, true);
            boolean plain = (p.used & Has.DEFLECTOR) != 0;
            p.used = (p.used & ~Has.DEFLECTOR) | Has.CLOAKING_DEVICE;
            d.deflector(p, true);
            boolean cloaked = (p.used & Has.DEFLECTOR) != 0;
            p.used &= ~Has.CLOAKING_DEVICE;
            p.items[it(Item.DEFLECTOR)] = 0;
            d.deflector(p, true);
            boolean none = (p.used & Has.DEFLECTOR) != 0;
            Player q = setup("cloakedShield: yes\n");
            q.items[it(Item.DEFLECTOR)] = 1;
            q.used |= Has.CLOAKING_DEVICE;
            d.deflector(q, true);
            assertAll(
                () -> assertTrue(plain, "an uncloaked ship with a deflector turns it on"),
                () -> assertFalse(cloaked, "a cloaked ship cannot without cloakedShield"),
                () -> assertFalse(none, "no deflector item: it stays off"),
                () -> assertTrue((q.used & Has.DEFLECTOR) != 0, "a cloaked ship can with cloakedShield"));
        }

        @Test
        @DisplayName("it goes off; the ability goes iff no item is left")
        void off() {
            Player p = setup("");
            p.items[it(Item.DEFLECTOR)] = 1;
            p.have |= Has.DEFLECTOR;
            p.used |= Has.DEFLECTOR;
            d.deflector(p, false);
            int keep = p.have;
            int used = p.used;
            p.items[it(Item.DEFLECTOR)] = 0;
            d.deflector(p, false);
            assertAll(
                () -> assertEquals(0, used & Has.DEFLECTOR, "the deflector goes off"),
                () -> assertTrue((keep & Has.DEFLECTOR) != 0, "items left: the ability stays"),
                () -> assertEquals(0, p.have & Has.DEFLECTOR, "no item left: the ability goes"));
        }
    }

    @Nested
    @DisplayName("emergency thrust (C Emergency_thrust)")
    class EmergencyThrust {
        @Test
        @DisplayName("on starts a 4*FPS run with one item when the last ran out and reports 15 afterburners")
        void on() {
            Player p = setup("");
            p.items[it(Item.EMERGENCY_THRUST)] = 2;
            p.items[it(Item.AFTERBURNER)] = 3;
            long from = w.events.seq();
            d.emergencyThrust(p, true);
            List<String> ev = thrust(w, from);
            int left = p.emergencyThrustLeft;
            p.emergencyThrustLeft = 7;
            long again = w.events.seq();
            d.emergencyThrust(p, true);
            assertAll(
                () -> assertEquals(RUN, left, "a new run lasts 4 * FPS frames"),
                () -> assertEquals(RUN, p.emergencyThrustMax, "the run maximum is 4 * FPS"),
                () -> assertTrue((p.used & Has.EMERGENCY_THRUST) != 0, "emergency thrust is in use"),
                () -> assertEquals(1, p.items[it(Item.EMERGENCY_THRUST)], "only the new run used an item"),
                () -> assertEquals(7, p.emergencyThrustLeft, "a running run is kept"),
                () -> assertEquals(List.of(p.id + " 15 " + p.power), ev,
                    "the change of effective afterburners (3 -> 15) is reported"),
                () -> assertEquals(List.of(), thrust(w, again), "no change, no report"));
        }

        @Test
        @DisplayName("off stops it; the ability goes iff run and items are used up")
        void off() {
            Player p = setup("");
            p.have |= Has.EMERGENCY_THRUST;
            p.used |= Has.EMERGENCY_THRUST;
            p.items[it(Item.AFTERBURNER)] = 3;
            p.emergencyThrustLeft = 0;
            long from = w.events.seq();
            d.emergencyThrust(p, false);
            List<String> ev = thrust(w, from);
            int spent = p.have;
            p.have |= Has.EMERGENCY_THRUST;
            p.emergencyThrustLeft = 3;
            d.emergencyThrust(p, false);
            int running = p.have;
            p.emergencyThrustLeft = 0;
            p.items[it(Item.EMERGENCY_THRUST)] = 1;
            d.emergencyThrust(p, false);
            assertAll(
                () -> assertEquals(0, p.used & Has.EMERGENCY_THRUST, "emergency thrust goes off"),
                () -> assertEquals(0, spent & Has.EMERGENCY_THRUST, "nothing left: the ability goes"),
                () -> assertTrue((running & Has.EMERGENCY_THRUST) != 0, "run time left: the ability stays"),
                () -> assertTrue((p.have & Has.EMERGENCY_THRUST) != 0, "items left: the ability stays"),
                () -> assertEquals(List.of(p.id + " 3 " + p.power), ev, "back to 3 afterburners is reported"));
        }
    }

    @Nested
    @DisplayName("emergency shield (C Emergency_shield)")
    class EmergencyShield {
        @Test
        @DisplayName("on needs the ability; a new run uses an item; shields only when not cloaked (or cloakedShield)")
        void on() {
            Player p = setup("cloakedShield: no\n");
            p.items[it(Item.EMERGENCY_SHIELD)] = 2;
            p.have &= ~(Has.EMERGENCY_SHIELD | Has.SHIELD);
            d.emergencyShield(p, true);
            int noAbility = p.used;
            int noAbilityLeft = p.emergencyShieldLeft;
            p.have |= Has.EMERGENCY_SHIELD;
            p.used |= Has.CLOAKING_DEVICE;
            d.emergencyShield(p, true);
            int cloakedUsed = p.used;
            int cloakedHave = p.have;
            int cloakedLeft = p.emergencyShieldLeft;
            p.used &= ~Has.CLOAKING_DEVICE;
            d.emergencyShield(p, true);
            Player q = setup("cloakedShield: yes\n");
            q.items[it(Item.EMERGENCY_SHIELD)] = 1;
            q.have = (q.have | Has.EMERGENCY_SHIELD) & ~Has.SHIELD;
            q.used |= Has.CLOAKING_DEVICE;
            d.emergencyShield(q, true);
            assertAll(
                () -> assertEquals(0, noAbility & Has.EMERGENCY_SHIELD, "without the ability nothing happens"),
                () -> assertEquals(0, noAbilityLeft, "without the ability no run starts"),
                () -> assertEquals(RUN, cloakedLeft, "a cloaked ship still starts a 4 * FPS run"),
                () -> assertEquals(0, cloakedUsed & Has.EMERGENCY_SHIELD, "but a cloaked ship gets no emergency shield"),
                () -> assertEquals(0, cloakedHave & Has.SHIELD, "and no shields"),
                () -> assertEquals(1, p.items[it(Item.EMERGENCY_SHIELD)], "the running run used no second item"),
                () -> assertTrue((p.used & Has.EMERGENCY_SHIELD) != 0, "uncloaked: the emergency shield is on"),
                () -> assertTrue((p.have & Has.SHIELD) != 0, "uncloaked: shields are available"),
                () -> assertEquals(RUN, p.emergencyShieldMax, "the run maximum is 4 * FPS"),
                () -> assertTrue((q.used & Has.EMERGENCY_SHIELD) != 0, "cloakedShield: on while cloaked"),
                () -> assertTrue((q.have & Has.SHIELD) != 0, "cloakedShield: shields while cloaked"));
        }

        @Test
        @DisplayName("off drops shields without default shields and the ability when run and items are spent")
        void off() {
            Player p = setup("");
            w.params.defHave &= ~Has.SHIELD;
            p.have |= Has.EMERGENCY_SHIELD | Has.SHIELD;
            p.used |= Has.EMERGENCY_SHIELD | Has.SHIELD;
            d.emergencyShield(p, false);
            int have = p.have;
            int used = p.used;
            w.params.defHave |= Has.SHIELD;
            p.have |= Has.EMERGENCY_SHIELD | Has.SHIELD;
            p.used |= Has.EMERGENCY_SHIELD | Has.SHIELD;
            p.emergencyShieldLeft = 4;
            d.emergencyShield(p, false);
            int haveRunning = p.have;
            p.emergencyShieldLeft = 0;
            p.items[it(Item.EMERGENCY_SHIELD)] = 1;
            d.emergencyShield(p, false);
            assertAll(
                () -> assertEquals(0, used & Has.EMERGENCY_SHIELD, "the emergency shield goes off"),
                () -> assertEquals(0, have & Has.EMERGENCY_SHIELD, "nothing left: the ability goes"),
                () -> assertEquals(0, have & Has.SHIELD, "without default shields shields are lost"),
                () -> assertEquals(0, used & Has.SHIELD, "without default shields shields go down"),
                () -> assertTrue((haveRunning & Has.EMERGENCY_SHIELD) != 0, "run time left: the ability stays"),
                () -> assertTrue((haveRunning & Has.SHIELD) != 0, "default shields are kept"),
                () -> assertTrue((p.used & Has.SHIELD) != 0, "default shields stay up"),
                () -> assertTrue((p.have & Has.EMERGENCY_SHIELD) != 0, "items left: the ability stays"));
        }
    }

    @Nested
    @DisplayName("autopilot (C Autopilot)")
    class Autopilot {
        @Test
        @DisplayName("on saves the settings and uses power 30, turnspeed 34, resistance 0.2; off restores them")
        void onOff() {
            Player p = setup("");
            p.power = 45;
            p.turnSpeed = 20;
            p.turnResistance = 0.5f;
            p.turnAcc = 3;
            p.status |= Obj.THRUSTING;
            long from = w.events.seq();
            d.autopilot(p, true);
            float power = p.power;
            float ts = p.turnSpeed;
            float tr = p.turnResistance;
            int used = p.used;
            int status = p.status;
            List<String> evOn = thrust(w, from);
            p.status |= Obj.THRUSTING;
            p.power = 33;
            p.turnAcc = 7;
            long off = w.events.seq();
            d.autopilot(p, false);
            assertAll(
                () -> assertEquals(30f, power, "power = (MIN_PLAYER_POWER + MAX_PLAYER_POWER) / 2"),
                () -> assertEquals(34f, ts, "turnspeed = (MIN_PLAYER_TURNSPEED + MAX_PLAYER_TURNSPEED) / 2"),
                () -> assertEquals(0.2f, tr, "turnresistance = 0.2"),
                () -> assertTrue((used & Has.AUTOPILOT) != 0, "the autopilot is on"),
                () -> assertEquals(0, status & Obj.THRUSTING, "turning it on stops thrusting"),
                () -> assertEquals(List.of(p.id + " 0 30.0"), evOn, "the power change is reported"),
                () -> assertEquals(45f, p.power, "off restores the power"),
                () -> assertEquals(20f, p.turnSpeed, "off restores the turnspeed"),
                () -> assertEquals(0.5f, p.turnResistance, "off restores the turnresistance"),
                () -> assertEquals(0f, p.turnAcc, "off stops turning"),
                () -> assertEquals(0, p.used & Has.AUTOPILOT, "the autopilot is off"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "turning it off stops thrusting"),
                () -> assertEquals(List.of(p.id + " 0 45.0"), thrust(w, off), "the restored power is reported"));
        }

        @Test
        @DisplayName("no power change, no report")
        void samePower() {
            Player p = setup("");
            p.power = 30;
            long from = w.events.seq();
            d.autopilot(p, true);
            d.autopilot(p, false);
            assertEquals(List.of(), thrust(w, from), "power stays 30 both ways: nothing is reported");
        }
    }

    // ================================================================ do_Autopilot

    @Nested
    @DisplayName("doAutopilot (C do_Autopilot, C floats)")
    class DoAutopilot {
        /** A ship on autopilot settings (power 30, turnspeed 34, resistance 0.2) at frame 10, last wall touch 0. */
        Player auto(String extra) {
            Player p = setup(extra);
            w.frames = 10;
            p.lastWallTouch = 0;
            p.power = 30;
            p.turnSpeed = 34;
            p.turnResistance = 0.2f;
            p.turnVel = 0;
            p.turnAcc = 0;
            return p;
        }

        /** C: power = (LENGTH(gx, gy) + vel) * mass with gravity 0. */
        float target(Player p) {
            float vel = (float) StrictMath.hypot(p.vx, p.vy);
            return (float) (0.0 + vel) * p.mass;
        }

        @Test
        @DisplayName("nothing happens within a frame of a wall touch")
        void wallTouch() {
            Player p = auto("");
            p.vx = 10;
            p.lastWallTouch = 9;
            d.doAutopilot(p);
            float ts = p.turnSpeed;
            float pw = p.power;
            float acc = p.turnAcc;
            p.lastWallTouch = 8;
            d.doAutopilot(p);
            assertAll(
                () -> assertEquals(34f, ts, "a touch last frame (9 + 1 >= 10): the turnspeed is not changed"),
                () -> assertEquals(30f, pw, "a touch last frame: the power is not changed"),
                () -> assertEquals(0f, acc, "a touch last frame: no turning"),
                () -> assertEquals(34.25f, p.turnSpeed, "a touch two frames ago (8 + 1 < 10): it steers"));
        }

        @Test
        @DisplayName("below 0.5 px/frame it stops dead at its previous position and powers down by 15/FPS")
        void deadStop() {
            Player p = auto("");
            p.vx = 0.3f;
            p.vy = 0.39f;
            p.prevX = 340;
            p.prevY = 345;
            p.dir = 10;
            p.turnVel = 0;
            p.status |= Obj.THRUSTING;
            long from = w.events.seq();
            d.doAutopilot(p);
            assertAll(
                () -> assertEquals(0f, p.vx, "x velocity is zeroed"),
                () -> assertEquals(0f, p.vy, "y velocity is zeroed"),
                () -> assertEquals(340, p.px, "the ship goes back to its previous x (Player_position_restore)"),
                () -> assertEquals(345, p.py, "the ship goes back to its previous y"),
                () -> assertEquals(34f - 0.25f, p.turnSpeed, "the wanted turnspeed 0 is approached by delta 15/60"),
                () -> assertEquals(0f, p.turnAcc, "turnspeed > 2.5 * wanted: no turning"),
                () -> assertEquals(0f, p.turnVel, "turnspeed > 2.5 * wanted: the turn stops"),
                () -> assertEquals(30f - 0.25f, p.power, "the wanted power 0 is approached by delta"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "more power than needed: no thrust"),
                () -> assertEquals(List.of(p.id + " 0 29.75"), thrust(w, from), "the power change is reported"));
        }

        @Test
        @DisplayName("moving: it turns away from its motion (autopilot items scale delta) and does not thrust yet")
        void turnsAway() {
            Player p = auto("");
            p.items[it(Item.AUTOPILOT)] = 2;
            p.vx = 10;
            p.vy = 0;
            p.dir = 0;
            p.status |= Obj.THRUSTING;
            d.doAutopilot(p);
            float ts = p.turnSpeed;
            float acc = p.turnAcc;
            float pw = p.power;
            int status = p.status;
            Player q = auto("");
            q.vx = 0;
            q.vy = 10;
            q.dir = 0;
            d.doAutopilot(q);
            assertAll(
                () -> assertEquals(34f + 2 * 0.25f, ts, "delta = 15/FPS * 2 autopilots toward 64 / 0.2 = 320"),
                () -> assertEquals(34.5f, acc, "motion is 64 units round: turn the positive way"),
                () -> assertEquals(30.5f, pw, "the power rises by delta toward 10 * mass"),
                () -> assertEquals(0, status & Obj.THRUSTING, "pointing 64 units off (> RES/32): no thrust"),
                () -> assertEquals(-34.25f, q.turnAcc, "motion 96 units round: turn the negative way by 32"),
                () -> assertEquals(34.25f, q.turnSpeed, "turnspeed rises toward 32 / 0.2 = 160"));
        }

        @Test
        @DisplayName("pointing against the motion it thrusts when not overpowered; a turn velocity flips the turn")
        void thrusts() {
            Player p = auto("");
            p.vx = 10;
            p.dir = 64;
            p.turnVel = 5;
            p.turnSpeed = 4;
            d.doAutopilot(p);
            Player q = auto("");
            q.vx = 0.6f;
            q.dir = 64;
            q.status |= Obj.THRUSTING;
            d.doAutopilot(q);
            float qTarget = target(q);
            assertAll(
                () -> assertEquals(4.25f, p.turnSpeed, "wanted turnspeed |0 / 0.2 - 5| = 5: rise by delta"),
                () -> assertEquals(-4.25f, p.turnAcc, "a negative wanted turnspeed reverses the turn"),
                () -> assertEquals(30.25f, p.power, "the power rises toward 10 * mass"),
                () -> assertTrue((p.status & Obj.THRUSTING) != 0, "pointing right and not overpowered: thrust"),
                () -> assertTrue(qTarget < 29.75f, "0.6 px/frame needs less than the current power"),
                () -> assertEquals(29.75f, q.power, "the power drops by delta"),
                () -> assertEquals(0, q.status & Obj.THRUSTING, "overpowered: no thrust"));
        }

        @Test
        @DisplayName("exactly 0.5 px/frame is not a dead stop; exactly RES/32 off it still thrusts")
        void boundaries() {
            Player p = auto("");
            p.vx = 0.5f;
            p.dir = 64;
            d.doAutopilot(p);
            Player q = auto("");
            q.vx = 10;
            q.dir = 60;
            d.doAutopilot(q);
            Player r = auto("");
            r.vx = 0;
            r.vy = 10;
            r.dir = 0;
            r.turnResistance = 1.0f;
            r.turnSpeed = 31.9f;
            d.doAutopilot(r);
            assertAll(
                () -> assertEquals(0.5f, p.vx, "0.5 px/frame keeps its velocity"),
                () -> assertTrue((q.status & Obj.THRUSTING) != 0, "4 units off (= RES/32, not more): thrust"),
                () -> assertEquals(-32f, r.turnAcc, "vad 96 is 128 - 96 = 32 the other way: wanted 32 / 1.0"));
        }

        @Test
        @DisplayName("turnspeed and power stop at the wanted values, and at their maxima")
        void limits() {
            Player p = auto("");
            p.vx = 1.2f;
            p.dir = 57;
            p.turnVel = 0.9f;
            p.power = (float) (target(p) - 0.1);
            d.doAutopilot(p);
            float upTs = p.turnSpeed;
            float upPw = p.power;
            float wantUp = target(p);
            Player q = auto("");
            q.vx = 1.2f;
            q.dir = 57;
            q.turnVel = 1.1f;
            q.power = (float) (target(q) + 0.1);
            d.doAutopilot(q);
            float wantDown = target(q);
            Player m = auto("");
            m.vx = 10;
            m.dir = 0;
            m.turnSpeed = 64;
            m.power = 55;
            long from = w.events.seq();
            d.doAutopilot(m);
            assertAll(
                () -> assertEquals(7 / 0.2f - 0.9f, upTs, "rising: stops at the wanted 7 / 0.2 - 0.9"),
                () -> assertEquals(wantUp, upPw, "rising: the power stops at the wanted value"),
                () -> assertEquals(7 / 0.2f - 1.1f, q.turnSpeed, "falling: stops at the wanted 7 / 0.2 - 1.1"),
                () -> assertEquals(wantDown, q.power, "falling: the power stops at the wanted value"),
                () -> assertTrue((q.status & Obj.THRUSTING) != 0, "exact settings: it thrusts although 7 > RES/32 off"),
                () -> assertEquals(64f, m.turnSpeed, "the turnspeed is limited to MAX_PLAYER_TURNSPEED"),
                () -> assertEquals(55f, m.power, "the power is limited to MAX_PLAYER_POWER"),
                () -> assertEquals(List.of(), thrust(w, from), "power stays at its maximum: nothing is reported"));
        }

        @Test
        @DisplayName("emergency thrust (15 afterburners, delta >= 150/FPS) and afterburners lower the needed power")
        void afterburners() {
            Player p = auto("");
            p.vx = 2;
            p.dir = 64;
            p.used |= Has.EMERGENCY_THRUST;
            p.power = 55;
            d.doAutopilot(p);
            Player e = auto("");
            e.vx = 2;
            e.dir = 64;
            e.used |= Has.EMERGENCY_THRUST;
            e.power = 13;
            d.doAutopilot(e);
            float etPower = (float) (target(e) / (1.0 + 15 * ((4.2 - 1.0) / 16.0)));
            Player q = auto("");
            q.vx = 2;
            q.dir = 64;
            q.items[it(Item.AUTOPILOT)] = 20;
            q.used |= Has.EMERGENCY_THRUST;
            q.power = 55;
            d.doAutopilot(q);
            Player a = auto("");
            a.vx = 2;
            a.dir = 64;
            a.items[it(Item.AFTERBURNER)] = 3;
            a.power = 31.3f;
            d.doAutopilot(a);
            float abPower = (float) (target(a) / (1.0 + 3 * ((4.2 - 1.0) / 16.0)));
            assertAll(
                () -> assertEquals(55f - 2.5f, p.power, "emergency thrust: delta is at least 150/60 = 2.5"),
                () -> assertEquals(34f - 2.5f, p.turnSpeed, "the turnspeed moves by the same delta"),
                () -> assertEquals(12.5f, etPower, "2 px/frame * mass 25 / (1 + 15 * 3.2/16) = 12.5"),
                () -> assertEquals(etPower, e.power, "emergency thrust: the needed power is divided by 4"),
                () -> assertEquals(55f - 5f, q.power, "20 autopilots give delta 20 * 0.25 = 5 > 2.5"),
                () -> assertEquals(31.25f, abPower, "50 / (1 + 3 * 3.2/16) = 31.25"),
                () -> assertEquals(abPower, a.power, "3 afterburners: the needed power is divided by 1.6"));
        }

        @Test
        @DisplayName("at rest under gravity it points against gravity; the needed power is (float)(|g| + vel) * mass")
        void gravity() {
            Player p = auto("gravity: -0.4\ngravityAngle: 30\n");
            p.vx = 0;
            p.vy = 0;
            p.dir = 0;
            p.power = 5;
            p.turnSpeed = 49.9f;
            float gx = w.gravityX(p.bx, p.by);
            float gy = w.gravityY(p.bx, p.by);
            int vad = Trig.mod((int) XMath.findDir(-gx, -gy));
            d.doAutopilot(p);
            Player q = auto("gravity: -0.4\ngravityAngle: 30\n");
            q.vx = 0.501f;
            q.vy = 0;
            q.dir = 64;
            q.power = 22.6f;
            float mass = q.mass;
            d.doAutopilot(q);
            // C: acc = LENGTH(gx, gy) + vel in double, stored in a DFLOAT; power = acc * mass (update.c:384-385).
            float cPower = (float) (StrictMath.hypot(gx, gy) + 0.501f) * mass;
            float floatSum = ((float) StrictMath.hypot(gx, gy) + 0.501f) * mass;
            assertAll(
                () -> assertEquals(-0.34641016f, gx, "gravity 0.4 at 30 degrees: gx"),
                () -> assertEquals(-0.2f, gy, "gravity 0.4 at 30 degrees: gy"),
                () -> assertEquals(10, vad, "-gravity points (int)(30 * 128 / 360) = 10 units round"),
                () -> assertEquals(50f, p.turnAcc, "at rest it turns toward -gravity: wanted 10 / 0.2 = 50"),
                () -> assertEquals(5.25f, p.power, "the power rises toward |g| * mass = 10"),
                () -> assertEquals(25f, mass, "the ship weighs 25"),
                () -> assertEquals(22.525f, cPower, "(float)(0.39999999610 + 0.501) * 25 = 22.525"),
                () -> assertTrue(floatSum != cPower, "rounding |g| to float before the sum would give another value"),
                () -> assertEquals(cPower, q.power, "the power stops at C's needed power"));
        }
    }

    // ================================================================ player loop

    @Nested
    @DisplayName("timers (update.c:825-857)")
    class Timers {
        @Test
        @DisplayName("a phase runs down; at its end the next item restarts it, or phasing ends")
        void phasing() {
            Player p = setup("");
            p.have |= Has.PHASING_DEVICE;
            p.used |= Has.PHASING_DEVICE;
            p.phasingLeft = 3;
            d.timers(p);
            int left = p.phasingLeft;
            p.phasingLeft = 1;
            p.items[it(Item.PHASING)] = 1;
            d.timers(p);
            int restarted = p.phasingLeft;
            int items = p.items[it(Item.PHASING)];
            p.phasingLeft = 1;
            d.timers(p);
            Player q = other("Q", 500, 500);
            q.phasingLeft = 3;
            d.timers(q);
            assertAll(
                () -> assertEquals(2, left, "a phase loses one frame"),
                () -> assertEquals(RUN, restarted, "the next item starts a new 4 * FPS phase"),
                () -> assertEquals(0, items, "the new phase used the item"),
                () -> assertEquals(0, p.used & Has.PHASING_DEVICE, "no item left: phasing ends"),
                () -> assertTrue((p.status & Obj.GRAVITY) != 0, "phasing ended: gravity again"),
                () -> assertEquals(3, q.phasingLeft, "a ship not phasing keeps its counter"));
        }

        @Test
        @DisplayName("emergency thrust runs down only while thrusting with fuel")
        void emergencyThrust() {
            Player p = setup("");
            p.have |= Has.EMERGENCY_THRUST;
            p.used |= Has.EMERGENCY_THRUST;
            p.emergencyThrustLeft = 5;
            d.timers(p);
            int idle = p.emergencyThrustLeft;
            p.status |= Obj.THRUSTING;
            double fuel = p.fuel;
            p.fuel = 0;
            d.timers(p);
            int empty = p.emergencyThrustLeft;
            p.fuel = fuel;
            d.timers(p);
            int thrusting = p.emergencyThrustLeft;
            p.emergencyThrustLeft = 1;
            p.items[it(Item.EMERGENCY_THRUST)] = 1;
            d.timers(p);
            int restarted = p.emergencyThrustLeft;
            p.emergencyThrustLeft = 1;
            d.timers(p);
            Player q = other("Q", 500, 500);
            q.status |= Obj.THRUSTING;
            q.emergencyThrustLeft = 5;
            d.timers(q);
            assertAll(
                () -> assertEquals(5, q.emergencyThrustLeft, "a thrusting ship without emergency thrust keeps its counter"),
                () -> assertEquals(5, idle, "not thrusting: the run does not count down"),
                () -> assertEquals(5, empty, "no fuel: the run does not count down"),
                () -> assertEquals(4, thrusting, "thrusting with fuel: one frame less"),
                () -> assertEquals(RUN, restarted, "at the end the next item restarts it"),
                () -> assertEquals(0, p.used & Has.EMERGENCY_THRUST, "no item left: it goes off"));
        }

        @Test
        @DisplayName("emergency shield runs down only while shielded with fuel")
        void emergencyShield() {
            Player p = setup("");
            p.have |= Has.EMERGENCY_SHIELD | Has.SHIELD;
            p.used = (p.used | Has.EMERGENCY_SHIELD) & ~Has.SHIELD;
            p.emergencyShieldLeft = 5;
            d.timers(p);
            int unshielded = p.emergencyShieldLeft;
            p.used |= Has.SHIELD;
            double fuel = p.fuel;
            p.fuel = 0;
            d.timers(p);
            int empty = p.emergencyShieldLeft;
            p.fuel = fuel;
            d.timers(p);
            int shielded = p.emergencyShieldLeft;
            p.emergencyShieldLeft = 1;
            p.items[it(Item.EMERGENCY_SHIELD)] = 1;
            d.timers(p);
            int restarted = p.emergencyShieldLeft;
            p.emergencyShieldLeft = 1;
            d.timers(p);
            assertAll(
                () -> assertEquals(5, unshielded, "shields down: the run does not count down"),
                () -> assertEquals(5, empty, "no fuel: the run does not count down"),
                () -> assertEquals(4, shielded, "shielded with fuel: one frame less"),
                () -> assertEquals(RUN, restarted, "at the end the next item restarts it"),
                () -> assertEquals(0, p.used & Has.EMERGENCY_SHIELD, "no item left: it goes off"));
        }
    }

    @Nested
    @DisplayName("use (update.c:859-879)")
    class Use {
        @Test
        @DisplayName("a laser in use fires unless the lasers are gone or the ship is phased")
        void laser() {
            Player p = setup("");
            p.used |= Has.LASER;
            p.items[it(Item.LASER)] = 0;
            d.use(p);
            int noLasers = p.used;
            p.used |= Has.LASER | Has.PHASING_DEVICE;
            p.items[it(Item.LASER)] = 1;
            d.use(p);
            int phased = p.used;
            int phasedPulses = p.numPulses;
            p.used &= ~(Has.LASER | Has.PHASING_DEVICE);
            d.use(p);
            int idlePulses = p.numPulses;
            p.used |= Has.LASER;
            d.use(p);
            assertAll(
                () -> assertEquals(0, idlePulses, "lasers not in use: no pulse"),
                () -> assertEquals(0, noLasers & Has.LASER, "no laser item: the laser goes off"),
                () -> assertEquals(0, phased & Has.LASER, "phased: the laser goes off"),
                () -> assertEquals(0, phasedPulses, "phased: no pulse"),
                () -> assertTrue((p.used & Has.LASER) != 0, "otherwise the laser stays on"),
                () -> assertEquals(1, p.numPulses, "and fires a pulse"));
        }

        @Test
        @DisplayName("a deflector in use works; the autopilot flies when on, or hover-paused and not blinded")
        void deflectorAndAutopilot() {
            Player p = setup("");
            w.frames = 10;
            p.items[it(Item.DEFLECTOR)] = 1;
            p.used |= Has.DEFLECTOR;
            double fuel = p.fuel;
            d.use(p);
            double used = fuel - p.fuel;
            Player a = other("A", 500, 500);
            a.vx = 10;
            a.turnSpeed = 34;
            a.used |= Has.AUTOPILOT;
            d.use(a);
            float onTs = a.turnSpeed;
            a.used &= ~Has.AUTOPILOT;
            a.status |= Obj.HOVERPAUSE;
            a.turnSpeed = 34;
            d.use(a);
            float hoverTs = a.turnSpeed;
            a.damaged = 3;
            a.turnSpeed = 34;
            d.use(a);
            float blindTs = a.turnSpeed;
            a.status &= ~Obj.HOVERPAUSE;
            a.damaged = 0;
            d.use(a);
            assertAll(
                () -> assertEquals(38.0, used, "the deflector costs (long)(0.15 * 256) = 38 fuel"),
                () -> assertTrue(onTs != 34f, "autopilot on: it steers"),
                () -> assertTrue(hoverTs != 34f, "hover-paused: it steers"),
                () -> assertEquals(34f, blindTs, "hover-paused but blinded: it does not steer"),
                () -> assertEquals(34f, a.turnSpeed, "neither on nor hover-paused: it does not steer"));
        }
    }

    @Test
    @DisplayName("drain: phasing costs (long)(0.40*256) = 102 and a cloak (long)(0.07*256) = 17 fuel per frame")
    void drain() {
        Player p = setup("");
        double f0 = p.fuel;
        d.drain(p);
        double none = f0 - p.fuel;
        p.used |= Has.PHASING_DEVICE;
        d.drain(p);
        double phasing = f0 - p.fuel;
        p.used = (p.used & ~Has.PHASING_DEVICE) | Has.CLOAKING_DEVICE;
        d.drain(p);
        double cloak = f0 - phasing - p.fuel;
        p.used |= Has.PHASING_DEVICE;
        double f1 = p.fuel;
        d.drain(p);
        double both = f1 - p.fuel;
        assertAll(
            () -> assertEquals(0.0, none, "no device: no cost"),
            () -> assertEquals(102.0, phasing, "phasing: 102"),
            () -> assertEquals(17.0, cloak, "cloak: 17"),
            () -> assertEquals(119.0, both, "both: 119"));
    }

    // ================================================================ deflector

    @Nested
    @DisplayName("doDeflector (C Do_deflector)")
    class DoDeflector {
        /** C: dv = ((range-dist)/range)^2 * maxforce * (RES/4 - |idir - RES/2|) / (RES/4) / |mass| (item.c:599-607). */
        float dv(int items, float dx, float dy, float vx, float vy, float mass) {
            float range = (float) ((items * 0.5 + 1) * Const.BLOCK_SZ);
            float maxforce = (float) (items * 0.2);
            long dist = (long) (StrictMath.hypot((long) dx, (long) dy) - Const.SHIP_SZ);
            int dir = (int) XMath.findDir((long) dx, (long) dy);
            int idir = Trig.mod((int) (dir - (float) XMath.findDir(vx, vy)));
            float a = (float) (range - dist) / range;
            float force = (float) (a * a * maxforce * ((128 * 0.25) - Math.abs(idir - 128 * 0.5)) / (128 * 0.25));
            return force / Math.abs(mass);
        }

        @Test
        @DisplayName("approaching objects within (n*0.5+1) blocks of the hull are pushed away; it costs 38 fuel")
        void pushes() {
            Player p = setup("");
            p.items[it(Item.DEFLECTOR)] = 1;
            p.used |= Has.DEFLECTOR;
            int headOn = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 390, 350, -2, 0, 10, 100);
            int oblique = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 380, 380, -1, -2, 10, 100);
            int away = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 350, 390, 0, 2, 10, 100);
            int inside = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 360, 350, -2, 0, 10, 100);
            int far = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 350, 420, 0, -2, 10, 100);
            int dead = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 310, 350, 2, 0, 10, 0);
            int hull = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 334, 350, 2, 0, 10, 100);
            double fuel = p.fuel;
            float hv = dv(1, 40, 0, -2, 0, 10);
            float ov = dv(1, 30, 30, -1, -2, 10);
            d.doDeflector(p);
            var o = w.objects;
            int odir = (int) XMath.findDir(30, 30);
            assertAll(
                () -> assertEquals(38.0, fuel - p.fuel, "the deflector costs (long)(0.15 * 256) = 38 fuel"),
                () -> assertTrue(hv > 0, "a head-on object 24 px from the hull gets a push"),
                () -> assertEquals(-2 + hv, o.vx[headOn], "head-on: pushed straight out (+x)"),
                () -> assertEquals(0f, o.vy[headOn], "head-on: no sideways push"),
                () -> assertEquals(-1 + Trig.tcos(odir) * ov, o.vx[oblique], "oblique: pushed along the line out, x"),
                () -> assertEquals(-2 + Trig.tsin(odir) * ov, o.vy[oblique], "oblique: pushed along the line out, y"),
                () -> assertEquals(2f, o.vy[away], "an object moving away is not pushed"),
                () -> assertEquals(-2f, o.vx[inside], "an object inside the hull (dist <= 0) is not pushed"),
                () -> assertEquals(-2f, o.vy[far], "an object 54 px from the hull (>= 52.5) is not pushed"),
                () -> assertEquals(2f, o.vx[dead], "a dead object is not pushed"),
                () -> assertEquals(2f, o.vx[hull], "an object exactly at the hull (dist 0) is not pushed"));
        }

        @Test
        @DisplayName("own immune or fusing objects, selfImmunity and balls at rest in their treasure are not pushed")
        void exemptions() {
            Player p = setup("");
            p.items[it(Item.DEFLECTOR)] = 2;
            int immune = SimFixture.obj(w, Obj.SHOT, p.id, 390, 350, -2, 0, 10, 100);
            w.objects.status[immune] |= Obj.OWNERIMMUNE;
            int fusing = SimFixture.obj(w, Obj.SHOT, p.id, 310, 350, 2, 0, 10, 100);
            w.objects.fuseLife[fusing] = 50;
            int own = SimFixture.obj(w, Obj.SHOT, p.id, 350, 390, 0, -2, 10, 100);
            int ballRest = SimFixture.obj(w, Obj.BALL, Obj.NO_ID, 350, 310, 0, 2, 10, 100);
            int ballFree = SimFixture.obj(w, Obj.BALL, Obj.NO_ID, 380, 380, -2, -2, 10, 100);
            w.objects.status[ballFree] |= Obj.GRAVITY;
            d.doDeflector(p);
            var o = w.objects;
            float ownVy = o.vy[own];
            int edge = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 350 - 86, 350, 2, 0, 10, 100);
            d.doDeflector(p);
            float edgeVx = w.objects.vx[edge];
            Player q = setup("selfImmunity: yes\n");
            q.items[it(Item.DEFLECTOR)] = 2;
            int selfImmune = SimFixture.obj(w, Obj.SHOT, q.id, 390, 350, -2, 0, 10, 100);
            d.doDeflector(q);
            float selfVx = w.objects.vx[selfImmune];
            assertAll(
                () -> assertEquals(-2f, o.vx[immune], "an own OWNERIMMUNE object is not pushed"),
                () -> assertEquals(2f, o.vx[fusing], "an own object still fusing (fuseLife < life) is not pushed"),
                () -> assertTrue(ownVy > -2f, "an own armed object is pushed"),
                () -> assertEquals(2f, o.vy[ballRest], "a ball without GRAVITY (in its treasure) is not pushed"),
                () -> assertTrue(o.vx[ballFree] > -2f, "a free ball is pushed"),
                () -> assertEquals(-2f, selfVx, "with selfImmunity own objects are not pushed"),
                () -> assertEquals(2f, edgeVx, "2 deflectors: an object 70 px from the hull (= range) is not pushed"));
        }

        @Test
        @DisplayName("team-immune objects are not pushed")
        void teamImmune() {
            s = SimFixture.sim(FRAME + "teamPlay: yes\nteamImmunity: yes\nreserveRobotTeam: no\n", 30, 30, "0011");
            w = s.world;
            d = s.devices;
            Player p = SimFixture.ready(s, "P", 0, 350, 350);
            Player mate = SimFixture.ready(s, "M", 0, 600, 600);
            Player foe = SimFixture.ready(s, "F", 1, 700, 700);
            p.items[it(Item.DEFLECTOR)] = 1;
            int fromMate = SimFixture.obj(w, Obj.SHOT, mate.id, 390, 350, -2, 0, 10, 100);
            int fromFoe = SimFixture.obj(w, Obj.SHOT, foe.id, 310, 350, 2, 0, 10, 100);
            d.doDeflector(p);
            assertAll(
                () -> assertEquals(-2f, w.objects.vx[fromMate], "a team mate's shot is team immune: not pushed"),
                () -> assertTrue(w.objects.vx[fromFoe] < 2f, "an enemy's shot is pushed back"));
        }

        @Test
        @DisplayName("below 0.15 fuel units (38.4) the deflector goes off and pushes nothing")
        void noFuel() {
            Player p = setup("");
            p.items[it(Item.DEFLECTOR)] = 1;
            p.used |= Has.DEFLECTOR;
            int h = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, 390, 350, -2, 0, 10, 100);
            p.fuel = 37;
            d.doDeflector(p);
            int usedLow = p.used;
            float vxLow = w.objects.vx[h];
            Player q = other("Q", 600, 600);
            q.items[it(Item.DEFLECTOR)] = 0;
            q.have |= Has.DEFLECTOR;
            q.used &= ~Has.DEFLECTOR;
            q.fuel = 10;
            d.doDeflector(q);
            int qHave = q.have;
            p.fuel = 38;
            p.used |= Has.DEFLECTOR;
            d.doDeflector(p);
            int used38 = p.used;
            double fuel38 = p.fuel;
            float vx38 = w.objects.vx[h];
            p.fuel = 39;
            p.used |= Has.DEFLECTOR;
            d.doDeflector(p);
            assertAll(
                () -> assertEquals(0, usedLow & Has.DEFLECTOR, "37 < 38.4: the deflector goes off"),
                () -> assertEquals(-2f, vxLow, "37 < 38.4: nothing is pushed"),
                () -> assertTrue((qHave & Has.DEFLECTOR) != 0, "a deflector not in use is not switched off"),
                () -> assertEquals(0, used38 & Has.DEFLECTOR, "38 < 38.4 (C compares the uncast ED_DEFLECTOR): off"),
                () -> assertEquals(38.0, fuel38, "and it costs nothing"),
                () -> assertEquals(-2f, vx38, "nor pushes"),
                () -> assertTrue((p.used & Has.DEFLECTOR) != 0, "39: the deflector stays on"),
                () -> assertEquals(1.0, p.fuel, "it pays (long)ED_DEFLECTOR = 38"),
                () -> assertTrue(w.objects.vx[h] > -2f, "39: the object is pushed"));
        }
    }

    // ================================================================ tractor beam

    @Nested
    @DisplayName("tractor beam (C Tractor_beam, General_tractor_beam)")
    class Tractor {
        @Test
        @DisplayName("range 200 + 50n, force -40 - 20n, percent 1 - dist/(2 maxdist), cost (long)(-384 * percent)")
        void formulas() {
            assertAll(
                () -> assertEquals(250f, Devices.tractorMaxRange(1), "one beam reaches 250 px"),
                () -> assertEquals(300f, Devices.tractorMaxRange(2), "two beams reach 300 px"),
                () -> assertEquals(-60f, Devices.tractorMaxForce(1), "one beam pulls with 60"),
                () -> assertEquals(-80f, Devices.tractorMaxForce(2), "two beams pull with 80"),
                () -> assertEquals(0.8f, Devices.tractorPercent(100, 250), "100 of 250 px: 80%"),
                () -> assertEquals(-307L, Devices.tractorCost(0.8f), "(long)(-384 * 0.8f) = -307"),
                () -> assertEquals(-319L, Devices.tractorCost(Devices.tractorPercent(100, 300)),
                    "(long)(-384 * (float)(5/6)) = (long)(-319.99999) = -319"));
        }

        Player v;

        Player beam() {
            Player p = setup("");
            v = other("V", 450, 350);
            p.items[it(Item.TRACTOR_BEAM)] = 1;
            p.have |= Has.TRACTOR_BEAM;
            p.used |= Has.TRACTOR_BEAM;
            p.lockTagged = Player.LOCK_PLAYER | Player.LOCK_VISIBLE;
            p.lockPlId = v.id;
            p.lockDistance = 100;
            return p;
        }

        @Test
        @DisplayName("a tractor beam pulls both ships together, costs fuel and records both shoves")
        void pulls() {
            Player p = beam();
            double fuel = p.fuel;
            float force = 0.8f * -60f;
            d.tractorBeam(p);
            assertAll(
                () -> assertEquals(307.0, fuel - p.fuel, "the beam costs 307 fuel at 100 of 250 px"),
                () -> assertEquals(Trig.tcos(64) * (force / p.mass), p.vx, "the puller moves toward the victim (+x)"),
                () -> assertTrue(p.vx > 0, "the puller is pulled toward the victim"),
                () -> assertEquals(-Trig.tcos(64) * (force / v.mass), v.vx, "the victim moves toward the puller (-x)"),
                () -> assertTrue(v.vx < 0, "the victim is pulled toward the puller"),
                () -> assertEquals(Trig.tsin(64) * (force / p.mass), p.vy, "the pull is along the line (tsin(64) ~ 0)"),
                () -> assertEquals(v.id, p.shovePusher[0], "the puller records the victim as a shover"),
                () -> assertEquals(p.id, v.shovePusher[0], "the victim records the puller as a shover"),
                () -> assertTrue((p.used & Has.TRACTOR_BEAM) != 0, "the beam stays on"));
        }

        @Test
        @DisplayName("a pressor beam pushes them apart")
        void pressor() {
            Player p = beam();
            p.tractorIsPressor = true;
            d.tractorBeam(p);
            assertAll(
                () -> assertEquals(Trig.tcos(64) * (48f / p.mass), p.vx, "the source is pushed away (-x)"),
                () -> assertTrue(p.vx < 0, "the source moves away from the victim"),
                () -> assertTrue(v.vx > 0, "the victim moves away from the source"));
        }

        @Test
        @DisplayName("the beam goes off without a visible player lock, on a dead or phased ship, out of range, phased")
        void offConditions() {
            List<String> off = new ArrayList<>();
            Player p = beam();
            p.lockTagged = Player.LOCK_PLAYER;
            d.tractorBeam(p);
            off.add("invisible " + ((p.used & Has.TRACTOR_BEAM) == 0));
            p = beam();
            v.status |= Obj.KILLED;
            d.tractorBeam(p);
            off.add("dead " + ((p.used & Has.TRACTOR_BEAM) == 0));
            p = beam();
            p.lockDistance = 250;
            d.tractorBeam(p);
            off.add("range " + ((p.used & Has.TRACTOR_BEAM) == 0));
            p = beam();
            p.used |= Has.PHASING_DEVICE;
            d.tractorBeam(p);
            off.add("phased " + ((p.used & Has.TRACTOR_BEAM) == 0));
            p = beam();
            v.used |= Has.PHASING_DEVICE;
            d.tractorBeam(p);
            off.add("victimPhased " + ((p.used & Has.TRACTOR_BEAM) == 0));
            p = beam();
            p.lockPlId = 999;
            d.tractorBeam(p);
            off.add("gone " + ((p.used & Has.TRACTOR_BEAM) == 0));
            p = beam();
            p.fuel = 306;
            d.tractorBeam(p);
            off.add("fuel " + ((p.used & Has.TRACTOR_BEAM) == 0) + " " + p.vx);
            p = beam();
            p.fuel = 307;
            d.tractorBeam(p);
            boolean exactFuel = (p.used & Has.TRACTOR_BEAM) != 0;
            p = beam();
            p.lockDistance = 249.9f;
            d.tractorBeam(p);
            boolean onEdge = (p.used & Has.TRACTOR_BEAM) != 0;
            double left = p.fuel;
            assertAll(
                () -> assertEquals(List.of("invisible true", "dead true", "range true", "phased true",
                    "victimPhased true", "gone true", "fuel true 0.0"), off,
                    "each condition turns the beam off (306 < 307 fuel: off and no pull)"),
                () -> assertTrue(exactFuel, "exactly 307 fuel: the beam works"),
                () -> assertTrue(onEdge, "just inside the range the beam works"),
                () -> assertTrue(left > 0, "and pays for the real 100 px distance"));
        }

        @Test
        @DisplayName("a cannon's beam (no player) only moves the victim, up to and including its range")
        void cannonBeam() {
            Player p = setup("");
            Player far = other("F", 350 + 251, 350);
            d.generalTractorBeam(null, 350, 350, 1, far, false);
            float farVx = far.vx;
            Player edge = other("E", 350, 350 + 250);
            d.generalTractorBeam(null, 350, 350, 1, edge, false);
            float edgeForce = 0.5f * -60f;
            assertAll(
                () -> assertEquals(0f, farVx, "251 px is beyond 250: nothing happens"),
                () -> assertEquals(-Trig.tsin(96) * (edgeForce / edge.mass), edge.vy,
                    "250 px: pulled with 50% of 60 toward the cannon"),
                () -> assertTrue(edge.vy < 0, "the victim moves toward the cannon (-y)"),
                () -> assertEquals(Obj.NO_ID, edge.shovePusher[0], "no shove is recorded"),
                () -> assertEquals(0f, p.vx, "nobody else moves"));
        }
    }

    @Test
    @DisplayName("afterMove: visibility requests clear, forceVisible counts down, beams work, lock distances refresh")
    void afterMove() {
        Player p = setup("");
        Player q = other("Q", 500, 350);
        Player r = other("R", 350, 500);
        p.updateVisibility = true;
        p.forceVisible = 0;
        q.forceVisible = 2;
        q.updateVisibility = true;
        r.forceVisible = 1;
        q.used |= Has.TRACTOR_BEAM;
        q.lockTagged = 0;
        p.lockTagged = Player.LOCK_PLAYER;
        p.lockPlId = q.id;
        p.lockDistance = 5;
        r.lockTagged = Player.LOCK_PLAYER;
        r.lockPlId = 999;
        r.lockDistance = 7;
        Player t = other("T", 400, 350);
        t.lockTagged = Player.LOCK_VISIBLE;
        t.lockPlId = p.id;
        t.lockDistance = 3;
        t.items[it(Item.TRACTOR_BEAM)] = 1;
        p.lockTagged = Player.LOCK_PLAYER | Player.LOCK_VISIBLE;
        p.lockDistance = 150;
        p.items[it(Item.TRACTOR_BEAM)] = 1;
        p.used &= ~Has.TRACTOR_BEAM;
        d.afterMove();
        assertAll(
            () -> assertFalse(p.updateVisibility, "a visibility request is cleared"),
            () -> assertEquals(1, q.forceVisible, "forceVisible counts down"),
            () -> assertFalse(q.updateVisibility, "still forced visible: no visibility update"),
            () -> assertEquals(0, r.forceVisible, "forceVisible reaches 0"),
            () -> assertTrue(r.updateVisibility, "at 0 a visibility update is asked for"),
            () -> assertEquals(0, q.used & Has.TRACTOR_BEAM, "a tractor beam in use is worked (no lock: it goes off)"),
            () -> assertEquals(150f, p.lockDistance, "the lock distance is the wrapped distance to the target"),
            () -> assertEquals(7f, r.lockDistance, "a lock on a player that is gone keeps its distance"),
            () -> assertEquals(3f, t.lockDistance, "without a player lock the distance is not refreshed"),
            () -> assertEquals(0f, q.vx, "a valid lock without the beam in use pulls nothing"));
    }

    // ================================================================ transporter

    @Nested
    @DisplayName("transporter (C Do_transporter)")
    class Transporter {
        Player thief() {
            Player p = setup("");
            p.items[it(Item.TRANSPORTER)] = 1;
            return p;
        }

        /** The victim owns one mine and nothing else (no fuel), so a theft takes the mine. */
        static void strip(Player v) {
            java.util.Arrays.fill(v.items, 0);
            v.items[it(Item.MINE)] = 1;
            v.fuel = 0;
            v.tankFuel[0] = 0;
        }

        @Test
        @DisplayName("the closest playing, unphased, non-tank ship within 200 px is robbed")
        void picksClosest() {
            Player p = thief();
            Player far = other("F", 500, 350);
            Player near = other("N", 400, 350);
            strip(far);
            strip(near);
            d.transporter(p);
            int nearMines = near.items[it(Item.MINE)];
            int farMines = far.items[it(Item.MINE)];
            Player p2 = thief();
            Player tank = other("T", 400, 350);
            Player phased = other("H", 350, 400);
            Player paused = other("U", 300, 350);
            Player f2 = other("F", 500, 350);
            for (Player x : List.of(tank, phased, paused, f2)) {
                strip(x);
            }
            tank.tank = true;
            phased.used |= Has.PHASING_DEVICE;
            paused.status |= Obj.PAUSE;
            d.transporter(p2);
            assertAll(
                () -> assertEquals(0, nearMines, "the ship 50 px away loses its mine"),
                () -> assertEquals(1, farMines, "the ship 150 px away keeps its mine"),
                () -> assertEquals(0, f2.items[it(Item.MINE)], "tanks, phased and paused ships are skipped"),
                () -> assertEquals(1, tank.items[it(Item.MINE)], "the tank keeps its mine"),
                () -> assertEquals(1, phased.items[it(Item.MINE)], "the phased ship keeps its mine"),
                () -> assertEquals(1, paused.items[it(Item.MINE)], "the paused ship keeps its mine"));
        }

        @Test
        @DisplayName("of two ships at the same distance the first is robbed; exactly 200 px is out of range")
        void tiesAndRange() {
            Player p = thief();
            Player a = other("A", 400, 350);
            Player b = other("B", 300, 350);
            strip(a);
            strip(b);
            d.transporter(p);
            int aMines = a.items[it(Item.MINE)];
            int bMines = b.items[it(Item.MINE)];
            Player q = thief();
            Player e = other("E", 550, 350);
            strip(e);
            d.transporter(q);
            assertAll(
                () -> assertEquals(0, aMines, "the first of two equally close ships is robbed"),
                () -> assertEquals(1, bMines, "the second keeps its mine"),
                () -> assertEquals(1, e.items[it(Item.MINE)], "a ship exactly 200 px away is not robbed"));
        }

        @Test
        @DisplayName("team mates are immune; with nobody in range the item and 60 fuel units are spent for nothing")
        void noVictim() {
            s = SimFixture.sim(FRAME + "teamPlay: yes\nteamImmunity: yes\nreserveRobotTeam: no\n", 30, 30, "0011");
            w = s.world;
            d = s.devices;
            Player p = SimFixture.ready(s, "P", 0, 350, 350);
            Player mate = SimFixture.ready(s, "M", 0, 400, 350);
            Player foe = SimFixture.ready(s, "F", 1, 350, 551);
            strip(mate);
            strip(foe);
            p.items[it(Item.TRANSPORTER)] = 2;
            double fuel = p.fuel;
            long from = w.events.seq();
            d.transporter(p);
            assertAll(
                () -> assertEquals(1, mate.items[it(Item.MINE)], "the team mate is immune"),
                () -> assertEquals(1, foe.items[it(Item.MINE)], "the enemy 201 px away is out of range"),
                () -> assertEquals(1, p.items[it(Item.TRANSPORTER)], "the transporter is used up"),
                () -> assertEquals(60.0 * 256, fuel - p.fuel, "60 fuel units are spent"),
                () -> assertEquals(List.of(), SimFixture.messages(w, from), "no message"),
                () -> assertEquals(0, w.ecms.transporters(), "no beam is shown"));
        }

        @Test
        @DisplayName("nothing happens without a transporter, with less than 60 units of fuel, or while phased")
        void guards() {
            Player p = thief();
            Player v = other("V", 400, 350);
            strip(v);
            p.items[it(Item.TRANSPORTER)] = 0;
            d.transporter(p);
            int none = v.items[it(Item.MINE)];
            p.items[it(Item.TRANSPORTER)] = 1;
            p.fuel = 60 * 256 - 1;
            d.transporter(p);
            int poor = v.items[it(Item.MINE)];
            int poorItems = p.items[it(Item.TRANSPORTER)];
            p.fuel = 100000;
            p.used |= Has.PHASING_DEVICE;
            d.transporter(p);
            int phased = v.items[it(Item.MINE)];
            p.used &= ~Has.PHASING_DEVICE;
            p.fuel = 60 * 256;
            d.transporter(p);
            assertAll(
                () -> assertEquals(1, none, "no transporter: nothing"),
                () -> assertEquals(1, poor, "60 units minus one: nothing"),
                () -> assertEquals(1, poorItems, "60 units minus one: the transporter is kept"),
                () -> assertEquals(1, phased, "phased: nothing"),
                () -> assertEquals(0, v.items[it(Item.MINE)], "exactly 60 units: the theft happens"));
        }
    }

    @Nested
    @DisplayName("generalTransporter (C Do_general_transporter)")
    class Steal {
        Player thief;
        Player victim;
        long from;

        /** A thief at (350,350) and a victim at (400,350) owning nothing; the first draw picks {@code kind}. */
        void steal(Item kind, java.util.function.Consumer<Player> prep) {
            thief = setup("");
            victim = other("V", 400, 350);
            java.util.Arrays.fill(victim.items, 0);
            victim.fuel = 0;
            victim.tankFuel[0] = 0;
            java.util.Arrays.fill(w.items.limit, 99);
            thief.items[it(Item.TRANSPORTER)] = 1;
            prep.accept(victim);
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == kind.ordinal()));
            from = w.events.seq();
            d.generalTransporter(thief, thief.px, thief.py, victim);
        }

        @Test
        @DisplayName("every countable item moves one from victim to thief with its message")
        void everyItem() {
            Object[][] table = {
                {Item.WIDEANGLE, "a wideangle"}, {Item.REARSHOT, "a rearshot"}, {Item.AFTERBURNER, "an afterburner"},
                {Item.CLOAK, "a cloaking device"}, {Item.SENSOR, "a sensor"}, {Item.TRANSPORTER, "a transporter"},
                {Item.MINE, "a mine"}, {Item.ECM, "an ECM"}, {Item.LASER, "a laser"},
                {Item.EMERGENCY_THRUST, "an emergency thrust"}, {Item.TRACTOR_BEAM, "a tractor beam"},
                {Item.AUTOPILOT, "an autopilot"}, {Item.EMERGENCY_SHIELD, "an emergency shield"},
                {Item.DEFLECTOR, "a deflector"}, {Item.HYPERJUMP, "a hyperjump"}, {Item.PHASING, "a phasing device"},
                {Item.MIRROR, "a mirror"}, {Item.ARMOR, "an armor"},
            };
            List<String> got = new ArrayList<>();
            List<String> want = new ArrayList<>();
            for (Object[] row : table) {
                Item kind = (Item) row[0];
                steal(kind, v -> v.items[it(kind)] = 1);
                double fuel = thief.fuel;
                got.add(kind + " " + SimFixture.messages(w, from) + " victim " + victim.items[it(kind)] + " thief "
                    + thief.items[it(kind)] + " transporters " + thief.items[it(Item.TRANSPORTER)]);
                want.add(kind + " [P stole " + row[1] + " from V.] victim 0 thief 1 transporters "
                    + (kind == Item.TRANSPORTER ? 1 : 0));
            }
            assertEquals(want, got, "each item: one moves to the thief, who pays a transporter; the message names it");
        }

        @Test
        @DisplayName("the thief pays 60 fuel units and a 5-frame beam is shown from the thief to the victim")
        void costAndBeam() {
            thief = setup("");
            victim = other("V", 400, 350);
            java.util.Arrays.fill(victim.items, 0);
            victim.items[it(Item.MINE)] = 2;
            victim.fuel = 0;
            victim.tankFuel[0] = 0;
            thief.items[it(Item.TRANSPORTER)] = 1;
            double fuel = thief.fuel;
            d.generalTransporter(thief, 123.5f, 456.25f, victim);
            var e = w.ecms;
            assertAll(
                () -> assertEquals(60.0 * 256, fuel - thief.fuel, "the theft costs 60 fuel units"),
                () -> assertEquals(1, e.transporters(), "one beam"),
                () -> assertEquals(123.5f, e.transX[0], "the beam starts at the given x"),
                () -> assertEquals(456.25f, e.transY[0], "the beam starts at the given y"),
                () -> assertEquals(victim.id, e.transTarget[0], "the beam ends at the victim"),
                () -> assertEquals(thief.id, e.transOwner[0], "the beam belongs to the thief"),
                () -> assertEquals(5, e.transCount[0], "the beam is shown for 5 frames"),
                () -> assertEquals(1, victim.items[it(Item.MINE)], "the victim lost one mine"));
        }

        @Test
        @DisplayName("with the beam list full the theft still happens")
        void beamListFull() {
            thief = setup("");
            victim = other("V", 400, 350);
            java.util.Arrays.fill(victim.items, 0);
            victim.items[it(Item.MINE)] = 1;
            victim.fuel = 0;
            victim.tankFuel[0] = 0;
            while (w.ecms.addTransporter() >= 0) {
                // fill
            }
            d.generalTransporter(thief, 0, 0, victim);
            assertAll(
                () -> assertEquals(com.fulcrologic.jxpilot.server.world.Ecms.TRANSPORTER_CAPACITY, w.ecms.transporters(),
                    "no beam is added beyond capacity"),
                () -> assertEquals(1, thief.items[it(Item.MINE)], "the mine is stolen anyway"));
        }

        @Test
        @DisplayName("losing the last device turns it off and drops the ability; the thief gets the ability")
        void abilities() {
            Item[] kinds = {Item.AFTERBURNER, Item.CLOAK, Item.ARMOR, Item.MIRROR, Item.DEFLECTOR, Item.PHASING,
                Item.EMERGENCY_THRUST, Item.EMERGENCY_SHIELD, Item.TRACTOR_BEAM, Item.AUTOPILOT};
            int[] bits = {Has.AFTERBURNER, Has.CLOAKING_DEVICE, Has.ARMOR, Has.MIRROR, Has.DEFLECTOR,
                Has.PHASING_DEVICE, Has.EMERGENCY_THRUST, Has.EMERGENCY_SHIELD, Has.TRACTOR_BEAM, Has.AUTOPILOT};
            List<String> last = new ArrayList<>();
            List<String> notLast = new ArrayList<>();
            List<String> thiefHas = new ArrayList<>();
            for (int k = 0; k < kinds.length; k++) {
                Item kind = kinds[k];
                int bit = bits[k];
                for (int n = 1; n <= 2; n++) {
                    int count = n;
                    steal(kind, v -> {
                        v.items[it(kind)] = count;
                        v.have |= bit;
                        v.used |= bit;
                    });
                    String row = kind + " have " + ((victim.have & bit) != 0) + " used " + ((victim.used & bit) != 0);
                    (n == 1 ? last : notLast).add(row);
                    if (n == 1) {
                        thiefHas.add(kind + " " + ((thief.have & bit) != 0));
                    }
                }
            }
            List<String> wantLast = new ArrayList<>();
            List<String> wantNotLast = new ArrayList<>();
            List<String> wantThief = new ArrayList<>();
            for (Item kind : kinds) {
                boolean usedStays = kind == Item.AFTERBURNER || kind == Item.ARMOR || kind == Item.MIRROR
                    || kind == Item.TRACTOR_BEAM;
                wantLast.add(kind + " have false used " + usedStays);
                wantNotLast.add(kind + " have true used true");
                wantThief.add(kind + " true");
            }
            assertAll(
                () -> assertEquals(wantLast, last,
                    "the last item: the ability goes; devices in use (cloak, deflector, phasing, emergencies, "
                        + "autopilot) go off"),
                () -> assertEquals(wantNotLast, notLast, "one item left: ability and use are kept"),
                () -> assertEquals(wantThief, thiefHas, "the thief gets each ability"));
        }

        @Test
        @DisplayName("visibility: cloak and sensor thefts ask both ships for a visibility update")
        void visibility() {
            steal(Item.SENSOR, v -> v.items[it(Item.SENSOR)] = 2);
            boolean sv = victim.updateVisibility;
            boolean st = thief.updateVisibility;
            steal(Item.CLOAK, v -> v.items[it(Item.CLOAK)] = 2);
            boolean cv = victim.updateVisibility;
            boolean ct = thief.updateVisibility;
            steal(Item.MINE, v -> {
                v.items[it(Item.MINE)] = 2;
                v.updateVisibility = false;
            });
            assertAll(
                () -> assertTrue(sv, "sensor: the victim's visibility is updated"),
                () -> assertTrue(st, "sensor: the thief's visibility is updated"),
                () -> assertTrue(cv, "cloak: the victim's visibility is updated"),
                () -> assertTrue(ct, "cloak: the thief's visibility is updated"),
                () -> assertFalse(victim.updateVisibility, "other items leave visibility alone"));
        }

        @Test
        @DisplayName("the last emergency shield also takes the shields when they are not a default ability")
        void emergencyShieldShields() {
            steal(Item.EMERGENCY_SHIELD, v -> {
                w.params.defHave &= ~Has.SHIELD;
                v.items[it(Item.EMERGENCY_SHIELD)] = 1;
                v.have |= Has.EMERGENCY_SHIELD | Has.SHIELD;
                v.used |= Has.SHIELD;
            });
            int noDefault = victim.have | victim.used;
            steal(Item.EMERGENCY_SHIELD, v -> {
                w.params.defHave |= Has.SHIELD;
                v.items[it(Item.EMERGENCY_SHIELD)] = 1;
                v.have |= Has.EMERGENCY_SHIELD | Has.SHIELD;
                v.used |= Has.SHIELD;
            });
            assertAll(
                () -> assertEquals(0, noDefault & Has.SHIELD, "no default shields: shields are lost and go down"),
                () -> assertTrue((victim.have & Has.SHIELD) != 0, "default shields: they are kept"),
                () -> assertTrue((victim.used & Has.SHIELD) != 0, "default shields: they stay up"));
        }

        @Test
        @DisplayName("devices that are not in use are not switched off when their last item goes")
        void unusedDevices() {
            steal(Item.AUTOPILOT, v -> {
                v.items[it(Item.AUTOPILOT)] = 1;
                v.have |= Has.AUTOPILOT;
                v.power = 45;
                v.autoPowerS = 12;
            });
            float power = victim.power;
            steal(Item.PHASING, v -> {
                v.items[it(Item.PHASING)] = 1;
                v.have |= Has.PHASING_DEVICE;
                v.status &= ~Obj.GRAVITY;
            });
            int status = victim.status;
            assertAll(
                () -> assertEquals(45f, power, "an autopilot that is off does not restore saved settings"),
                () -> assertEquals(0, status & Obj.GRAVITY, "a phasing device that is off does not touch gravity"));
        }

        @Test
        @DisplayName("losing the autopilot restores the ship's own settings")
        void autopilotOff() {
            steal(Item.AUTOPILOT, v -> {
                v.items[it(Item.AUTOPILOT)] = 1;
                v.have |= Has.AUTOPILOT;
                v.power = 50;
                d.autopilot(v, true);
            });
            assertAll(
                () -> assertEquals(0, victim.used & Has.AUTOPILOT, "the autopilot is off"),
                () -> assertEquals(50f, victim.power, "the saved power is back"));
        }

        @Test
        @DisplayName("missiles: up to 3 are copied (the victim keeps them, as in C); one gets its own message")
        void missiles() {
            steal(Item.MISSILE, v -> v.items[it(Item.MISSILE)] = 5);
            List<String> many = SimFixture.messages(w, from);
            int victimMany = victim.items[it(Item.MISSILE)];
            int thiefMany = thief.items[it(Item.MISSILE)];
            steal(Item.MISSILE, v -> v.items[it(Item.MISSILE)] = 1);
            List<String> one = SimFixture.messages(w, from);
            int thiefOne = thief.items[it(Item.MISSILE)];
            steal(Item.MISSILE, v -> {
                v.items[it(Item.MISSILE)] = 5;
                w.items.limit[it(Item.MISSILE)] = 2;
            });
            assertAll(
                () -> assertEquals(List.of("P stole 3 missiles from V"), many, "three: C's message has no period"),
                () -> assertEquals(5, victimMany, "the victim's missiles are not reduced (C quirk)"),
                () -> assertEquals(3, thiefMany, "the thief gets 3"),
                () -> assertEquals(List.of("P stole a missile from V."), one, "one missile: singular message"),
                () -> assertEquals(1, thiefOne, "the thief gets 1"),
                () -> assertEquals(2, thief.items[it(Item.MISSILE)], "the thief's count is limited by maxMissiles"));
        }

        @Test
        @DisplayName("afterburners are limited to 15, and every item to its limit")
        void limits() {
            steal(Item.AFTERBURNER, v -> {
                v.items[it(Item.AFTERBURNER)] = 1;
                thief.items[it(Item.AFTERBURNER)] = 15;
            });
            int ab = thief.items[it(Item.AFTERBURNER)];
            steal(Item.MINE, v -> {
                v.items[it(Item.MINE)] = 1;
                thief.items[it(Item.MINE)] = 4;
                w.items.limit[it(Item.MINE)] = 4;
            });
            assertAll(
                () -> assertEquals(15, ab, "the 16th afterburner is not kept (MAX_AFTERBURNER)"),
                () -> assertEquals(4, thief.items[it(Item.MINE)], "a mine beyond maxMines is not kept"),
                () -> assertEquals(0, victim.items[it(Item.MINE)], "the victim loses it anyway"));
        }

        @Test
        @DisplayName("fuel: 10-50% of the victim's fuel in C float arithmetic, with units and percent in the message")
        void fuel() {
            thief = setup("");
            victim = other("V", 400, 350);
            java.util.Arrays.fill(victim.items, 0);
            victim.fuel = 256000;
            victim.tankFuel[0] = 256000;
            thief.items[it(Item.TRANSPORTER)] = 1;
            double thiefFuel = thief.fuel;
            // Seed 10721: first draw (int)(r * 21) = 0 (FUEL), second r = 0.009150385856628418.
            w.rng.seed(10721);
            Rng ref = new Rng(10721);
            ref.nextFloat();
            double r2 = ref.nextFloat();
            float percent = (float) (10.0f + 40.0f * r2);
            long amount = (long) ((float) 256000 * percent / 100);
            from = w.events.seq();
            d.generalTransporter(thief, thief.px, thief.py, victim);
            assertAll(
                () -> assertEquals(26537L, amount, "(long)((float)256000 * 10.366015f / 100) = 26537 in float"),
                () -> assertEquals(List.of("P stole 103 units (10%) of fuel from V."), SimFixture.messages(w, from),
                    "units are amount >> 8, the percent is rounded"),
                () -> assertEquals(256000.0 - 26537, victim.fuel, "the victim loses the amount"),
                () -> assertEquals(thiefFuel - 60 * 256 + 26537, thief.fuel, "the thief gains it after paying 60 units"));
        }

        @Test
        @DisplayName("tank: a random extra tank and its fuel move to the thief (unless the thief has 8)")
        void tank() {
            thief = setup("");
            victim = other("V", 400, 350);
            java.util.Arrays.fill(victim.items, 0);
            victim.fuel = 0;
            victim.tankFuel[0] = 0;
            s.tanks.add(victim, 500 * 256);
            s.tanks.add(victim, 700 * 256);
            thief.items[it(Item.TRANSPORTER)] = 1;
            int seed = seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.TANK)
                && (int) ((double) r.nextFloat() * 2) + 1 == 2);
            w.rng.seed(seed);
            double second = victim.tankFuel[2];
            from = w.events.seq();
            d.generalTransporter(thief, thief.px, thief.py, victim);
            List<String> msg = SimFixture.messages(w, from);
            int thiefTanks = thief.numTanks;
            double thiefTank = thief.tankFuel[1];
            int victimTanks = victim.numTanks;
            double victimFirst = victim.tankFuel[1];
            Player full = other("X", 300, 300);
            for (int i = 0; i < Player.MAX_TANKS; i++) {
                s.tanks.add(full, 0);
            }
            full.items[it(Item.TRANSPORTER)] = 1;
            victim.items[it(Item.TANK)] = 0;
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.TANK)));
            d.generalTransporter(full, full.px, full.py, victim);
            assertAll(
                () -> assertEquals(List.of("P stole a tank from V."), msg, "the message names a tank"),
                () -> assertEquals(1, victimTanks, "the victim has one tank left"),
                () -> assertEquals(500.0 * 256, victimFirst, "the victim keeps the first tank"),
                () -> assertEquals(1, thiefTanks, "the thief gets a tank"),
                () -> assertEquals(700.0 * 256, second, "the second tank holds 700 units"),
                () -> assertEquals(second, thiefTank, "the thief's tank has the second tank's fuel"),
                () -> assertEquals(Player.MAX_TANKS, full.numTanks, "a thief with 8 tanks gets none"),
                () -> assertEquals(0, victim.numTanks, "but the victim loses it"));
        }

        @Test
        @DisplayName("a cannon gets the item kind and amount (fuel in units); 50 misses steal nothing")
        void cannon() {
            thief = setup("");
            victim = other("V", 400, 350);
            java.util.Arrays.fill(victim.items, 0);
            victim.items[it(Item.MINE)] = 2;
            victim.fuel = 0;
            victim.tankFuel[0] = 0;
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.MINE)));
            from = w.events.seq();
            d.generalTransporter(null, 100, 200, victim);
            List<String> msg = SimFixture.messages(w, from);
            int item = d.stolenItem;
            long amount = d.stolenAmount;
            int owner = w.ecms.transOwner[0];
            int mines = victim.items[it(Item.MINE)];
            java.util.Arrays.fill(victim.items, 0);
            victim.fuel = 256000;
            victim.tankFuel[0] = 256000;
            w.rng.seed(10721);
            d.generalTransporter(null, 100, 200, victim);
            int fuelItem = d.stolenItem;
            long fuelUnits = d.stolenAmount;
            victim.fuel = 0;
            victim.tankFuel[0] = 0;
            w.rng.seed(3);
            Rng ref = new Rng(3);
            for (int i = 0; i < 50; i++) {
                ref.nextInt();
            }
            int beams = w.ecms.transporters();
            long none = w.events.seq();
            d.generalTransporter(null, 100, 200, victim);
            assertAll(
                () -> assertEquals(List.of("A cannon stole a mine from V."), msg, "the thief is called A cannon"),
                () -> assertEquals(it(Item.MINE), item, "the cannon gets a mine"),
                () -> assertEquals(1L, amount, "one of it"),
                () -> assertEquals(Obj.NO_ID, owner, "the cannon's beam has no owner"),
                () -> assertEquals(1, mines, "the victim lost one mine"),
                () -> assertEquals(it(Item.FUEL), fuelItem, "fuel can be stolen"),
                () -> assertEquals(103L, fuelUnits, "the cannon gets the fuel in units (26537 >> 8)"),
                () -> assertEquals(-1, d.stolenItem, "nothing to steal: item -1"),
                () -> assertEquals(0L, d.stolenAmount, "nothing to steal: amount 0"),
                () -> assertEquals(ref.nextInt(), w.rng.nextInt(), "exactly 50 draws were made"),
                () -> assertEquals(beams, w.ecms.transporters(), "no beam for a failed theft"),
                () -> assertEquals(List.of(), SimFixture.messages(w, none), "no message for a failed theft"));
        }

        @Test
        @DisplayName("a player that finds nothing to steal keeps its transporter")
        void playerMisses() {
            thief = setup("");
            victim = other("V", 400, 350);
            java.util.Arrays.fill(victim.items, 0);
            victim.fuel = 0;
            victim.tankFuel[0] = 0;
            thief.items[it(Item.TRANSPORTER)] = 1;
            double fuel = thief.fuel;
            d.generalTransporter(thief, thief.px, thief.py, victim);
            assertAll(
                () -> assertEquals(1, thief.items[it(Item.TRANSPORTER)], "the transporter is not used"),
                () -> assertEquals(fuel, thief.fuel, "no fuel is spent"));
        }
    }

    // ================================================================ hyperjump

    @Test
    @DisplayName("hyperjumpKey: with a hyperjump and more than 60 fuel units one is used and the ship warps")
    void hyperjumpKey() {
        Player p = setup("");
        p.items[it(Item.HYPERJUMP)] = 1;
        p.fuel = 60 * 256;
        d.hyperjumpKey(p);
        int poorStatus = p.status;
        int poorItems = p.items[it(Item.HYPERJUMP)];
        p.fuel = 60 * 256 + 1;
        p.wormHoleHit = 3;
        d.hyperjumpKey(p);
        int status = p.status;
        Player q = other("Q", 500, 500);
        q.items[it(Item.HYPERJUMP)] = 0;
        d.hyperjumpKey(q);
        assertAll(
            () -> assertEquals(0, poorStatus & Obj.WARPING, "exactly 60 units is not enough (strict >)"),
            () -> assertEquals(1, poorItems, "and the hyperjump is kept"),
            () -> assertTrue((status & Obj.WARPING) != 0, "with enough fuel the ship warps"),
            () -> assertEquals(-1, p.wormHoleHit, "to no wormhole (a random destination)"),
            () -> assertEquals(0, p.items[it(Item.HYPERJUMP)], "the hyperjump is used"),
            () -> assertEquals(1.0, p.fuel, "60 fuel units are paid"),
            () -> assertEquals(0, q.status & Obj.WARPING, "no hyperjump: no warp"));
    }

}
