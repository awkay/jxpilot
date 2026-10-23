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
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SnapshotBuilder")
class SnapshotBuilderTest {
    /** 60 blocks (2100 px) wide so a ship 1500 px away is outside the 640 px view. */
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

    @BeforeEach
    void setUp() {
        sim = TestWorlds.sim("framesPerSecond: 60\ngravity: 0", ROWS);
        w = sim.world;
        a = TestWorlds.join(sim, "A", 0);
        b = TestWorlds.join(sim, "B", 0);
        c = TestWorlds.join(sim, "C", 0);
        a.initPixels(200.5, 120.25);
        b.initPixels(400, 130);
        c.initPixels(1800, 120);
        a.dir = 10;
        b.dir = 40;
        b.vx = 2.5f;
        b.vy = -1.0f;
        b.used &= ~Has.SHIELD;
        a.used |= Has.SHIELD;
        a.fuel = 500.5 * 256;
        for (int i = 0; i < parts.length; i++) {
            parts[i] = ByteBuffer.allocate(1200);
        }
        w.tick = 42;
    }

    /** Decoded content of all parts. */
    static final class Decoded {
        int partCount;
        final List<int[]> headers = new ArrayList<>();
        final List<String> self = new ArrayList<>();
        final List<String> ships = new ArrayList<>();
        final Map<Integer, List<String>> shots = new TreeMap<>();
        final List<String> bursts = new ArrayList<>();
        final List<String> cannons = new ArrayList<>();
        final List<String> fuel = new ArrayList<>();
        final List<String> radar = new ArrayList<>();
    }

    Decoded decode(int n) {
        Decoded d = new Decoded();
        d.partCount = n;
        SnapshotReader r = new SnapshotReader();
        r.setWorld(w.pixelWidth, w.pixelHeight, w.wrap);
        Records.Self self = new Records.Self();
        Records.Ship ship = new Records.Ship();
        Records.Shot shot = new Records.Shot();
        Records.Burst burst = new Records.Burst();
        Records.CannonState cs = new Records.CannonState();
        Records.FuelState fs = new Records.FuelState();
        Records.Radar rd = new Records.Radar();
        DebrisBurst db = new DebrisBurst();
        for (int p = 0; p < n; p++) {
            ByteBuffer buf = parts[p].duplicate();
            buf.flip();
            assertTrue(r.begin(buf), "part " + p + " has a header");
            d.headers.add(new int[] {r.tick(), r.lastInputSeq(), r.part(), r.partCount(), r.flags()});
            while (r.nextSection()) {
                for (int i = 0; i < r.count(); i++) {
                    switch (r.type()) {
                        case Section.SELF -> {
                            self.wrap(r, i);
                            d.self.add(self.id() + " " + self.flags() + " " + self.x() + "," + self.y() + " v"
                                + self.vx() + "," + self.vy() + " h" + self.heading() + " f" + self.fuel() + "/"
                                + self.fuelMax());
                        }
                        case Section.SHIP -> {
                            ship.wrap(r, i);
                            d.ships.add(ship.id() + " " + ship.flags() + " " + ship.x() + "," + ship.y() + " v"
                                + ship.vx() + "," + ship.vy() + " h" + Math.round(ship.heading() * Const.RES / (2 * Math.PI)));
                        }
                        case Section.SHOT -> {
                            shot.wrap(r, i);
                            d.shots.computeIfAbsent(r.variant(), k -> new ArrayList<>())
                                .add(shot.x() + "," + shot.y() + " v" + shot.vx() + "," + shot.vy());
                        }
                        case Section.BURST -> {
                            burst.wrap(r, i);
                            burst.read(db);
                            d.bursts.add(db.x + "," + db.y + " n" + db.count + " k" + burst.kind() + " c" + burst.color()
                                + " age" + burst.age() + " seed" + db.seed);
                        }
                        case Section.CANNON -> {
                            cs.wrap(r, i);
                            d.cannons.add(cs.index() + ":" + cs.flags());
                        }
                        case Section.FUEL -> {
                            fs.wrap(r, i);
                            d.fuel.add(fs.index() + ":" + fs.fraction());
                        }
                        case Section.RADAR -> {
                            rd.wrap(r, i);
                            d.radar.add(rd.cellX() + "," + rd.cellY() + ":" + rd.flags());
                        }
                        default -> throw new AssertionError("unexpected section " + r.type());
                    }
                }
            }
            assertTrue(!r.malformed(), "part " + p + " parses cleanly");
        }
        return d;
    }

