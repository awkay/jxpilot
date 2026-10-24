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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.link.LinkProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the latency harness. */
@DisplayName("latency harness parts")
class HarnessPartsTest {
    static final long MS = 1_000_000L;

    @Nested
    @DisplayName("Stats")
    class StatsTest {
        @Test
        @DisplayName("nearest-rank percentiles, NaN left out, NaN for an empty sample")
        void percentiles() {
            Stats s = Stats.of(5, Double.NaN, 1, 4, 2, 3, 10, 9, 8, 7, 6);
            Stats empty = Stats.of(Double.NaN);
            assertAll(
                () -> assertEquals(10, s.count(), "NaN is not counted"),
                () -> assertEquals(5, s.p50(), "p50 of 1..10 is the 5th value"),
                () -> assertEquals(10, s.p95(), "p95 of 1..10 is the 10th (ceil 9.5)"),
                () -> assertEquals(1, s.percentile(0), "p0 is the minimum"),
                () -> assertEquals(3, s.percentile(21), "p21 rounds the rank up: the 3rd"),
                () -> assertEquals(10, s.max(), "max"),
                () -> assertTrue(Double.isNaN(empty.p50()), "empty: NaN percentile"),
                () -> assertTrue(Double.isNaN(empty.max()), "empty: NaN max"),
                () -> assertEquals(0, empty.count(), "empty count"));
        }

        @Test
        @DisplayName("only the first count values are used, and the input array is left as it was")
        void prefix() {
            double[] v = {3, 1, 2, 100};
            Stats s = new Stats(v, 3);
            assertAll(
                () -> assertEquals(3, s.max(), "the 4th value is not part of the sample"),
                () -> assertEquals(3, v[0], "input not sorted in place"));
        }
    }

    @Nested
    @DisplayName("Scenario and Course")
    class ScenarioTest {
        @Test
        @DisplayName("a direction carries half the RTT plus the cell's jitter and loss")
        void direction() {
            LinkProfile clean = new Scenario(200, Scenario.Jitter.NONE, Scenario.Loss.NONE).direction();
            LinkProfile normal = new Scenario(100, Scenario.Jitter.NORMAL_15, Scenario.Loss.UNIFORM_1).direction();
            LinkProfile spikes = new Scenario(300, Scenario.Jitter.PARETO, Scenario.Loss.BURSTY_5).direction();
            assertAll(
                () -> assertEquals(100 * MS, clean.delayNanos, "200 ms RTT: 100 ms each way"),
                () -> assertEquals(0, clean.jitterSigmaNanos, "no jitter"),
                () -> assertEquals(0, clean.spikeProbability, "no spikes"),
                () -> assertEquals(0, clean.lossGood + clean.lossBad + clean.goodToBad, "no loss"),
                () -> assertEquals(50 * MS, normal.delayNanos, "100 ms RTT: 50 ms each way"),
                () -> assertEquals(15 * MS, normal.jitterSigmaNanos, "sigma 15 ms"),
                () -> assertEquals(0.01, normal.lossGood, "1% uniform"),
                () -> assertEquals(0, normal.goodToBad, "uniform: no bad state"),
                () -> assertEquals(150 * MS, spikes.delayNanos, "300 ms RTT: 150 ms each way"),
                () -> assertEquals(0.05, spikes.spikeProbability, "5% spikes"),
                () -> assertEquals(20 * MS, spikes.spikeScaleNanos, "20 ms scale"),
                () -> assertEquals(1.5, spikes.spikeShape, "shape 1.5"),
                () -> assertEquals(300 * MS, spikes.spikeCapNanos, "capped at 300 ms"),
                () -> assertEquals(1 / 3.0, spikes.badToGood, 1e-12, "bursts of mean length 3"),
                () -> assertEquals(0.05, spikes.goodToBad / (spikes.goodToBad + spikes.badToGood), 1e-12,
                    "5% long-run loss"));
        }

