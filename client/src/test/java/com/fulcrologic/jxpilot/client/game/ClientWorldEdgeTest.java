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

import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Records.ShipFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.WorldFixture.at;
import static org.junit.jupiter.api.Assertions.*;

/** Boundaries and configuration of {@link ClientWorld}. */
class ClientWorldEdgeTest {
    static final double EPS = 1e-3; // nanosecond rounding of local times

    @Nested
    @DisplayName("configure and reset")
    class Setup {
        @Test
        @DisplayName("rates and world come from WELCOME and the map; zero rates fall back")
        void rates() {
            WorldFixture f = new WorldFixture(true);
            ClientWorld w = f.world;
            double ww = w.worldWidth();
            double wh = w.worldHeight();
            boolean wraps = w.wraps();
            float ff = w.frameFraction();
            double interval = w.snapshotIntervalTicks();
            double delay = w.interpolationDelayTicks();
            Msg.Welcome zero = new Msg.Welcome();
            w.configure(f.map, f.options, zero);
            assertAll(
                () -> assertEquals(700.0, ww, "width from the map"),
                () -> assertEquals(350.0, wh, "height from the map"),
                () -> assertTrue(wraps, "wrap from the map"),
                () -> assertEquals(0.25f, ff, "gameFps / simHz"),
                () -> assertEquals(2.0, interval, "simHz / snapshotHz"),
                () -> assertEquals(2.0 + 0.02 * 60, delay, 1e-12, "one interval plus 20 ms of ticks"),
                () -> assertEquals(0.25f, w.frameFraction(), "zero gameFps falls back to framesPerSecond, simHz to 60"),
                () -> assertEquals(2.0, w.snapshotIntervalTicks(), "zero snapshotHz falls back to simHz / 2"),
                () -> assertEquals(60.0, w.clock().simHz(), "the clock runs at simHz"),
                () -> assertFalse(new WorldFixture(false).world.wraps(), "non-wrapping map"));
        }

        @Test
        @DisplayName("the default render delay is one snapshot interval plus 20 ms")
        void defaultDelay() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).ship(9, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            f.world.update(at(103));
            assertAll(
                () -> assertEquals(103 - 2 - 1.2, f.world.renderTick(), EPS, "103 - (2 + 0.02 * 60)"),
                () -> assertEquals(103.0, f.world.serverTickNow(), EPS, "estimated server tick"));
        }

