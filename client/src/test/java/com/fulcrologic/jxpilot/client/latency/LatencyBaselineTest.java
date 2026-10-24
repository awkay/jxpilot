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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 of docs/latency-fixes.md: the baseline latency tables. Every course (free flight, walls, gravity
 * well) over every link scenario (RTT 0/100/200/300 ms x jitter none/normal 15 ms/Pareto spikes x loss
 * none/1% uniform/5% bursty), measured headless in virtual time on the real server and client. The table is
 * printed and written to {@code target/latency-baseline.md}; docs/latency-measurements.md holds a copy and
 * says how to rerun it.
 * <p>
 * The assertions are loose regression guards around today's design (the own ship dead-reckoned from the
 * newest SELF; since phase 1b inputs tagged with the predicted tick and applied at that tick, sent ahead by the
 * input lead), so a change that adds own-ship latency fails here. Phase 1b restated them: the input lead's
 * slack (0.5 tick + 2 jitter) now comes on top of the wait for the tick (docs/latency-measurements.md).
 * <p>
 * Phase 1c predicts the own ship: by default ({@code -Djxpilot.latency.predict=true}) the cells are measured with
 * the predictor on and checked against its exit criteria (own-ship I2P p95 at most 50 ms at any RTT, corrections
 * small, no snaps); {@code -Djxpilot.latency.predict=false} measures the snapshot timeline (the "before" columns of
 * docs/latency-measurements.md) and checks the phase 1b bounds. With prediction on it also runs the exactness
 * table: every course flown by an active pilot ({@link Course#pilot}) on clean links, with and without the server
 * option predictableBounces.
 */
@DisplayName("latency baseline (phase 0)")
class LatencyBaselineTest {
    static final int TRIALS = Integer.getInteger("jxpilot.latency.trials", 48);
    /** Seeds 1-3 measured clean shot means RTT + 137.6..138.2 ms and p95 at most RTT + 167 ms. */
    static final int SHOT_LO = 130;
    static final int SHOT_HI = 148;
    static final int SHOT_P95 = 190;
    static final int SHOT_TRIALS = Integer.getInteger("jxpilot.latency.shotTrials", 24);
    static final int MOTION_SECONDS = Integer.getInteger("jxpilot.latency.seconds", 60);
    static final long SEED = Long.getLong("jxpilot.latency.seed", 1);
    static final int[] RTTS = {0, 100, 200, 300};
    static final boolean PREDICT = Boolean.parseBoolean(System.getProperty("jxpilot.latency.predict", "true"));
    static final int EXACT_SECONDS = Integer.getInteger("jxpilot.latency.exactSeconds", 60);

    static final List<MetricTable.Row> ROWS = new ArrayList<>();

    /** One exactness run: a course on a clean link at {@code rtt}, predictableBounces on or off. */
    record ExactRow(Course course, int rtt, boolean predictable, MotionMetrics.Result motion) { }

    static final List<ExactRow> EXACT = new ArrayList<>();

    @BeforeAll
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    static void measure() throws IOException {
        LatencyRig.withPrediction(PREDICT, () -> {
            try {
                measureAll();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        });
    }

    static void measureAll() throws IOException {
        long t0 = System.nanoTime();
        for (Course c : Course.values()) {
            for (int rtt : RTTS) {
                for (Scenario.Jitter j : Scenario.Jitter.values()) {
                    for (Scenario.Loss l : Scenario.Loss.values()) {
                        Scenario s = new Scenario(rtt, j, l);
                        ROWS.add(new MetricTable.Row(c, s, InputToPhoton.measure(c, s, TRIALS, SEED),
                            MotionMetrics.measure(c, s, c.motionRobots, MOTION_SECONDS, SEED),
                            c.shotTrials ? InputToPhoton.measureShots(c, s, SHOT_TRIALS, SEED)
                                : new InputToPhoton.Result(List.of(), 0, 0, 0)));
                    }
                }
            }
        }
        if (PREDICT) {
            for (Course c : Course.values()) {
                for (int rtt : new int[] {0, 100, 300}) {
                    for (boolean pb : new boolean[] {false, true}) {
                        EXACT.add(new ExactRow(c, rtt, pb, MotionMetrics.measure(c,
                            new Scenario(rtt, Scenario.Jitter.NONE, Scenario.Loss.NONE), 0, EXACT_SECONDS, SEED, pb,
                            Course::pilot)));
                    }
                }
            }
        }
        String table = MetricTable.format(ROWS) + (PREDICT ? "\n" + exactTable() : "");
        System.out.println("[latency baseline] prediction " + (PREDICT ? "on" : "off") + ", " + ROWS.size() + " cells, "
            + TRIALS + " I2P and " + SHOT_TRIALS + " shot trials each, " + MOTION_SECONDS + " s motion runs, seed "
            + SEED + ", " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        System.out.println(table);
        Path out = Path.of("target", PREDICT ? "latency-baseline.md" : "latency-baseline-off.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);
    }

    /** The exactness table: per course, RTT and predictableBounces, the reconciliations and their corrections. */
    static String exactTable() {
        StringBuilder sb = new StringBuilder("| course | RTT | predictableBounces | reconciliations | exact % |"
            + " correction p95/max | own err p95 | snaps/min |\n|---|---:|---|---:|---:|---:|---:|---:|\n");
        for (ExactRow r : EXACT) {
            MotionMetrics.Result m = r.motion();
            sb.append("| ").append(r.course().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                .append(" | ").append(r.rtt()).append(" | ").append(r.predictable() ? "on" : "off")
                .append(" | ").append(m.correction().count())
                .append(" | ").append(MetricTable.num(m.exactPercent(), 2))
                .append(" | ").append(MetricTable.num(m.correction().p95(), 2)).append('/')
                .append(MetricTable.num(m.correction().max(), 1))
                .append(" | ").append(MetricTable.num(m.ownError().p95(), 2))
                .append(" | ").append(MetricTable.num(m.snapsPerMinute(), 1)).append(" |\n");
        }
        return sb.toString();
    }

    static boolean clean(Scenario s) {
        return s.jitter() == Scenario.Jitter.NONE && s.loss() == Scenario.Loss.NONE;
    }

    @Test
    @DisplayName("the harness is sound: runs are identical before the key, every key shows, few trials hit a crash")
    void harnessSound() {
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            String cell = r.course() + " " + r.scenario().label() + ": ";
            InputToPhoton.Result i = r.i2p();
            checks.add(() -> assertEquals(0, i.preT0Mismatches(), cell + "every frame before the key equals the baseline"));
            checks.add(() -> assertEquals(0, i.spawnMismatches(), cell + "every trial spawns when the baseline did"));
            checks.add(() -> assertEquals(0, i.invisible(), cell + "the I2P frame draws a heading/velocity the baseline does not"));
            checks.add(() -> assertEquals(0, i.count(InputToPhoton.Outcome.NO_DEPARTURE), cell + "every key shows within 2 s"));
            long lost = i.count(InputToPhoton.Outcome.DEAD_AT_T0) + i.count(InputToPhoton.Outcome.DIED);
            checks.add(() -> assertTrue(lost * 4 <= i.trials().size(), cell
                + "at most a quarter of the trials lost to a crash at or after t0: " + lost));
            InputToPhoton.Result sh = r.shot();
            checks.add(() -> assertEquals(0, sh.preT0Mismatches(), cell + "shots: frames before the press equal the baseline"));
            checks.add(() -> assertEquals(0, sh.invisible(), cell + "shots: none drawn before a snapshot of its tick"));
            checks.add(() -> assertEquals(0, sh.count(InputToPhoton.Outcome.NO_DEPARTURE), cell
                + "shots: every press fired a server shot that was drawn within 2 s"));
            long shotLost = sh.count(InputToPhoton.Outcome.DEAD_AT_T0) + sh.count(InputToPhoton.Outcome.DIED)
                + sh.count(InputToPhoton.Outcome.SHOT_DIED) + sh.count(InputToPhoton.Outcome.SHOT_UNSEEN);
            // lost presses are retried in their stratum (up to 3 times); seeds 1-3 lost none after the retries
            checks.add(() -> assertTrue(shotLost * 24 <= sh.trials().size(), cell
                + "shots: at most 1 in 24 presses still lost after the retries: " + shotLost));
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("shot I2P: on clean links mean RTT + 130..148 ms, p95 at most RTT + 190 ms, never under RTT + 91 ms;"
        + " each shot drawn 11-12 frames (the interpolation delay) after its own-ship SELF")
    void shotI2p() {
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            if (!r.course().shotTrials) {
                continue;
            }
            int rtt = r.scenario().rttMs();
            InputToPhoton.Result sh = r.shot();
            Stats draw = sh.drawDelayStats();
            String cell = r.course() + " " + r.scenario().label() + ": ";
            checks.add(() -> assertTrue(sh.stats(null).count() * 2 >= sh.trials().size(), cell + "shots: half measured"));
            if (clean(r.scenario())) {
                // 1 snapshot interval + 20 ms = 91.4 ms at 14 Hz, drawn at the next 120 Hz frame: 11-12 frames
                checks.add(() -> assertTrue(draw.percentile(0) >= 83 && draw.max() <= 101, cell + "shot - own "
                    + draw.percentile(0) + ".." + draw.max()));
                double mean = sh.mean();
                checks.add(() -> assertTrue(mean >= rtt + SHOT_LO && mean <= rtt + SHOT_HI, cell + "shot mean " + mean));
                checks.add(() -> assertTrue(sh.stats(null).p95() <= rtt + SHOT_P95, cell + "shot p95 "
                    + sh.stats(null).p95()));
                checks.add(() -> assertTrue(sh.stats(null).percentile(0) >= rtt + 91, cell
                    + "no shot shows sooner than RTT + the interpolation delay: " + sh.stats(null).percentile(0)));
            }
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("own-ship I2P on clean links: mean RTT + 75..100 ms, p50 RTT + 70..100 ms, p95 at most RTT + 135 ms")
    void cleanI2p() {
        assumeFalse(PREDICT, "the snapshot timeline's bounds (prediction off)");
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            if (!clean(r.scenario())) {
                continue;
            }
            int rtt = r.scenario().rttMs();
            Stats s = r.i2p().stats(null);
            String cell = r.course() + " " + r.scenario().label() + ": ";
            // phase 0: RTT + T/2 (36 ms, the wait for the next 14 Hz tick) + about half a frame each for the key poll
            // and the draw, about 44 ms (seeds 1-3: mean RTT + 43.3..45.8). Phase 1b: the wait for the predicted
            // tick (T/2) + the input slack the lead controller holds (0.5 tick + half its 0.125 deadband, ~40 ms)
            // + the frames: seed 1 measured mean RTT + 80..93, p50 RTT + 80..92, p95 at most RTT + 124
            double mean = r.i2p().mean();
            checks.add(() -> assertTrue(mean >= rtt + 75 && mean <= rtt + 100, cell + "mean " + mean));
            checks.add(() -> assertTrue(s.p50() >= rtt + 70 && s.p50() <= rtt + 100, cell + "p50 " + s.p50()));
            checks.add(() -> assertTrue(s.p95() <= rtt + 135, cell + "p95 " + s.p95()));
            checks.add(() -> assertTrue(s.percentile(0) >= rtt, cell + "no key shows sooner than one round trip: "
                + s.percentile(0)));
            for (ControlKey k : new ControlKey[] {ControlKey.TURN_LEFT, ControlKey.THRUST}) {
                Stats ks = r.i2p().stats(k);
                checks.add(() -> assertTrue(ks.p50() <= rtt + 100, cell + k + " p50 " + ks.p50()));
            }
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("own-ship I2P with jitter and loss: p50 at most RTT + 180 ms; p95 at most RTT + 400 ms, + 600 ms"
        + " with bursty loss")
    void impairedI2p() {
        assumeFalse(PREDICT, "the snapshot timeline's bounds (prediction off)");
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            int rtt = r.scenario().rttMs();
            Stats s = r.i2p().stats(null);
            String cell = r.course() + " " + r.scenario().label() + ": ";
            // phase 0 (seeds 1-3): p50 up to RTT + 69 ms, p95 up to RTT + 196 ms without bursty loss and RTT + 467 ms
            // with it. Phase 1b (seed 1): p50 up to RTT + 168 ms (jitter raises the slack target by 2 x jitter), p95 up
            // to RTT + 353 ms without bursty loss (Pareto spikes) and RTT + 380 ms with it (the lead jumps on a
            // starvation, and the mid-tick repeat doubles the copies sent before the deadline)
            int tail = r.scenario().loss() == Scenario.Loss.BURSTY_5 ? 600 : 400;
            checks.add(() -> assertTrue(s.p50() <= rtt + 180, cell + "p50 " + s.p50()));
            checks.add(() -> assertTrue(s.p95() <= rtt + tail, cell + "p95 " + s.p95()));
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("the HUD's RTT (client PING) reads the link's RTT on clean links: RTT .. RTT + 10 ms")
    void hudRtt() {
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            if (!clean(r.scenario())) {
                continue;
            }
            int rtt = r.scenario().rttMs();
            double hud = r.motion().hudRttMs();
            String cell = r.course() + " " + r.scenario().label() + ": ";
            // the PONG is answered on arrival and read at the next 120 Hz frame: at most 8.3 ms late
            checks.add(() -> assertTrue(hud >= rtt && hud <= rtt + 10, cell + "HUD rtt " + hud));
            checks.add(() -> assertEquals(0, r.motion().hudLossPercent(), 1e-9, cell + "HUD loss 0 on a clean link"));
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("own ship drawn close to the server's truth, smoothly, without snaps (dead reckoning, F1)")
    void ownMotion() {
        assumeFalse(PREDICT, "the snapshot timeline's bounds (prediction off)");
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            MotionMetrics.Result m = r.motion();
            String cell = r.course() + " " + r.scenario().label() + ": ";
            checks.add(() -> assertTrue(m.ownError().count() > 0, cell + "own error measured"));
            if (r.course() == Course.DOGFIGHT) {
                checks.add(() -> assertTrue(m.otherFrames() > 100, cell + "the robots stay in view: " + m.otherFrames()));
            }
            checks.add(() -> assertTrue(m.ownError().p95() < 20, cell + "own error p95 < 20 px: " + m.ownError().p95()));
            if (clean(r.scenario())) {
                checks.add(() -> assertTrue(m.ownSmooth().p95() < 0.5, cell + "own step change p95 < 0.5 px: "
                    + m.ownSmooth().p95()));
                checks.add(() -> assertEquals(0, m.snapsPerMinute(), 1e-9, cell + "no snaps on a clean link"));
            }
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("predicted own-ship I2P: p95 at most 50 ms in every cell (any RTT, jitter and loss), never negative")
    void predictedI2p() {
        assumeTrue(PREDICT, "prediction on");
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            Stats s = r.i2p().stats(null);
            String cell = r.course() + " " + r.scenario().label() + ": ";
            // the key shows in the frame that polls it (sendInput runs before the draw) or the next: 0-8.3 ms, and a
            // tap of less than a frame shows in the speculative tick's alpha
            checks.add(() -> assertTrue(s.p95() <= 50, cell + "p95 " + s.p95()));
            checks.add(() -> assertTrue(s.percentile(0) >= 0, cell + "min " + s.percentile(0)));
            checks.add(() -> assertTrue(s.count() * 4 >= r.i2p().trials().size() * 3, cell + "measured "
                + s.count()));
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("predicted own ship: drawn on the server's path (error p95 < 4 px on clean links), smooth, no snaps")
    void predictedMotion() {
        assumeTrue(PREDICT, "prediction on");
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            MotionMetrics.Result m = r.motion();
            String cell = r.course() + " " + r.scenario().label() + ": ";
            checks.add(() -> assertTrue(m.ownError().count() > 0, cell + "own error measured"));
            checks.add(() -> assertTrue(m.correction().count() > 0, cell + "reconciliations reported"));
            checks.add(() -> assertTrue(m.ownError().p95() < 20, cell + "own error p95 < 20 px: " + m.ownError().p95()));
            if (clean(r.scenario()) && r.course() != Course.DOGFIGHT) {
                checks.add(() -> assertTrue(m.ownError().p95() < 4, cell + "own error p95 < 4 px: "
                    + m.ownError().p95()));
                checks.add(() -> assertTrue(m.correction().p95() < 4, cell + "correction p95 < 4 px: "
                    + m.correction().p95()));
                checks.add(() -> assertTrue(m.ownSmooth().p95() < 0.5, cell + "own step change p95 < 0.5 px: "
                    + m.ownSmooth().p95()));
                checks.add(() -> assertEquals(0, m.snapsPerMinute(), 1e-9, cell + "no snaps on a clean link"));
            }
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("exactness with an active pilot: 100% bit-equal with predictableBounces on contact-free courses,"
        + " at least 99% without; corrections p95 < 4 px; no snaps")
    void exactness() {
        assumeTrue(PREDICT, "prediction on");
        List<Executable> checks = new ArrayList<>();
        for (ExactRow r : EXACT) {
            MotionMetrics.Result m = r.motion();
            String cell = r.course() + " RTT " + r.rtt() + (r.predictable() ? " predictableBounces: " : ": ");
            boolean contactFree = r.course() == Course.WALLS || r.course() == Course.GRAVITY_WELL;
            checks.add(() -> assertTrue(m.correction().count() > EXACT_SECONDS * 10, cell + "reconciled "
                + m.correction().count()));
            if (contactFree) {
                checks.add(() -> assertTrue(m.exactPercent() >= (r.predictable() ? 100 : 99), cell + "exact "
                    + m.exactPercent() + "%"));
            }
            checks.add(() -> assertTrue(m.correction().p95() < 4, cell + "correction p95 " + m.correction().p95()));
            checks.add(() -> assertEquals(0, m.snapsPerMinute(), 1e-9, cell + "no snaps"));
        }
        assertAll(checks);
    }
}