        @Test
        @DisplayName("labels read RTT/jitter/loss")
        void labels() {
            assertAll(
                () -> assertEquals("0/0/0", new Scenario(0, Scenario.Jitter.NONE, Scenario.Loss.NONE).label(), "clean"),
                () -> assertEquals("100/s15/1%u",
                    new Scenario(100, Scenario.Jitter.NORMAL_15, Scenario.Loss.UNIFORM_1).label(), "normal, uniform"),
                () -> assertEquals("300/spk/5%b",
                    new Scenario(300, Scenario.Jitter.PARETO, Scenario.Loss.BURSTY_5).label(), "spikes, bursty"));
        }

        @Test
        @DisplayName("every course thrusts from 300 to 800 ms after the spawn, and runs its map without or with robots")
        void course() {
            long thrust = ControlKey.THRUST.mask();
            assertAll(
                () -> assertEquals(0, Course.WALLS.keys(-1), "nothing before the spawn"),
                () -> assertEquals(0, Course.WALLS.keys(299 * MS), "nothing before 300 ms"),
                () -> assertEquals(thrust, Course.WALLS.keys(300 * MS), "thrust from 300 ms"),
                () -> assertEquals(thrust, Course.WALLS.keys(799 * MS), "still at 799 ms"),
                () -> assertEquals(0, Course.WALLS.keys(800 * MS), "coasting from 800 ms"),
                () -> assertEquals(List.of("--map", "../maps/pad.xp", "-maxRobots", "3", "-minRobots", "3"),
                    List.of(Course.GRAVITY_WELL.serverArgs(3)), "server command line"),
                () -> assertEquals("globe", Course.FREE_FLIGHT.map, "free flight on globe"),
                () -> assertEquals("tournament", Course.WALLS.map, "walls on tournament"),
                () -> assertEquals("doggy", Course.DOGFIGHT.map, "the dogfight on the smallest map"),
                () -> assertEquals(6, Course.DOGFIGHT.motionRobots, "with 6 robots, so they stay in view"),
                () -> assertEquals(3, Course.FREE_FLIGHT.motionRobots, "3 robots elsewhere"),
                () -> assertTrue(Course.FREE_FLIGHT.shotTrials && Course.WALLS.shotTrials, "shots on free flight and walls"),
                () -> assertFalse(Course.GRAVITY_WELL.shotTrials || Course.DOGFIGHT.shotTrials,
                    "not where the unshielded ship keeps crashing"));
        }
    }

    @Nested
    @DisplayName("InputToPhoton scheduling")
    class Scheduling {
        @Test
        @DisplayName("trials: earliest 1.5 s after the spawn, 263 ms apart; turn and thrust alternate; tick phase < 71 ms")
        void schedule() {
            assertAll(
                () -> assertEquals(1000 * MS + 1500 * MS, InputToPhoton.earliestT0(1000 * MS, 0), "first trial"),
                () -> assertEquals(1000 * MS + (1500 + 3 * 263) * MS, InputToPhoton.earliestT0(1000 * MS, 3),
                    "fourth trial"),
                () -> assertEquals(ControlKey.TURN_LEFT, InputToPhoton.keyOf(0), "even trials turn"),
                () -> assertEquals(ControlKey.THRUST, InputToPhoton.keyOf(1), "odd trials thrust"),
                () -> assertEquals(5, InputToPhoton.blockSeed(5, 0), "block 0 is the seed itself"),
                () -> assertEquals(5 + 2 * 7919, InputToPhoton.blockSeed(5, 2), "later blocks step by 7919"),
                () -> assertEquals(7_919_993L, InputToPhoton.tickPhase(1), "phase of seed 1"),
                () -> assertTrue(InputToPhoton.tickPhase(-5) >= 0 && InputToPhoton.tickPhase(-5) < 71_000_000L,
                    "phase is within one 14 Hz tick for any seed"));
        }

