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
package com.fulcrologic.jxpilot.client.game.predict;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.OPEN;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.Reference;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.Source;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.WALL;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.options;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.px;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.py;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.ship;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OwnShipPredictor}: the own ship stepped by the shared kernel at the predicted tick. */
class OwnShipPredictorTest {
    static final long THRUST = ControlKey.THRUST.mask();
    static final long LEFT = ControlKey.TURN_LEFT.mask();
    static final long FIRE = ControlKey.FIRE_SHOT.mask();
    static final long MS = 1_000_000L;

    /** A source on the open map with the ship at (200, 200) heading right, PREDICT of tick 10 at frame 100. */
    static Source open() {
        Source s = new Source(options("", OPEN));
        s.predict(10, 100, ship(200, 200, 0));
        return s;
    }

    /** The reference ship stepped from PREDICT 10 (frame 100) with {@code keys} for ticks 11..{@code to}. */
    static ShipBody reference(Source s, ShipBody start, int to, long keys) {
        Reference r = new Reference(s.world);
        ShipBody b = new ShipBody();
        b.copyFrom(start);
        for (int k = s.tick + 1; k <= to; k++) {
            r.step(b, s.frames + (k - s.tick - 1), keys, 0, 0);
        }
        return b;
    }

    @Nested
    @DisplayName("when it predicts")
    class Activation {
        @Test
        @DisplayName("no source, prediction off, an invalid PREDICT, no kernel world or no final entry: inactive")
        void inactive() {
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(null, 0);
            boolean noSource = p.active();
            Source s = open();
            s.hold(11, 12, 0, 0);
            p.enabled = false;
            p.update(s, 0);
            boolean off = p.active();
            p.enabled = true;
            s.valid = false;
            p.update(s, 0);
            boolean invalid = p.active();
            s.valid = true;
            s.haveFinal = false;
            p.update(s, 0);
            boolean noFinal = p.active();
            s.haveFinal = true;
            s.world = null;
            p.update(s, 0);
            boolean noWorld = p.active();
            Source t = open();
            t.hold(11, 12, 0, 0);
            t.clock = Double.NaN;
            p.update(t, 0);
            boolean noClock = p.active();
            t.clock = 12;
            p.update(t, 0);
            assertAll(
                () -> assertFalse(noSource, "no source"),
                () -> assertFalse(off, "prediction off"),
                () -> assertFalse(invalid, "invalid PREDICT"),
                () -> assertFalse(noFinal, "no final entry"),
                () -> assertFalse(noWorld, "no kernel world"),
                () -> assertFalse(noClock, "the clock not running"),
                () -> assertTrue(p.active(), "all there: active"));
        }

