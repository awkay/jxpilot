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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Records.ShipFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.WorldFixture.at;
import static org.junit.jupiter.api.Assertions.*;

class ClientWorldTest {
    /** Local times are rounded to whole nanoseconds, so the render tick is off by up to ~1e-7 ticks. */
    static final double CLOCK_EPS = 1e-3;
    /** Wire angles are u16 (9.6e-5 rad per step). */
    static final double ANGLE_EPS = 1e-3;

    static double deg(double d) {
        return Math.toRadians(d);
    }

    @Nested
    @DisplayName("ingest")
    class Ingest {
        @Test
        @DisplayName("classifies new, part, duplicate, late, stale, malformed and unconfigured snapshots")
        void results() {
            WorldFixture f = new WorldFixture(true);
            int newTick = f.begin(100, 0, 2).ingestAtTick(100);
            int part = f.begin(100, 1, 2).ingestAtTick(100);
            int dup = f.begin(100, 1, 2).ingestAtTick(100);
            f.begin(104).ingestAtTick(104);
            int late = f.begin(102).ingestAtTick(102);
            int stale = f.begin(99).ingestAtTick(99);
            int dupLate = f.begin(102).ingestAtTick(102);
            f.buf.clear().putInt(5).flip();
            int malformed = f.world.ingest(f.buf, at(105));
            ClientWorld fresh = new ClientWorld(new Players());
            f.begin(1);
            f.buf.flip();
            int noMap = fresh.ingest(f.buf, 0);
            assertAll(
                () -> assertEquals(ClientWorld.NEW_TICK, newTick, "first tick is new"),
                () -> assertEquals(ClientWorld.PART_ADDED, part, "second part of the same tick merges"),
                () -> assertEquals(ClientWorld.DUPLICATE, dup, "a repeated part is ignored"),
                () -> assertEquals(ClientWorld.LATE_TICK, late, "an older tick newer than the oldest is inserted"),
                () -> assertEquals(ClientWorld.STALE, stale, "a tick older than a full ring is dropped"),
                () -> assertEquals(ClientWorld.DUPLICATE, dupLate, "the inserted late tick is now known"),
                () -> assertEquals(ClientWorld.MALFORMED, malformed, "a body shorter than the header is malformed"),
                () -> assertEquals(1, f.world.malformedSnapshots(), "malformed bodies are counted"),
                () -> assertEquals(ClientWorld.NO_MAP, noMap, "nothing is ingested before the map"),
                () -> assertEquals(104, f.world.newestTick(), "late ticks do not change the newest tick"));
        }