        @Test
        @DisplayName("shot trials: 2.5 s warm-up; the spawn shield is tapped off 100-200 ms after the spawn; parts")
        void shotSchedule() {
            long shield = ControlKey.SHIELD.mask();
            InputToPhoton.Result r = new InputToPhoton.Result(List.of(
                new InputToPhoton.Trial(ControlKey.FIRE_SHOT, 0, 140, InputToPhoton.Outcome.MEASURED, 0, Double.NaN, 48),
                new InputToPhoton.Trial(ControlKey.FIRE_SHOT, 0, 99, InputToPhoton.Outcome.DIED, 0, Double.NaN, 9),
                new InputToPhoton.Trial(ControlKey.TURN_LEFT, 0, 40, InputToPhoton.Outcome.MEASURED)), 0, 0, 0);
            assertAll(
                () -> assertEquals(1000 * MS + (2500 + 2 * 263) * MS, InputToPhoton.earliestShotT0(1000 * MS, 2),
                    "third shot trial"),
                () -> assertEquals(0, InputToPhoton.shotCourseKeys(Course.WALLS, 99 * MS), "no shield before 100 ms"),
                () -> assertEquals(shield, InputToPhoton.shotCourseKeys(Course.WALLS, 100 * MS), "shield from 100 ms"),
                () -> assertEquals(shield, InputToPhoton.shotCourseKeys(Course.WALLS, 199 * MS), "until 199 ms"),
                () -> assertEquals(0, InputToPhoton.shotCourseKeys(Course.WALLS, 200 * MS), "released at 200 ms"),
                () -> assertEquals(Course.THRUST, InputToPhoton.shotCourseKeys(Course.WALLS, 400 * MS),
                    "the course's own keys still apply"),
                () -> assertEquals(1, r.selfStats().count(), "own-ship part of measured fire trials only"),
                () -> assertEquals(48, r.selfStats().max(), "its value"),
                () -> assertEquals(92, r.drawDelayStats().max(), "shot minus own-ship part: 140 - 48"));
        }

        @Test
        @DisplayName("arrival phases are stratified over one tick; the tick/frame period; t0 lands on a period tick")
        void phases() {
            long[] ph = new long[4];
            for (int j = 0; j < 4; j++) {
                ph[j] = InputToPhoton.arrivalPhase(j, 4, 10);
            }
            // a fake tick grid: tick k at 1000 + 100 ms * k
            java.util.function.LongUnaryOperator grid = k -> 1000 + k * 100 * MS;
            long t0 = InputToPhoton.scheduleT0(grid, 1050 * MS, 30 * MS, 10 * MS, 7, 0);
            long far = InputToPhoton.scheduleT0(grid, 1_000_000 * MS, 0, 0, 3, 0);
            long exact = InputToPhoton.scheduleT0(grid, 1000 + 700 * MS + 20 * MS, 30 * MS, 10 * MS, 7, 7);
            long res = InputToPhoton.scheduleT0(grid, 1050 * MS, 30 * MS, 10 * MS, 7, 2);
            long neg = InputToPhoton.scheduleT0(grid, 1050 * MS, 30 * MS, 10 * MS, 7, -5);
            assertAll(
                () -> assertArrayEquals(new long[] {12_500_000, 37_500_000, 62_500_000, 87_500_000}, ph,
                    "(j + 1/2) / n of a 100 ms tick"),
                () -> assertEquals(7, InputToPhoton.phasePeriodTicks(14), "14 Hz: 7 ticks = 60 frames"),
                () -> assertEquals(1, InputToPhoton.phasePeriodTicks(12), "12 Hz: every tick is 10 frames"),
                () -> assertEquals(13, InputToPhoton.phasePeriodTicks(13), "13 Hz: 13 ticks = 120 frames"),
                () -> assertEquals(1000 + 1400 * MS + 20 * MS, t0, "residue 0: tick 14 (the first multiple of 7 whose t0 is"
                    + " late enough), + 30 ms phase - 10 ms uplink"),
                () -> assertEquals(1000 + 700 * MS + 20 * MS, exact, "a t0 exactly at the earliest time is taken"),
                () -> assertEquals(1000 + 1600 * MS + 20 * MS, res, "residue 2: tick 16 (11 is too early, 16 = 2 mod 7)"),
                () -> assertEquals(res, neg, "a negative residue is taken mod the period (-5 = 2 mod 7)"),
                () -> assertEquals(0, (far - 1000) % (300 * MS), "far ahead: still on a multiple of the period"),
                () -> assertTrue(far >= 1_000_000 * MS && far < 1_000_000 * MS + 300 * MS, "and the first one"));
        }

