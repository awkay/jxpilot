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
 * newest SELF, inputs applied at the next server tick), so a change that adds own-ship latency fails here.
 * Phase 1c (prediction) is expected to break the lower bounds on purpose and must restate them.
 */
@DisplayName("latency baseline (phase 0)")
class LatencyBaselineTest {
    static final int TRIALS = Integer.getInteger("jxpilot.latency.trials", 48);
    /** Seeds 1-3 measured clean shot means RTT + 133.4..142.1 ms and p95 at most RTT + 171 ms. */
    static final int SHOT_LO = 120;
    static final int SHOT_HI = 150;
    static final int SHOT_P95 = 190;
    static final int SHOT_TRIALS = Integer.getInteger("jxpilot.latency.shotTrials", 24);
    static final int MOTION_SECONDS = Integer.getInteger("jxpilot.latency.seconds", 60);
    static final long SEED = Long.getLong("jxpilot.latency.seed", 1);
    static final int[] RTTS = {0, 100, 200, 300};

    static final List<MetricTable.Row> ROWS = new ArrayList<>();

    @BeforeAll
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    static void measure() throws IOException {
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
        String table = MetricTable.format(ROWS);
        System.out.println("[latency baseline] " + ROWS.size() + " cells, " + TRIALS + " I2P and " + SHOT_TRIALS
            + " shot trials each, "
            + MOTION_SECONDS + " s motion runs, seed " + SEED + ", " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        System.out.println(table);
        Path out = Path.of("target", "latency-baseline.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);
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
                + sh.count(InputToPhoton.Outcome.SHOT_DIED);
            // on the walls course a press often fires into the box's wall (the shot dies in its first tick), and the
            // unshielded ship crashes now and then: seeds 1-3 lost up to 8 of 24 there
            checks.add(() -> assertTrue(shotLost * 5 <= sh.trials().size() * 2, cell
                + "shots: at most 40% of the presses lost to a crash or a shot into a wall: " + shotLost));
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("shot I2P: on clean links mean RTT + 120..150 ms, p95 at most RTT + 190 ms, never under RTT + 91 ms;"
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
    @DisplayName("own-ship I2P on clean links: mean RTT + 40..48 ms, p50 RTT + 30..55 ms, p95 at most RTT + 90 ms")
    void cleanI2p() {
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            if (!clean(r.scenario())) {
                continue;
            }
            int rtt = r.scenario().rttMs();
            Stats s = r.i2p().stats(null);
            String cell = r.course() + " " + r.scenario().label() + ": ";
            // RTT + T/2 (36 ms, the wait for the next 14 Hz tick over stratified phases) + about half a frame each for
            // the key poll and the draw: about 44 ms. Seeds 1-3 measured mean RTT + 43.3..45.8, p50 RTT + 41..46, p95
            // RTT + 75..77
            double mean = r.i2p().mean();
            checks.add(() -> assertTrue(mean >= rtt + 40 && mean <= rtt + 48, cell + "mean " + mean));
            checks.add(() -> assertTrue(s.p50() >= rtt + 30 && s.p50() <= rtt + 55, cell + "p50 " + s.p50()));
            checks.add(() -> assertTrue(s.p95() <= rtt + 90, cell + "p95 " + s.p95()));
            checks.add(() -> assertTrue(s.percentile(0) >= rtt, cell + "no key shows sooner than one round trip: "
                + s.percentile(0)));
            for (ControlKey k : new ControlKey[] {ControlKey.TURN_LEFT, ControlKey.THRUST}) {
                Stats ks = r.i2p().stats(k);
                checks.add(() -> assertTrue(ks.p50() <= rtt + 80, cell + k + " p50 " + ks.p50()));
            }
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("own-ship I2P with jitter and loss: p50 at most RTT + 90 ms; p95 at most RTT + 250 ms, + 600 ms"
        + " with bursty loss")
    void impairedI2p() {
        List<Executable> checks = new ArrayList<>();
        for (MetricTable.Row r : ROWS) {
            int rtt = r.scenario().rttMs();
            Stats s = r.i2p().stats(null);
            String cell = r.course() + " " + r.scenario().label() + ": ";
            // seeds 1-3 measured p50 up to RTT + 69 ms, p95 up to RTT + 196 ms without bursty loss and RTT + 467 ms with
            // it (a burst can eat an INPUT and its redundant copy, and the SELF after it)
            int tail = r.scenario().loss() == Scenario.Loss.BURSTY_5 ? 600 : 250;
            checks.add(() -> assertTrue(s.p50() <= rtt + 90, cell + "p50 " + s.p50()));
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
}