        @Test
        @DisplayName("a ship not playing, paused, hover-paused, warping, warped or on autopilot is not predicted")
        void unpredictable() {
            int[] statuses = {0, Obj.PLAYING | Obj.PAUSE, Obj.PLAYING | Obj.GAME_OVER, Obj.PLAYING | Obj.HOVERPAUSE,
                Obj.PLAYING | Obj.WARPING, Obj.PLAYING | Obj.WARPED};
            List<Boolean> got = new ArrayList<>();
            for (int st : statuses) {
                Source s = open();
                ShipBody b = ship(200, 200, 0);
                b.status = st;
                s.predict(10, 100, b);
                s.hold(11, 12, 0, 0);
                OwnShipPredictor p = new OwnShipPredictor();
                p.update(s, 0);
                got.add(p.active());
            }
            Source s = open();
            ShipBody auto = ship(200, 200, 0);
            auto.have |= Has.AUTOPILOT;
            auto.used |= Has.AUTOPILOT;
            s.predict(10, 100, auto);
            s.hold(11, 12, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            Source killed = open();
            ShipBody k = ship(200, 200, 0);
            k.status |= Obj.KILLED;
            killed.predict(10, 100, k);
            killed.hold(11, 12, 0, 0);
            OwnShipPredictor q = new OwnShipPredictor();
            q.update(killed, 0);
            assertAll(
                () -> assertEquals(List.of(false, false, false, false, false, false), got,
                    "dead, paused, game over, hover-paused, warping, warped: inactive"),
                () -> assertFalse(p.active(), "autopilot: inactive"),
                () -> assertTrue(q.active(), "KILLED (a predicted crash) is drawn, frozen"),
                () -> assertTrue(q.frozen(), "and frozen"));
        }

        @Test
        @DisplayName("during the round delay nothing is predicted")
        void roundDelay() {
            Source s = open();
            s.roundDelay = 5;
            s.hold(11, 12, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            boolean during = p.active();
            s.roundDelay = 0;
            p.update(s, 0);
            boolean stale = p.active();
            s.predict(11, 100, ship(200, 200, 0));
            p.update(s, 0);
            assertAll(
                () -> assertFalse(during, "round delay: inactive"),
                () -> assertFalse(stale, "until a PREDICT says it is over"),
                () -> assertTrue(p.active(), "after it: active"));
        }

        @Test
        @DisplayName("losing the kernel world forgets the ring: the next PREDICT seeds afresh")
        void reset() {
            Source s = open();
            s.hold(11, 12, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            com.fulcrologic.jxpilot.common.sim.KernelWorld w = s.world;
            s.world = null;
            p.update(s, 0);
            s.world = w;
            s.predict(12, 102, ship(100, 100, 0));
            p.update(s, 0);
            assertAll(
                () -> assertEquals(0, p.reconciliations(), "the PREDICT after a reset is a seed, not a comparison"),
                () -> assertEquals(100, p.x(), 1e-9, "drawn from the new seed"));
        }

        @Test
        @DisplayName("an invalid PREDICT after a valid one is not compared; the ring rides on and is drawn")
        void invalidRidesOn() {
            Source s = open();
            s.hold(11, 12, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            s.valid = false;
            s.predict(12, 101, ship(100, 100, 0));
            s.hold(13, 14, 0, 0);
            p.update(s, 0);
            boolean drawn = p.active();
            double x = p.x();
            s.valid = true;
            p.update(s, 0);
            assertAll(
                () -> assertTrue(drawn, "still drawn"),
                () -> assertEquals(200, x, 1e-9, "from the ring, not the invalid PREDICT"),
                () -> assertEquals(14, p.headTick(), "advanced to 14"),
                () -> assertEquals(1, p.reconciliations(), "compared once it is valid"));
        }

        @Test
        @DisplayName("a PREDICT whose world state is not the client's (a newer or missing KERNEL_STATE) is not compared")
        void worldNotAsOf() {
            Source s = open();
            s.hold(11, 12, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            s.worldAsOf = false;
            s.predict(12, 101, ship(100, 100, 0));
            p.update(s, 0);
            long skipped = p.reconciliations();
            double x = p.x();
            s.worldAsOf = true;
            s.predict(12, 101, ship(100, 100, 0));
            p.update(s, 0);
            assertAll(
                () -> assertEquals(0, skipped, "skipped"),
                () -> assertEquals(200, x, 1e-9, "the ring rode on"),
                () -> assertEquals(1, p.reconciliations(), "compared once the world is as of its tick"));
        }

        @Test
        @DisplayName("a new kernel world rebuilds the kernel and drops the ring")
        void newWorld() {
            Source s = open();
            s.hold(11, 12, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            s.world = com.fulcrologic.jxpilot.common.sim.KernelWorld.of(s.options);
            s.predict(12, 102, ship(100, 100, 0));
            p.update(s, 0);
            assertAll(
                () -> assertTrue(p.active(), "active on the new world"),
                () -> assertEquals(0, p.reconciliations(), "the old ring was dropped"));
        }
    }

    @Nested
    @DisplayName("the ring")
    class Ring {
        @Test
        @DisplayName("from PREDICT S each final entry is one kernel step in the server's order and C frame clock")
        void stepsLikeTheServer() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.predict(10, 100, start);
            s.hold(11, 16, THRUST | LEFT, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            ShipBody want = reference(s, start, 16, THRUST | LEFT);
            assertAll(
                () -> assertEquals(16, p.headTick(), "stepped to the newest final entry"),
                () -> assertFalse(p.frozen(), "not frozen"),
                () -> assertEquals(px(want), p.x(), 1e-9, "x as the reference after 6 thrusting, turning ticks"),
                () -> assertEquals(py(want), p.y(), 1e-9, "y as the reference"),
                () -> assertEquals(want.dir * 2 * Math.PI / Const.RES, p.heading(), 1e-9, "heading as the reference"),
                () -> assertEquals(want.vx, p.vx(), "vx as the reference"),
                () -> assertEquals(want.fuel, p.fuel(), 1e-9, "fuel as the reference"),
                () -> assertNotEquals(200, p.x(), "the ship moved"),
                () -> assertEquals(16.0, p.drawnTick(), "drawn at the final tick (alpha 0)"));
        }

        @Test
        @DisplayName("taps are applied (keys | taps, then keys) and the pointer turn first")
        void tapsAndAim() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.predict(10, 100, start);
            s.input.set(11, 0, FIRE, 0);
            s.input.set(12, 0, 0, 40);
            s.finalTick = 12;
            s.haveFinal = true;
            s.clock = 12;
            OwnShipPredictor p = new OwnShipPredictor();
            List<Integer> fired = new ArrayList<>();
            p.setShotSink((t, o, x, y, vx, vy, spec) -> fired.add(t));
            p.update(s, 0);
            Reference r = new Reference(s.world);
            ShipBody want = new ShipBody();
            want.copyFrom(start);
            r.step(want, 100, 0, FIRE, 0);
            r.step(want, 101, 0, 0, 40);
            ShipBody noAim = new ShipBody();
            noAim.copyFrom(start);
            r.step(noAim, 100, 0, 0, 0);
            r.step(noAim, 101, 0, 0, 0);
            assertAll(
                () -> assertEquals(want.dir * 2 * Math.PI / Const.RES, p.heading(), 1e-9, "the pointer turn applied"),
                () -> assertNotEquals(noAim.dir, want.dir, "the aim matters in this case"),
                () -> assertEquals(List.of(11), fired, "the tapped fire key fired in tick 11"),
                () -> assertEquals(px(want), p.x(), 1e-9, "the recoil as the reference"));
        }

        @Test
        @DisplayName("a missing entry or more than the ring holds stops predicting until the next PREDICT")
        void halts() {
            Source s = open();
            s.hold(11, 12, 0, 0);
            s.finalTick = 14;
            s.clock = 14;
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            boolean gap = p.active();
            s.hold(13, 14, 0, 0);
            p.update(s, 0);
            boolean stillHalted = p.active();
            s.predict(14, 104, ship(200, 200, 0));
            p.update(s, 0);
            boolean resumed = p.active();
            Source far = open();
            far.hold(11, 10 + OwnShipPredictor.RING, 0, 0);
            OwnShipPredictor q = new OwnShipPredictor();
            q.update(far, 0);
            assertAll(
                () -> assertFalse(gap, "entry 13 missing: inactive"),
                () -> assertFalse(stillHalted, "the entry arriving later does not resume"),
                () -> assertTrue(resumed, "the next PREDICT resumes"),
                () -> assertFalse(q.active(), "RING ticks past the PREDICT: inactive"));
        }

        @Test
        @DisplayName("a crash freezes the ship where it crashed, still drawn")
        void crash() {
            Source s = new Source(options("", WALL));
            ShipBody start = ship(260, 200, 0);
            start.vx = 30;
            start.velocity = 30;
            s.predict(10, 100, start);
            s.hold(11, 14, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            double x1 = p.x();
            boolean frozen = p.frozen();
            s.hold(15, 18, 0, 0.5);
            p.update(s, 0);
            assertAll(
                () -> assertTrue(frozen, "frozen after hitting the wall at x = 280"),
                () -> assertTrue(x1 < 8 * 35, "stopped before the wall"),
                () -> assertTrue(p.active(), "still drawn"),
                () -> assertEquals(x1, p.x(), "does not move on later ticks"),
                () -> assertEquals(18.0, p.drawnTick(), "alpha is 0 while frozen"));
        }
    }

    @Nested
    @DisplayName("reconciliation")
    class Reconciliation {
        @Test
        @DisplayName("a PREDICT equal to the ring's tick is exact: nothing moves")
        void exact() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 15, THRUST, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            List<String> log = new ArrayList<>();
            p.setListener((t, ex, px) -> log.add(t + ":" + ex + ":" + px));
            p.update(s, 0);
            double x = p.x();
            s.predict(13, 102, reference(s, start, 13, THRUST));
            p.update(s, 0);
            assertAll(
                () -> assertEquals(1, p.reconciliations(), "one comparison"),
                () -> assertEquals(1, p.exactReconciliations(), "exact"),
                () -> assertEquals(0, p.corrections(), "no correction"),
                () -> assertEquals(List.of("13:true:0.0"), log, "listener told: exact"),
                () -> assertEquals(x, p.x(), "the drawn ship did not move"));
        }

        @Test
        @DisplayName("a different PREDICT reseeds and replays; the drawn ship stays and the offset decays")
        void corrects() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 15, THRUST, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            List<String> log = new ArrayList<>();
            p.setListener((t, ex, px) -> log.add(t + ":" + ex + ":" + px));
            p.update(s, 0);
            double x = p.x();
            ShipBody server = reference(s, start, 13, THRUST);
            server.initPixels(px(server) + 3, py(server) + 4);
            s.predict(13, 102, server);
            double y = p.y();
            p.update(s, 0);
            double drawnAfter = p.x();
            double drawnAfterY = p.y();
            double offset = p.smoother().offsetPx();
            p.update(s, 100 * MS);
            assertAll(
                () -> assertEquals(1, p.corrections(), "one correction"),
                () -> assertEquals(0, p.exactReconciliations(), "not exact"),
                () -> assertEquals(1, log.size(), "listener told once"),
                () -> assertTrue(log.get(0).startsWith("13:false:"), "listener: tick 13, not exact"),
                () -> assertEquals(5, p.lastCorrectionPx(), 0.05, "a 3-4-5 px correction"),
                () -> assertEquals(x, drawnAfter, 0.05, "the drawn ship did not jump"),
                () -> assertEquals(y, drawnAfterY, 0.05, "nor in y"),
                () -> assertEquals(5, offset, 0.05, "the offset holds the difference"),
                () -> assertEquals(2.5, p.smoother().offsetPx(), 0.05, "half of it is left after 100 ms"),
                () -> assertEquals(15, p.headTick(), "replayed to the old head"));
        }

        @Test
        @DisplayName("a ship dead on the server is not judged: no reconciliation, no correction, no listener call")
        void deathNotJudged() {
            Source s = open();
            s.hold(11, 15, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            List<Integer> told = new ArrayList<>();
            p.setListener((t, ex, px) -> told.add(t));
            p.update(s, 0);
            ShipBody dead = ship(100, 100, 0);
            dead.status = 0;
            s.predict(13, 102, dead);
            p.update(s, 0);
            assertAll(
                () -> assertEquals(0, p.reconciliations(), "not judged"),
                () -> assertEquals(0, p.corrections(), "no correction counted"),
                () -> assertEquals(List.of(), told, "listener not told"),
                () -> assertFalse(p.active(), "and not drawn"));
        }

        @Test
        @DisplayName("a correction while the ship was not drawn snaps")
        void snapWhenNotDrawn() {
            Source s = open();
            ShipBody dead = ship(200, 200, 0);
            dead.status = 0;
            s.predict(10, 100, dead);
            s.hold(11, 15, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            s.predict(12, 101, ship(100, 100, 0));
            p.update(s, 0);
            assertAll(
                () -> assertTrue(p.active(), "alive again: drawn"),
                () -> assertEquals(0, p.reconciliations(), "a dead ring state is not judged"),
                () -> assertEquals(0, p.smoother().offsetPx(), "no glide from the dead ship"),
                () -> assertEquals(0, p.smoother().snaps(), "an asked-for snap (not counted)"));
        }

        @Test
        @DisplayName("corrections per second over each second and the last correction go to the diagnostics")
        void diagnostics() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 15, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            ShipBody off = reference(s, start, 12, 0);
            off.initPixels(px(off) + 2, py(off));
            s.predict(12, 101, off);
            p.update(s, 500 * MS);
            double before = s.diag.correctionsPerSecond;
            p.update(s, 1000 * MS);
            assertAll(
                () -> assertTrue(Double.isNaN(before), "no rate before a whole second"),
                () -> assertEquals(1.0, s.diag.correctionsPerSecond, 1e-9, "one correction in the first second"),
                () -> assertEquals(2, s.diag.lastCorrectionPx, 0.05, "the last correction's size"),
                () -> assertEquals(1.0, p.correctionsPerSecond(), 1e-9, "also on the predictor"));
        }
    }

    @Nested
    @DisplayName("the speculative partial tick")
    class Speculative {
        @Test
        @DisplayName("drawn between the final tick and one step with the collected keys, by alpha")
        void lerp() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            start.vx = 4;
            s.predict(10, 100, start);
            s.hold(11, 12, 0, 0.25);
            s.keys = THRUST;
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            ShipBody a = reference(s, start, 12, 0);
            ShipBody b = new ShipBody();
            b.copyFrom(a);
            new Reference(s.world).step(b, 102, THRUST, 0, 0);
            double quarter = p.x();
            int flags = p.flags(0);
            s.clock = 14;
            p.update(s, 0);
            double clampedHigh = p.x();
            s.clock = 11;
            p.update(s, 0);
            assertAll(
                () -> assertEquals(px(a) + 0.25 * (px(b) - px(a)), quarter, 1e-9, "a quarter of the way"),
                () -> assertTrue((flags & Records.ShipFlags.THRUSTING) != 0, "the collected thrust shows at once"),
                () -> assertEquals(px(b), clampedHigh, 1e-9, "alpha clamped to 1"),
                () -> assertEquals(px(a), p.x(), 1e-9, "alpha clamped to 0"),
                () -> assertEquals(12, p.headTick(), "the speculative step is not kept"));
        }
    }

    @Nested
    @DisplayName("own bullets")
    class Bullets {
        @Test
        @DisplayName("bullets go to the sink; each is given back shotLife ticks after it was fired")
        void expiry() {
            Source s = new Source(options("shotLife: 3\nfireRepeatRate: 1", OPEN));
            ShipBody start = ship(200, 200, 0);
            start.shotMax = 2;
            s.predict(10, 100, start);
            List<String> fired = new ArrayList<>();
            OwnShipPredictor p = new OwnShipPredictor();
            p.setShotSink((t, o, x, y, vx, vy, spec) -> {
                if (!spec) {
                    fired.add(t + "/" + o);
                }
            });
            s.hold(11, 18, FIRE, 0);
            p.update(s, 0);
            assertAll(
                () -> assertEquals(List.of("11/0", "12/0", "14/0", "15/0", "17/0", "18/0"), fired,
                    "two in the air at most: the one of tick 11 is back at the end of tick 13, so 14 fires"));
        }
    }

    @Nested
    @DisplayName("own bullets given back")
    class GiveBack {
        @Test
        @DisplayName("a bullet into a wall is given back in the tick it crashes, so the gun fires again sooner")
        void wallShot() {
            Source s = new Source(options("fireRepeatRate: 1", WALL));
            ShipBody start = ship(240, 200, 0);
            start.shotMax = 1;
            s.predict(10, 100, start);
            s.hold(11, 16, FIRE, 0);
            List<Integer> fired = new ArrayList<>();
            OwnShipPredictor p = new OwnShipPredictor();
            p.setShotSink((t, o, x, y, vx, vy, spec) -> {
                if (!spec) {
                    fired.add(t);
                }
            });
            p.update(s, 0);
            assertTrue(fired.size() >= 3, "shotMax 1, but each shot dies in the wall at x 280 at once: " + fired);
        }
    }

    @Nested
    @DisplayName("own exhaust")
    class Exhaust {
        @Test
        @DisplayName("one exhaust per newly final thrusting tick, at its predicted state, never twice")
        void once() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 13, THRUST, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            List<Integer> ticks = new ArrayList<>();
            ShipBody at12 = reference(s, start, 12, THRUST);
            float x12 = Float.NaN;
            for (int i = p.takeExhaust(); i >= 0; i = p.takeExhaust()) {
                ticks.add(p.exhaustTick(i));
                if (p.exhaustTick(i) == 12) {
                    x12 = p.exhaustX(i);
                }
            }
            ShipBody off = reference(s, start, 12, THRUST);
            off.initPixels(px(off) + 1, py(off));
            s.predict(12, 101, off);
            s.hold(14, 14, 0, 0);
            p.update(s, 0);
            int afterReplay = p.exhaustPending();
            float x = x12;
            assertAll(
                () -> assertEquals(List.of(11, 12, 13), ticks, "ticks 11..13 once each"),
                () -> assertEquals((float) px(at12), x, "at the tick's predicted position"),
                () -> assertEquals(0, afterReplay, "replayed ticks and a non-thrusting tick make none"),
                () -> assertEquals(-1, p.takeExhaust(), "nothing pending"));
        }
    }

    @Nested
    @DisplayName("flags")
    class Flags {
        @Test
        @DisplayName("the eight key-decided bits come from the prediction, the rest from SELF")
        void merge() {
            int self = Records.ShipFlags.SHIELD | Records.ShipFlags.THRUSTING | Records.ShipFlags.CLOAKED
                | Records.ShipFlags.PHASING | Records.ShipFlags.EMERGENCY_SHIELD | Records.ShipFlags.DEFLECTOR
                | Records.ShipFlags.REFUELING | Records.ShipFlags.REPAIRING | Records.ShipFlags.PAUSED
                | Records.ShipFlags.HAS_BALL;
            int none = OwnShipPredictor.mergeFlags(self, 0, 0);
            int all = OwnShipPredictor.mergeFlags(0, Has.SHIELD | Has.CLOAKING_DEVICE | Has.PHASING_DEVICE
                | Has.EMERGENCY_SHIELD | Has.DEFLECTOR | Has.REFUEL | Has.REPAIR, Obj.THRUSTING);
            assertAll(
                () -> assertEquals(Records.ShipFlags.PAUSED | Records.ShipFlags.HAS_BALL, none,
                    "nothing predicted: only the SELF-owned bits stay"),
                () -> assertEquals(self & ~(Records.ShipFlags.PAUSED | Records.ShipFlags.HAS_BALL), all,
                    "each predicted bit maps to its flag"));
        }
    }

    @Nested
    @DisplayName("drawn state")
    class Drawn {
        @Test
        @DisplayName("heading, velocity and fuel are drawn between the final tick and the speculative step too")
        void lerpAll() {
            Source s = open();
            ShipBody start = ship(200, 200, 10);
            start.vx = 2;
            start.vy = -3;
            s.predict(10, 100, start);
            s.hold(11, 12, 0, 0.5);
            s.keys = LEFT | THRUST;
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            ShipBody a = reference(s, start, 12, 0);
            ShipBody b = new ShipBody();
            b.copyFrom(a);
            new Reference(s.world).step(b, 102, LEFT | THRUST, 0, 0);
            assertAll(
                () -> assertNotEquals(a.dir, b.dir, "the speculative step turned"),
                () -> assertEquals((a.dir + 0.5 * (b.dir - a.dir)) * 2 * Math.PI / Const.RES, p.heading(), 1e-9,
                    "heading half way"),
                () -> assertEquals(a.vx + 0.5 * (b.vx - a.vx), p.vx(), 1e-5, "vx half way"),
                () -> assertEquals(a.vy + 0.5 * (b.vy - a.vy), p.vy(), 1e-5, "vy half way"),
                () -> assertEquals(py(a) + 0.5 * (py(b) - py(a)), p.y(), 1e-9, "y half way"),
                () -> assertEquals(a.fuel + 0.5 * (b.fuel - a.fuel), p.fuel(), 1e-9, "fuel half way"),
                () -> assertEquals(a.fuelMax, p.fuelMax(), "fuelMax"),
                () -> assertEquals(12.5, p.drawnTick(), 1e-12, "drawn at 12.5"));
        }

        @Test
        @DisplayName("on an edge-wrapped map the drawn ship wraps across the edge, the heading across 0")
        void wraps() {
            Source s = new Source(options("edgeWrap: yes", OPEN));
            ShipBody start = ship(419, 200, 0);
            start.vx = 4;
            start.velocity = 4;
            s.predict(10, 100, start);
            s.hold(11, 11, 0, 0.5);
            s.keys = ControlKey.TURN_RIGHT.mask();
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            ShipBody a = reference(s, start, 11, 0);
            assertAll(
                () -> assertTrue(p.x() >= 0 && p.x() < 420, "inside the map: " + p.x()),
                () -> assertEquals(px(a) + 2, p.x(), 0.05, "half a tick past the wrapped tick 11 (x 3)"),
                () -> assertTrue(p.heading() >= 0 && p.heading() < 2 * Math.PI, "heading in [0, 2pi): " + p.heading()),
                () -> assertTrue(p.heading() > Math.PI, "turning right from 0 wraps below 2pi: " + p.heading()));
        }

        @Test
        @DisplayName("touching a wall is reported and the offset decays with the wall half-life there")
        void wall() {
            Source s = new Source(options("", WALL));
            ShipBody start = ship(262, 200, 0);
            start.vx = 6;
            start.velocity = 6;
            start.status |= 0;
            s.predict(10, 100, start);
            s.hold(11, 13, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            Source free = open();
            free.hold(11, 13, 0, 0);
            OwnShipPredictor q = new OwnShipPredictor();
            q.update(free, 0);
            assertAll(
                () -> assertTrue(p.touchingWall(), "bounced off the wall: touching"),
                () -> assertFalse(q.touchingWall(), "open space: not touching"));
        }

        @Test
        @DisplayName("a heading correction also glides: the drawn heading stays, the offset holds the turn")
        void headingCorrection() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 15, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            double h = p.heading();
            ShipBody server = reference(s, start, 13, 0);
            server.dir = 2;
            server.floatDir = 2;
            s.predict(13, 102, server);
            p.update(s, 0);
            assertAll(
                () -> assertEquals(h, p.heading(), 1e-9, "the drawn heading did not jump"),
                () -> assertEquals(-2, p.smoother().offsetHeading(), 1e-9, "the offset holds -2 RES units"));
        }
    }

    @Nested
    @DisplayName("ring details")
    class RingDetails {
        @Test
        @DisplayName("with fireRepeatRate the C frame clock decides which held ticks fire, as the reference")
        void frameClock() {
            Source s = new Source(options("fireRepeatRate: 3", OPEN));
            ShipBody start = ship(200, 200, 0);
            s.predict(10, 100, start);
            s.hold(11, 22, FIRE, 0);
            List<Integer> got = new ArrayList<>();
            OwnShipPredictor p = new OwnShipPredictor();
            p.setShotSink((t, o, x, y, vx, vy, spec) -> {
                if (!spec) {
                    got.add(t);
                }
            });
            p.update(s, 0);
            Reference r = new Reference(s.world);
            ShipBody b = new ShipBody();
            b.copyFrom(start);
            List<Integer> wantTicks = new ArrayList<>();
            for (int k = 11; k <= 22; k++) {
                r.step(b, 100 + (k - 11), FIRE, 0, 0);
                if (r.ev.fired() > 0) {
                    wantTicks.add(k);
                }
            }
            assertAll(
                () -> assertEquals(wantTicks, got, "fires on the reference's ticks"),
                () -> assertTrue(got.size() >= 3 && got.size() < 12, "not every tick: " + got));
        }

        @Test
        @DisplayName("a PAUSE key in an entry stops predicting; the next PREDICT resumes")
        void pauseKey() {
            Source s = open();
            s.hold(11, 12, 0, 0);
            s.input.set(13, ControlKey.PAUSE.mask(), 0, 0);
            s.finalTick = 13;
            s.clock = 13;
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            boolean paused = p.active();
            s.predict(12, 101, ship(200, 200, 0));
            s.hold(14, 14, 0, 0);
            p.update(s, 0);
            assertAll(
                () -> assertFalse(paused, "the PAUSE entry halts"),
                () -> assertFalse(p.active(), "the replay meets it again"));
        }

        @Test
        @DisplayName("a PREDICT older than the ring's base, or newer than its head, seeds without a comparison")
        void unknownTicks() {
            Source s = open();
            s.hold(11, 15, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            s.predict(16, 106, ship(150, 150, 0));
            s.hold(16, 17, 0, 0);
            p.update(s, 0);
            long afterNewer = p.reconciliations();
            s.predict(9, 99, ship(120, 120, 0));
            p.update(s, 0);
            assertAll(
                () -> assertEquals(0, afterNewer, "tick 16 beyond the head 15: a seed"),
                () -> assertEquals(0, p.reconciliations(), "tick 9 before the base 16: a seed"),
                () -> assertFalse(p.active(), "seeded at 9, which has no entry 10 left: halted"));
        }

        @Test
        @DisplayName("reset drops the offset and the provisional shots; a fresh seed clears an old offset")
        void resetClears() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 15, FIRE, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            int shotsBefore = p.shots().count();
            ShipBody off = reference(s, start, 13, FIRE);
            off.initPixels(px(off) + 3, py(off));
            s.predict(13, 102, off);
            p.update(s, 0);
            double offset = p.smoother().offsetPx();
            p.reset();
            double afterReset = p.smoother().offsetPx();
            int shotsAtReset = p.shots().count();
            p.update(s, 0);
            int shotsAfter = p.shots().count();
            assertAll(
                () -> assertTrue(shotsBefore > 0, "provisional shots drawn by default: " + shotsBefore),
                () -> assertTrue(offset > 2, "an offset after the correction"),
                () -> assertEquals(0, afterReset, "reset: no offset"),
                () -> assertEquals(0, shotsAtReset, "reset: no provisional shots"),
                () -> assertTrue(shotsAfter < shotsBefore, "reset dropped the older shots: " + shotsAfter));
        }

        @Test
        @DisplayName("a frozen ship fires and moves no more; its later ticks are copies")
        void frozenCopies() {
            Source s = new Source(options("", WALL));
            ShipBody start = ship(260, 200, 0);
            start.vx = 30;
            start.velocity = 30;
            s.predict(10, 100, start);
            s.hold(11, 18, FIRE | THRUST, 0);
            List<Integer> fired = new ArrayList<>();
            OwnShipPredictor p = new OwnShipPredictor();
            p.setShotSink((t, o, x, y, vx, vy, spec) -> fired.add(t));
            p.update(s, 0);
            List<Integer> exhaust = new ArrayList<>();
            for (int i = p.takeExhaust(); i >= 0; i = p.takeExhaust()) {
                exhaust.add(p.exhaustTick(i));
            }
            assertAll(
                () -> assertTrue(exhaust.stream().allMatch(t -> t <= 12), "no exhaust after the crash: " + exhaust),
                () -> assertTrue(p.frozen(), "crashed"),
                () -> assertTrue(fired.stream().allMatch(t -> t <= 12), "no shots after the crash: " + fired),
                () -> assertEquals(18, p.headTick(), "the ring still advanced"));
        }
    }

    @Nested
    @DisplayName("exhaust and statistics")
    class Stats {
        @Test
        @DisplayName("the exhaust of a tick carries its predicted position, velocity and heading; at most 16 pending")
        void exhaustFields() {
            Source s = open();
            ShipBody start = ship(200, 200, 8);
            s.predict(10, 100, start);
            s.hold(11, 30, THRUST, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            int pending = p.exhaustPending();
            int i = p.takeExhaust();
            ShipBody at = reference(s, start, p.exhaustTick(i), THRUST);
            assertAll(
                () -> assertEquals(OwnShipPredictor.EXHAUST_RING, pending, "20 thrusting ticks: the newest 16 kept"),
                () -> assertEquals(15, p.exhaustTick(i), "the oldest kept is tick 15"),
                () -> assertEquals((float) px(at), p.exhaustX(i), "x"),
                () -> assertEquals((float) py(at), p.exhaustY(i), "y"),
                () -> assertEquals(at.vx, p.exhaustVx(i), "vx"),
                () -> assertEquals(at.vy, p.exhaustVy(i), "vy"),
                () -> assertEquals((float) (8 * 2 * Math.PI / Const.RES), p.exhaustHeading(i), 1e-6, "heading"),
                () -> assertEquals(15, p.exhaustPending(), "one taken"));
        }

        @Test
        @DisplayName("the rate is the corrections over each whole second")
        void rate() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 15, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            long t0 = 10_000 * MS;
            p.update(s, t0);
            for (int k = 12; k <= 13; k++) {
                ShipBody off = reference(s, start, k, 0);
                off.initPixels(px(off) + k, py(off));
                s.predict(k, 100 + k - 10, off);
                p.update(s, t0 + 400 * MS * (k - 11));
            }
            p.update(s, t0 + 2000 * MS);
            double first = p.correctionsPerSecond();
            p.update(s, t0 + 4500 * MS);
            assertAll(
                () -> assertEquals(1.0, first, 1e-9, "2 corrections in the 2 s window"),
                () -> assertEquals(0.0, p.correctionsPerSecond(), 1e-9, "none in the next window"),
                () -> assertEquals(2, p.corrections(), "2 corrections"));
        }

        @Test
        @DisplayName("the tie-break is the reseeded source with predictableBounces (server options), else 0.5")
        void tieBreak() {
            Source on = new Source(options("predictableBounces: yes", OPEN));
            on.predict(10, 100, ship(200, 200, 0));
            on.hold(11, 11, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(on, 0);
            double a = p.tie();
            double b = p.tie();
            Source off = open();
            off.hold(11, 11, 0, 0);
            OwnShipPredictor q = new OwnShipPredictor();
            q.update(off, 0);
            assertAll(
                () -> assertNotEquals(a, b, "a stream of draws"),
                () -> assertEquals(0.5, q.tie(), "without predictableBounces: 0.5"));
        }
    }

    @Nested
    @DisplayName("more")
    class More {
        @Test
        @DisplayName("the ring outlasts the input history, so the history runs out first")
        void ringSize() {
            assertTrue(OwnShipPredictor.RING > com.fulcrologic.jxpilot.common.net.InputHistory.RING, "64 > 32");
        }

        @Test
        @DisplayName("after firing, a PREDICT of the server's state is still bit-equal (the C frame clock is right)")
        void exactAfterFiring() {
            Source s = new Source(options("fireRepeatRate: 3", OPEN));
            ShipBody start = ship(200, 200, 0);
            s.predict(10, 100, start);
            s.hold(11, 16, FIRE, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            s.predict(14, 103, reference(s, start, 14, FIRE));
            p.update(s, 0);
            assertAll(
                () -> assertEquals(1, p.exactReconciliations(), "bit-equal, shot time included"),
                () -> assertEquals(0, p.corrections(), "no correction"));
        }

        @Test
        @DisplayName("switching prediction off resets: the next PREDICT seeds; a fresh seed drops an old offset")
        void offResets() {
            Source s = open();
            ShipBody start = ship(200, 200, 0);
            s.hold(11, 15, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            ShipBody off = reference(s, start, 13, 0);
            off.initPixels(px(off) + 3, py(off));
            s.predict(13, 102, off);
            p.update(s, 0);
            double offset = p.smoother().offsetPx();
            p.enabled = false;
            p.update(s, 0);
            p.enabled = true;
            s.predict(15, 104, reference(s, start, 15, 0));
            p.update(s, 0);
            OwnShipPredictor q = new OwnShipPredictor();
            Source t = open();
            t.hold(11, 15, 0, 0);
            q.update(t, 0);
            ShipBody o2 = reference(t, start, 13, 0);
            o2.initPixels(px(o2) + 3, py(o2));
            t.predict(13, 102, o2);
            q.update(t, 0);
            t.predict(20, 109, ship(100, 100, 0));
            t.hold(16, 21, 0, 0);
            q.update(t, 0);
            assertAll(
                () -> assertTrue(offset > 2, "an offset before"),
                () -> assertEquals(1, p.reconciliations(), "after off and on the PREDICT is a seed"),
                () -> assertEquals(0, p.smoother().offsetPx(), "and the offset is gone"),
                () -> assertEquals(0, q.smoother().offsetPx(), "a PREDICT past the head seeds without the old offset"));
        }

        @Test
        @DisplayName("the shot rules come from the kernel options: without them shotLife 60 and no predictableBounces,"
            + " whatever the visible options say")
        void noKernelOptions() {
            Source s = new Source(options("shotLife: 2\npredictableBounces: yes", OPEN)) {
                @Override
                public com.fulcrologic.jxpilot.common.config.Options kernelOptions() {
                    return null;
                }
            };
            ShipBody start = ship(200, 200, 0);
            start.shotMax = 2;
            s.predict(10, 100, start);
            s.hold(11, 16, FIRE, 0);
            List<Integer> fired = new ArrayList<>();
            OwnShipPredictor p = new OwnShipPredictor();
            p.setShotSink((t, o, x, y, vx, vy, spec) -> {
                if (!spec) {
                    fired.add(t);
                }
            });
            p.update(s, 0);
            assertAll(
                () -> assertEquals(List.of(11, 13), fired, "shotLife 60 (not the visible 2): two shots, then the cap"
                    + " holds"),
                () -> assertEquals(0.5, p.tie(), "no predictableBounces"));
        }

        @Test
        @DisplayName("with predictableBounces the kernel's fly reseeds from (tick, own id): the speculative tick's")
        void reseeds() {
            Source on = new Source(options("predictableBounces: yes", OPEN));
            on.predict(10, 100, ship(200, 200, 0));
            on.hold(11, 12, 0, 0);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(on, 0);
            com.fulcrologic.jxpilot.common.sim.SeededTieBreak ref = new com.fulcrologic.jxpilot.common.sim.SeededTieBreak();
            ref.reseed(13, on.id);
            assertEquals(ref.getAsDouble(), p.tie(), "the source was reseeded for tick 13 and own id 3");
        }

        @Test
        @DisplayName("a speculative bullet is the collected entry's, numbered with the tick after the newest final")
        void speculativeShot() {
            Source s = open();
            s.hold(11, 12, 0, 0.5);
            s.keys = FIRE;
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            assertAll(
                () -> assertEquals(1, p.shots().count(), "drawn"),
                () -> assertEquals(13, p.shots().firedTick(0), "of tick 13"));
        }

        @Test
        @DisplayName("frozen, the speculative bullets are dropped")
        void frozenDropsSpeculative() {
            Source s = new Source(options("", WALL));
            ShipBody start = ship(200, 200, 64);   // nose away from the wall: the bullet flies free
            start.vx = 30;
            start.velocity = 30;
            s.predict(10, 100, start);
            s.hold(11, 11, 0, 0.5);
            s.keys = FIRE;
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            int before = p.shots().count();
            s.hold(12, 14, 0, 0.5);
            p.update(s, 0);
            assertAll(
                () -> assertEquals(1, before, "a speculative shot before the crash"),
                () -> assertTrue(p.frozen(), "crashed"),
                () -> assertEquals(0, p.shots().count(), "none drawn while frozen"));
        }

        @Test
        @DisplayName("the lerp of a ship crossing the wrapped edge mid-tick lands on the far side")
        void lerpAcrossEdge() {
            Source s = new Source(options("edgeWrap: yes", OPEN));
            ShipBody start = ship(414, 200, 0);
            start.vx = 4;
            start.velocity = 4;
            s.predict(10, 100, start);
            s.hold(11, 11, 0, 0.5);
            OwnShipPredictor p = new OwnShipPredictor();
            p.update(s, 0);
            ShipBody a = reference(s, start, 11, 0);
            assertEquals(Math.floorMod((long) Math.round((px(a) + 2) * 64), 420 * 64) / 64.0, p.x(), 1e-6,
                "half way from " + px(a) + ", wrapped");
        }
    }
}