        @Test
        @DisplayName("results: stats per key over measured trials only; outcome counts")
        void result() {
            InputToPhoton.Result r = new InputToPhoton.Result(List.of(
                new InputToPhoton.Trial(ControlKey.TURN_LEFT, 0, 40, InputToPhoton.Outcome.MEASURED),
                new InputToPhoton.Trial(ControlKey.THRUST, 0, 60, InputToPhoton.Outcome.MEASURED, 0, 85),
                new InputToPhoton.Trial(ControlKey.THRUST, 0, Double.NaN, InputToPhoton.Outcome.DEAD_AT_T0, 0, 99),
                new InputToPhoton.Trial(ControlKey.TURN_LEFT, 0, Double.NaN, InputToPhoton.Outcome.NO_DEPARTURE)),
                0, 0, 0);
            InputToPhoton.Result none = new InputToPhoton.Result(List.of(), 0, 0, 0);
            assertAll(
                () -> assertEquals(50, r.mean(), 1e-12, "mean of the measured trials"),
                () -> assertTrue(Double.isNaN(none.mean()), "no trials: NaN mean"),
                () -> assertEquals(1, r.positionStats().count(), "position delay of measured thrust trials only"),
                () -> assertEquals(85, r.positionStats().max(), "its value"),
                () -> assertEquals(0, r.selfStats().count(), "no fire trials: no own-ship part"),
                () -> assertEquals(0, r.drawDelayStats().count(), "no fire trials: no draw delay"),
                () -> assertEquals(2, r.stats(null).count(), "two measured"),
                () -> assertEquals(40, r.stats(ControlKey.TURN_LEFT).max(), "turn trials"),
                () -> assertEquals(60, r.stats(ControlKey.THRUST).max(), "thrust trials"),
                () -> assertEquals(1, r.count(InputToPhoton.Outcome.DEAD_AT_T0), "one dead"),
                () -> assertEquals(1, r.count(InputToPhoton.Outcome.NO_DEPARTURE), "one without departure"));
        }
    }

    @Nested
    @DisplayName("MotionMetrics and ServerTruth")
    class Motion {
        @Test
        @DisplayName("distance and step change are wrap-aware; uniform motion has no step change")
        void geometry() {
            assertAll(
                () -> assertEquals(5, MotionMetrics.distance(0, 0, 3, 4, 100, 100, false), 1e-12, "3-4-5"),
                () -> assertEquals(5, MotionMetrics.distance(98, 0, 1, 4, 100, 100, true), 1e-12,
                    "across the wrap edge: 3 px in x"),
                () -> assertEquals(97, MotionMetrics.distance(98, 0, 1, 0, 100, 100, false), 1e-12, "no wrap: 97 px"),
                () -> assertEquals(0, MotionMetrics.stepChange(30, 0, 20, 0, 10, 0, 100, 100, false), 1e-12,
                    "uniform 10 px/frame"),
                () -> assertEquals(5, MotionMetrics.stepChange(35, 0, 20, 0, 10, 0, 100, 100, false), 1e-12,
                    "a 15 px step after a 10 px one"),
                () -> assertEquals(0, MotionMetrics.stepChange(2, 0, 96, 0, 90, 0, 100, 100, true), 1e-12,
                    "uniform across the wrap edge"),
                () -> assertEquals(5, MotionMetrics.stepChange(0, 5, 0, 0, 0, 0, 100, 100, false), 1e-12, "y counts"));
        }

