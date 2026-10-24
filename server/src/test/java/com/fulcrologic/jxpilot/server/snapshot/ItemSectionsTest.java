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
package com.fulcrologic.jxpilot.server.snapshot;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ItemSections (item snapshot sections)")
class ItemSectionsTest {
    /** 60 blocks wide: a position 1500 px from A is out of A's view. */
    static final String[] ROWS = {
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "x                                                          x",
        "x                                                          x",
        "x _ _                                                   _  x",
        "x                                                          x",
        "x     r  #                                                 x",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    };

    Simulation sim;
    World w;
    Player a;
    Player b;
    Player c;
    final ByteBuffer[] parts = new ByteBuffer[8];

    void make(String extra) {
        sim = TestWorlds.sim("framesPerSecond: 12\ngravity: 0\n" + extra, ROWS);
        w = sim.world;
        a = TestWorlds.join(sim, "A", 0);
        b = TestWorlds.join(sim, "B", 0);
        c = TestWorlds.join(sim, "C", 0);
        a.initPixels(200, 120);
        b.initPixels(400, 130);
        c.initPixels(1800, 120);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = ByteBuffer.allocate(1200);
        }
        w.tick = 7;
    }

    @BeforeEach
    void setUp() {
        make("");
    }

    /** All records of a section type as strings, from every part, for viewer {@code v}. */
    List<String> records(Player v, int type, boolean radar) {
        for (ByteBuffer b : parts) {
            b.clear();
        }
        int n = new SnapshotBuilder(w).build(v, 0, w.events.burstSeq(), radar, parts);
        List<String> out = new ArrayList<>();
        SnapshotReader r = new SnapshotReader();
        r.setWorld(w.pixelWidth, w.pixelHeight, w.wrap);
        for (int p = 0; p < n; p++) {
            ByteBuffer buf = parts[p].duplicate();
            buf.flip();
            assertTrue(r.begin(buf), "part header");
            while (r.nextSection()) {
                if (r.type() != (type < 0 ? Section.SELF : type)) {
                    continue;
                }
                for (int i = 0; i < r.count(); i++) {
                    out.add(describe(r, type, i));
                }
            }
        }
        return out;
    }

    /** Pseudo section type for {@link #describe}: the lock and modifier fields of SELF. */
    static final int SELF_LOCK = -1;
    /** Pseudo section type for {@link #describe}: the flags of SELF. */
    static final int SELF_FLAGS = -2;

    static int res(float radians) {
        return Math.round(radians * Const.RES / (float) (2 * Math.PI)) & (Const.RES - 1);
    }

    static String describe(SnapshotReader r, int type, int i) {
        switch (type) {
            case Section.SELF_ITEMS -> {
                Records.SelfItems s = new Records.SelfItems().wrap(r, i);
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < Item.COUNT; k++) {
                    sb.append(s.count(k)).append(k < Item.COUNT - 1 ? "," : "");
                }
                return sb.toString();
            }
            case SELF_FLAGS -> {
                return Integer.toString(new Records.Self().wrap(r, i).flags());
            }
            case SELF_LOCK -> {
                Records.Self s = new Records.Self().wrap(r, i);
                return s.lockDist() + " " + s.lockDir() + " m" + s.mods();
            }
            case Section.TIMERS -> {
                Records.Timers t = new Records.Timers().wrap(r, i);
                return t.damaged() + " " + t.roundDelay() + " " + t.timeLeft() + " d" + t.destruct() + " t"
                    + t.thrustLeft() + "/" + t.thrustMax() + " s" + t.shieldLeft() + "/" + t.shieldMax() + " p"
                    + t.phasingLeft() + "/" + t.phasingMax() + " x" + t.shutdownCount();
            }
            case Section.ITEM -> {
                Records.ItemObj t = new Records.ItemObj().wrap(r, i);
                return t.x() + "," + t.y() + " " + t.itemType();
            }
            case Section.MINE -> {
                Records.Mine m = new Records.Mine().wrap(r, i);
                return m.x() + "," + m.y() + " f" + m.flags() + " o" + m.ownerId();
            }
            case Section.MISSILE -> {
                Records.Missile m = new Records.Missile().wrap(r, i);
                return m.id() + " " + m.x() + "," + m.y() + " d" + res(m.heading()) + " k" + m.kind() + " l" + m.length();
            }
            case Section.LASER -> {
                Records.Laser l = new Records.Laser().wrap(r, i);
                return l.x() + "," + l.y() + " d" + res(l.heading()) + " l" + l.length() + " c" + l.color();
            }
            case Section.ECM -> {
                Records.Ecm e = new Records.Ecm().wrap(r, i);
                return e.x() + "," + e.y() + " r" + e.radius();
            }
            case Section.CONNECTOR -> {
                Records.Connector c = new Records.Connector().wrap(r, i);
                return c.x0() + "," + c.y0() + "-" + c.x1() + "," + c.y1() + " k" + c.kind();
            }
            case Section.TRANSPORTER -> {
                Records.Transporter t = new Records.Transporter().wrap(r, i);
                return t.x0() + "," + t.y0() + "-" + t.x1() + "," + t.y1();
            }
            case Section.RADAR -> {
                Records.Radar d = new Records.Radar().wrap(r, i);
                return d.cellX() + "," + d.cellY() + ":" + d.flags();
            }
            case Section.SHIP -> {
                Records.Ship s = new Records.Ship().wrap(r, i);
                return s.id() + " f" + s.flags();
            }
            case Section.SELF -> {
                Records.Self s = new Records.Self().wrap(r, i);
                return s.lockId() + " " + s.numTanks() + " " + s.currentTank();
            }
            default -> {
                return "?";
            }
        }
    }

    int obj(int type, double x, double y) {
        Objects o = w.objects;
        int h = o.alloc();
        o.type[h] = type;
        o.initPixels(h, x, y);
        o.life[h] = 100;
        return h;
    }

    @Nested
    @DisplayName("HUD sections")
    class Hud {
        @Test
        @DisplayName("SELF carries lock id (with compass), tanks and the current tank; SELF_ITEMS the counts")
        void selfAndItems() {
            a.items[Item.MINE.ordinal()] = 3;
            a.items[Item.LASER.ordinal()] = 2;
            a.numTanks = 2;
            a.currentTank = 1;
            a.lockTagged = Player.LOCK_PLAYER;
            a.lockPlId = b.id;
            a.used |= Has.COMPASS;
            String self = records(a, Section.SELF, false).get(0);
            a.used &= ~Has.COMPASS;
            String noCompass = records(a, Section.SELF, false).get(0);
            String items = records(a, Section.SELF_ITEMS, false).get(0);
            assertAll(
                () -> assertEquals(b.id + " 2 1", self, "lock id, two tanks, tank 1 current"),
                () -> assertEquals(Protocol.NO_ID + " 2 1", noCompass, "no compass: no lock shown"),
                () -> assertEquals("0,0,0,0,0,0,0,0,3,0,0,2,0,0,0,0,0,0,0,0,0", items, "the item counts"));
        }

        @Test
        @DisplayName("SELF: lock distance/direction only when LOCK_VISIBLE; the modifiers in SELF's layout")
        void lockAndMods() {
            a.lockTagged = Player.LOCK_PLAYER | Player.LOCK_VISIBLE;
            a.lockPlId = b.id;
            a.lockDistance = 200.7f;
            a.mods = Mods.withNuclear(Mods.withMini(Obj.MOD_CLUSTER, 2), Mods.NUCLEAR | Mods.FULLNUCLEAR);
            String on = records(a, SELF_LOCK, false).get(0);
            a.lockTagged = Player.LOCK_PLAYER;
            String off = records(a, SELF_LOCK, false).get(0);
            int mods = Records.Self.MOD_NUCLEAR | Records.Self.MOD_FULL_NUCLEAR | Records.Self.MOD_CLUSTER
                | (2 << Records.Self.MOD_MINI_SHIFT);
            assertAll(
                () -> assertEquals("200 1 m" + mods, on, "B is 200 px right and 10 up (dir 1): the lock is shown"),
                () -> assertEquals("0 0 m" + mods, off, "no LOCK_VISIBLE: no distance or direction"));
        }

        @Test
        @DisplayName("SELF mods: warhead and nuclear fields swap places, the other fields keep theirs")
        void selfMods() {
            assertAll(
                () -> assertEquals(Records.Self.MOD_CLUSTER, ItemSections.selfMods(Mods.withWarhead(0, Obj.MOD_CLUSTER)),
                    "cluster"),
                () -> assertEquals(Records.Self.MOD_IMPLOSION,
                    ItemSections.selfMods(Mods.withWarhead(0, Obj.MOD_IMPLOSION)), "implosion"),
                () -> assertEquals(Records.Self.MOD_NUCLEAR, ItemSections.selfMods(Mods.withNuclear(0, Mods.NUCLEAR)),
                    "nuclear"),
                () -> assertEquals(Records.Self.MOD_LASER_BLIND | (3 << Records.Self.MOD_POWER_SHIFT)
                    | (1 << Records.Self.MOD_SPREAD_SHIFT) | (2 << Records.Self.MOD_VELOCITY_SHIFT),
                    ItemSections.selfMods(Mods.withLaser(Mods.withPower(Mods.withSpread(Mods.withVelocity(0, 2), 1), 3),
                        Mods.BLIND)), "velocity, spread, power and laser in place"));
        }

        @Test
        @DisplayName("TIMERS meters: emergency thrust/shield and phasing only while in use; self-destruct countdown")
        void meters() {
            a.used |= Has.EMERGENCY_THRUST;
            a.emergencyThrustLeft = 30;
            a.emergencyThrustMax = 48;
            a.emergencyShieldLeft = 5;
            a.emergencyShieldMax = 6;
            a.phasingLeft = 9;
            a.phasingMax = 12;
            w.roundTime = -1;
            String thrust = records(a, Section.TIMERS, false).get(0);
            a.used ^= Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE;
            a.status |= Obj.SELF_DESTRUCT;
            a.count = 36;
            String others = records(a, Section.TIMERS, false).get(0);
            assertAll(
                () -> assertEquals("0 0 -1 d0 t30/48 s0/0 p0/0 x-1", thrust, "only the thrust meter is in use"),
                () -> assertEquals("0 0 -1 d36 t0/0 s5/6 p9/12 x-1", others,
                    "shield and phasing meters, 36 frames to self-destruct"));
        }

        @Test
        @DisplayName("TIMERS: blindness, round delay, round time left in seconds (-1 without a limit)")
        void timers() {
            a.damaged = 40;
            w.roundDelay = 0;
            w.roundTime = -1;
            String none = records(a, Section.TIMERS, false).get(0);
            w.roundTime = 120;
            w.roundDelay = 24;
            String limited = records(a, Section.TIMERS, false).get(0);
            assertAll(
                () -> assertEquals("40 0 -1 d0 t0/0 s0/0 p0/0 x-1", none, "damaged 40, no delay, no limit"),
                () -> assertEquals("40 24 10 d0 t0/0 s0/0 p0/0 x-1", limited, "120 frames at 12 fps = 10 s"));
        }
    }

    @Nested
    @DisplayName("objects")
    class Objs {
        @Test
        @DisplayName("items in view with their kind; out of view culled; random items show a valid kind")
        void items() {
            int h = obj(Obj.ITEM, 250, 120);
            w.objects.info[h] = Item.CLOAK.ordinal();
            obj(Obj.ITEM, 1700, 120);
            int r = obj(Obj.ITEM, 260, 120);
            w.objects.info[r] = Item.MINE.ordinal();
            w.objects.status[r] |= Obj.RANDOM_ITEM;
            List<String> it = records(a, Section.ITEM, false);
            int kind = Integer.parseInt(it.get(1).split(" ")[1]);
            assertAll(
                () -> assertEquals(2, it.size(), "the far item is culled"),
                () -> assertEquals("250.0,120.0 " + Item.CLOAK.ordinal(), it.get(0), "a cloak"),
                () -> assertTrue(kind >= 0 && kind < Item.COUNT, "a random item shows some item kind"));
        }

        @Test
        @DisplayName("mines: ARMED, FRIENDLY, NUCLEAR and OWN flags; the controller only when identified")
        void mines() {
            Objects o = w.objects;
            int own = obj(Obj.MINE, 230, 120);
            o.owner[own] = a.id;
            o.mineOwner[own] = a.id;
            o.status[own] |= Obj.OWNERIMMUNE;
            int enemy = obj(Obj.MINE, 240, 120);
            o.owner[enemy] = b.id;
            o.mineOwner[enemy] = b.id;
            o.mods[enemy] = Mods.withNuclear(0, Mods.NUCLEAR);
            int far = obj(Obj.MINE, 600, 120);
            o.owner[far] = b.id;
            List<String> m = records(a, Section.MINE, false);
            assertAll(
                () -> assertEquals("230.0,120.0 f" + (Records.Mine.FLAG_FRIENDLY | Records.Mine.FLAG_OWN) + " o" + a.id,
                    m.get(0), "own fused mine: friendly, own, identified"),
                () -> assertEquals("240.0,120.0 f" + (Records.Mine.FLAG_ARMED | Records.Mine.FLAG_NUCLEAR) + " o" + b.id,
                    m.get(1), "B's nuclear mine nearby: armed, identified"),
                () -> assertEquals("600.0,120.0 f" + Records.Mine.FLAG_ARMED + " o" + Protocol.NO_ID, m.get(2),
                    "a mine 400 px away (beyond 16 + 130 px): controller unknown"));
        }

        @Test
        @DisplayName("missiles: owner id, kind (+nuclear), C lengths with distinguishMissiles, direction")
        void missiles() {
            Objects o = w.objects;
            int t = obj(Obj.TORPEDO, 230, 120);
            o.owner[t] = b.id;
            o.missileDir[t] = 32;
            int s = obj(Obj.SMART_SHOT, 240, 120);
            o.mods[s] = Mods.withNuclear(0, Mods.NUCLEAR);
            int hs = obj(Obj.HEAT_SHOT, 250, 120);
            o.owner[hs] = a.id;
            List<String> m = records(a, Section.MISSILE, false);
            assertAll(
                () -> assertEquals(b.id + " 230.0,120.0 d32 k" + Records.Missile.KIND_TORPEDO + " l18", m.get(0), "torpedo"),
                () -> assertEquals(Protocol.NO_ID + " 240.0,120.0 d0 k"
                    + (Records.Missile.KIND_SMART | Records.Missile.KIND_NUCLEAR) + " l12", m.get(1), "nuclear smart"),
                () -> assertEquals(a.id + " 250.0,120.0 d0 k" + Records.Missile.KIND_HEAT + " l15", m.get(2), "heat"));
            make("distinguishMissiles: no\n");
            int t2 = obj(Obj.TORPEDO, 230, 120);
            assertEquals(Protocol.NO_ID + " 230.0,120.0 d0 k" + Records.Missile.KIND_TORPEDO + " l15",
                records(a, Section.MISSILE, false).get(0), "without distinguishMissiles every missile is 15 long (" + t2 + ")");
        }

        @Test
        @DisplayName("lasers: from the start in view, else from the end backwards; blue when harmless; length 0 skipped")
        void lasers() {
            Pulses p = w.pulses;
            int i = p.add();
            p.x[i] = 250;
            p.y[i] = 120;
            p.dir[i] = 0;
            p.len[i] = 85;
            p.owner[i] = b.id;
            int j = p.add();
            p.x[j] = 1000;
            p.y[j] = 120;
            p.dir[j] = 64;
            p.len[j] = 200;
            p.owner[j] = Obj.NO_ID;
            int k = p.add();
            p.x[k] = 260;
            p.y[k] = 120;
            p.len[k] = 0;
            List<String> l = records(a, Section.LASER, false);
            make("selfImmunity: yes\n");
            int s = w.pulses.add();
            w.pulses.x[s] = 250;
            w.pulses.y[s] = 120;
            w.pulses.len[s] = 85;
            w.pulses.owner[s] = a.id;
            List<String> own = records(a, Section.LASER, false);
            assertAll(
                () -> assertEquals(List.of("250.0,120.0 d0 l85 c" + Records.ObjColor.RED,
                    "800.0,120.0 d0 l200 c" + Records.ObjColor.RED), l,
                    "the far pulse pointing left ends at 800 px (in view) and is drawn back from there; length 0 skipped"),
                () -> assertEquals(List.of("250.0,120.0 d0 l85 c" + Records.ObjColor.BLUE), own,
                    "own pulse with selfImmunity: blue"));
        }

        @Test
        @DisplayName("ECM blasts with their radius; transporter beams from the victim to the thief or the cannon point")
        void ecmAndTransporters() {
            int e = w.ecms.add();
            w.ecms.x[e] = 300;
            w.ecms.y[e] = 100;
            w.ecms.size[e] = 50;
            int t = w.ecms.addTransporter();
            w.ecms.transTarget[t] = b.id;
            w.ecms.transOwner[t] = a.id;
            int u = w.ecms.addTransporter();
            w.ecms.transTarget[u] = b.id;
            w.ecms.transOwner[u] = Obj.NO_ID;
            w.ecms.transX[u] = 150;
            w.ecms.transY[u] = 60;
            List<String> ecm = records(a, Section.ECM, false);
            List<String> tr = records(a, Section.TRANSPORTER, false);
            assertAll(
                () -> assertEquals(List.of("300.0,100.0 r50"), ecm, "the blast"),
                () -> assertEquals(List.of("400.0,130.0-200.0,120.0", "400.0,130.0-150.0,60.0"), tr,
                    "B to A, and B to the cannon's firing point"));
        }

        @Test
        @DisplayName("tractor beams: three connectors to the target's hull, pressor kind when pushing")
        void beams() {
            a.used |= Has.TRACTOR_BEAM;
            a.lockPlId = b.id;
            a.tractorIsPressor = true;
            b.dir = 0;
            List<String> cn = records(a, Section.CONNECTOR, false);
            assertAll(
                () -> assertEquals(3, cn.size(), "three lines"),
                () -> assertEquals("415.0,130.0-200.0,120.0 k" + Records.Connector.KIND_PRESSOR, cn.get(0),
                    "from B's nose (+15,0) to A"));
        }
    }

    @Nested
    @DisplayName("visibility")
    class Visibility {
        @Test
        @DisplayName("a cloaked opponent the viewer cannot see is neither drawn nor on radar; seen it is, with CLOAKED")
        void cloaked() {
            b.used |= Has.CLOAKING_DEVICE;
            a.canSee[b.slot] = false;
            List<String> hidden = records(a, Section.SHIP, true);
            List<String> radarHidden = records(a, Section.RADAR, true);
            a.canSee[b.slot] = true;
            List<String> seen = records(a, Section.SHIP, true);
            assertAll(
                () -> assertEquals(List.of(), hidden, "not drawn"),
                () -> assertEquals(2, radarHidden.size(), "radar: A itself and C only"),
                () -> assertEquals(1, seen.size(), "seen: drawn"),
                () -> assertTrue((Integer.parseInt(seen.get(0).split(" f")[1]) & Records.ShipFlags.CLOAKED) != 0,
                    "with the CLOAKED flag"));
        }

        @Test
        @DisplayName("radar shows missiles and mines in sensor range, nuclear ones flagged")
        void radarObjects() {
            make("minesOnRadar: yes\nnukesOnRadar: no\n");
            Objects o = w.objects;
            obj(Obj.TORPEDO, 300, 120);
            int m = obj(Obj.MINE, 320, 120);
            o.mods[m] = Mods.withNuclear(0, Mods.NUCLEAR);
            a.sensorRange = 500;
            obj(Obj.TORPEDO, 1000, 120);
            List<String> r = records(a, Section.RADAR, true);
            long missiles = r.stream().filter(s -> (Integer.parseInt(s.split(":")[1]) & Records.Radar.FLAG_MISSILE) != 0).count();
            long mines = r.stream().filter(s -> (Integer.parseInt(s.split(":")[1]) & Records.Radar.FLAG_MINE) != 0).count();
            assertAll(
                () -> assertEquals(1, missiles, "only the torpedo within 500 px"),
                () -> assertEquals(1, mines, "the mine (minesOnRadar)"));
        }
    }
}