    @Nested
    @DisplayName("build: one viewer, everything fits")
    class SinglePart {
        @Test
        @DisplayName("round-trips self, ships in view, shots by colour, bursts, cannons, fuel and radar")
        void roundTrip() {
            int ownShot = sim.shots.fire(a, a.team, false, 230, 120, Obj.SHOT, 0, 0);
            int enemyShot = sim.shots.fire(b, b.team, false, 380, 140, Obj.SHOT, 64, 0);
            int cannonShot = sim.shots.fire(null, Const.TEAM_NOT_SET, true, 300, 100, Obj.CANNON_SHOT, 32, 0);
            int farShot = sim.shots.fire(c, c.team, false, 1800, 140, Obj.SHOT, 0, 0);
            a.vx = 0;
            a.fuel = 500.5 * 256;
            b.vx = 2.5f;
            b.vy = -1.0f;
            long burstFrom = w.events.burstSeq();
            sim.debris.make(250, 110, 1, 0, a.id, a.team, Obj.DEBRIS, 3.5f, Obj.GRAVITY, Records.ObjColor.RED,
                Records.BurstKind.DEBRIS, 8, 10, 10, 0, 127, 20, 30, 5, 20);
            w.tick = 44;
            sim.debris.make(1900, 110, 0, 0, Obj.NO_ID, 0, Obj.DEBRIS, 3.5f, Obj.GRAVITY, Records.ObjColor.RED,
                Records.BurstKind.DEBRIS, 8, 10, 10, 0, 127, 20, 30, 5, 20);
            w.tick = 46;
            sim.crashes.cannonDies(0, Obj.NO_ID);
            w.fuelLevel[0] = 250 * 256;
            DebrisBurst near = w.events.burst[(int) burstFrom];

            int n = new SnapshotBuilder(w).build(a, 77, burstFrom, true, parts);
            Decoded d = decode(n);
            float ax = (float) (a.cx / (double) Click.CLICK);
            float ay = (float) (a.cy / (double) Click.CLICK);
            float bx = (float) (b.cx / (double) Click.CLICK);
            float by = (float) (b.cy / (double) Click.CLICK);
            int own = Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.OWN);
            int enemy = Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.ENEMY);
            int cannon = Records.ShotKind.variant(Records.ShotKind.CANNON_SHOT, Records.ObjColor.ENEMY);
            assertAll(
                () -> assertEquals(1, n, "everything fits in one part"),
                () -> assertEquals(List.of(46, 77, 0, 1, SnapshotWriter.FLAG_LAST_PART).toString(),
                    toList(d.headers.get(0)).toString(),
                    "header: world.tick (the tick just simulated), input seq, part 0 of 1, last"),
                () -> assertEquals(List.of(a.id + " " + (Records.ShipFlags.SHIELD | Records.ShipFlags.FRIENDLY) + " "
                    + ax + "," + ay + " v0.0,0.0 h" + (float) (10 * 2 * Math.PI / Const.RES) + " f500.5/2600.0"),
                    d.self, "SELF has full-precision position, heading dir*2pi/RES and fuel in units"),
                () -> assertEquals(List.of(b.id + " 0 " + bx + "," + by + " v2.5,-1.0 h40"), d.ships,
                    "SHIP lists only the other ship in view (C is 1600 px away), without shield"),
                () -> assertEquals(1, d.shots.get(own).size(), "the viewer's shot is OWN"),
                () -> assertEquals(1, d.shots.get(enemy).size(), "another player's shot is ENEMY"),
                () -> assertEquals(1, d.shots.get(cannon).size(), "a cannon shot has the CANNON_SHOT kind"),
                () -> assertEquals(3, d.shots.values().stream().mapToInt(List::size).sum(), "the far shot is culled"),
                () -> assertEquals(List.of(w.objects.cx[ownShot] / 64f + "," + w.objects.cy[ownShot] / 64f + " v21,0"),
                    d.shots.get(own), "shot position and whole-pixel velocity"),
                () -> assertEquals(2, d.bursts.size(), "the bursts in view are sent (the far one is culled)"),
                () -> assertTrue(d.bursts.get(0).startsWith(near.x + "," + near.y + " n10 k" + Records.BurstKind.DEBRIS
                    + " c" + Records.ObjColor.RED + " age4 seed" + near.seed), "burst parameters, age = 46 - 42"),
                () -> assertTrue(d.bursts.get(1).contains(" k" + Records.BurstKind.DEBRIS + " c" + Records.ObjColor.RED
                    + " age0 "), "the dying cannon burst of this tick follows with age 0"),
                () -> assertEquals(List.of("0:" + Records.CannonState.FLAG_DEAD), d.cannons, "the dead cannon is flagged"),
                () -> assertEquals(1, d.fuel.size(), "the fuel station in view is sent"),
                () -> assertEquals(0.5, Float.parseFloat(d.fuel.get(0).substring(2)), 1.0 / 65535,
                    "fuel station level as a fraction of 500 (u16 precision)"),
                () -> assertEquals(3, d.radar.size(), "radar has every playing ship"),
                () -> assertTrue(d.radar.contains(radarCell(a) + ":" + Records.Radar.FLAG_SELF), "own blip is SELF"));
            assertTrue(enemyShot >= 0 && cannonShot >= 0 && farShot >= 0, "setup fired every shot");
        }

        @Test
        @DisplayName("radar is left out unless requested; dead ships are not listed")
        void noRadarNoDead() {
            b.status &= ~Obj.PLAYING;
            int n = new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), false, parts);
            Decoded d = decode(n);
            assertAll(
                () -> assertEquals(0, d.radar.size(), "no RADAR section without the radar flag"),
                () -> assertEquals(0, d.ships.size(), "a ship that is not playing is not in SHIP"),
                () -> assertEquals(1, d.self.size(), "SELF is always present"));
        }
    }

    String radarCell(Player p) {
        return com.fulcrologic.jxpilot.common.net.Wire.toRadar8(p.cx / 64.0, w.pixelWidth) + ","
            + com.fulcrologic.jxpilot.common.net.Wire.toRadar8(p.cy / 64.0, w.pixelHeight);
    }

    static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int v : a) {
            l.add(v);
        }
        return l;
    }

    @Nested
    @DisplayName("build: more than one datagram")
    class MultiPart {
        @Test
        @DisplayName("splits into self-contained parts with the same tick, numbered, last one flagged")
        void splits() {
            for (int i = 0; i < 400; i++) {
                sim.shots.fire(b, b.team, false, 300 + (i % 20) * 10, 100 + (i / 20), Obj.SHOT, i % 128, 0);
                b.shots = 0;
            }
            int n = new SnapshotBuilder(w).build(a, 5, w.events.burstSeq(), false, parts);
            Decoded d = decode(n);
            int total = d.shots.values().stream().mapToInt(List::size).sum();
            assertAll(
                () -> assertTrue(n >= 3, "400 shots of 6 bytes need at least 3 datagrams (" + n + ")"),
                () -> assertEquals(400, total, "every shot is sent exactly once across the parts"),
                () -> {
                    for (int p = 0; p < n; p++) {
                        int[] h = d.headers.get(p);
                        assertEquals(42, h[0], "part " + p + " has the snapshot tick (world.tick)");
                        assertEquals(p, h[2], "part " + p + " is numbered");
                        assertEquals(n, h[3], "part " + p + " has the part count");
                        assertEquals(p == n - 1 ? SnapshotWriter.FLAG_LAST_PART : 0, h[4], "only the last part is flagged");
                    }
                },
                () -> assertEquals(1, d.self.size(), "SELF is in the first part only"));
        }

        @Test
        @DisplayName("drops what does not fit in the given buffers")
        void drops() {
            for (int i = 0; i < 400; i++) {
                sim.shots.fire(b, b.team, false, 300 + (i % 20) * 10, 100 + (i / 20), Obj.SHOT, 0, 0);
                b.shots = 0;
            }
            ByteBuffer[] one = {ByteBuffer.allocate(1200)};
            int n = new SnapshotBuilder(w).build(a, 5, w.events.burstSeq(), false, one);
            parts[0] = one[0];
            Decoded d = decode(n);
            int total = d.shots.values().stream().mapToInt(List::size).sum();
            assertAll(
                () -> assertEquals(1, n, "one buffer gives one part"),
                () -> assertEquals(SnapshotWriter.FLAG_LAST_PART, d.headers.get(0)[4], "which is the last part"),
                () -> assertTrue(total > 100 && total < 400, "the shots that fit are sent (" + total + ")"));
        }
    }

    @Nested
    @DisplayName("shipFlags / shotVariant")
    class Flags {
        @Test
        @DisplayName("flags describe shield, thrust, death, pause, refuel and friendliness")
        void shipFlags() {
            SnapshotBuilder sb = new SnapshotBuilder(w);
            b.status |= Obj.THRUSTING | Obj.PAUSE | Obj.SELF_DESTRUCT;
            b.used |= Has.SHIELD | Has.REFUEL;
            int f = sb.shipFlags(b, a);
            b.status &= ~(Obj.PLAYING | Obj.THRUSTING | Obj.PAUSE | Obj.SELF_DESTRUCT);
            b.used &= ~(Has.SHIELD | Has.REFUEL);
            int g = sb.shipFlags(b, a);
            assertAll(
                () -> assertEquals(Records.ShipFlags.SHIELD | Records.ShipFlags.THRUSTING | Records.ShipFlags.PAUSED
                    | Records.ShipFlags.SELF_DESTRUCT | Records.ShipFlags.REFUELING, f, "each status maps to its flag"),
                () -> assertEquals(Records.ShipFlags.DEAD, g, "a ship that is not playing is DEAD"),
                () -> assertEquals(Records.ShipFlags.FRIENDLY, sb.shipFlags(a, a) & Records.ShipFlags.FRIENDLY,
                    "the viewer itself is FRIENDLY"));
        }

        @Test
        @DisplayName("team play: team mates' ships and shots are friendly")
        void teams() {
            Simulation ts = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nteamPlay: yes", "xxxxxxxxxx",
                "x 1 1 2  x", "x        x", "xxxxxxxxxx");
            World tw = ts.world;
            Player p1 = TestWorlds.join(ts, "p1", 1);
            Player p2 = TestWorlds.join(ts, "p2", 1);
            Player p3 = TestWorlds.join(ts, "p3", 2);
            SnapshotBuilder sb = new SnapshotBuilder(tw);
            int mate = ts.shots.fire(p2, p2.team, false, 100, 60, Obj.SHOT, 0, 0);
            int foe = ts.shots.fire(p3, p3.team, false, 100, 60, Obj.SHOT, 0, 0);
            int unowned = tw.objects.alloc();
            tw.objects.type[unowned] = Obj.SHOT;
            tw.objects.team[unowned] = 5;
            assertAll(
                () -> assertEquals(Records.ShipFlags.FRIENDLY, sb.shipFlags(p2, p1) & Records.ShipFlags.FRIENDLY,
                    "a team mate is FRIENDLY"),
                () -> assertEquals(0, sb.shipFlags(p3, p1) & Records.ShipFlags.FRIENDLY, "an opponent is not"),
                () -> assertEquals(Records.ShotKind.variant(0, Records.ObjColor.TEAM, true), sb.shotVariant(mate, p1),
                    "a team mate's shot is TEAM, and harmless with teamImmunity"),
                () -> assertEquals(Records.ShotKind.variant(0, Records.ObjColor.ENEMY), sb.shotVariant(foe, p1),
                    "an opponent's shot is ENEMY"),
                () -> assertEquals(Records.ShotKind.variant(0, Records.ObjColor.NEUTRAL), sb.shotVariant(unowned, p1),
                    "an unowned shot of another team is NEUTRAL"));
        }

        @Test
        @DisplayName("the harmless flag follows C frame.c: team immune, paused owner, unowned own team, selfImmunity")
        void harmless() {
            Simulation ts = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nteamPlay: yes\nteamImmunity: no",
                "xxxxxxxxxx", "x 1 1 2  x", "x        x", "xxxxxxxxxx");
            World tw = ts.world;
            Player p1 = TestWorlds.join(ts, "p1", 1);
            Player p2 = TestWorlds.join(ts, "p2", 1);
            Player p3 = TestWorlds.join(ts, "p3", 2);
            SnapshotBuilder sb = new SnapshotBuilder(tw);
            int mate = ts.shots.fire(p2, p2.team, false, 100, 60, Obj.SHOT, 0, 0);
            int foe = ts.shots.fire(p3, p3.team, false, 100, 60, Obj.SHOT, 0, 0);
            int own = ts.shots.fire(p1, p1.team, false, 100, 60, Obj.SHOT, 0, 0);
            int unownedMine = tw.objects.alloc();
            tw.objects.type[unownedMine] = Obj.CANNON_SHOT;
            tw.objects.team[unownedMine] = 1;
            int unownedOther = tw.objects.alloc();
            tw.objects.type[unownedOther] = Obj.CANNON_SHOT;
            tw.objects.team[unownedOther] = 2;
            boolean mateNoImmunity = Records.ShotKind.harmless(sb.shotVariant(mate, p1));
            boolean foeLive = Records.ShotKind.harmless(sb.shotVariant(foe, p1));
            boolean ownNoSelf = Records.ShotKind.harmless(sb.shotVariant(own, p1));
            p3.status |= Obj.PAUSE;
            boolean foePaused = Records.ShotKind.harmless(sb.shotVariant(foe, p1));
            tw.params.selfImmunity = true;
            boolean ownSelf = Records.ShotKind.harmless(sb.shotVariant(own, p1));
            assertAll(
                () -> assertFalse(mateNoImmunity, "a team mate's shot hurts without teamImmunity"),
                () -> assertFalse(foeLive, "an opponent's shot hurts"),
                () -> assertTrue(foePaused, "a paused owner's shot is harmless"),
                () -> assertFalse(ownNoSelf, "the own shot hurts without selfImmunity"),
                () -> assertTrue(ownSelf, "the own shot is harmless with selfImmunity"),
                () -> assertTrue(Records.ShotKind.harmless(sb.shotVariant(unownedMine, p1)),
                    "an unowned shot of the own team is harmless in team play"),
                () -> assertFalse(Records.ShotKind.harmless(sb.shotVariant(unownedOther, p1)),
                    "an unowned shot of another team hurts"));
        }

        @Test
        @DisplayName("cluster fragments have the CLUSTER kind")
        void cluster() {
            int h = w.objects.alloc();
            w.objects.type[h] = Obj.SHOT;
            w.objects.mods[h] = Obj.MOD_CLUSTER;
            w.objects.owner[h] = b.id;
            assertEquals(Records.ShotKind.variant(Records.ShotKind.CLUSTER, Records.ObjColor.ENEMY),
                new SnapshotBuilder(w).shotVariant(h, a), "cluster shots are CLUSTER kind");
        }
    }

    @Nested
    @DisplayName("culling")
    class Culling {
        int shotAt(double x, double y) {
            int h = w.objects.alloc();
            w.objects.type[h] = Obj.SHOT;
            w.objects.owner[h] = b.id;
            w.objects.life[h] = 10;
            w.objects.initPixels(h, x, y);
            return h;
        }

        @Test
        @DisplayName("objects up to the view half-size (640 px) on each axis are sent, farther ones are not; bursts get 400 px more")
        void edges() {
            a.initPixels(700, 120);
            shotAt(700 + 640, 120);
            shotAt(700 - 640, 120);
            shotAt(700 + 641, 120);
            shotAt(700 - 641, 120);
            w.objects.initPixels(shotAt(0, 0), 700, 120 + 640);
            w.objects.initPixels(shotAt(0, 0), 700, 150);
            w.objects.initPixels(shotAt(0, 0), 700, 151);
            w.objects.initPixels(shotAt(0, 0), 700, 90);
            w.objects.initPixels(shotAt(0, 0), 700, 89);
            SnapshotBuilder builder = new SnapshotBuilder(w);
            builder.setView(640, 30);
            long from = w.events.burstSeq();
            sim.debris.make(700 + 1040, 120, 0, 0, Obj.NO_ID, 0, Obj.DEBRIS, 1, 0, Records.ObjColor.RED,
                Records.BurstKind.DEBRIS, 1, 1, 1, 0, 1, 1, 1, 1, 1);
            sim.debris.make(700 - 1041, 120, 0, 0, Obj.NO_ID, 0, Obj.DEBRIS, 1, 0, Records.ObjColor.RED,
                Records.BurstKind.DEBRIS, 1, 1, 1, 0, 1, 1, 1, 1, 1);
            sim.debris.make(700, 120 + 431, 0, 0, Obj.NO_ID, 0, Obj.DEBRIS, 1, 0, Records.ObjColor.RED,
                Records.BurstKind.DEBRIS, 1, 1, 1, 0, 1, 1, 1, 1, 1);
            int n = builder.build(a, 0, from, false, parts);
            Decoded d = decode(n);
            List<String> sent = d.shots.values().iterator().next();
            assertAll(
                () -> assertEquals(4, sent.size(), "x at +-640 and y at +-30 are in, one pixel more is out: " + sent),
                () -> assertTrue(sent.contains("1340.0,120.0 v0,0"), "x +640 is in view"),
                () -> assertTrue(sent.contains("60.0,120.0 v0,0"), "x -640 is in view"),
                () -> assertTrue(sent.contains("700.0,150.0 v0,0"), "y +30 is in view"),
                () -> assertTrue(sent.contains("700.0,90.0 v0,0"), "y -30 is in view"),
                () -> assertEquals(1, d.bursts.size(), "a burst 1040 px away is in (640 + 400), 1041 and +431 in y are out"));
        }

        @Test
        @DisplayName("only live shots and cannon shots are SHOT records; debris and sparks are not")
        void kinds() {
            int dead = shotAt(250, 120);
            w.objects.life[dead] = 0;
            int debris = shotAt(250, 120);
            w.objects.type[debris] = Obj.DEBRIS;
            int spark = shotAt(250, 120);
            w.objects.type[spark] = Obj.SPARK;
            shotAt(260, 120);
            int n = new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), false, parts);
            Decoded d = decode(n);
            assertEquals(1, d.shots.values().stream().mapToInt(List::size).sum(), "one live shot is sent");
        }
    }

    @Nested
    @DisplayName("other fields")
    class Fields {
        @Test
        @DisplayName("a dead viewer's SELF carries the frames until respawn; a live one 0")
        void respawn() {
            Records.Self self = new Records.Self();
            SnapshotReader r = new SnapshotReader();
            a.status &= ~Obj.PLAYING;
            a.count = 41;
            new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), false, parts);
            ByteBuffer buf = parts[0].duplicate().flip();
            r.begin(buf);
            r.nextSection();
            int dead = self.wrap(r, 0).respawnFrames();
            a.status |= Obj.PLAYING;
            parts[0].clear();
            new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), false, parts);
            buf = parts[0].duplicate().flip();
            r.begin(buf);
            r.nextSection();
            int alive = self.wrap(r, 0).respawnFrames();
            assertAll(
                () -> assertEquals(41, dead, "41 frames left are sent as 41"),
                () -> assertEquals(0, alive, "a playing ship sends 0"));
        }

        @Test
        @DisplayName("unowned plain shots are NEUTRAL (variant 0), a live cannon is unflagged, team mates are friendly on radar")
        void misc() {
            int h = w.objects.alloc();
            w.objects.type[h] = Obj.SHOT;
            w.objects.life[h] = 5;
            w.objects.initPixels(h, 220, 120);
            int n = new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), true, parts);
            Decoded d = decode(n);
            assertAll(
                () -> assertEquals(List.of(0), List.copyOf(d.shots.keySet()), "the unowned shot is sent with variant 0"),
                () -> assertEquals(List.of("0:0"), d.cannons, "a live cannon has no flags"),
                () -> assertTrue(d.radar.stream().noneMatch(x -> x.endsWith(":" + Records.Radar.FLAG_FRIENDLY)),
                    "outside team play nobody is friendly on radar"));
            Simulation ts = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nteamPlay: yes", "xxxxxxxxxx",
                "x 1 1 2  x", "x        x", "xxxxxxxxxx");
            Player p1 = TestWorlds.join(ts, "p1", 1);
            Player p2 = TestWorlds.join(ts, "p2", 1);
            Player p3 = TestWorlds.join(ts, "p3", 2);
            ByteBuffer one = ByteBuffer.allocate(1200);
            new SnapshotBuilder(ts.world).build(p1, 0, 0, true, new ByteBuffer[] {one});
            SnapshotReader r = new SnapshotReader();
            r.setWorld(ts.world.pixelWidth, ts.world.pixelHeight, false);
            r.begin(one.flip());
            Records.Radar rd = new Records.Radar();
            List<Integer> flags = new ArrayList<>();
            while (r.nextSection()) {
                if (r.type() == Section.RADAR) {
                    for (int i = 0; i < r.count(); i++) {
                        flags.add(rd.wrap(r, i).flags());
                    }
                }
            }
            assertEquals(List.of(Records.Radar.FLAG_SELF, Records.Radar.FLAG_FRIENDLY, 0), flags,
                "radar: self, team mate friendly, opponent plain (p2 " + p2.id + ", p3 " + p3.id + ")");
        }

        @Test
        @DisplayName("a buffer too small for any record gives one empty, last part; I8: a record that does not fit drops only itself")
        void tiny() {
            ByteBuffer none = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + 7);
            int n = new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), true, new ByteBuffer[] {none});
            ByteBuffer small = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + 10);
            new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), true, new ByteBuffer[] {small});
            assertAll(
                () -> assertEquals(1, n, "one part"),
                () -> assertEquals(SnapshotWriter.HEADER_SIZE, none.position(), "only the header was written"),
                () -> assertEquals(SnapshotWriter.FLAG_LAST_PART, none.get(10), "flagged last"),
                () -> assertEquals(SnapshotWriter.HEADER_SIZE + 5 + 3, small.position(),
                    "SELF (38 bytes) did not fit, but a later 3-byte record (cannon) still did"),
                () -> assertEquals(Section.CANNON, small.get(SnapshotWriter.HEADER_SIZE), "the cannon section"));
        }
    }

    int shotAt(World wd, int owner, double x, double y) {
        int h = wd.objects.alloc();
        wd.objects.type[h] = Obj.SHOT;
        wd.objects.owner[h] = owner;
        wd.objects.life[h] = 10;
        wd.objects.initPixels(h, x, y);
        return h;
    }

    @Nested
    @DisplayName("budget, order and visibility (perf F1/F2, I3, I7)")
    class Budget {
        @Test
        @DisplayName("under saturation the fixed sections are all present and the nearest shots win the room")
        void saturation() {
            List<String> near = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int h = shotAt(w, b.id, 160 + i * 3, 100 + (i % 5) * 8);
                near.add(w.objects.cx[h] / 64f + "," + w.objects.cy[h] / 64f);
            }
            for (int i = 0; i < 1500; i++) {
                shotAt(w, b.id, 700 + (i % 100), 100 + (i / 100) * 2);
            }
            long burstFrom = w.events.burstSeq();
            sim.debris.make(250, 110, 1, 0, a.id, a.team, Obj.DEBRIS, 3.5f, Obj.GRAVITY, Records.ObjColor.RED,
                Records.BurstKind.DEBRIS, 8, 10, 10, 0, 127, 20, 30, 5, 20);
            ByteBuffer[] two = {ByteBuffer.allocate(1200), ByteBuffer.allocate(1200)};
            parts[0] = two[0];
            parts[1] = two[1];
            int n = new SnapshotBuilder(w).build(a, 0, burstFrom, true, two);
            Decoded d = decode(n);
            List<String> sent = new ArrayList<>();
            for (List<String> l : d.shots.values()) {
                for (String e : l) {
                    sent.add(e.substring(0, e.indexOf(' ')));
                }
            }
            int total = sent.size();
            assertAll(
                () -> assertEquals(2, n, "both datagrams are used"),
                () -> assertEquals(1, d.self.size(), "SELF is present"),
                () -> assertEquals(1, d.ships.size(), "the ship in view is present"),
                () -> assertEquals(1, d.cannons.size(), "the cannon is present"),
                () -> assertEquals(1, d.fuel.size(), "the fuel station is present"),
                () -> assertEquals(3, d.radar.size(), "the radar is present"),
                () -> assertEquals(1, d.bursts.size(), "the burst is present"),
                () -> assertTrue(total > 300 && total < 1530, "shots fill the rest, not everything: " + total),
                () -> assertTrue(sent.containsAll(near), "every near shot is sent (nearest first)"));
        }

        @Test
        @DisplayName("shots are collected only up to the room left, so a far variant written first cannot crowd out near shots")
        void mixedVariants() {
            int first = shotAt(w, Obj.NO_ID, 201, 121);
            w.objects.type[first] = Obj.CANNON_SHOT;
            List<String> near = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int h = shotAt(w, b.id, 160 + i * 2, 100 + (i % 5) * 8);
                near.add(w.objects.cx[h] / 64f + "," + w.objects.cy[h] / 64f);
            }
            for (int i = 0; i < 1500; i++) {
                int h = shotAt(w, Obj.NO_ID, 700 + (i % 100), 100 + (i / 100) * 2);
                w.objects.type[h] = Obj.CANNON_SHOT;
            }
            ByteBuffer[] two = {ByteBuffer.allocate(1200), ByteBuffer.allocate(1200)};
            parts[0] = two[0];
            parts[1] = two[1];
            Decoded d = decode(new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), false, two));
            List<String> sent = new ArrayList<>();
            for (List<String> l : d.shots.values()) {
                for (String e : l) {
                    sent.add(e.substring(0, e.indexOf(' ')));
                }
            }
            assertAll(
                () -> assertEquals(2, d.shots.size(), "(fixture: two shot variants, the cannon one written first)"),
                () -> assertTrue(sent.containsAll(near), "every near shot of the second variant is sent"));
        }

        @Test
        @DisplayName("I7: paused ships are sent with PAUSED; dead (being carried home) and game-over ships are not")
        void pausedShips() {
            b.status = Obj.PAUSE | Obj.GRAVITY;
            Player d1 = c;
            d1.initPixels(260, 120);
            d1.status = Obj.GRAVITY;
            int n = new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), true, parts);
            Decoded d = decode(n);
            b.status = Obj.PLAYING | Obj.GAME_OVER;
            parts[0].clear();
            Decoded g = decode(new SnapshotBuilder(w).build(a, 0, w.events.burstSeq(), false, parts));
            assertAll(
                () -> assertEquals(1, d.ships.size(), "only the paused ship B (C is dead)"),
                () -> assertTrue(d.ships.get(0).startsWith(b.id + " " + (Records.ShipFlags.PAUSED | Records.ShipFlags.DEAD)
                    + " "), "B is flagged PAUSED (and DEAD: not playing): " + d.ships.get(0)),
                () -> assertEquals(1, d.radar.size(), "radar shows only playing ships (C frame.c), here A itself"),
                () -> assertEquals(0, g.ships.size(), "a GAME_OVER ship is not sent"));
        }

        @Test
        @DisplayName("I3: on a wrapping map a shot across the seam is in view")
        void wrapCull() {
            Simulation ws = TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nedgeWrap: yes", ROWS);
            World ww = ws.world;
            Player v = TestWorlds.join(ws, "V", 0);
            v.initPixels(20, 120);
            shotAt(ww, Obj.NO_ID, ww.pixelWidth - 20, 120);
            shotAt(ww, Obj.NO_ID, 1000, 120);
            ByteBuffer[] one = {ByteBuffer.allocate(1200)};
            parts[0] = one[0];
            World keep = w;
            w = ww;
            Decoded d = decode(new SnapshotBuilder(ww).build(v, 0, ww.events.burstSeq(), false, one));
            w = keep;
            int shots = d.shots.values().stream().mapToInt(List::size).sum();
            assertAll(
                () -> assertEquals(1, shots, "the shot 40 px away across the seam is sent, the one 980 px away is not"),
                () -> assertEquals((ww.pixelWidth - 20) + ".0,120.0",
                    d.shots.values().iterator().next().get(0).substring(0, d.shots.values().iterator().next().get(0).indexOf(' ')),
                    "at its true position"));
        }

        @Test
        @DisplayName("I3: bursts older than the ring are skipped (the cursor is clamped to the oldest readable burst)")
        void burstClamp() {
            for (int i = 0; i < com.fulcrologic.jxpilot.server.world.Events.BURSTS; i++) {
                com.fulcrologic.jxpilot.common.math.DebrisBurst db = w.events.addBurst(0, 0, -1, 42);
                db.x = 1500;
                db.y = 100;
            }
            for (int i = 0; i < 10; i++) {
                com.fulcrologic.jxpilot.common.math.DebrisBurst db = w.events.addBurst(0, 0, -1, 42);
                db.x = 210;
                db.y = 120;
                db.count = 1;
            }
            Decoded d = decode(new SnapshotBuilder(w).build(a, 0, 0, false, parts));
            assertEquals(10, d.bursts.size(), "each in-view burst once (a stale cursor 0 would revisit the ring)");
        }

        @Test
        @DisplayName("the shot hash is rebuilt once per frame; beginRound picks up objects changed within the frame")
        void rounds() {
            SnapshotBuilder sb = new SnapshotBuilder(w);
            sb.build(a, 0, w.events.burstSeq(), false, parts);
            shotAt(w, b.id, 210, 120);
            parts[0].clear();
            int stale = decode(sb.build(a, 0, w.events.burstSeq(), false, parts)).shots.size();
            sb.beginRound();
            parts[0].clear();
            int fresh = decode(sb.build(a, 0, w.events.burstSeq(), false, parts)).shots.size();
            w.frames++;
            shotAt(w, b.id, 220, 120);
            parts[0].clear();
            int next = decode(sb.build(a, 0, w.events.burstSeq(), false, parts)).shots.values().iterator().next().size();
            assertAll(
                () -> assertEquals(0, stale, "within the frame the hash is not rebuilt"),
                () -> assertEquals(1, fresh, "beginRound rebuilds it"),
                () -> assertEquals(2, next, "a new frame rebuilds it automatically"));
        }
    }
}