        @Test
        @DisplayName("truth positions interpolate between ticks (wrap-aware); a missing or dead tick gives none")
        void truth() {
            ServerTruth t = new ServerTruth(1);
            t.put(1, 7, 10, 20, true, 0, 0, 0);
            t.put(2, 7, 20, 40, true, 0, 0, 0);
            t.put(3, 7, 95, 40, true, 0, 0, 0);
            t.put(4, 7, 5, 40, true, 0, 0, 0);
            t.put(5, 7, 5, 40, false, 0, 0, 0);
            ServerTruth ends = new ServerTruth(1);
            ends.put(1, 7, 0, 0, true, 0, 0, 0);
            ends.put(2, 7, 10, 0, true, 0, 0, 0);
            double[] out = new double[2];
            boolean mid = t.positionAt(7, 1.25, 100, 100, false, out);
            double mx = out[0];
            double my = out[1];
            boolean wrapped = t.positionAt(7, 3.5, 100, 100, true, out);
            double wx = out[0];
            assertAll(
                () -> assertTrue(mid, "between two recorded live ticks"),
                () -> assertEquals(12.5, mx, 1e-12, "x a quarter of the way"),
                () -> assertEquals(25, my, 1e-12, "y a quarter of the way"),
                () -> assertTrue(wrapped, "across the wrap"),
                () -> assertEquals(100, wx, 1e-12, "95 -> 5 across the edge: halfway is 100 (= 0)"),
                () -> assertFalse(t.positionAt(7, 4.5, 100, 100, true, out), "dead at tick 5: none"),
                () -> assertFalse(t.positionAt(7, 5.0, 100, 100, true, out), "tick 6 not recorded: none"),
                () -> assertFalse(t.positionAt(7, 0.5, 100, 100, true, out), "before tick 1: none"),
                () -> assertFalse(t.positionAt(8, 1.5, 100, 100, true, out), "unknown player: none"),
                () -> assertEquals(5, t.lastTick(), "newest tick"),
                () -> assertTrue(ends.positionAt(7, 1.5, 100, 100, false, new double[2]),
                    "between the second-newest and the newest recorded tick"));
        }

        @Test
        @DisplayName("ticks beyond the recording's capacity are ignored")
        void capacity() {
            ServerTruth t = new ServerTruth(1);
            t.put(ServerTruth.MAX_TICKS - 1, 7, 1, 1, true, 0, 0, 0);
            t.put(ServerTruth.MAX_TICKS, 7, 2, 2, true, 0, 0, 0);
            assertEquals(ServerTruth.MAX_TICKS - 1, t.lastTick(), "the last slot is used, the one past it is not");
        }

        @Test
        @DisplayName("jumped: a tick-to-tick move beyond the threshold in the range, wrap-aware")
        void jumped() {
            ServerTruth t = new ServerTruth(1);
            t.put(1, 7, 0, 0, true, 0, 0, 0);
            t.put(2, 7, 10, 0, true, 0, 0, 0);
            t.put(3, 7, 500, 0, true, 0, 0, 0);
            t.put(4, 7, 510, 0, true, 0, 0, 0);
            ServerTruth wrap = new ServerTruth(1);
            wrap.put(1, 7, 995, 0, true, 0, 0, 0);
            wrap.put(2, 7, 5, 0, true, 0, 0, 0);
            ServerTruth vertical = new ServerTruth(1);
            vertical.put(1, 7, 0, 990, true, 0, 0, 0);
            vertical.put(2, 7, 0, 10, true, 0, 0, 0);
            vertical.put(3, 7, 0, 200, true, 0, 0, 0);
            ServerTruth exact = new ServerTruth(1);
            exact.put(1, 7, 0, 0, true, 0, 0, 0);
            exact.put(2, 7, 60, 80, true, 0, 0, 0); // exactly 100 px
            assertAll(
                () -> assertTrue(t.jumped(7, 1, 4, 96, 1000, 1000, false), "490 px between ticks 2 and 3"),
                () -> assertTrue(t.jumped(7, 3, 3, 96, 1000, 1000, false), "the range includes the step into tick 3"),
                () -> assertFalse(t.jumped(7, 4, 9, 96, 1000, 1000, false), "after the jump: 10 px steps"),
                () -> assertFalse(t.jumped(7, 1, 2, 96, 1000, 1000, false), "before it"),
                () -> assertFalse(wrap.jumped(7, 1, 2, 96, 1000, 1000, true), "995 -> 5 across the wrap is 10 px"),
                () -> assertTrue(wrap.jumped(7, 1, 2, 96, 1000, 1000, false), "without wrap it is 990 px"),
                () -> assertFalse(t.jumped(9, 1, 4, 96, 1000, 1000, false), "unknown player"),
                () -> assertFalse(vertical.jumped(7, 1, 2, 96, 1000, 1000, true), "990 -> 10 in y across the wrap is 20 px"),
                () -> assertTrue(vertical.jumped(7, 3, 3, 96, 1000, 1000, true), "190 px in y is a jump"),
                () -> assertFalse(exact.jumped(7, 1, 2, 100, 1000, 1000, false), "exactly the threshold is no jump"),
                () -> assertFalse(vertical.jumped(7, 3, 3, 196, 1000, 1000, false), "10 -> 200 in y is 190 px, below 196"),
                () -> assertTrue(exact.jumped(7, 1, 2, 99.9, 1000, 1000, false), "just over it is"));
        }

