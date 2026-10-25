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
        @DisplayName("F14: a shot with a (wire) velocity that B no longer has died: drawn where A has it, no overshoot")
        void shots() {
            WorldFixture f = twoSnapshots(true, 690, 10);
            f.world.update(at(101));
            assertAll(
                () -> assertEquals(1, f.world.shotCount(), "A's one shot"),
                () -> assertEquals(200.0, f.world.shotX(0), CLOCK_EPS, "x as in A (not + 4 px/frame * 0.25)"),
                () -> assertEquals(150.0, f.world.shotY(0), CLOCK_EPS, "y as in A"),
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
    @DisplayName("association across a lost or late snapshot (frames link to the nearest within 2 intervals)")
    class Gaps {
        static final int LIFE = 12; // plus the lone bullet: within the fixture's 16 shots
        static final int STEP = 4; // px per tick: a wire velocity of 16 px per game frame (4 ticks)
        final int lone = Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.WHITE);

        /**
         * A dense stream: one bullet fired every tick at x 50 moving +x, so neighbours are STEP px apart, living
         * LIFE ticks; the records reversed on every other snapshot. Plus one bullet without a wire velocity moving
         * 2 px per tick at y 250. Returns the spawn tick of each record, by index.
         */
        int[] stream(WorldFixture f, int tick) {
            f.begin(tick);
            int[] spawn = new int[LIFE];
            boolean reverse = (tick / 2) % 2 == 1;
            for (int k = 0; k < LIFE; k++) {
                int sp = reverse ? tick - LIFE + 1 + k : tick - k;
                spawn[k] = sp;
                f.shot(50 + STEP * (tick - sp), 100, 4 * STEP, 0);
            }
            f.shot(lone, 300 + 2 * (tick - 100), 250);
            return spawn;
        }

        /** Index of {@code spawn} in {@code spawns}, -1 when absent. */
        int indexOf(int[] spawns, int spawn) {
            for (int k = 0; k < spawns.length; k++) {
                if (spawns[k] == spawn) {
                    return k;
                }
            }
            return -1;
        }

        @Test
        @DisplayName("a dense burst with one snapshot lost: every bullet links to itself across the gap, ages run on"
            + " continuously, and a velocity-less bullet's speed is estimated over the whole gap")
        void lostSnapshot() {
            WorldFixture f = new WorldFixture(false);
            int[] ticks = {100, 102, 104, 108, 110};
            int[][] spawns = new int[ticks.length][];
            int mislinks = 0;
            int unlinked = 0;
            int acrossGap = 0;
            int ageErrors = 0;
            for (int n = 0; n < ticks.length; n++) {
                spawns[n] = stream(f, ticks[n]);
                f.ingestAtTick(ticks[n]);
                if (n == 0) {
                    continue;
                }
                SnapshotFrame a = f.world.frameAt(ticks[n - 1]);
                SnapshotFrame b = f.world.frameAt(ticks[n]);
                for (int i = 0; i < LIFE; i++) {
                    int want = indexOf(spawns[n], spawns[n - 1][i]);
                    int got = a.shotNext[i];
                    if (want >= 0 && got < 0) {
                        unlinked++;
                    } else if (got != want) {
                        mislinks++;
                    } else if (want >= 0 && ticks[n] - ticks[n - 1] == 4) {
                        acrossGap++;
                    }
                }
                for (int j = 0; j < LIFE; j++) {
                    int firstSeen = spawns[n][j];
                    for (int t : ticks) {
                        if (t >= firstSeen) {
                            firstSeen = Math.max(t, 100);
                            break;
                        }
                    }
                    firstSeen = Math.max(firstSeen, 100);
                    if (b.shotAge[j] != ticks[n] - firstSeen) {
                        ageErrors++;
                    }
                }
            }
            SnapshotFrame at108 = f.world.frameAt(108);
            float loneVx = at108.shotEstVx[LIFE];
            int fMislinks = mislinks;
            int fUnlinked = unlinked;
            int fAcross = acrossGap;
            int fAgeErrors = ageErrors;
            assertAll(
                () -> assertEquals(0, fMislinks, "no bullet linked to a neighbour STEP px away"),
                () -> assertEquals(0, fUnlinked, "no continuing bullet left unlinked"),
                () -> assertEquals(LIFE - 4, fAcross, "104 to 108: the bullets alive in both are linked across the gap"),
                () -> assertEquals(0, fAgeErrors, "ages: ticks since first seen, also for bullets first seen after the gap"),
                () -> assertEquals(8f, loneVx, 1e-3f, "the velocity-less bullet: 8 px over the 4-tick gap = 8 px/frame"),
                () -> assertEquals(Tracker.KNOWN, at108.shotState[LIFE], "from its link (KNOWN)"));
        }

        @Test
        @DisplayName("a late, reordered middle snapshot: the later frame's first sights and tracer origins are kept"
            + " (no second tracer), its ages count from the late frame, and it links both ways")
        void lateMiddle() {
            WorldFixture f = new WorldFixture(false);
            int[] s100 = stream(f, 100);
            f.ingestAtTick(100);
            int[] s104 = stream(f, 104);
            f.ingestAtTick(104);
            SnapshotFrame b = f.world.frameAt(104);
            long[] seen = java.util.Arrays.copyOf(b.shotSeenNanos, LIFE);
            float[] ox = java.util.Arrays.copyOf(b.shotOriginX, LIFE);
            int fresh = 0;
            for (int j = 0; j < LIFE; j++) {
                fresh += seen[j] == at(104) ? 1 : 0;
            }
            int[] s102 = stream(f, 102);
            int r = f.ingest(at(104) + 5_000_000L);
            SnapshotFrame a = f.world.frameAt(100);
            SnapshotFrame m = f.world.frameAt(102);
            int fFresh = fresh;
            int wrongLinks = 0;
            for (int i = 0; i < LIFE; i++) {
                wrongLinks += a.shotNext[i] != indexOf(s102, s100[i]) ? 1 : 0;
                wrongLinks += m.shotNext[i] != indexOf(s104, s102[i]) ? 1 : 0;
            }
            int fWrong = wrongLinks;
            int[] wantAges = new int[LIFE];
            for (int j = 0; j < LIFE; j++) {
                int sp = s104[j];
                wantAges[j] = 104 - (sp <= 100 ? 100 : sp <= 102 ? 102 : 104);
            }
            assertAll(
                () -> assertEquals(ClientWorld.LATE_TICK, r, "102 arrives after 104"),
                () -> assertEquals(4, fFresh, "104 first showed the 4 bullets fired after 100, with tracers"),
                () -> assertArrayEquals(seen, java.util.Arrays.copyOf(b.shotSeenNanos, LIFE),
                    "104's first sights unchanged: no tracer restarted at 102's (later) arrival"),
                () -> assertArrayEquals(ox, java.util.Arrays.copyOf(b.shotOriginX, LIFE), 0f, "nor its origins"),
                () -> assertArrayEquals(wantAges, java.util.Arrays.copyOf(b.shotAge, LIFE),
                    "104's ages learn from 102: ticks since the first snapshot that had the bullet"),
                () -> assertEquals(0, fWrong, "100 to 102 and 102 to 104 link each bullet to itself"));
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
        @DisplayName("watching another player: our own (paused) ship is in the ship list; the watched one (SELF) is not")
        void ownShipListedWhileWatching() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.players.setSelfId(7);
            f.begin(100).self(9, 0, 100, 100, 0, 0, 0).ship(7, ShipFlags.PAUSED, 300, 100, 0, 5, 0)
                .ship(9, 0, 100, 100, 0, 0, 0).ingestAtTick(100);
            f.begin(101).self(9, 0, 100, 100, 0, 0, 0).ship(7, ShipFlags.PAUSED, 300, 100, 0, 4, 0).ingestAtTick(101);
            f.world.update(at(100.5));
            int n = f.world.shipCount();
            int own = n > 0 && f.world.shipId(0) == 7 ? 0 : -1;
            WorldFixture plain = new WorldFixture(true);
            plain.world.setInterpolationDelayTicks(0);
            plain.players.setSelfId(7);
            plain.begin(100).self(7, 0, 100, 100, 0, 0, 0).ship(8, 0, 300, 100, 0, 0, 0).ingestAtTick(100);
            plain.begin(101).self(7, 0, 100, 100, 0, 0, 0).ship(8, 0, 300, 100, 0, 0, 0).ingestAtTick(101);
            plain.world.update(at(100.5));
            assertAll(
                () -> assertEquals(1, n, "one ship drawn from the list: ours (the watched id 9 is SELF)"),
                () -> assertEquals(0, own, "it is our own id 7, paused, at its place"),
                () -> assertEquals(300.0, f.world.shipX(0), CLOCK_EPS, "where it was sent"),
                () -> assertEquals(1, plain.world.shipCount(), "not watching: the ships are the others"),
                () -> assertEquals(8, plain.world.shipId(0), "not watching: our own id never appears in the list"));
        }

        @Test
        @DisplayName("a new shot is seeded from the nearest ship's velocity, but a paused ship (vx = count) is still")
        void seedIgnoresPauseCount() {
            WorldFixture f = new WorldFixture(true);
            SnapshotFrame pausedNear = new SnapshotFrame(4, 4);
            SnapshotFrame flyingNear = new SnapshotFrame(4, 4);
            for (SnapshotFrame a : new SnapshotFrame[] {pausedNear, flyingNear}) {
                a.ships = 1;
                a.shipId[0] = 9;
                a.shipX[0] = 100;
                a.shipY[0] = 100;
                a.shipVx[0] = 8; // the pause count when paused, a velocity otherwise
                a.shipVy[0] = 0;
                a.shots = 1;
                a.shotX[0] = 110;
                a.shotY[0] = 100;
                a.shotState[0] = Tracker.UNKNOWN;
            }
            pausedNear.shipFlags[0] = ShipFlags.PAUSED;
            f.world.seedShots(pausedNear, 1);
            f.world.seedShots(flyingNear, 1);
            float shotSpeed = flyingNear.shotEstVx[0] - 8; // heading 0: ship vx + shotSpeed
            assertAll(
                () -> assertEquals(Tracker.GUESSED, flyingNear.shotState[0], "(the shot was seeded)"),
                () -> assertTrue(shotSpeed > 0, "(a flying ship adds its velocity: " + shotSpeed + ")"),
                () -> assertEquals(Tracker.GUESSED, pausedNear.shotState[0], "a paused ship seeds the shot too"),
                () -> assertEquals(shotSpeed, pausedNear.shotEstVx[0], 1e-4f, "but adds no velocity: its vx is a count"),
                () -> assertEquals(0f, pausedNear.shotEstVy[0], 1e-4f, "heading 0: no y speed"));
        }

        @Test
        @DisplayName("a paused ship's vx is its pause count, not motion: it stays put and keeps the count")
        void pausedShipStays() {
            WorldFixture f = new WorldFixture(true);
            f.world.setInterpolationDelayTicks(0);
            f.begin(100).ship(9, ShipFlags.PAUSED, 100, 100, 0, 8, 0).ship(10, 0, 200, 100, 0, 8, 0).ingestAtTick(100);
            f.world.update(at(110));
            int paused = f.world.shipId(0) == 9 ? 0 : 1;
            int flying = 1 - paused;
            assertAll(
                () -> assertEquals(100.0, f.world.shipX(paused), CLOCK_EPS, "the paused ship is not moved by its count"),
                () -> assertEquals(8f, f.world.shipVx(paused), "the count survives to the scene"),
                () -> assertEquals(208.0, f.world.shipX(flying), CLOCK_EPS, "a flying ship with the same vx does move"));
        }

        /** Frame {@code tick}: the own ship 7 at (x, 50) and every kind of line with an end on it. */
        static void ownLines(WorldFixture f, int tick, double x) {
            f.begin(tick).self(7, 0, (float) x, 50, 4, 0, 0)
                .connector(300, 50, x, 50, Records.Connector.KIND_REFUEL)
                .connector(90, 40, x, 50, Records.Connector.KIND_BALL)
                .connector(x + 4, 46, 200, 100, Records.Connector.KIND_TRACTOR)
                .transporter(x, 50, 250, 80)
                .ingestAtTick(tick);
        }

        @Test
        @DisplayName("lines ending on the own ship end where it is drawn, not where the render tick's frames had it")
        void ownLines() {
            WorldFixture f = new WorldFixture(true);
            ownLines(f, 100, 100);
            ownLines(f, 102, 102);
            ClientWorld w = f.world;
            w.update(at(103.5));
            double renderSelf = 100 + 2 * (w.renderTick() - 100) / 2.0;
            ObjectLists o = w.objects();
            int c = ObjectLists.CONNECTOR;
            float sx = w.selfX();
            float sy = w.selfY();
            assertAll(
                () -> assertTrue(w.renderTick() > 100 && w.renderTick() < 102, "the render tick is between the frames ("
                    + w.renderTick() + ")"),
                () -> assertTrue(sx - renderSelf > 1, "the own ship is drawn ahead of where the frames had it ("
                    + sx + " vs " + renderSelf + ")"),
                () -> assertEquals(sx, o.get(c, 0, 2), 1e-3, "refuel: the ship end is the drawn ship's x"),
                () -> assertEquals(sy, o.get(c, 0, 3), 1e-3, "and y"),
                () -> assertEquals(300f, o.get(c, 0, 0), 1e-3, "the fuel station end stays"),
                () -> assertEquals(sx, o.get(c, 1, 2), 1e-3, "ball pick-up: the ship end follows"),
                () -> assertEquals(90f, o.get(c, 1, 0), 1e-3, "the ball end stays"),
                () -> assertEquals(sx + 4, o.get(c, 2, 0), 1e-3, "tractor on us: the hull point keeps its offset"),
                () -> assertEquals(sy - 4, o.get(c, 2, 1), 1e-3, "in y too"),
                () -> assertEquals(200f, o.get(c, 2, 2), 1e-3, "the beamer far away stays"),
                () -> assertEquals(sx, o.get(ObjectLists.TRANSPORTER, 0, 0), 1e-3, "transporter: the victim end follows"),
                () -> assertEquals(250f, o.get(ObjectLists.TRANSPORTER, 0, 2), 1e-3, "the thief's end stays"));
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
            double drawn = w.selfDrawnTick();
            w.update(at(130));
            assertAll(
                () -> assertTrue(w.hasSelf(), "own ship present"),
                () -> assertEquals(7, w.selfId(), "own id"),
                () -> assertEquals(102.0, x, CLOCK_EPS, "predicted to the server tick now (102): 4 px/frame * 0.5 frames"),
                () -> assertEquals(54.0, y, CLOCK_EPS, "8 px/frame * 0.5 frames"),
                () -> assertEquals(102.0, drawn, CLOCK_EPS, "drawn at the server tick now (F1)"),
                () -> assertEquals(104.0, w.selfDrawnTick(), CLOCK_EPS, "the prediction horizon is capped at 4 ticks"),
                () -> assertEquals(100, w.newestSelfTick(), "newest SELF"),
                () -> assertEquals(104.0, w.selfX(), CLOCK_EPS, "server tick 130: predicted, capped at 4 ticks"),
                () -> assertEquals(58.0, w.selfY(), CLOCK_EPS, "8 px/frame for 1 frame"),
                () -> assertEquals(1.5f, w.selfHeading(), "full-precision heading"),
                () -> assertEquals(700f, w.selfFuel(), "fuel"),
                () -> assertEquals(1000f, w.selfFuelMax(), "fuel max"),
                () -> assertEquals(2, w.selfNumTanks(), "tanks"),
                () -> assertEquals(1, w.selfCurrentTank(), "current tank"),
                () -> assertEquals(Protocol.NO_ID, w.selfLockId(), "no lock"));
        }

        @Test
        @DisplayName("the own ship is predicted from the newest SELF with its velocity and the estimated acceleration")
        void selfPredicted() {
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
            // a = (6 - 4) / (2 ticks * 0.25) = 4 px/frame^2; at 104.2: 2.2 ticks = 0.55 frames past 102
            assertAll(
                () -> assertEquals(104 + 6 * 0.55 + 2 * 0.55 * 0.55, x, CLOCK_EPS, "x + v t + a t^2 / 2"),
                () -> assertEquals(54 + 8 * 0.55, y, CLOCK_EPS, "y: no acceleration"),
                () -> assertEquals(1.7f, h, 1e-6f, "the newest heading as is"),
                () -> assertEquals(6 + 4 * 0.55, vx, 1e-5, "velocity + a t"),
                () -> assertEquals(104 + 6 * 0.7 + 2 * 0.7 * 0.7, w.selfX(), CLOCK_EPS, "at 104.8: 0.7 frames"),
                () -> assertEquals(ShipFlags.SHIELD, w.selfFlags(), "flags from the newest record"));
        }

        @Test
        @DisplayName("records arriving out of order: the newest two by tick give the acceleration, in x and y")
        void selfPredictedOutOfOrder() {
            WorldFixture f = new WorldFixture(true);
            f.begin(104).self(7, 0, 104, 58, 6, 7, 0).ingestAtTick(104);
            f.begin(100).self(7, 0, 100, 50, 4, 8, 0).ingestAtTick(100);
            f.begin(102).self(7, 0, 102, 54, 4, 8, 0).ingestAtTick(102);
            ClientWorld w = f.world;
            w.update(at(105.2));
            // from 102 to 104 (0.5 frames): a = (2 / 0.5, -1 / 0.5) = (4, -2); 1.2 ticks = 0.3 frames past 104
            assertAll(
                () -> assertEquals(104, w.newestSelfTick(), "newest by tick, not by arrival"),
                () -> assertEquals(104 + 6 * 0.3 + 0.5 * 4 * 0.09, w.selfX(), CLOCK_EPS, "x"),
                () -> assertEquals(58 + 7 * 0.3 - 0.5 * 2 * 0.09, w.selfY(), CLOCK_EPS, "y decelerates"),
                () -> assertEquals(7 - 2 * 0.3, w.selfVy(), 1e-5, "vy + ay t"));
        }

        @Test
        @DisplayName("blending keeps the old accelerating trajectory in x and y; a jump beyond TELEPORT_PX over "
            + "both axes snaps")
        void selfBlended2d() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).self(7, 0, 100, 50, 4, 4, 0).ingestAtTick(100);
            f.begin(102).self(7, 0, 101, 51, 5, 5, 0).ingestAtTick(102);
            ClientWorld w = f.world;
            w.update(at(103.0));
            f.begin(104).self(7, 0, 120, 60, 5, 5, 0).ingestAtTick(104);
            w.update(at(103.2));
            float bx = w.selfX();
            float by = w.selfY();
            WorldFixture g = new WorldFixture(false);
            g.begin(100).self(7, 0, 10, 20, 0, 0, 0).ingestAtTick(100);
            g.world.update(at(101.0));
            g.begin(102).self(7, 0, 310, 220, 0, 0, 0).ingestAtTick(102);
            g.world.update(at(101.2));
            // old trajectory from 102 (a = (1 / 0.5) = 2 in both axes) at 103.2: 0.3 frames
            assertAll(
                () -> assertEquals(101 + 5 * 0.3 + 0.5 * 2 * 0.09, bx, CLOCK_EPS, "x: no jump at the new SELF"),
                () -> assertEquals(51 + 5 * 0.3 + 0.5 * 2 * 0.09, by, CLOCK_EPS, "y: no jump at the new SELF"),
                () -> assertEquals(310.0, g.world.selfX(), CLOCK_EPS,
                    "300 px in x and 200 in y: 361 px, beyond TELEPORT_PX (350): snapped"),
                () -> assertEquals(220.0, g.world.selfY(), CLOCK_EPS, "y snapped too"));
        }

        @Test
        @DisplayName("a new SELF that differs from the prediction is blended in: no jump, the error decays")
        void selfBlended() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).self(7, 0, 100, 50, 4, 0, 0).ingestAtTick(100);
            ClientWorld w = f.world;
            w.update(at(101));
            float before = w.selfX();
            f.begin(102).self(7, 0, 110, 50, 4, 0, 0).ingestAtTick(102);
            w.update(at(101.2));
            float atSwitch = w.selfX();
            w.update(at(102.2));
            float later = w.selfX();
            for (double t = 102.4; t < 140; t += 1) {
                w.update(at(t));
            }
            double decay = Math.exp(-(1.0 / 60) / ClientWorld.ERROR_DECAY_SECONDS);
            assertAll(
                () -> assertEquals(101.0, before, CLOCK_EPS, "prediction from tick 100: 100 + 4 * 0.25"),
                () -> assertEquals(101.2, atSwitch, CLOCK_EPS,
                    "the frame the new SELF arrives: where the old prediction is now (no jump)"),
                () -> assertEquals(110.2 + (101.2 - 109.2) * decay, later, CLOCK_EPS,
                    "one tick (1/60 s) later: the new prediction plus the decayed error"),
                () -> assertEquals(110 + 4 * 1.0, w.selfX(), 0.01, "after many frames: the new prediction"));
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
        @DisplayName("a teleport (respawn) snaps to the new SELF instead of blending across the map")
        void selfTeleport() {
            WorldFixture f = new WorldFixture(false);
            f.begin(100).self(7, 0, 10, 50, 0, 0, 0).ingestAtTick(100);
            f.world.update(at(101.0));
            float early = f.world.selfX();
            f.begin(102).self(7, 0, 600, 300, 0, 0, 0).ingestAtTick(102);
            f.world.update(at(101.2));
            assertAll(
                () -> assertEquals(10.0, early, CLOCK_EPS, "before: the only record"),
                () -> assertEquals(600.0, f.world.selfX(), CLOCK_EPS, "an error beyond TELEPORT_PX is not blended"),
                () -> assertEquals(300.0, f.world.selfY(), CLOCK_EPS, "y too"));
        }

        @Test
        @DisplayName("R15: a server teleport signal (wormhole/hyperjump sound, own respawn) snaps even a short jump")
        void selfTeleportSignal() {
            WorldFixture blended = new WorldFixture(true);
            blended.begin(100).self(7, 0, 100, 50, 0, 0, 0).ingestAtTick(100);
            blended.world.update(at(101.0));
            blended.begin(102).self(7, 0, 150, 50, 0, 0, 0).ingestAtTick(102);
            blended.world.update(at(101.2));
            WorldFixture worm = new WorldFixture(true);
            worm.begin(100).self(7, 0, 100, 50, 0, 0, 0).ingestAtTick(100);
            worm.world.update(at(101.0));
            worm.begin(102).self(7, 0, 150, 50, 0, 0, 0).sound(
                com.fulcrologic.jxpilot.common.SoundEvent.WORM_HOLE.ordinal(), 50).ingestAtTick(102);
            worm.world.update(at(101.2));
            WorldFixture marked = new WorldFixture(true);
            marked.begin(100).self(7, 0, 100, 50, 0, 0, 0).ingestAtTick(100);
            marked.world.update(at(101.0));
            marked.world.markSelfTeleport(102);
            marked.begin(102).self(7, 0, 150, 50, 0, 0, 0).ingestAtTick(102);
            marked.world.update(at(101.2));
            float b = blended.world.selfX();
            float wx = worm.world.selfX();
            float mx = marked.world.selfX();
            marked.begin(104).self(7, 0, 160, 50, 0, 0, 0).ingestAtTick(104);
            marked.world.update(at(103.2));
            assertAll(
                () -> assertEquals(100.0, b, CLOCK_EPS, "a 50 px jump without a signal is blended (no jump yet)"),
                () -> assertEquals(150.0, wx, CLOCK_EPS, "with the wormhole sound: snapped"),
                () -> assertEquals(150.0, mx, CLOCK_EPS, "marked (own respawn): snapped"),
                () -> assertEquals(150.0, marked.world.selfX(), CLOCK_EPS,
                    "the mark is used once: the next jump (to 160) is blended again, no jump yet"));
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
            em.configure(60, 15, 700, 350, true);
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