        @Test
        @DisplayName("a truncated section keeps the sections before it")
        void truncatedSection() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).cannon(0, Records.CannonState.FLAG_DEAD).ship(9, 0, 10, 10, 0, 0, 0);
            f.buf.position(f.buf.position() - 3);
            int r = f.ingest(at(100));
            assertAll(
                () -> assertEquals(ClientWorld.MALFORMED, r, "reported malformed"),
                () -> assertEquals(1, f.world.malformedSnapshots(), "and counted"),
                () -> assertEquals(Records.CannonState.FLAG_DEAD, f.world.cannonFlags(0), "the cannon section applied"));
        }
    }

    @Nested
    @DisplayName("interpolation between snapshots")
    class Interpolation {
        WorldFixture twoSnapshots(boolean wrap, double ax, double bx) {
            WorldFixture f = new WorldFixture(wrap);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, ShipFlags.SHIELD, ax, 100, deg(350), 40, 0).ship(7, 0, 50, 50, 0, 0, 0)
                .shot(200, 150, 4, -2).ingestAtTick(100);
            f.begin(102).ship(9, ShipFlags.THRUSTING, bx, 100, deg(10), 40, 0).ship(11, 0, 300, 200, deg(90), 0, 0)
                .ship(7, 0, 50, 50, 0, 0, 0).shot(600, 300, 0, 0).ingestAtTick(102);
            f.players.setSelfId(7);
            return f;
        }

        @Test
        @DisplayName("crossing the wrap seam between snapshots moves the short way")
        void seam() {
            WorldFixture f = twoSnapshots(true, 690, 10);
            ClientWorld w = f.world;
            w.update(at(100.5));
            double rt1 = w.renderTick();
            float x1 = w.shipX(0);
            float h1 = w.shipHeading(0);
            int flags1 = w.shipFlags(0);
            int n = w.shipCount();
            w.update(at(101.5));
            assertAll(
                () -> assertEquals(100.5, rt1, CLOCK_EPS, "render tick = server tick - delay (0)"),
                () -> assertEquals(2, n, "ships 9 and 11; the own ship 7 is not listed"),
                () -> assertEquals(9, w.shipId(0), "B's order is kept"),
                () -> assertEquals(695.0, x1, CLOCK_EPS, "t = 0.25: 690 + 0.25 * (+20 across the seam)"),
                () -> assertEquals(5.0, w.shipX(0), CLOCK_EPS, "t = 0.75: 705 wraps to 5"),
                () -> assertEquals(100.0, w.shipY(0), CLOCK_EPS, "y unchanged"),
                () -> assertEquals(deg(355), h1, ANGLE_EPS, "heading 350 -> 10 degrees goes through 0: 355 at t=0.25"),
                () -> assertEquals(deg(5), w.shipHeading(0), ANGLE_EPS, "heading 5 degrees at t=0.75"),
                () -> assertEquals(ShipFlags.SHIELD, flags1, "flags of the nearer frame A at t=0.25"),
                () -> assertEquals(ShipFlags.THRUSTING, w.shipFlags(0), "flags of the nearer frame B at t=0.75"),
                () -> assertEquals(11, w.shipId(1), "a ship only in B is drawn"),
                () -> assertEquals(300.0, w.shipX(1), 1e-6, "at B's position"),
                () -> assertEquals(deg(90), w.shipHeading(1), ANGLE_EPS, "with B's heading"));
        }

        @Test
        @DisplayName("without wrap the delta is not folded")
        void noWrap() {
            WorldFixture f = twoSnapshots(false, 100, 140);
            f.world.update(at(100.5));
            assertEquals(110.0, f.world.shipX(0), CLOCK_EPS, "t = 0.25 of +40");
        }

        @Test
        @DisplayName("a ship that jumps farther than TELEPORT_PX is snapped to B")
        void teleport() {
            WorldFixture f = twoSnapshots(false, 100, 600);
            f.world.update(at(100.5));
            assertEquals(600.0, f.world.shipX(0), 1e-6, "snapped to B, not interpolated");
        }

        @Test
        @DisplayName("shots are extrapolated from A by their whole-pixel velocity")
        void shots() {
            WorldFixture f = twoSnapshots(true, 690, 10);
            f.world.update(at(101));
            assertAll(
                () -> assertEquals(1, f.world.shotCount(), "A's one shot"),
                () -> assertEquals(201.0, f.world.shotX(0), CLOCK_EPS, "x + 4 px/frame * 0.25 frames"),
                () -> assertEquals(149.5, f.world.shotY(0), CLOCK_EPS, "y - 2 px/frame * 0.25 frames"),
                () -> assertEquals(Records.ShotKind.CANNON_SHOT, f.world.shotKind(0), "kind from the variant"),
                () -> assertEquals(Records.ObjColor.TEAM, f.world.shotColor(0), "colour from the variant"));
        }

        @Test
        @DisplayName("before the oldest snapshot the oldest is drawn as is")
        void beforeOldest() {
            WorldFixture f = twoSnapshots(true, 690, 10);
            f.world.update(at(99));
            assertAll(
                () -> assertEquals(690.0, f.world.shipX(0), 1e-6, "ship at tick 100's position"),
                () -> assertEquals(ShipFlags.SHIELD, f.world.shipFlags(0), "with tick 100's flags"),
                () -> assertEquals(200.0, f.world.shotX(0), 1e-6, "shots of tick 100 not moved backwards"));
        }
    }

    @Nested
    @DisplayName("extrapolation")
    class Extrapolation {
        @Test
        @DisplayName("past the newest snapshot ships move by velocity, at most two snapshot intervals")
        void ships() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 100, 0, 8, -4).ingestAtTick(100);
            f.world.update(at(101));
            float x1 = f.world.shipX(0);
            float y1 = f.world.shipY(0);
            f.world.update(at(110));
            assertAll(
                () -> assertEquals(102.0, x1, CLOCK_EPS, "1 tick = 0.25 frames at 8 px/frame"),
                () -> assertEquals(99.0, y1, CLOCK_EPS, "-4 px/frame"),
                () -> assertEquals(108.0, f.world.shipX(0), CLOCK_EPS, "capped at 4 ticks (1 frame)"));
        }

        @Test
        @DisplayName("the own ship is drawn at the render tick: the later SELF as is, the latest extrapolated (capped)")
        void self() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).self(7, 0, 100, 50, 4, 8, 1.5f).ingestAtTick(100);
            ClientWorld w = f.world;
            w.update(at(102));
            float x = w.selfX();
            float y = w.selfY();
            w.update(at(130));
            assertAll(
                () -> assertTrue(w.hasSelf(), "own ship present"),
                () -> assertEquals(7, w.selfId(), "own id"),
                () -> assertEquals(100.0, x, CLOCK_EPS, "render tick 98.8 is before tick 100: drawn at tick 100"),
                () -> assertEquals(50.0, y, CLOCK_EPS, "y as sent"),
                () -> assertEquals(104.0, w.selfX(), CLOCK_EPS, "render tick 126.8: extrapolated, capped at 4 ticks"),
                () -> assertEquals(58.0, w.selfY(), CLOCK_EPS, "8 px/frame for 1 frame"),
                () -> assertEquals(1.5f, w.selfHeading(), "full-precision heading"),
                () -> assertEquals(700f, w.selfFuel(), "fuel"),
                () -> assertEquals(1000f, w.selfFuelMax(), "fuel max"),
                () -> assertEquals(2, w.selfNumTanks(), "tanks"),
                () -> assertEquals(1, w.selfCurrentTank(), "current tank"),
                () -> assertEquals(Protocol.NO_ID, w.selfLockId(), "no lock"));
        }

        @Test
        @DisplayName("the own ship is interpolated between the SELF records around the render tick")
        void selfInterpolated() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).self(7, 0, 100, 50, 4, 8, 1.5f).ingestAtTick(100);
            f.begin(102).self(7, ShipFlags.SHIELD, 104, 54, 6, 8, 1.7f).ingestAtTick(102);
            ClientWorld w = f.world;
            w.update(at(104.2));
            float x = w.selfX();
            float y = w.selfY();
            float h = w.selfHeading();
            float vx = w.selfVx();
            w.update(at(104.8));
            assertAll(
                () -> assertEquals(102.0, x, CLOCK_EPS, "halfway between 100 and 104"),
                () -> assertEquals(52.0, y, CLOCK_EPS, "halfway between 50 and 54"),
                () -> assertEquals(1.6f, h, 1e-5f, "heading halfway"),
                () -> assertEquals(5.0f, vx, 1e-5f, "velocity halfway"),
                () -> assertEquals(103.2, w.selfX(), CLOCK_EPS, "render tick 101.6: 0.8 of the way"),
                () -> assertEquals(ShipFlags.SHIELD, w.selfFlags(), "flags from the nearer (later) record"));
        }

        @Test
        @DisplayName("a dead own ship is not interpolated or extrapolated: it snaps to the nearer record and stays put")
        void selfDead() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).self(7, 0, 100, 50, 4, 8, 1.5f).ingestAtTick(100);
            f.begin(102).self(7, ShipFlags.DEAD, 110, 50, 4, 8, 1.5f).ingestAtTick(102);
            ClientWorld w = f.world;
            w.update(at(104.6));
            float near = w.selfX();
            w.update(at(106));
            assertAll(
                () -> assertEquals(110.0, near, CLOCK_EPS, "render tick 101.4: snapped to the nearer, dead record"),
                () -> assertEquals(110.0, w.selfX(), CLOCK_EPS, "after the last record: stays put while dead"));
        }

        @Test
        @DisplayName("a teleport (respawn) between SELF records snaps instead of sliding across the map")
        void selfTeleport() {
            WorldFixture f = new WorldFixture(false);
            f.begin(100).self(7, 0, 10, 50, 0, 0, 0).ingestAtTick(100);
            f.begin(102).self(7, 0, 600, 300, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(104.0));
            float early = f.world.selfX();
            f.world.update(at(104.6));
            assertAll(
                () -> assertEquals(10.0, early, CLOCK_EPS, "t = 0.4: the earlier record, no slide"),
                () -> assertEquals(600.0, f.world.selfX(), CLOCK_EPS, "t = 0.7: the later record, no slide"));
        }

        @Test
        @DisplayName("nothing is output before the first snapshot")
        void empty() {
            WorldFixture f = new WorldFixture(true);
            f.world.update(at(100));
            assertAll(
                () -> assertFalse(f.world.hasSelf(), "no own ship"),
                () -> assertEquals(0, f.world.shipCount(), "no ships"),
                () -> assertFalse(f.world.hasSnapshot(), "no snapshot"));
        }
    }

    @Nested
    @DisplayName("map object state, radar and items")
    class MapState {
        @Test
        @DisplayName("applied from the newest tick only; radar replaced per tick and merged across parts")
        void newestOnly() {
            WorldFixture f = new WorldFixture(true);
            ClientWorld w = f.world;
            boolean initialAlive = w.cannonFlags(0) == 0;
            float initialFuel = w.fuelLevel(0);
            f.begin(100).radar(100, 100, Records.Radar.FLAG_SELF).radar(300, 200, 0).ingestAtTick(100);
            int radar100 = w.radarCount();
            f.begin(104, 0, 2).cannon(0, Records.CannonState.FLAG_DEAD).fuel(0, 0.5).radar(10, 10, 0)
                .items(0, 2, 0, 3).ingestAtTick(104);
            int radar104 = w.radarCount();
            f.begin(104, 1, 2).radar(20, 20, Records.Radar.FLAG_FRIENDLY).ingestAtTick(104);
            f.begin(102).cannon(0, 0).fuel(0, 1).radar(1, 1, 0).ingestAtTick(102);
            assertAll(
                () -> assertTrue(initialAlive, "cannons start alive"),
                () -> assertEquals(1f, initialFuel, "fuel stations start full"),
                () -> assertEquals(2, radar100, "two blips at tick 100"),
                () -> assertEquals(1, radar104, "a new radar tick replaces the blips"),
                () -> assertEquals(2, w.radarCount(), "a second part appends; the late tick is ignored"),
                () -> assertEquals(Records.Radar.FLAG_FRIENDLY, w.radarFlags(1), "the appended blip"),
                () -> assertEquals(Records.CannonState.FLAG_DEAD, w.cannonFlags(0), "late tick cannot revive the cannon"),
                () -> assertEquals(0.5f, w.fuelLevel(0), 1e-4, "fuel from the newest tick (u16 fraction)"),
                () -> assertEquals(2, w.selfItem(Item.WIDEANGLE.ordinal()), "item counts"),
                () -> assertEquals(3, w.selfItem(Item.AFTERBURNER.ordinal()), "item counts by ordinal"));
        }

        @Test
        @DisplayName("base owners are set by index; bad indices are ignored")
        void baseOwner() {
            WorldFixture f = new WorldFixture(true);
            f.world.setBaseOwner(0, 7);
            f.world.setBaseOwner(5, 9);
            f.world.setBaseOwner(-1, 9);
            assertAll(
                () -> assertEquals(1, f.map.bases().size(), "the fixture has one base"),
                () -> assertEquals(7, f.world.baseOwner(0), "owner set"));
        }
    }

    @Nested
    @DisplayName("bursts and thrust sparks")
    class Particles {
        record P(float vx, float vy, int life) {}

        @Test
        @DisplayName("a BURST record expands into exactly the generator's particles, born at tick - age")
        void burst() {
            WorldFixture f = new WorldFixture(true);
            DebrisBurst d = WorldFixture.burst();
            f.begin(100).burst(d, Records.BurstKind.DEBRIS, Records.ObjColor.WHITE, 3).ingestAtTick(100);
            ParticlePool pool = f.world.particles();
            DebrisBurst e = WorldFixture.burst();
            e.hypotenuse = (float) Math.sqrt(700 * 700 + 350 * 350);
            List<P> expected = new ArrayList<>();
            e.generate(new Rng(0), (i, vx, vy, s, life) -> expected.add(new P(vx, vy, life)));
            List<P> got = new ArrayList<>();
            for (int i = 0; i < pool.count(); i++) {
                got.add(new P(pool.vx(i), pool.vy(i), (int) pool.life(i)));
            }
            assertAll(
                () -> assertEquals(expected, got, "same velocities and lives in order"),
                () -> assertEquals(97.0, pool.birth(0), "born at tick 100 - age 3"),
                () -> assertEquals(Records.ObjColor.WHITE, pool.color(0), "colour from the record"),
                () -> assertEquals(Records.BurstKind.DEBRIS, pool.kind(0), "kind from the record"),
                () -> assertEquals(300f, pool.originX(0), "origin x from the record"),
                () -> assertEquals(100f, pool.originY(0), "origin y from the record"));
        }

        int sparksFor(int fromTick, int toTick, int snapTick, float x, float y, float vx) {
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 0.25f, 700, 350, true);
            ParticlePool p = new ParticlePool(4096);
            for (int t = fromTick; t <= toTick; t++) {
                double back = (snapTick - t) * 0.25;
                em.emit(t, 9, x - vx * back, y, 0, vx, 0, 50, 2, com.fulcrologic.jxpilot.common.math.ShipShape.defaultShip().engineX,
                    com.fulcrologic.jxpilot.common.math.ShipShape.defaultShip().engineY, false, p);
            }
            return p.count();
        }

        @Test
        @DisplayName("thrusting ships emit sparks for every tick after the previous newest, once")
        void sparks() {
            WorldFixture f = new WorldFixture(true);
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 9;
            j.power = 50;
            j.afterburners = 2;
            f.players.join(j);
            ParticlePool pool = f.world.particles();
            f.begin(100).ship(9, ShipFlags.THRUSTING, 200, 100, 0, 8, 0).ingestAtTick(100);
            int first = pool.count();
            double minBirth = 1e9;
            double maxBirth = -1e9;
            for (int i = 0; i < pool.count(); i++) {
                minBirth = Math.min(minBirth, pool.birth(i));
                maxBirth = Math.max(maxBirth, pool.birth(i));
            }
            f.begin(102).ship(9, ShipFlags.THRUSTING, 204, 100, 0, 8, 0).ingestAtTick(102);
            int second = pool.count() - first;
            int beforeLate = pool.count();
            f.begin(101).ship(9, ShipFlags.THRUSTING, 202, 100, 0, 8, 0).ingestAtTick(101);
            int afterLate = pool.count();
            f.begin(120).ship(9, ShipFlags.THRUSTING, 300, 100, 0, 8, 0).ingestAtTick(120);
            int gap = pool.count() - afterLate;
            f.begin(122).ship(9, ShipFlags.THRUSTING | ShipFlags.DEAD, 300, 100, 0, 8, 0).ingestAtTick(122);
            int dead = pool.count() - afterLate - gap;
            double lo = minBirth;
            double hi = maxBirth;
            assertAll(
                () -> assertEquals(sparksFor(99, 100, 100, 200, 100, 8), first,
                    "the first snapshot covers one interval: ticks 99 and 100"),
                () -> assertEquals(99.0, lo, "earliest spark born at tick 99"),
                () -> assertEquals(100.0, hi, "latest spark born at tick 100"),
                () -> assertEquals(sparksFor(101, 102, 102, 204, 100, 8), second, "the next snapshot adds ticks 101-102"),
                () -> assertEquals(beforeLate, afterLate, "a late tick adds no sparks"),
                () -> assertEquals(sparksFor(113, 120, 120, 300, 100, 8), gap, "after a gap only the last 8 ticks"),
                () -> assertEquals(0, dead, "dead ships emit nothing"),
                () -> assertTrue(first > 0, "sparks were produced"));
        }

        @Test
        @DisplayName("the own ship's sparks come from SELF, not again from its SHIP record")
        void selfSparks() {
            WorldFixture f = new WorldFixture(true);
            f.players.setSelfId(7);
            f.begin(100).self(7, ShipFlags.THRUSTING, 200, 100, 8, 0, 0).ingestAtTick(100);
            int selfOnly = f.world.particles().count();
            WorldFixture g = new WorldFixture(true);
            g.players.setSelfId(7);
            g.begin(100).self(7, ShipFlags.THRUSTING, 200, 100, 8, 0, 0)
                .ship(7, ShipFlags.THRUSTING, 200, 100, 0, 8, 0).ingestAtTick(100);
            assertAll(
                () -> assertTrue(selfOnly > 0, "SELF with THRUSTING emits"),
                () -> assertEquals(selfOnly, g.world.particles().count(), "the duplicate SHIP record adds none"));
        }

        @Test
        @DisplayName("particles are advanced to the render tick on update")
        void stepOnUpdate() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            DebrisBurst d = WorldFixture.burst();
            f.begin(100).burst(d, Records.BurstKind.DEBRIS, Records.ObjColor.WHITE, 0).ingestAtTick(100);
            ParticlePool p = f.world.particles();
            f.world.update(at(104));
            assertAll(
                () -> assertTrue(p.visible(0), "born at 100, visible at 104"),
                () -> assertEquals(300 + p.vx(0) * 1.0, p.x(0), 1e-3, "moved by 4 ticks = 1 frame"));
        }
    }

    @Test
    @DisplayName("reset forgets snapshots, particles and radar but keeps the map")
    void reset() {
        WorldFixture f = new WorldFixture(true);
        f.begin(100).radar(1, 1, 0).burst(WorldFixture.burst(), 1, 6, 0).cannon(0, 1).ingestAtTick(100);
        f.world.reset();
        f.world.update(at(100));
        ByteBuffer unused = f.buf;
        assertAll(
            () -> assertFalse(f.world.hasSnapshot(), "no snapshot"),
            () -> assertEquals(0, f.world.particles().count(), "no particles"),
            () -> assertEquals(0, f.world.radarCount(), "no radar"),
            () -> assertEquals(0, f.world.cannonFlags(0), "cannon state reset"),
            () -> assertTrue(f.world.configured(), "still configured"),
            () -> assertNotNull(unused, "buffer reusable"));
    }
}