        @Test
        @DisplayName("fire: the last fire frame differs, and live shots compare, for the own player")
        void fired() {
            ServerTruth a = new ServerTruth(1);
            ServerTruth b = new ServerTruth(1);
            a.putShots(5, 40, 1);
            b.putShots(5, 4, 0);
            a.putShots(6, 41, 0);
            b.putShots(6, 4, 0);
            a.putShots(7, 4, 2);
            b.putShots(7, 4, 2);
            assertAll(
                () -> assertTrue(a.shotTimeDiffers(b, 5), "fired at 5"),
                () -> assertTrue(a.moreShots(b, 5), "and the shot lives"),
                () -> assertTrue(a.shotTimeDiffers(b, 6), "fired at 6"),
                () -> assertFalse(a.moreShots(b, 6), "but no shot lives (died in a wall)"),
                () -> assertFalse(a.shotTimeDiffers(b, 7), "same last fire"),
                () -> assertFalse(a.moreShots(b, 7), "same shots"));
        }

        @Test
        @DisplayName("own heading and velocity compare as sent: dir, and the float bits of vx and vy")
        void differs() {
            ServerTruth a = new ServerTruth(1);
            ServerTruth b = new ServerTruth(1);
            a.put(1, 1, 0, 0, true, 32, 1.5f, 2f);
            b.put(1, 1, 0, 0, true, 32, 1.5f, 2f);
            a.put(2, 1, 0, 0, true, 33, 1.5f, 2f);
            b.put(2, 1, 0, 0, true, 32, 1.5f, -2f);
            a.put(3, 1, 0, 0, true, 0, 0.0f, 0f);
            b.put(3, 1, 0, 0, true, 0, -0.0f, 0f);
            a.put(3, 2, 0, 0, true, 99, 9f, 9f); // another player does not count
            assertAll(
                () -> assertFalse(a.headingDiffers(b, 1), "same dir"),
                () -> assertFalse(a.velocityDiffers(b, 1), "same velocity"),
                () -> assertTrue(a.headingDiffers(b, 2), "dir 33 vs 32"),
                () -> assertTrue(a.velocityDiffers(b, 2), "vy 2 vs -2"),
                () -> assertTrue(a.velocityDiffers(b, 3), "0.0 and -0.0 differ on the wire"),
                () -> assertFalse(a.headingDiffers(b, 3), "only the own player is compared"));
        }

        @Test
        @DisplayName("the predictor sink: exactness in percent, NaN before any reconciliation")
        void sink() {
            MotionMetrics.Sink s = new MotionMetrics.Sink();
            double none = s.exactPercent();
            s.correction(0, true);
            s.correction(3, false);
            s.correction(0, true);
            s.correction(0, true);
            assertAll(
                () -> assertTrue(Double.isNaN(none), "no reconciliations: NaN"),
                () -> assertEquals(75, s.exactPercent(), 1e-12, "3 of 4 bit-equal"),
                () -> assertEquals(4, s.corrections, "every correction kept"),
                () -> assertEquals(3, s.correction[1], "in order"));
        }
    }