        @Test
        @DisplayName("relative positions decode wrapped into the configured world")
        void readerWorld() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.beginView(100, 0, 1, 690, 340).ship(9, 0, 5, 3, 0, 0, 0).ingestAtTick(100);
            f.world.update(at(100));
            assertAll(
                () -> assertEquals(5f, f.world.shipX(0), "690 + 15 wraps to 5"),
                () -> assertEquals(3f, f.world.shipY(0), "340 + 13 wraps to 3"));
        }

        @Test
        @DisplayName("reset restores every map object, items and the clock")
        void reset() {
            WorldFixture f = new WorldFixture(true);
            ClientWorld w = f.world;
            f.begin(100).fuel(0, 0.25).target(0, Records.TargetState.FLAG_DEAD, 0.5).treasure(0, 1).items(3)
                .ingestAtTick(100);
            w.update(at(100));
            w.setBaseOwner(0, 9);
            boolean before = w.clock().synced();
            w.reset();
            assertAll(
                () -> assertTrue(before, "clock synced before"),
                () -> assertFalse(w.clock().synced(), "clock reset"),
                () -> assertEquals(1f, w.fuelLevel(0), "fuel full"),
                () -> assertEquals(0, w.targetFlags(0), "target alive"),
                () -> assertEquals(1f, w.targetDamageLeft(0), "target intact"),
                () -> assertEquals(0, w.treasureFlags(0), "treasure empty"),
                () -> assertEquals(Protocol.NO_ID, w.baseOwner(0), "no base owner"),
                () -> assertEquals(0, w.selfItem(0), "no items"));
        }
    }

    @Nested
    @DisplayName("ingest boundaries")
    class IngestBounds {
        @Test
        @DisplayName("capacities clip ships, shots and radar; out-of-range indices are ignored")
        void capacities() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100);
            for (int i = 0; i < 17; i++) {
                f.ship(20 + i, 0, 10 + i, 10, 0, 0, 0);
                f.shot(10 + i, 20, 0, 0);
            }
            for (int i = 0; i < 65; i++) {
                f.radar(i, i, 0);
            }
            f.cannon(1, 1).fuel(1, 0.1).target(1, 1, 0.1).treasure(1, 1)
                .target(0, Records.TargetState.FLAG_DEAD, 0.5).treasure(0, Records.TreasureState.FLAG_HAS_BALL);
            int r = f.ingestAtTick(100);
            f.world.update(at(100));
            long ingested = f.world.ingested();
            assertAll(
                () -> assertEquals(ClientWorld.NEW_TICK, r, "accepted"),
                () -> assertEquals(16, f.world.shipCount(), "16 of 17 ships"),
                () -> assertEquals(16, f.world.shotCount(), "16 of 17 shots"),
                () -> assertEquals(64, f.world.radarCount(), "64 of 65 blips"),
                () -> assertEquals(1, f.map.cannons().size(), "one cannon: index 1 ignored"),
                () -> assertEquals(Records.TargetState.FLAG_DEAD, f.world.targetFlags(0), "target flags"),
                () -> assertEquals(0.5f, f.world.targetDamageLeft(0), 1e-4, "target strength"),
                () -> assertEquals(Records.TreasureState.FLAG_HAS_BALL, f.world.treasureFlags(0), "treasure flags"),
                () -> assertEquals(1, ingested, "one part ingested"));
        }

        @Test
        @DisplayName("parts 64 and up are malformed and never merged, so repeats cannot double a frame")
        void highParts() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100, 0, 65).ingestAtTick(100);
            long bad = f.world.malformedSnapshots();
            int p64 = f.begin(100, 64, 65).ship(9, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            int p64again = f.begin(100, 64, 65).ship(9, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            int p63 = f.begin(100, 63, 65).ship(10, 0, 50, 100, 0, 0, 0).ingestAtTick(100);
            int p63again = f.begin(100, 63, 65).ship(10, 0, 50, 100, 0, 0, 0).ingestAtTick(100);
            f.world.update(at(100));
            assertAll(
                () -> assertEquals(ClientWorld.MALFORMED, p64, "part 64 is malformed"),
                () -> assertEquals(ClientWorld.MALFORMED, p64again, "and so is its repeat"),
                () -> assertEquals(bad + 2, f.world.malformedSnapshots(), "both counted"),
                () -> assertEquals(ClientWorld.PART_ADDED, p63, "part 63 is merged"),
                () -> assertEquals(ClientWorld.DUPLICATE, p63again, "part 63 repeated is a duplicate"),
                () -> assertEquals(1, f.world.shipCount(), "only part 63's ship, once"),
                () -> assertEquals(10, f.world.shipId(0), "the ship of part 63"));
        }

        @Test
        @DisplayName("a new tick recycles the oldest frame; stale ship slots are not reused")
        void recycleOldest() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 100, 0, 0, 0).ship(10, 0, 200, 100, 0, 0, 0).ingestAtTick(100);
            f.begin(102).ship(9, 0, 102, 100, 0, 0, 0).ingestAtTick(102);
            f.begin(104).ship(9, 0, 104, 100, 0, 0, 0).ingestAtTick(104);
            f.begin(106).ship(9, 0, 106, 100, 0, 0, 0).ingestAtTick(106);
            f.world.update(at(101));
            float oldest = f.world.shipX(0);
            f.world.update(at(107));
            assertAll(
                () -> assertEquals(102f, oldest, "tick 100 was recycled: 102 is the oldest"),
                () -> assertEquals(1, f.world.shipCount(), "only the newest frame's one ship"),
                () -> assertEquals(106f, f.world.shipX(0), "at its position"));
        }
    }

    @Nested
    @DisplayName("frame choice and interpolation boundaries")
    class Choice {
        /** Frames at ticks multiple of 3 so the render tick is exact (3 ticks = 50 ms). */
        WorldFixture frames(int... ticks) {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            for (int t : ticks) {
                int k = (t - 100) / 3;
                f.begin(t).ship(9, k == 0 ? ShipFlags.SHIELD : k == 1 ? ShipFlags.THRUSTING : 0, 100 + 20 * k,
                    100 + 40 * k, 0, 0, 0).shot(10 + 10 * k, 10, 0, 0).ingestAtTick(t);
            }
            return f;
        }

        @Test
        @DisplayName("a frame exactly at the render tick is A; the latest A and earliest B are used")
        void choose() {
            WorldFixture f = frames(100, 103, 106);
            ClientWorld w = f.world;
            w.update(at(103));
            float shotAt103 = w.shotX(0);
            w.update(at(106.5));
            float shotPast = w.shotX(0);
            float shipPast = w.shipX(0);
            w.update(at(99));
            float before = w.shipX(0);
            WorldFixture g = frames(100, 106);
            g.world.update(at(103));
            ClientWorld m = g.world;
            assertAll(
                () -> assertEquals(20f, shotAt103, "at tick 103 the shots are tick 103's"),
                () -> assertEquals(30f, shotPast, EPS, "past the newest, shots come from the newest frame"),
                () -> assertEquals(140f, shipPast, EPS, "ships extrapolated from the newest frame (velocity 0)"),
                () -> assertEquals(100f, before, EPS, "before all frames the earliest is drawn"),
                () -> assertEquals(120f, m.shipX(0), EPS, "t = 0.5 between 100 and 106: x halfway from 100 to 140"),
                () -> assertEquals(140f, m.shipY(0), EPS, "y halfway from 100 to 180"),
                () -> assertEquals(0, m.shipFlags(0), "t = 0.5 takes B's flags"));
        }

        @Test
        @DisplayName("a move of exactly TELEPORT_PX is still interpolated")
        void teleportBoundary() {
            WorldFixture f = new WorldFixture(false);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            f.begin(102).ship(9, 0, 450, 100, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(101));
            assertEquals(275f, f.world.shipX(0), EPS, "halfway along 350 px");
        }

        @Test
        @DisplayName("y crossing the seam between snapshots moves the short way")
        void ySeam() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 345, 0, 0, 0).ingestAtTick(100);
            f.begin(102).ship(9, 0, 100, 5, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(101.5));
            assertEquals(2.5f, f.world.shipY(0), EPS, "345 + 0.75 * 10 = 352.5 wraps to 2.5");
        }

        @Test
        @DisplayName("the output list is capped at the ship capacity")
        void outputCap() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100);
            for (int i = 0; i < 16; i++) {
                f.ship(20 + i, 0, 10 + i, 10, 0, 0, 0);
            }
            f.ingestAtTick(100);
            f.world.update(at(100));
            assertEquals(16, f.world.shipCount(), "all 16 fit");
        }

        @Test
        @DisplayName("the own ship comes from the newest frame with SELF; extrapolated ships skip it")
        void newestSelf() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.players.setSelfId(7);
            f.begin(100).self(7, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            f.begin(102).self(7, ShipFlags.SHIELD, 200, 100, 0, 0, 0).ship(7, 0, 200, 100, 0, 0, 0)
                .ship(9, 0, 1, 1, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(104));
            assertAll(
                () -> assertEquals(200f, f.world.selfX(), EPS, "tick 102's SELF"),
                () -> assertEquals(ShipFlags.SHIELD, f.world.selfFlags(), "its flags"),
                () -> assertEquals(1, f.world.shipCount(), "the own SHIP record is skipped when extrapolating"),
                () -> assertEquals(9, f.world.shipId(0), "the other ship remains"),
                () -> assertEquals(0f, f.world.selfVx(), "velocity x"),
                () -> assertEquals(0f, f.world.selfVy(), "velocity y"),
                () -> assertEquals(0, f.world.selfRespawnFrames(), "respawn frames"));
        }

        @Test
        @DisplayName("base owner index equal to the base count is ignored")
        void baseBound() {
            WorldFixture f = new WorldFixture(true);
            f.world.setBaseOwner(1, 9);
            assertEquals(Protocol.NO_ID, f.world.baseOwner(0), "unchanged");
        }

        @Test
        @DisplayName("accessors expose velocities, radar positions and counters")
        void accessors() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 100, 0, 8, -4).radar(350, 175, 0).ingestAtTick(100);
            f.world.update(at(100));
            assertAll(
                () -> assertEquals(8f, f.world.shipVx(0), "ship vx"),
                () -> assertEquals(-4f, f.world.shipVy(0), "ship vy"),
                () -> assertEquals(351.3671875f, f.world.radarX(0), 1e-3, "radar x at the cell centre"),
                () -> assertEquals(175.68359375f, f.world.radarY(0), 1e-3, "radar y at the cell centre"),
                () -> assertTrue(f.world.hasSnapshot(), "has a snapshot"),
                () -> assertTrue(f.world.configured(), "configured"));
        }
    }

    @Nested
    @DisplayName("spark edges")
    class SparkEdges {
        record S(double birth, float x, float y, float vx, float vy, float life) {}

        List<S> list(ParticlePool p) {
            List<S> l = new ArrayList<>();
            for (int i = 0; i < p.count(); i++) {
                l.add(new S(p.birth(i), p.originX(i), p.originY(i), p.vx(i), p.vy(i), p.life(i)));
            }
            return l;
        }

        List<S> expected(int from, int to, int snap, float x, float y, float vx, float vy, float power, int ab) {
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 0.25f, 700, 350, true);
            ParticlePool p = new ParticlePool(4096);
            for (int t = from; t <= to; t++) {
                double back = (snap - t) * 0.25;
                em.emit(t, 9, x - vx * back, y - vy * back, 0, vx, vy, power, ab, ShipShape.defaultShip().engineX,
                    ShipShape.defaultShip().engineY, false, p);
            }
            return list(p);
        }

        @Test
        @DisplayName("joined power/afterburners are used, back-extrapolated per tick; unknown ships use defaults")
        void exactParticles() {
            WorldFixture f = new WorldFixture(true);
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 9;
            j.power = 55;
            j.afterburners = 15;
            f.players.join(j);
            f.begin(100).ship(9, ShipFlags.THRUSTING, 200, 100, 0, 8, 4).ingestAtTick(100);
            List<S> joined = list(f.world.particles());
            WorldFixture g = new WorldFixture(true);
            g.begin(100).ship(9, ShipFlags.THRUSTING, 200, 100, 0, 8, 4).ingestAtTick(100);
            assertAll(
                () -> assertEquals(expected(99, 100, 100, 200, 100, 8, 4, 55, 15), joined,
                    "slot 0's power 55 and 15 afterburners, origins stepped back by velocity"),
                () -> assertEquals(expected(99, 100, 100, 200, 100, 8, 4, 45, 0), list(g.world.particles()),
                    "unknown ship: power 45, no afterburners, default engine"));
        }

        @Test
        @DisplayName("consecutive ticks still spark; non-thrusting ships and the own SHIP record do not")
        void edges() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).ingestAtTick(100);
            f.begin(101).ship(9, ShipFlags.THRUSTING, 200, 100, 0, 0, 0).ingestAtTick(101);
            int consecutive = f.world.particles().count();
            WorldFixture g = new WorldFixture(true);
            g.begin(100).ship(9, ShipFlags.SHIELD, 200, 100, 0, 0, 0).ingestAtTick(100);
            WorldFixture h = new WorldFixture(true);
            h.begin(100).self(7, 0, 1, 1, 0, 0, 0).ship(7, ShipFlags.THRUSTING, 200, 100, 0, 0, 0).ingestAtTick(100);
            WorldFixture k = new WorldFixture(true);
            k.players.setSelfId(9);
            k.begin(100).ship(9, ShipFlags.THRUSTING, 200, 100, 0, 0, 0).ingestAtTick(100);
            assertAll(
                () -> assertTrue(consecutive > 0, "tick 101 right after 100 emits"),
                () -> assertEquals(0, g.world.particles().count(), "not thrusting: no sparks"),
                () -> assertEquals(0, h.world.particles().count(), "the SELF id identifies the own SHIP record"),
                () -> assertEquals(0, k.world.particles().count(), "without SELF the known self id is used"));
        }
    }
}
