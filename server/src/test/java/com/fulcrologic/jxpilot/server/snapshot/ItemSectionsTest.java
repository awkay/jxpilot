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
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.net.Wire;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Ecms;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;
import com.fulcrologic.jxpilot.server.world.ShipGeom;
import com.fulcrologic.jxpilot.server.world.Trig;
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
            a.canSee[b.ind] = false;
            List<String> hidden = records(a, Section.SHIP, true);
            List<String> radarHidden = records(a, Section.RADAR, true);
            a.canSee[b.ind] = true;
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

    /** The snapshot RNG ItemSections draws from for viewer {@code v} this tick. */
    Rng snapshotRng(Player v) {
        return new Rng(w.tick * 0x9E3779B9 + v.id);
    }

    ItemSections sections() {
        return new ItemSections(w, new SnapshotBuilder(w));
    }

    void onlyProbs(Item lo, float pLo, Item hi, float pHi) {
        java.util.Arrays.fill(w.items.prob, 0f);
        w.items.prob[lo.ordinal()] = pLo;
        w.items.prob[hi.ordinal()] = pHi;
    }

    /** Only the radar blips of objects (missiles and mines), not players. */
    List<String> radarObjects(Player v) {
        List<String> out = new ArrayList<>();
        for (String s : records(v, Section.RADAR, true)) {
            if ((Integer.parseInt(s.split(":")[1]) & (Records.Radar.FLAG_MISSILE | Records.Radar.FLAG_MINE)) != 0) {
                out.add(s);
            }
        }
        return out;
    }

    String blip(double x, double y, int flags) {
        return Wire.toRadar8(x, w.pixelWidth) + "," + Wire.toRadar8(y, w.pixelHeight) + ":" + flags;
    }

    @Nested
    @DisplayName("randomItem (C item.c:133 Choose_random_item)")
    class RandomItem {
        @Test
        @DisplayName("draws kinds in proportion to their probabilities; a draw of exactly 0 gives item 0 as in C;"
            + " no probability at all gives NUM_ITEMS")
        void distribution() {
            onlyProbs(Item.CLOAK, 1f, Item.MINE, 3f);
            ItemSections is = sections();
            Rng same = new Rng(1);
            int cloaks = 0;
            int mines = 0;
            List<String> others = new ArrayList<>();
            for (int i = 0; i < 4000; i++) {
                float f = same.nextFloat();
                int k = is.randomItem();
                if (k == Item.CLOAK.ordinal()) {
                    cloaks++;
                } else if (k == Item.MINE.ordinal()) {
                    mines++;
                } else {
                    others.add(k + " for " + f);
                }
            }
            java.util.Arrays.fill(w.items.prob, 0f);
            int none = is.randomItem();
            int c = cloaks;
            int m = mines;
            // tolerance: 4000 draws at p = 1/4 have sd 27.4; 120 is over 4 sd (and the RNG is seeded)
            assertAll(
                () -> assertEquals(List.of(Item.FUEL.ordinal() + " for 0.0"), others,
                    "only kinds with a probability are drawn, except that the one draw of exactly 0 stops at item 0"
                        + " (C item.c:146-147: 0 - 0 <= 0)"),
                () -> assertTrue(Math.abs(c - 1000) < 120, "a quarter are cloaks (1 of 4): " + c),
                () -> assertTrue(Math.abs(m - 3000) < 120, "three quarters are mines (3 of 4): " + m),
                () -> assertEquals(Item.COUNT, none, "with every probability 0 the kind is NUM_ITEMS (C loop end)"));
        }

        @Test
        @DisplayName("random items in a snapshot draw from an RNG seeded by the tick and the viewer, the same every build")
        void seeded() {
            onlyProbs(Item.CLOAK, 1f, Item.MINE, 1f);
            for (int i = 0; i < 8; i++) {
                int h = obj(Obj.ITEM, 210 + 10 * i, 120);
                w.objects.info[h] = Item.ARMOR.ordinal();
                w.objects.status[h] |= Obj.RANDOM_ITEM;
            }
            List<String> forA = new ArrayList<>();
            List<String> forB = new ArrayList<>();
            Rng ra = snapshotRng(a);
            Rng rb = snapshotRng(b);
            for (int i = 0; i < 8; i++) {
                // CLOAK comes first in item order: 2f - 1 <= 0 picks it (C item.c:145-149)
                forA.add((210.0 + 10 * i) + ",120.0 " + (ra.nextFloat() <= 0.5f ? Item.CLOAK : Item.MINE).ordinal());
                forB.add((210.0 + 10 * i) + ",120.0 " + (rb.nextFloat() <= 0.5f ? Item.CLOAK : Item.MINE).ordinal());
            }
            List<String> first = records(a, Section.ITEM, false);
            List<String> second = records(a, Section.ITEM, false);
            List<String> seenByB = records(b, Section.ITEM, false);
            assertAll(
                () -> assertEquals(forA, first, "A's draws come from Rng(tick * 0x9E3779B9 + A's id)"),
                () -> assertEquals(forA, second, "rebuilding the same tick for A gives the same kinds"),
                () -> assertEquals(forB, seenByB, "B's draws come from Rng(tick * 0x9E3779B9 + B's id)"));
        }
    }

    @Nested
    @DisplayName("mine flags and controller (C frame.c:858-889)")
    class Mines {
        int mine(double x, int owner, int status, int mineOwner) {
            int h = obj(Obj.MINE, x, 120);
            w.objects.owner[h] = owner;
            w.objects.status[h] = status;
            w.objects.mineOwner[h] = mineOwner;
            return h;
        }

        @Test
        @DisplayName("friendly for a paused controller or the viewer's own fused mine; confused identified mines hide"
            + " the controller and draw their friendliness")
        void flags() {
            c.status |= Obj.PAUSE;
            int armed = Records.Mine.FLAG_ARMED;
            int friendly = Records.Mine.FLAG_FRIENDLY;
            mine(210, b.id, 0, a.id);
            mine(220, c.id, 0, c.id);
            mine(230, c.id, Obj.CONFUSED, c.id);
            mine(240, b.id, Obj.OWNERIMMUNE, a.id);
            for (int i = 0; i < 6; i++) {
                mine(250 + 10 * i, b.id, Obj.CONFUSED, b.id);
            }
            mine(310, Obj.NO_ID, 0, Obj.NO_ID);
            mine(420, b.id, Obj.CONFUSED | Obj.OWNERIMMUNE, a.id);
            mine(430, b.id, Obj.CONFUSED, b.id);
            List<String> m = records(a, Section.MINE, false);
            Rng r = snapshotRng(a);
            List<String> confused = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                // C frame.c:886: laid_by_team = rfrac() < 0.5f
                confused.add((250.0 + 10 * i) + ",120.0 f" + (armed | (r.nextFloat() < 0.5f ? friendly : 0)) + " o"
                    + Protocol.NO_ID);
            }
            assertAll(
                () -> assertEquals("210.0,120.0 f" + armed + " o" + b.id, m.get(0),
                    "a mine A once owned, no longer owner-immune: armed, not friendly"),
                () -> assertEquals("220.0,120.0 f" + (armed | friendly) + " o" + c.id, m.get(1),
                    "a paused controller's mine is friendly"),
                () -> assertEquals("230.0,120.0 f" + (armed | friendly) + " o" + c.id, m.get(2),
                    "a paused controller's confused mine stays friendly and identified"),
                () -> assertEquals("240.0,120.0 f" + friendly + " o" + b.id, m.get(3),
                    "A's own fused (owner-immune) mine: friendly, not armed"),
                () -> assertEquals(confused, m.subList(4, 10),
                    "confused identified mines: nobody shown, friendliness from the snapshot RNG"),
                () -> assertEquals("310.0,120.0 f" + armed + " o" + Protocol.NO_ID, m.get(10),
                    "an unowned mine shows nobody"),
                () -> assertEquals("420.0,120.0 f" + friendly + " o" + Protocol.NO_ID, m.get(11),
                    "out of sensing range a confused mine follows the normal rule: A's own fused mine is friendly"),
                () -> assertEquals("430.0,120.0 f" + armed + " o" + Protocol.NO_ID, m.get(12),
                    "out of sensing range a confused enemy mine is plainly hostile"));
        }

        @Test
        @DisplayName("identified strictly within SHIP_SZ + 130 + sensors * 30 px (C frame.c:866-870)")
        void range() {
            a.items[Item.SENSOR.ordinal()] = 2;
            mine(405, b.id, 0, b.id);
            mine(406, b.id, 0, b.id);
            List<String> two = records(a, Section.MINE, false);
            a.items[Item.SENSOR.ordinal()] = 0;
            List<String> none = records(a, Section.MINE, false);
            int armed = Records.Mine.FLAG_ARMED;
            assertAll(
                () -> assertEquals(List.of("405.0,120.0 f" + armed + " o" + b.id, "406.0,120.0 f" + armed + " o"
                    + Protocol.NO_ID), two, "two sensors: 16 + 130 + 60 = 206 px; 205 px is identified, 206 px is not"),
                () -> assertEquals(List.of("405.0,120.0 f" + armed + " o" + Protocol.NO_ID, "406.0,120.0 f" + armed
                    + " o" + Protocol.NO_ID), none, "no sensors: 146 px, neither is identified"));
        }
    }

    @Nested
    @DisplayName("writeLasers (C frame.c:921-979)")
    class Lasers {
        @Test
        @DisplayName("an expired pulse slot is not drawn; the direction is sent; drawn back from the end it is reversed")
        void geometry() {
            Pulses p = w.pulses;
            int i = p.add();
            p.x[i] = 250;
            p.y[i] = 120;
            p.dir[i] = 32;
            p.len[i] = 40;
            p.owner[i] = b.id;
            int j = p.add();
            p.x[j] = 1000;
            p.y[j] = 20;
            p.dir[j] = 48;
            p.len[j] = 300;
            p.owner[j] = b.id;
            int k = p.add();
            p.x[k] = 300;
            p.y[k] = 120;
            p.len[k] = 50;
            p.remove(k);
            List<String> l = records(a, Section.LASER, false);
            String[] back = l.get(1).split("[, ]");
            // C frame.c:952-953: end = start + (tcos, tsin) * len; 135 deg: (1000 - 212.13, 20 + 212.13)
            double ex = 1000 + Trig.tcos(48) * 300;
            double ey = 20 + Trig.tsin(48) * 300;
            assertAll(
                () -> assertEquals(2, l.size(), "the removed pulse left in the array past the count is not drawn"),
                () -> assertEquals("250.0,120.0 d32 l40 c" + Records.ObjColor.RED, l.get(0), "straight up, from its start"),
                // tolerance: positions go out in half pixels
                () -> assertEquals(ex, Double.parseDouble(back[0]), 0.5, "x of the end in view"),
                () -> assertEquals(ey, Double.parseDouble(back[1]), 0.5, "y of the end in view"),
                () -> assertEquals("d112", back[2], "direction reversed: 48 + 64 (C frame.c:961)"));
        }

        @Test
        @DisplayName("blue for a team-immune shooter, red for the viewer's own pulse without selfImmunity")
        void colors() {
            w.params.teamPlay = true;
            w.params.teamImmunity = true;
            a.team = 2;
            b.team = 2;
            Pulses p = w.pulses;
            int i = p.add();
            p.x[i] = 250;
            p.y[i] = 120;
            p.len[i] = 30;
            p.owner[i] = b.id;
            int j = p.add();
            p.x[j] = 260;
            p.y[j] = 120;
            p.len[j] = 30;
            p.owner[j] = a.id;
            List<String> l = records(a, Section.LASER, false);
            assertAll(
                () -> assertEquals("250.0,120.0 d0 l30 c" + Records.ObjColor.BLUE, l.get(0), "team mate's pulse: blue"),
                () -> assertEquals("260.0,120.0 d0 l30 c" + Records.ObjColor.RED, l.get(1),
                    "own pulse without selfImmunity: red"));
        }
    }

    @Nested
    @DisplayName("writeBeams (C frame.c:981-1076)")
    class Beams {
        String hull(Player t, int j, double x, double y, int kind) {
            return (double) (int) (t.px + t.ship.ptX[j][t.dir]) + "," + (double) (int) (t.py + t.ship.ptY[j][t.dir])
                + "-" + x + "," + y + " k" + kind;
        }

        @Test
        @DisplayName("a player's tractor beam: three lines from the target's first three hull points to the beamer")
        void tractor() {
            a.used |= Has.TRACTOR_BEAM;
            a.lockPlId = b.id;
            b.dir = 0;
            b.lockPlId = a.id;
            List<String> plain = records(a, Section.CONNECTOR, false);
            b.ship = new ShipGeom(ShipShape.parse("(SH: 10,0 0,10 -10,0 0,-10)(EN: -10,0)(MG: 10,0)"));
            List<String> diamond = records(a, Section.CONNECTOR, false);
            int k = Records.Connector.KIND_TRACTOR;
            assertAll(
                () -> assertEquals(List.of("415.0,130.0-200.0,120.0 k" + k, "391.0,138.0-200.0,120.0 k" + k,
                    "391.0,122.0-200.0,120.0 k" + k), plain,
                    "default ship points (15,0) (-9,8) (-9,-8) to A; B, locked on A without a tractor beam, draws none"),
                () -> assertEquals(List.of("410.0,130.0-200.0,120.0 k" + k, "400.0,140.0-200.0,120.0 k" + k,
                    "390.0,130.0-200.0,120.0 k" + k), diamond, "a four-point hull still gets three lines"));
        }

        @Test
        @DisplayName("no beam from a dead, paused, game-over or out-of-view beamer, to an absent or out-of-view target")
        void hidden() {
            a.used |= Has.TRACTOR_BEAM;
            a.lockPlId = b.id;
            int playing = a.status;
            a.status = playing & ~Obj.PLAYING;
            List<String> dead = records(a, Section.CONNECTOR, false);
            a.status = playing | Obj.PAUSE;
            List<String> paused = records(a, Section.CONNECTOR, false);
            a.status = playing | Obj.GAME_OVER;
            List<String> over = records(a, Section.CONNECTOR, false);
            a.status = playing;
            a.lockPlId = Obj.NO_ID;
            List<String> noTarget = records(a, Section.CONNECTOR, false);
            a.lockPlId = c.id;
            List<String> farTarget = records(a, Section.CONNECTOR, false);
            a.used &= ~Has.TRACTOR_BEAM;
            c.used |= Has.TRACTOR_BEAM;
            c.lockPlId = b.id;
            List<String> farBeamer = records(a, Section.CONNECTOR, false);
            b.used |= Has.TRACTOR_BEAM;
            b.lockPlId = a.id;
            List<String> fromB = records(a, Section.CONNECTOR, false);
            assertAll(
                () -> assertEquals(Obj.PLAYING, playing & Obj.PLAYING, "A starts playing"),
                () -> assertEquals(List.of(), dead, "a dead beamer draws none"),
                () -> assertEquals(List.of(), paused, "a paused beamer draws none"),
                () -> assertEquals(List.of(), over, "a game-over beamer draws none"),
                () -> assertEquals(List.of(), noTarget, "no lock target: none"),
                () -> assertEquals(List.of(), farTarget, "a target out of view: none"),
                () -> assertEquals(List.of(), farBeamer, "C, out of A's view, beaming B: none (C frame.c:1015)"),
                () -> assertEquals(3, fromB.size(), "B, in view, beaming A: three lines"),
                () -> assertEquals(hull(a, 0, 400.0, 130.0, Records.Connector.KIND_TRACTOR), fromB.get(0),
                    "from A's nose to B"));
        }

        @Test
        @DisplayName("a cannon's tractor or pressor beam goes to the cannon's position while its count runs")
        void cannon() {
            com.fulcrologic.jxpilot.common.map.Cannon can = w.map.cannons().get(0);
            double cx = (int) can.x();
            double cy = (int) can.y();
            b.dir = 0;
            w.cannonItems.tractorCount[0] = 1;
            w.cannonItems.tractorTarget[0] = b.id;
            List<String> tractor = records(a, Section.CONNECTOR, false);
            w.cannonItems.tractorIsPressor[0] = true;
            List<String> pressor = records(a, Section.CONNECTOR, false);
            w.cannonItems.tractorCount[0] = 0;
            List<String> idle = records(a, Section.CONNECTOR, false);
            w.cannonItems.tractorCount[0] = 1;
            w.cannonItems.tractorTarget[0] = Obj.NO_ID;
            List<String> noTarget = records(a, Section.CONNECTOR, false);
            w.cannonItems.tractorTarget[0] = c.id;
            List<String> farTarget = records(a, Section.CONNECTOR, false);
            int t = Records.Connector.KIND_TRACTOR;
            int p = Records.Connector.KIND_PRESSOR;
            assertAll(
                () -> assertEquals(List.of(hull(b, 0, cx, cy, t), hull(b, 1, cx, cy, t), hull(b, 2, cx, cy, t)), tractor,
                    "count 1: three tractor lines from B's hull to the cannon"),
                () -> assertEquals(List.of(hull(b, 0, cx, cy, p), hull(b, 1, cx, cy, p), hull(b, 2, cx, cy, p)), pressor,
                    "pressor kind"),
                () -> assertEquals(List.of(), idle, "count 0: none"),
                () -> assertEquals(List.of(), noTarget, "no such target: none"),
                () -> assertEquals(List.of(), farTarget, "a target out of view: none"));
        }

        @Test
        @DisplayName("transporter beams are drawn when either end is in view; expired or victimless ones are not")
        void transporters() {
            Ecms e = w.ecms;
            int t = e.addTransporter();
            e.transTarget[t] = c.id;
            e.transOwner[t] = a.id;
            int u = e.addTransporter();
            e.transTarget[u] = c.id;
            e.transOwner[u] = Obj.NO_ID;
            e.transX[u] = 1900;
            e.transY[u] = 100;
            int g = e.addTransporter();
            e.transTarget[g] = Obj.NO_ID;
            e.transOwner[g] = a.id;
            int x = e.addTransporter();
            e.transTarget[x] = b.id;
            e.transOwner[x] = a.id;
            e.removeTransporter(x);
            List<String> tr = records(a, Section.TRANSPORTER, false);
            assertAll(
                () -> assertEquals(List.of("1800.0,120.0-200.0,120.0"), tr,
                    "C (out of view) robbed by A (in view) is drawn; the far cannon beam, the victimless one and the"
                        + " expired one are not"));
        }
    }

    @Nested
    @DisplayName("writeRadarObjects (C frame.c:1100-1147)")
    class RadarObjects {
        void place() {
            a.sensorRange = 150;
            obj(Obj.TORPEDO, 300, 120);
            int n = obj(Obj.SMART_SHOT, 310, 120);
            w.objects.mods[n] = Mods.withNuclear(0, Mods.NUCLEAR);
            obj(Obj.MINE, 320, 120);
            int nm = obj(Obj.MINE, 330, 120);
            w.objects.mods[nm] = Mods.withNuclear(0, Mods.NUCLEAR);
            obj(Obj.HEAT_SHOT, 350, 120);
            obj(Obj.HEAT_SHOT, 351, 120);
        }

        List<String> with(boolean nukes, boolean missiles, boolean mines) {
            w.items.nukesOnRadar = nukes;
            w.items.missilesOnRadar = missiles;
            w.items.minesOnRadar = mines;
            return radarObjects(a);
        }

        @Test
        @DisplayName("missilesOnRadar/minesOnRadar pick the kinds; nukesOnRadar adds nuclear ones, flagged;"
            + " only within the sensor range")
        void options() {
            place();
            int mis = Records.Radar.FLAG_MISSILE;
            int min = Records.Radar.FLAG_MINE;
            int nuke = Records.Radar.FLAG_NUKE;
            List<String> missiles = with(false, true, false);
            List<String> mines = with(false, false, true);
            List<String> nukes = with(true, false, false);
            List<String> all = with(true, true, true);
            List<String> off = with(false, false, false);
            assertAll(
                () -> assertEquals(List.of(blip(300, 120, mis), blip(310, 120, mis), blip(350, 120, mis)), missiles,
                    "missiles only, nuclear not flagged without nukesOnRadar; 150 px is in range, 151 px is not"),
                () -> assertEquals(List.of(blip(320, 120, min), blip(330, 120, min)), mines, "mines only"),
                () -> assertEquals(List.of(blip(310, 120, mis | nuke), blip(330, 120, min | nuke)), nukes,
                    "nukesOnRadar alone: just the nuclear missile and mine, flagged"),
                () -> assertEquals(List.of(blip(300, 120, mis), blip(310, 120, mis | nuke), blip(320, 120, min),
                    blip(330, 120, min | nuke), blip(350, 120, mis)), all, "everything in range"),
                () -> assertEquals(List.of(), off, "no option: no object blips"));
        }
    }

    @Nested
    @DisplayName("shipShown (C frame.c:1025-1029)")
    class ShipShown {
        @Test
        @DisplayName("drawn when seen, the viewer itself, a team mate or the viewer's own tank")
        void rules() {
            ItemSections is = sections();
            a.canSee[a.ind] = false;
            a.canSee[b.ind] = false;
            a.canSee[c.ind] = false;
            boolean self = is.shipShown(a, a);
            boolean unseen = is.shipShown(b, a);
            a.canSee[b.ind] = true;
            boolean seen = is.shipShown(b, a);
            w.params.teamPlay = true;
            a.team = 3;
            c.team = 3;
            boolean mate = is.shipShown(c, a);
            w.params.teamPlay = false;
            boolean noTeamPlay = is.shipShown(c, a);
            c.tank = true;
            c.lockPlId = a.id;
            boolean ownTank = is.shipShown(c, a);
            c.lockPlId = b.id;
            boolean othersTank = is.shipShown(c, a);
            c.tank = false;
            c.lockPlId = a.id;
            boolean locking = is.shipShown(c, a);
            assertAll(
                () -> assertTrue(self, "the viewer's own ship"),
                () -> assertEquals(false, unseen, "an unseen (cloaked) opponent is hidden"),
                () -> assertTrue(seen, "a seen opponent is drawn"),
                () -> assertTrue(mate, "an unseen team mate is drawn"),
                () -> assertEquals(false, noTeamPlay, "the same team number without team play is no team"),
                () -> assertTrue(ownTank, "the viewer's own unseen tank is drawn"),
                () -> assertEquals(false, othersTank, "someone else's unseen tank is hidden"),
                () -> assertEquals(false, locking, "an unseen ship locked on the viewer is not a tank"));
        }
    }

    @Nested
    @DisplayName("onRadar (C frame.c:1159-1192)")
    class OnRadar {
        @Test
        @DisplayName("only playing ships, not paused or game over; the viewer always")
        void status() {
            ItemSections is = sections();
            w.items.playersOnRadar = true;
            int playing = b.status;
            boolean on = is.onRadar(b, a);
            b.status = playing & ~Obj.PLAYING;
            boolean dead = is.onRadar(b, a);
            b.status = playing | Obj.PAUSE;
            boolean paused = is.onRadar(b, a);
            b.status = playing | Obj.GAME_OVER;
            boolean over = is.onRadar(b, a);
            a.status = 0;
            boolean self = is.onRadar(a, a);
            assertAll(
                () -> assertTrue(on, "a playing, seen ship with playersOnRadar"),
                () -> assertEquals(false, dead, "not playing: off"),
                () -> assertEquals(false, paused, "paused: off"),
                () -> assertEquals(false, over, "game over: off"),
                () -> assertTrue(self, "the viewer is always on its own radar"));
        }

        @Test
        @DisplayName("opponents need playersOnRadar and to be seen; team mates and the own tank need neither")
        void friends() {
            ItemSections is = sections();
            w.items.playersOnRadar = false;
            a.canSee[b.ind] = false;
            a.canSee[c.ind] = false;
            boolean hidden = is.onRadar(b, a);
            w.params.teamPlay = true;
            a.team = 4;
            b.team = 4;
            boolean mate = is.onRadar(b, a);
            w.params.teamPlay = false;
            c.tank = true;
            c.lockPlId = a.id;
            boolean tank = is.onRadar(c, a);
            c.lockPlId = b.id;
            boolean otherTank = is.onRadar(c, a);
            w.items.playersOnRadar = true;
            boolean unseen = is.onRadar(b, a);
            a.canSee[b.ind] = true;
            boolean seen = is.onRadar(b, a);
            w.items.playersOnRadar = false;
            boolean seenNoOption = is.onRadar(b, a);
            assertAll(
                () -> assertEquals(false, hidden, "an unseen opponent without playersOnRadar is off"),
                () -> assertTrue(mate, "an unseen team mate is on"),
                () -> assertTrue(tank, "the viewer's own unseen tank is on"),
                () -> assertEquals(false, otherTank, "another player's tank is off"),
                () -> assertEquals(false, unseen, "playersOnRadar but unseen (cloaked): off"),
                () -> assertTrue(seen, "playersOnRadar and seen: on"),
                () -> assertEquals(false, seenNoOption, "seen but no playersOnRadar: off"));
        }

        @Test
        @DisplayName("limitedVisibility keeps ships within the sensor range (C frame.c:1175-1178)")
        void sensorRange() {
            ItemSections is = sections();
            w.items.playersOnRadar = true;
            a.sensorRange = 200;
            b.initPixels(400, 120);
            w.items.limitedVisibility = true;
            boolean edge = is.onRadar(b, a);
            b.initPixels(401, 120);
            boolean beyond = is.onRadar(b, a);
            w.items.limitedVisibility = false;
            boolean unlimited = is.onRadar(b, a);
            assertAll(
                () -> assertTrue(edge, "exactly at the sensor range (200 px): on"),
                () -> assertEquals(false, beyond, "201 px: off"),
                () -> assertTrue(unlimited, "without limitedVisibility the range does not matter"));
        }

        @Test
        @DisplayName("the compass-locked ship stays on the radar marked LOCKED (the client blinks it, C frame.c:1180)")
        void locked() {
            ItemSections is = sections();
            w.items.playersOnRadar = true;
            a.used |= Has.COMPASS;
            a.lockTagged = Player.LOCK_PLAYER;
            a.lockPlId = b.id;
            w.frames = 13;
            boolean shown = is.onRadar(b, a);
            boolean locked = is.radarLocked(b, a);
            boolean other = is.radarLocked(c, a);
            a.used &= ~Has.COMPASS;
            boolean noCompass = is.radarLocked(b, a);
            a.used |= Has.COMPASS;
            a.lockTagged = 0;
            boolean noLock = is.radarLocked(b, a);
            assertAll(
                () -> assertTrue(shown, "shown at frame 13 (13 % 5 = 3): the server no longer hides it"),
                () -> assertTrue(locked, "the compass lock is LOCKED"),
                () -> assertEquals(false, other, "an unlocked ship is not"),
                () -> assertEquals(false, noCompass, "without the compass it is not"),
                () -> assertEquals(false, noLock, "without a player lock it is not"));
        }

        @Test
        @DisplayName("radar friendliness without team play: only the viewer's own tank (C frame.c:1186 OWNS_TANK)")
        void friendly() {
            ItemSections is = sections();
            boolean other = is.radarFriendly(b, a);
            c.tank = true;
            c.lockPlId = a.id;
            boolean ownTank = is.radarFriendly(c, a);
            c.lockPlId = b.id;
            boolean othersTank = is.radarFriendly(c, a);
            assertAll(
                () -> assertEquals(false, other, "another player is not friendly"),
                () -> assertTrue(ownTank, "the viewer's own tank is friendly"),
                () -> assertEquals(false, othersTank, "someone else's tank is not"));
        }
    }
}