    @Nested
    @DisplayName("MetricTable")
    class Table {
        @Test
        @DisplayName("numbers: fixed decimals, '-' for NaN")
        void num() {
            assertAll(
                () -> assertEquals("43", MetricTable.num(43.4, 0), "no decimals"),
                () -> assertEquals("0.25", MetricTable.num(0.25, 2), "two decimals"),
                () -> assertEquals("1.5", MetricTable.num(1.46, 1), "rounded"),
                () -> assertEquals("4.3", MetricTable.num(4.25, 1), "ties round half up"),
                () -> assertEquals("-", MetricTable.num(Double.NaN, 1), "NaN"));
        }

        @Test
        @DisplayName("a row lists I2P, offsets, trial counts, motion metrics and n/a for the predictor metrics")
        void row() {
            InputToPhoton.Result i = new InputToPhoton.Result(List.of(
                new InputToPhoton.Trial(ControlKey.TURN_LEFT, 0, 240, InputToPhoton.Outcome.MEASURED),
                new InputToPhoton.Trial(ControlKey.THRUST, 0, 250, InputToPhoton.Outcome.MEASURED, 0, 270),
                new InputToPhoton.Trial(ControlKey.THRUST, 0, Double.NaN, InputToPhoton.Outcome.DEAD_AT_T0)), 0, 0, 0);
            MotionMetrics.Result m = new MotionMetrics.Result(Stats.of(0.2, 1.0), Stats.of(9, 22), Stats.of(0.02, 5),
                Stats.of(0.1), 0, Stats.of(), Double.NaN, 42, 2, 203.4, 4.25, Double.NaN);
            InputToPhoton.Result shot = new InputToPhoton.Result(List.of(
                new InputToPhoton.Trial(ControlKey.FIRE_SHOT, 0, 340, InputToPhoton.Outcome.MEASURED, 0, Double.NaN, 248),
                new InputToPhoton.Trial(ControlKey.FIRE_SHOT, 0, 330, InputToPhoton.Outcome.MEASURED, 0, Double.NaN, 240),
                new InputToPhoton.Trial(ControlKey.FIRE_SHOT, 0, Double.NaN, InputToPhoton.Outcome.SHOT_DIED)), 0, 0, 0);
            MetricTable.Row r = new MetricTable.Row(Course.WALLS, new Scenario(200, Scenario.Jitter.NONE,
                Scenario.Loss.NONE), i, m, shot);
            MotionMetrics.Result noTeleport = new MotionMetrics.Result(Stats.of(0.2), Stats.of(), Stats.of(0.02),
                Stats.of(), 1.5, Stats.of(3, 4), 97.5, 0, 0, 8, 4.1, 0);
            String quiet = MetricTable.line(new MetricTable.Row(Course.GRAVITY_WELL, new Scenario(0, Scenario.Jitter.PARETO,
                Scenario.Loss.BURSTY_5), i, noTeleport, shot));
            String table = MetricTable.format(List.of(r));
            assertAll(
                () -> assertEquals("| walls | 200/0/0 | 240 | 250 | 40 | 45.0 | 240 | 250 | 270 | 2/3 | 135.0 | 330 | 340 | 90 | 2/3 | 0.2/1.0 | 9.0/22.0 (42)"
                    + " | 5.00/5.0 | 0.10 | 0.0 (2 tp) | 203/4.3/- | n/a (1c) | n/a (1c) |", MetricTable.line(r), "the row"),
                () -> assertEquals(MetricTable.HEADER + "\n" + MetricTable.RULE + "\n" + MetricTable.line(r) + "\n", table,
                    "header, rule, rows"),
                () -> assertEquals(MetricTable.HEADER.split("\\|").length, MetricTable.RULE.split("\\|").length,
                    "the rule has a cell per column"),
                () -> assertTrue(quiet.startsWith("| gravity well | 0/spk/5%b |"), quiet),
                () -> assertTrue(quiet.endsWith("| 1.5 | 8/4.1/0.0 | 4.0 | 97.5 |"),
                    "no teleports: no '(tp)'; predictor figures once there are some: " + quiet),
                () -> assertTrue(quiet.contains("| -/- (0) |"), "no robot seen: no aim error"));
        }
    }
}
