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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records.ShipFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.WorldFixture.at;
import static org.junit.jupiter.api.Assertions.*;

/** Clock batching, tick-rate independence, own exhaust, interpolation gaps and base ownership. */
class ClientWorldReviewTest {
    static final double EPS = 1e-3; // nanosecond rounding of local times

    @Nested
    @DisplayName("tick clock fed once per receive batch")
    class ClockBatches {
        @Test
        @DisplayName("only the newest tick of a batch is sampled, at its arrival time")
        void newestOfBatch() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).ingestAtTick(100);
            f.world.update(at(100));
            f.begin(102).ingest(at(104));
            f.begin(104).ingest(at(104));
            f.world.update(at(104));
            assertEquals(104.0, f.world.serverTickNow(), EPS,
                "the late tick 102 of the batch does not pull the estimate back");
        }

        @Test
        @DisplayName("ingest alone does not touch the clock; the next update samples it")
        void sampledOnUpdate() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).ingestAtTick(100);
            boolean beforeUpdate = f.world.clock().synced();
            f.world.update(at(101));
            assertAll(
                () -> assertFalse(beforeUpdate, "not synced by ingest"),
                () -> assertTrue(f.world.clock().synced(), "synced by the update"),
                () -> assertEquals(101.0, f.world.serverTickNow(), EPS, "from tick 100 at its arrival"));
        }

        @Test
        @DisplayName("a 0.6 s stall then a burst: the render tick never goes back and stays one delay behind")
        void stallThenBurst() {
            WorldFixture f = new WorldFixture(true);
            ClientWorld w = f.world;
            for (int t = 100; t <= 200; t += 2) {
                f.begin(t).ship(9, ShipFlags.THRUSTING, 100 + t, 100, 0, 4, 0).ingestAtTick(t);
                w.update(at(t + 1));
            }
            long burstAt = at(238);
            for (int t = 202; t <= 236; t += 2) {
                f.begin(t).ship(9, ShipFlags.THRUSTING, 100 + t, 100, 0, 4, 0).ingest(burstAt);
            }
            List<Double> lags = new ArrayList<>();
            double prev = w.renderTick();
            boolean monotonic = true;
            for (int t = 238; t <= 300; t += 2) {
                f.begin(t).ship(9, ShipFlags.THRUSTING, 100 + t, 100, 0, 4, 0).ingestAtTick(t);
                w.update(at(t + 1));
                monotonic &= w.renderTick() >= prev;
                prev = w.renderTick();
                lags.add(w.newestTick() - w.renderTick());
            }
            double maxLag = lags.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double minLag = lags.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            boolean fMono = monotonic;
            assertAll(
                () -> assertTrue(fMono, "the render tick never moves backward"),
                () -> assertEquals(w.interpolationDelayTicks() - 1, maxLag, EPS,
                    "always delay - 1 tick behind the newest (rendered 1 tick after arrival)"),
                () -> assertEquals(w.interpolationDelayTicks() - 1, minLag, EPS, "no lag build-up after the burst"));
        }
    }

    @Nested
    @DisplayName("TickClock outliers")
    class ClockOutliers {
        @Test
        @DisplayName("far ahead resyncs at once; far behind only after BACKWARD_RESYNC_SAMPLES in a row")
        void asymmetric() {
            TickClock c = new TickClock();
            c.reset(60);
            c.sample(1000, 0);
            c.sample(1100, 0);
            double ahead = c.serverTick(0);
            for (int i = 1; i < TickClock.BACKWARD_RESYNC_SAMPLES; i++) {
                c.sample(500, 0);
            }
            double ignored = c.serverTick(0);
            c.sample(500, 0);
            double resynced = c.serverTick(0);
            assertAll(
                () -> assertEquals(1100.0, ahead, 1e-9, "100 ticks ahead: replaced at once"),
                () -> assertEquals(1100.0, ignored, 1e-9, "three samples far behind are ignored"),
                () -> assertEquals(500.0, resynced, 1e-9, "the fourth in a row moves the estimate back"));
        }

        @Test
        @DisplayName("an in-range sample restarts the backward count; a far-ahead one does too")
        void countRestarts() {
            TickClock c = new TickClock();
            c.reset(60);
            c.sample(1000, 0);
            for (int i = 1; i < TickClock.BACKWARD_RESYNC_SAMPLES; i++) {
                c.sample(500, 0);
            }
            c.sample(1000, 0);
            for (int i = 1; i < TickClock.BACKWARD_RESYNC_SAMPLES; i++) {
                c.sample(500, 0);
            }
            double afterInRange = c.serverTick(0);
            c.sample(1100, 0);
            for (int i = 1; i < TickClock.BACKWARD_RESYNC_SAMPLES; i++) {
                c.sample(500, 0);
            }
            assertAll(
                () -> assertEquals(1000.0, afterInRange, 1e-9, "the count started over after the in-range sample"),
                () -> assertEquals(1100.0, c.serverTick(0), 1e-9, "and after the far-ahead resync"));
        }

        @Test
        @DisplayName("the outlier threshold is half a second at any tick rate")
        void thresholdInTime() {
            TickClock c = new TickClock();
            c.reset(14);
            c.sample(100, 0);
            c.sample(107, 0);
            double within = c.serverTick(0);
            c.sample(115, 0);
            TickClock b = new TickClock();
            b.reset(14);
            b.sample(100, 0);
            b.sample(93, 0);
            assertAll(
                () -> assertEquals(99.3, b.serverTick(0), 1e-9, "exactly 7 ticks behind is smoothed, not an outlier"),
                () -> assertEquals(7.0, c.resyncTicks(), 1e-12, "0.5 s at 14 Hz"),
                () -> assertEquals(100.7, within, 1e-9, "7 ticks ahead is smoothed"),
                () -> assertEquals(115.0, c.serverTick(0), 1e-9, "more than 7 ticks ahead resyncs"));
        }
    }

    @Nested
    @DisplayName("tick rate from the server (framesPerSecond, one snapshot per tick)")
    class Rates14 {
        long at14(double tick) {
            return WorldFixture.T0 + Math.round((tick - 100) / 14.0 * 1e9);
        }

        WorldFixture fourteen() {
            WorldFixture f = new WorldFixture(true);
            Msg.Welcome w = new Msg.Welcome();
            w.simHz = 14;
            w.snapshotHz = 14;
            w.gameFps = 14;
            f.world.configure(f.map, f.options, w);
            return f;
        }

        @Test
        @DisplayName("at 14 Hz: frameFraction 1, interval 1 tick, delay 1 tick + 20 ms, clock at 14 Hz")
        void rates() {
            ClientWorld w = fourteen().world;
            assertAll(
                () -> assertEquals(1f, w.frameFraction(), "one tick per original frame"),
                () -> assertEquals(1.0, w.snapshotIntervalTicks(), "a snapshot every tick"),
                () -> assertEquals(1 + 0.02 * 14, w.interpolationDelayTicks(), 1e-12, "1 tick plus 20 ms"),
                () -> assertEquals(14.0, w.clock().simHz(), "the clock runs at 14 Hz"));
        }

        @Test
        @DisplayName("at 14 Hz ships interpolate in time and extrapolation stops after 2 intervals")
        void interpolateAt14() {
            WorldFixture f = fourteen();
            ClientWorld w = f.world;
            w.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 100, 0, 10, 0).ingest(at14(100));
            f.begin(101).ship(9, 0, 110, 100, 0, 10, 0).ingest(at14(101));
            w.update(at14(100.5));
            float mid = w.shipX(0);
            w.update(at14(106));
            assertAll(
                () -> assertEquals(105f, mid, EPS, "half way at half a tick (35.7 ms)"),
                () -> assertEquals(130f, w.shipX(0), EPS, "extrapolated at most 2 ticks of 10 px"));
        }

        @Test
        @DisplayName("at 14 Hz the first snapshot sparks its own tick only; the next one tick each")
        void sparksPerTick() {
            WorldFixture f = fourteen();
            f.begin(100).ship(9, ShipFlags.THRUSTING, 100, 100, 0, 0, 0).ingest(at14(100));
            int first = f.world.particles().count();
            f.begin(101).ship(9, ShipFlags.THRUSTING, 100, 100, 0, 0, 0).ingest(at14(101));
            int second = f.world.particles().count() - first;
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 700, 350, true);
            ParticlePool p100 = new ParticlePool(1000);
            em.emit(100, 9, 100, 100, 0, 0, 0, 45, 0, ShipShape.defaultShip().engineX,
                ShipShape.defaultShip().engineY, false, p100);
            ParticlePool p101 = new ParticlePool(1000);
            em.emit(101, 9, 100, 100, 0, 0, 0, 45, 0, ShipShape.defaultShip().engineX,
                ShipShape.defaultShip().engineY, false, p101);
            assertAll(
                () -> assertEquals(p100.count(), first, "tick 100 only"),
                () -> assertEquals(p101.count(), second, "then tick 101 only"));
        }
    }

    @Nested
    @DisplayName("own ship exhaust")
    class OwnExhaust {
        @Test
        @DisplayName("own sparks step at the own ship's drawn tick (the present, F1); others at the render tick")
        void ownAtPresent() {
            WorldFixture f = new WorldFixture(true);
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 7;
            j.flags = Msg.PlayerJoin.FLAG_SELF;
            j.power = 45;
            j.afterburners = 8;
            f.players.join(j);
            f.begin(100).self(7, ShipFlags.THRUSTING, 200, 100, 8, 0, 0)
                .ship(9, ShipFlags.THRUSTING, 400, 100, 0, 8, 0).ingestAtTick(100);
            f.world.update(at(100));
            ParticlePool p = f.world.particles();
            int own = 0;
            int ownVisible = 0;
            int other = 0;
            int otherVisible = 0;
            for (int i = 0; i < p.count(); i++) {
                if (p.own(i)) {
                    own++;
                    ownVisible += p.visible(i) ? 1 : 0;
                } else {
                    other++;
                    otherVisible += p.visible(i) ? 1 : 0;
                }
            }
            int fOwn = own;
            int fOwnVisible = ownVisible;
            int fOther = other;
            int fOtherVisible = otherVisible;
            boolean ownBlue = false;
            for (int i = 0; i < p.count(); i++) {
                ownBlue |= p.own(i) && p.color(i) == com.fulcrologic.jxpilot.common.net.Records.ObjColor.BLUE;
            }
            boolean fOwnBlue = ownBlue;
            assertAll(
                () -> assertTrue(fOwnBlue, "the own afterburner (blue) sparks are own exhaust too"),
                () -> assertTrue(fOwn > 0 && fOther > 0, "both ships sparked"),
                () -> assertEquals(fOwn, fOwnVisible, "own sparks of ticks 99-100 are visible at the drawn tick 100"),
                () -> assertEquals(0, fOtherVisible, "the other ship's are not yet at render tick 96.8"));
        }

        @Test
        @DisplayName("own sparks are seeded from SELF quantized like the wire (half pixels, 1/16 px/frame, RES dir)")
        void quantizedSelf() {
            WorldFixture f = new WorldFixture(true);
            f.players.setSelfId(7);
            float heading = (float) (5 * 2 * Math.PI / 128);
            f.begin(100).self(7, ShipFlags.THRUSTING, 200.26f, 100.1f, 8.03f, -1.04f, heading).ingestAtTick(100);
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 700, 350, true);
            ParticlePool expected = new ParticlePool(4096);
            double qx = ThrustSparkEmitter.quantizePos(200.26f);
            double qy = ThrustSparkEmitter.quantizePos(100.1f);
            float qvx = ThrustSparkEmitter.quantizeVel(8.03f);
            float qvy = ThrustSparkEmitter.quantizeVel(-1.04f);
            for (int t = 99; t <= 100; t++) {
                double back = (100 - t) * 0.25;
                em.emit(t, 7, qx - qvx * back, qy - qvy * back, heading, qvx, qvy, 45, 0,
                    ShipShape.defaultShip().engineX, ShipShape.defaultShip().engineY, true, expected);
            }
            assertAll(
                () -> assertEquals(200.5, qx, "x rounds to 200.5"),
                () -> assertEquals(ParticlesTest.pool(expected), ParticlesTest.pool(f.world.particles()),
                    "exactly the particles of the quantized state"));
        }
    }

    @Nested
    @DisplayName("interpolation gaps")
    class Gaps {
        @Test
        @DisplayName("a ship only in A (B's part lost) is extrapolated from A instead of vanishing")
        void onlyInA() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 100, 0, 0, 0).ship(10, 0, 50, 100, 0, 4, 0).ingestAtTick(100);
            f.begin(102).ship(9, 0, 102, 100, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(101));
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < f.world.shipCount(); i++) {
                ids.add(f.world.shipId(i));
            }
            assertAll(
                () -> assertEquals(List.of(9, 10), ids, "both ships drawn, B's first"),
                () -> assertEquals(101f, f.world.shipX(0), EPS, "ship 9 interpolated"),
                () -> assertEquals(51f, f.world.shipX(1), EPS, "ship 10 moved by v * 1 tick * 0.25 frames"));
        }

        @Test
        @DisplayName("the id index is rebuilt for every update: a ship only in B is not matched to a stale A index")
        void indexRebuilt() {
            WorldFixture f = new WorldFixture(false);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(10, 0, 50, 100, 0, 0, 0).ship(9, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            f.begin(102).ship(9, 0, 102, 100, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(101));
            f.begin(104).ship(10, 0, 300, 100, 0, 0, 0).ship(9, 0, 104, 100, 0, 0, 0).ingestAtTick(104);
            f.world.update(at(103));
            assertEquals(300f, f.world.shipX(0), EPS, "ship 10 only in B is drawn at B, not interpolated from ship 9");
        }

        @Test
        @DisplayName("a mostly diagonal jump beyond TELEPORT_PX is snapped (x and y both count)")
        void diagonalTeleport() {
            WorldFixture f = new WorldFixture(false);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            f.begin(102).ship(9, 0, 400, 300, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(101));
            assertAll(
                () -> assertEquals(400f, f.world.shipX(0), EPS, "300 x 200 px is 360 px: snapped to B's x"),
                () -> assertEquals(300f, f.world.shipY(0), EPS, "and B's y"));
        }

        @Test
        @DisplayName("the fifth tick recycles the oldest remaining frame, not the newest")
        void fifthTickRecycle() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            int[] x = {100, 102, 104, 150, 108};
            for (int k = 0; k < 5; k++) {
                f.begin(100 + 2 * k).ship(9, 0, x[k], 100, 0, 0, 0).ingestAtTick(100 + 2 * k);
            }
            f.world.update(at(106));
            assertEquals(150f, f.world.shipX(0), EPS, "tick 106 is still in the ring after 108 arrived");
        }

        @Test
        @DisplayName("the own ship is the newest SELF even when the ring has wrapped")
        void selfAcrossWrap() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.players.setSelfId(7);
            for (int t = 100; t <= 106; t += 2) {
                f.begin(t).self(7, 0, t, 100, 0, 0, 0).ingestAtTick(t);
            }
            f.world.update(at(106));
            assertEquals(106f, f.world.selfX(), EPS, "tick 106 (in the recycled first slot), not 104");
        }
    }

    @Nested
    @DisplayName("base ownership (C Handle_base)")
    class Bases {
        ClientWorld twoBases() {
            Options o = new Options();
            ConfigParser.parse("mapWidth: 6\nmapHeight: 3\nmapData: \\multiline: E\n      \n _  _ \n      \nE\n",
                "bases", OptionOrigin.MAP, o);
            WorldMap m = WorldMap.fromOptions(o);
            Msg.Welcome wel = new Msg.Welcome();
            wel.simHz = 60;
            wel.snapshotHz = 30;
            ClientWorld w = new ClientWorld(new Players(), 4, 4, 16, 4);
            w.configure(m, o, wel);
            return w;
        }

        @Test
        @DisplayName("taking a base releases the player's other base; NO_ID releases; bad indices change nothing")
        void handleBase() {
            ClientWorld w = twoBases();
            w.setBaseOwner(0, 9);
            w.setBaseOwner(1, 9);
            int firstAfterMove = w.baseOwner(0);
            int second = w.baseOwner(1);
            w.setBaseOwner(0, 5);
            w.setBaseOwner(1, Protocol.NO_ID);
            int released = w.baseOwner(1);
            w.setBaseOwner(2, 5);
            w.setBaseOwner(-1, 5);
            assertAll(
                () -> assertEquals(Protocol.NO_ID, firstAfterMove, "player 9 moved from base 0 to base 1"),
                () -> assertEquals(9, second, "base 1 is player 9's"),
                () -> assertEquals(Protocol.NO_ID, released, "BASE_OWNER(1, NO_ID) releases base 1"),
                () -> assertEquals(5, w.baseOwner(0), "an out-of-range index does not release player 5's base"));
        }
    }
}
