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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FPS14;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.gameEvents;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.killCauses;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Collisions (collision.c)")
class CollisionsTest {
    /** Double math that is exact up to rounding. */
    static final double EPS = 1e-9;
    /** Initial fuel 1000 in C fixed point. */
    static final double FUEL0 = 1000 * 256;

    /** C score.c Rate(winner, loser). */
    static float rate(float winner, float loser) {
        float t = (10 * 1024) / (Math.abs(loser - winner) + 1024); // C DFLOAT: float arithmetic
        return loser > winner ? 20 - t : t;
    }

    static void check(Simulation s) {
        s.world.grid.rebuild(s.world.objects);
        s.collisions.check();
    }

    static boolean killed(Player p) {
        return (p.status & Obj.KILLED) != 0;
    }

    // ================================================================ in_range_acd

    /** The texts of the SCORE_OBJECT events from {@code seq} (C Send_score_object labels). */
    static List<String> scoreTexts(World w, long seq) {
        return ShipFx.events(w, seq).stream().filter(e -> e.startsWith("SCORE_OBJECT"))
            .map(e -> e.split(" ", 6)[5]).toList();
    }

    @Nested
    @DisplayName("inRange (in_range_acd)")
    class InRange {
        final Collisions flat = sim(FRAME, 20, 20, "_").collisions;
        final Collisions wrap = sim(FRAME + "edgeWrap: yes\n", 20, 20, "_").collisions;

        @Test
        @DisplayName("end points and closest approach on a flat map")
        void flatCases() {
            assertAll(
                () -> assertTrue(flat.inRange(0, 0, 100, 100, 0, 0, 105, 100, 10),
                    "end points 5 apart are within 10"),
                () -> assertFalse(flat.inRange(100, 100, 100, 100, 110, 100, 110, 100, 10),
                    "stationary end points exactly r apart are not in range (strict <)"),
                () -> assertTrue(flat.inRange(100, 100, 100, 100, 109, 100, 109, 100, 10),
                    "stationary end points r-1 apart are in range"),
                // p passes q at (50, 5): tmin = 0.5, closest distance 5 (collision.c in_range_acd)
                () -> assertTrue(flat.inRange(0, 0, 100, 0, 50, 5, 50, 5, 10),
                    "a path passing within 5 of a stationary object is in range"),
                () -> assertFalse(flat.inRange(0, 0, 100, 0, 50, 10, 50, 10, 10),
                    "a path whose closest approach is exactly r is not in range"),
                () -> assertTrue(flat.inRange(0, 0, 100, 0, 100, 5, 0, 5, 10),
                    "head-on paths that meet halfway are in range"),
                // top < 0: the closest approach would come after the end of the move
                () -> assertFalse(flat.inRange(0, 0, 20, 0, 100, 0, 100, 0, 10),
                    "approaching but still far at the end is not in range"),
                // top > bot: moving apart, the start (5 apart) is never checked, as in C
                () -> assertFalse(flat.inRange(50, 0, 100, 0, 45, 0, 45, 0, 10),
                    "moving apart from a start within r is not in range (C checks no start points)"),
                // bot < 1: parallel motion with equal velocity
                () -> assertFalse(flat.inRange(0, 0, 10, 0, 0, 20, 10, 20, 10),
                    "parallel equal motion 20 apart is not in range"),
                () -> assertTrue(flat.inRange(0, 0, 10, 0, 0, 20, 10, 20, 25),
                    "parallel equal motion 20 apart is within 25 via the end points"),
                () -> assertFalse(flat.inRange(695, 100, 5, 100, 698, 100, 698, 100, 5),
                    "without wrapping a long path is taken literally"),
                () -> assertFalse(flat.inRange(2, 100, 2, 100, 698, 100, 698, 100, 10),
                    "without wrapping objects at opposite edges are far apart"),
                // bot = 1 and top = bot = 1: the closest approach is the (unchecked) start, where they coincide
                () -> assertTrue(flat.inRange(100, 100, 100, 100, 100, 100, 101, 100, 1),
                    "a one-pixel relative move whose closest approach is at the start counts (tmin = 1)"));
        }

        @Test
        @DisplayName("seam-crossing paths are unwrapped as C does")
        void wrapCases() {
            assertAll(
                // p 695 -> 5 crosses the x seam: p1 -> -5; q at 698 > half -> -2; closest approach 0
                () -> assertTrue(wrap.inRange(695, 100, 5, 100, 698, 100, 698, 100, 5),
                    "a path across the x seam hits an object just across it"),
                () -> assertTrue(wrap.inRange(5, 100, 695, 100, 2, 100, 2, 100, 5),
                    "a path across the x seam the other way hits an object just across it"),
                () -> assertTrue(wrap.inRange(698, 100, 698, 100, 695, 100, 5, 100, 5),
                    "the object's path crossing the seam hits a stationary ship on the high side"),
                () -> assertFalse(wrap.inRange(100, 695, 100, 5, 100, 698, 100, 698, 5),
                    "a path across the y seam misses an object just across it: C's y shift tests 'q crossed' twice"),
                () -> assertTrue(wrap.inRange(100, 698, 100, 698, 100, 695, 100, 5, 5),
                    "the object's path crossing the y seam hits a stationary ship on the high side"),
                () -> assertTrue(wrap.inRange(100, 5, 100, 695, 100, 2, 100, 2, 5),
                    "a path across the y seam downward hits an object on the low side"),
                () -> assertTrue(wrap.inRange(2, 2, 2, 2, 690, 2, 10, 2, 5),
                    "an object crossing the seam passes a stationary ship on the low side"),
                () -> assertFalse(wrap.inRange(695, 100, 5, 100, 350, 100, 350, 100, 5),
                    "a seam-crossing path does not hit an object in the middle of the world"),
                // As C: neither crossed the seam, so objects on opposite sides of it are not seen as close
                () -> assertFalse(wrap.inRange(2, 100, 2, 100, 698, 100, 698, 100, 10),
                    "objects 4 px apart across the x seam are not in range (C)"),
                () -> assertFalse(wrap.inRange(698, 100, 698, 100, 2, 100, 2, 100, 10),
                    "whichever side p is on (x)"),
                () -> assertFalse(wrap.inRange(100, 2, 100, 2, 100, 698, 100, 698, 10),
                    "objects 4 px apart across the y seam are not in range (C)"),
                () -> assertFalse(wrap.inRange(100, 698, 100, 698, 100, 2, 100, 2, 10),
                    "whichever side p is on (y)"),
                () -> assertFalse(wrap.inRange(2, 100, 2, 100, 340, 100, 340, 100, 10),
                    "objects less than half a world apart are not shifted"),
                () -> assertTrue(wrap.inRange(0, 100, 350, 100, 175, 100, 175, 100, 5),
                    "a move of exactly half the world (x) is not unwrapped"),
                () -> assertTrue(wrap.inRange(100, 0, 100, 350, 100, 175, 100, 175, 5),
                    "a move of exactly half the world (y) is not unwrapped"),
                () -> assertTrue(wrap.inRange(175, 100, 175, 100, 0, 100, 350, 100, 5),
                    "an object move of exactly half the world (x) is not unwrapped"),
                () -> assertTrue(wrap.inRange(100, 175, 100, 175, 100, 0, 100, 350, 5),
                    "an object move of exactly half the world (y) is not unwrapped"));
        }

        @Test
        @DisplayName("as C, an object moving along the other side of the seam (neither crossed it) is not seen")
        void seamPaths() {
            assertAll(
                // on one map copy the paths pass within 4 or end within 5, but C compares raw coordinates here
                () -> assertFalse(wrap.inRange(698, 100, 698, 100, 2, 80, 2, 120, 6),
                    "x, ship on the high side: the object's path passes within 4"),
                () -> assertFalse(wrap.inRange(698, 100, 698, 100, 2, 80, 2, 97, 6),
                    "x, ship on the high side: the object's end is within 5"),
                () -> assertFalse(wrap.inRange(2, 100, 2, 100, 698, 80, 698, 120, 6),
                    "x, ship on the low side: the object's path passes within 4"),
                () -> assertFalse(wrap.inRange(2, 100, 2, 100, 698, 80, 698, 97, 6),
                    "x, ship on the low side: the object's end is within 5"),
                () -> assertFalse(wrap.inRange(100, 698, 100, 698, 80, 2, 120, 2, 6),
                    "y, ship on the high side: the object's path passes within 4"),
                () -> assertFalse(wrap.inRange(100, 698, 100, 698, 80, 2, 97, 2, 6),
                    "y, ship on the high side: the object's end is within 5"),
                () -> assertFalse(wrap.inRange(100, 2, 100, 2, 80, 698, 120, 698, 6),
                    "y, ship on the low side: the object's path passes within 4"),
                () -> assertFalse(wrap.inRange(100, 2, 100, 2, 80, 698, 97, 698, 6),
                    "y, ship on the low side: the object's end is within 5"));
        }

        @Test
        @DisplayName("on a wrapping map it equals C in_range_acd (collision.c:144-222) in every case")
        void matchesC() {
            Rng r = new Rng(42);
            int same = 0;
            for (int i = 0; i < 20000; i++) {
                int[] a = new int[8];
                for (int k = 0; k < 8; k++) {
                    a[k] = Integer.remainderUnsigned(r.nextInt(), 700);
                }
                // short moves: end = start +- 40, wrapped
                for (int k = 2; k < 8; k += 4) {
                    a[k] = Math.floorMod(a[k - 2] + Integer.remainderUnsigned(r.nextInt(), 81) - 40, 700);
                    a[k + 1] = Math.floorMod(a[k - 1] + Integer.remainderUnsigned(r.nextInt(), 81) - 40, 700);
                }
                int rad = 1 + Integer.remainderUnsigned(r.nextInt(), 60);
                boolean c = cInRange(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], rad, 700, 700);
                same++;
                assertEquals(c, wrap.inRange(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], rad),
                    "equals C for case " + i);
            }
            assertEquals(20000, same, "every random case is compared");
        }

        @Test
        @DisplayName("farApart (perf F4) never rejects a pair inRange accepts, and rejects distant pairs")
        void farApartIsConservative() {
            Rng r = new Rng(7);
            int rejected = 0;
            int wrong = 0;
            for (int i = 0; i < 20000; i++) {
                int[] a = new int[8];
                for (int k = 0; k < 8; k++) {
                    a[k] = Integer.remainderUnsigned(r.nextInt(), 700);
                }
                for (int k = 2; k < 8; k += 4) {
                    a[k] = Math.floorMod(a[k - 2] + Integer.remainderUnsigned(r.nextInt(), 161) - 80, 700);
                    a[k + 1] = Math.floorMod(a[k - 1] + Integer.remainderUnsigned(r.nextInt(), 161) - 80, 700);
                }
                int rad = 1 + Integer.remainderUnsigned(r.nextInt(), 60);
                boolean far = wrap.farApart(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], rad);
                if (far) {
                    rejected++;
                    if (wrap.inRange(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], rad)) {
                        wrong++;
                    }
                }
            }
            int rej = rejected;
            int bad = wrong;
            assertAll(
                () -> assertEquals(0, bad, "no rejected pair is in range"),
                () -> assertTrue(rej > 10000, "most random pairs are rejected cheaply (" + rej + ")"),
                () -> assertTrue(wrap.farApart(0, 0, 0, 0, 100, 0, 100, 0, 99), "x gap 100 > 99"),
                () -> assertFalse(wrap.farApart(0, 0, 0, 0, 100, 0, 100, 0, 100), "x gap 100 = r is left to inRange"),
                () -> assertFalse(wrap.farApart(0, 0, 10, 0, 100, 0, 100, 0, 89), "x: the moves' lengths widen the reach"),
                () -> assertTrue(wrap.farApart(0, 0, 0, 0, 0, 100, 0, 100, 99), "y gap 100 > 99"),
                () -> assertFalse(wrap.farApart(0, 0, 0, 0, 0, 90, 0, 100, 99), "y: the moves' lengths widen the reach"),
                () -> assertFalse(wrap.farApart(0, 0, 0, 0, 110, 0, 100, 0, 95), "x: q's move widens the reach too"),
                () -> assertFalse(wrap.farApart(0, 0, 0, 10, 0, 100, 0, 100, 89), "y: p's move widens the reach too"),
                () -> assertFalse(wrap.farApart(0, 0, 0, 0, 0, 100, 0, 100, 100), "y gap 100 = r is left to inRange"),
                () -> assertFalse(wrap.farApart(0, 0, 0, 0, 690, 0, 690, 0, 20), "across the seam the gap is 10"));
        }

        /** Literal port of C in_range_acd (WRAP_PLAY). */
        boolean cInRange(int p1x, int p1y, int p2x, int p2y, int q1x, int q1y, int q2x, int q2y, int r, int ww,
                         int wh) {
            boolean mpx = Math.abs(p2x - p1x) > ww / 2;
            if (mpx) {
                if (p1x > p2x) {
                    p1x -= ww;
                } else {
                    p2x -= ww;
                }
            }
            boolean mpy = Math.abs(p2y - p1y) > wh / 2;
            if (mpy) {
                if (p1y > p2y) {
                    p1y -= wh;
                } else {
                    p2y -= wh;
                }
            }
            boolean mqx = Math.abs(q2x - q1x) > ww / 2;
            if (mqx) {
                if (q1x > q2x) {
                    q1x -= ww;
                } else {
                    q2x -= ww;
                }
            }
            boolean mqy = Math.abs(q2y - q1y) > wh / 2;
            if (mqy) {
                if (q1y > q2y) {
                    q1y -= wh;
                } else {
                    q2y -= wh;
                }
            }
            if (mpx && !mqx && (q2x > ww / 2 || q1x > ww / 2)) {
                q1x -= ww;
                q2x -= ww;
            }
            if (mqy && !mpy && (q2y > wh / 2 || q1y > wh / 2)) {
                q1y -= wh;
                q2y -= wh;
            }
            if (mqx && !mpx && (p2x > ww / 2 || p1x > ww / 2)) {
                p1x -= ww;
                p2x -= ww;
            }
            if (mqy && !mpy && (p2y > wh / 2 || p1y > wh / 2)) {
                p1y -= wh;
                p2y -= wh;
            }
            if ((p2x - q2x) * (p2x - q2x) + (p2y - q2y) * (p2y - q2y) < r * r) {
                return true;
            }
            long fac1 = -p1x + p2x + q1x - q2x;
            long fac2 = -p1y + p2y + q1y - q2y;
            long top = -(fac1 * (-p2x + q2x) + fac2 * (-p2y + q2y));
            long bot = fac1 * fac1 + fac2 * fac2;
            if (top < 0 || bot < 1 || top > bot) {
                return false;
            }
            double tmin = ((double) top) / ((double) bot);
            double fminx = -p2x + q2x + fac1 * tmin;
            double fminy = -p2y + q2y + fac2 * tmin;
            return fminx * fminx + fminy * fminy < r * r;
        }
    }

    // ================================================================ ship - ship

    @Nested
    @DisplayName("ship-ship (PlayerCollision)")
    class ShipShip {
        static final String MULTS = "crashScoreMult: 0.75\nrunoverKillScoreMult: 0.25\n";

        @Test
        @DisplayName("two unshielded ships crash: both die, lose fuel, repel and lose Rate * crashScoreMult")
        void bothCrash() {
            Simulation s = sim(FRAME + MULTS, 20, 20, "__");
            World w = s.world;
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            a.score = 100;
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertTrue(killed(a), "the first ship dies"),
                () -> assertTrue(killed(b), "the second ship dies"),
                () -> assertEquals(List.of("A and B crashed."), messages(w, seq), "announces the crash"),
                () -> assertEquals(List.of("2:" + a.id + ":" + b.id), gameEvents(w, seq),
                    "emits CRASH(first, other): b = a player who crashed, c = the other"),
                // sc = Rate(B, A) * crashScoreMult; sc2 = Rate(A, B) * crashScoreMult (collision.c PlayerCollision)
                () -> assertEquals(100 - rate(0, 100) * 0.75f, a.score, EPS, "A loses Rate(B, A) * crashScoreMult"),
                () -> assertEquals(-rate(100, 0) * 0.75f, b.score, EPS, "B loses Rate(A, B) * crashScoreMult"),
                // ED_PL_CRASH = -100*256 per frame of overlap
                () -> assertEquals(FUEL0 - 25600, a.fuel, EPS, "A pays ED_PL_CRASH for the bounce"),
                () -> assertEquals(FUEL0 - 25600, b.fuel, EPS, "B pays ED_PL_CRASH for the bounce"),
                // Obj_repel: force = min(2*SHIP_SZ - 10, 10) = 10 along heading 0, dm = 1
                () -> assertEquals(-10.0, a.vx, EPS, "A is repelled away from B by the capped force 10"),
                () -> assertEquals(10.0, b.vx, EPS, "B is repelled away from A"),
                () -> assertEquals(b.id, a.shovePusher[0], "A records B as a shover"),
                () -> assertEquals(a.id, b.shovePusher[0], "B records A as a shover"),
                () -> assertEquals(0, a.kills + b.kills, "a mutual crash is nobody's kill"),
                () -> assertEquals(0, a.items[Item.ARMOR.ordinal()] + b.items[Item.ARMOR.ordinal()],
                    "ships without armor use none"));
        }

        @Test
        @DisplayName("a shielded ship runs over an unshielded one: Rate * runoverKillScoreMult and a kill")
        void runOverSecondSurvives() {
            Simulation s = sim(FRAME + MULTS, 20, 20, "__");
            World w = s.world;
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            b.used |= Has.SHIELD;
            a.score = 100;
            long seq = w.events.seq();
            check(s);
            float sc = rate(0, 100) * 0.25f;
            assertAll(
                () -> assertTrue(killed(a), "the unshielded ship dies"),
                () -> assertFalse(killed(b), "the shielded ship survives"),
                () -> assertEquals(List.of("B ran over A."), messages(w, seq), "the survivor ran over the victim"),
                () -> assertEquals(List.of("1:" + b.id + ":" + a.id), gameEvents(w, seq), "emits KILL(B, A)"),
                () -> assertEquals(List.of(b.id + ":" + a.id + ":" + Events.KILL_RUNOVER + ":" + Obj.NO_ID),
                    killCauses(w, seq), "tagged as a run-over (no Robot_war in C)"),
                () -> assertEquals(1, b.kills, "the survivor gets the kill"),
                () -> assertEquals(0, a.kills, "the victim gets no kill"),
                () -> assertEquals(sc, b.score, EPS, "the survivor gains Rate(B, A) * runoverKillScoreMult"),
                () -> assertEquals(100 - sc, a.score, EPS, "the victim loses the same"));
        }

        @Test
        @DisplayName("the first ship runs over the second")
        void runOverFirstSurvives() {
            Simulation s = sim(FRAME + MULTS, 20, 20, "__");
            World w = s.world;
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            a.used |= Has.SHIELD;
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertFalse(killed(a), "the shielded first ship survives"),
                () -> assertTrue(killed(b), "the unshielded second ship dies"),
                () -> assertEquals(List.of("A ran over B."), messages(w, seq), "A ran over B"),
                () -> assertEquals(List.of("1:" + a.id + ":" + b.id), gameEvents(w, seq), "emits KILL(A, B)"),
                () -> assertEquals(1, a.kills, "A gets the kill"),
                () -> assertEquals(10 * 0.25, a.score, EPS, "A gains Rate(0, 0) * runoverKillScoreMult"),
                () -> assertEquals(-10 * 0.25, b.score, EPS, "B loses it"));
        }

        @Test
        @DisplayName("bounces only, at 14 fps: one frame's fuel cost and repulsion; fully shielded pays nothing")
        void bounceOnlyScaled() {
            Simulation s = sim(FPS14 + "allowPlayerCrashes: no\n", 20, 20, "__");
            World w = s.world;
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            b.used |= Has.BOTH_SHIELDS;
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertFalse(killed(a), "no crash without allowPlayerCrashes"),
                () -> assertFalse(killed(b), "no crash for the other ship either"),
                () -> assertEquals(List.of(), messages(w, seq), "no message"),
                () -> assertEquals(FUEL0 - 25600, a.fuel, EPS, "fuel cost is ED_PL_CRASH per frame (= tick)"),
                () -> assertEquals(FUEL0, b.fuel, EPS, "a fully shielded ship pays no fuel"),
                () -> assertEquals(-10.0f, a.vx, "repulsion is 10"),
                () -> assertEquals(10.0f, b.vx, "the other ship gets the opposite push"),
                () -> assertEquals(b.id, a.shovePusher[0], "the bounce is recorded as a shove"));
        }

        @Test
        @DisplayName("bounces only, at 14 fps: the second ship pays ED_PL_CRASH too")
        void bounceSecondPays() {
            Simulation s = sim(FPS14 + "allowPlayerCrashes: no\n", 20, 20, "__");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            a.used |= Has.BOTH_SHIELDS;
            check(s);
            assertAll(
                () -> assertEquals(FUEL0, a.fuel, EPS, "the fully shielded first ship pays nothing"),
                () -> assertEquals(FUEL0 - 25600, b.fuel, EPS, "the second ship pays ED_PL_CRASH"));
        }

        @Test
        @DisplayName("crashes only: no fuel cost, no repulsion, still a crash")
        void crashOnly() {
            Simulation s = sim(FRAME + "allowPlayerBounces: no\n", 20, 20, "__");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            check(s);
            assertAll(
                () -> assertTrue(killed(a) && killed(b), "both unshielded ships die"),
                () -> assertEquals(FUEL0, a.fuel, EPS, "no bounce fuel cost"),
                () -> assertEquals(0.0, a.vx, EPS, "no repulsion"),
                () -> assertEquals(Obj.NO_ID, a.shovePusher[0], "no shove recorded"));
        }

        @Test
        @DisplayName("neither crashes nor bounces: ships pass through each other")
        void neither() {
            Simulation s = sim(FRAME + "allowPlayerBounces: no\nallowPlayerCrashes: no\n", 20, 20, "__");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            check(s);
            assertAll(
                () -> assertFalse(killed(a) || killed(b), "nobody dies"),
                () -> assertEquals(FUEL0, a.fuel, EPS, "no fuel cost"),
                () -> assertEquals(0.0, b.vx, EPS, "no repulsion"));
        }

        @Test
        @DisplayName("repulsion splits by the mass ratio along the RES-quantized line between the ships")
        void repelMassRatio() {
            Simulation s = sim(FRAME + "allowPlayerCrashes: no\n", 20, 20, "__");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 210);
            b.mass = 2 * a.mass;
            check(s);
            // Obj_repel: theta = findDir(10, 10) = 16, dm = mA/mB = 0.5, force 10
            float c = Trig.tcos(16);
            float sn = Trig.tsin(16);
            assertAll(
                () -> assertEquals(-(c * 10f / 0.5f), a.vx, "the lighter ship gets -cos * force / dm (C floats)"),
                () -> assertEquals(-(sn * 10f / 0.5f), a.vy, "and -sin * force / dm"),
                () -> assertEquals(c * 10f * 0.5f, b.vx, "the heavier ship gets cos * force * dm"),
                () -> assertEquals(sn * 10f * 0.5f, b.vy, "and sin * force * dm"));
        }

        @Test
        @DisplayName("repel does nothing when the ships are at least the repel distance apart")
        void repelOutOfReach() {
            Simulation s = sim(FRAME, 20, 20, "__");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 232, 200);
            s.collisions.repel(a, b, 32);
            assertAll(
                () -> assertEquals(0.0, a.vx, EPS, "no push at exactly the repel distance"),
                () -> assertEquals(Obj.NO_ID, a.shovePusher[0], "no shove recorded"));
        }

        @Test
        @DisplayName("an armored unshielded ship survives a crash and loses one armor")
        void armor() {
            Simulation s = sim(FRAME, 20, 20, "______");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            b.used |= Has.SHIELD;
            a.have |= Has.ARMOR;
            a.items[Item.ARMOR.ordinal()] = 2;
            Player c = ready(s, "C", 0, 400, 200);
            Player d = ready(s, "D", 0, 410, 200);
            d.used |= Has.SHIELD;
            c.have |= Has.ARMOR;
            c.items[Item.ARMOR.ordinal()] = 1;
            Player e = ready(s, "E", 0, 200, 500);
            Player f = ready(s, "F", 0, 210, 500);
            e.used |= Has.SHIELD;
            f.have |= Has.ARMOR;
            f.items[Item.ARMOR.ordinal()] = 3;
            check(s);
            assertAll(
                () -> assertFalse(killed(a), "armor saves an unshielded ship"),
                () -> assertEquals(1, a.items[Item.ARMOR.ordinal()], "one armor piece is used up"),
                () -> assertTrue((a.have & Has.ARMOR) != 0, "armor remains while pieces are left"),
                () -> assertFalse(killed(c), "the last armor piece still saves the ship"),
                () -> assertEquals(0, c.items[Item.ARMOR.ordinal()], "the last piece is used up"),
                () -> assertEquals(0, c.have & Has.ARMOR, "and the ship no longer has armor"),
                () -> assertFalse(killed(f), "armor saves the second ship of a pair too"),
                () -> assertEquals(2, f.items[Item.ARMOR.ordinal()], "which uses one piece"),
                () -> assertEquals(0, e.items[Item.ARMOR.ordinal()], "a shielded ship uses no armor"));
        }

        @Test
        @DisplayName("a shielded ship whose fuel the bounce empties dies")
        void shieldedOutOfFuel() {
            Simulation s = sim(FRAME, 20, 20, "____");
            Player a = ready(s, "A", 0, 200, 200);
            Player b = ready(s, "B", 0, 210, 200);
            Player c = ready(s, "C", 0, 200, 500);
            Player d = ready(s, "D", 0, 210, 500);
            a.used |= Has.SHIELD;
            b.used |= Has.SHIELD;
            c.used |= Has.SHIELD;
            d.used |= Has.SHIELD;
            a.fuel = 1000;
            d.fuel = 1000;
            check(s);
            assertAll(
                () -> assertEquals(0.0, a.fuel, EPS, "the bounce empties the tank"),
                () -> assertTrue(killed(a), "a shielded ship with no fuel dies"),
                () -> assertFalse(killed(b), "a shielded ship with fuel survives"),
                () -> assertTrue(killed(d), "a shielded second ship with no fuel dies"),
                () -> assertFalse(killed(c), "its shielded partner with fuel survives"));
        }

        @Test
        @DisplayName("who can collide: range, team immunity, pseudo teams, phasing, dead ships")
        void filters() {
            Simulation s1 = sim(FRAME, 20, 20, "__");
            Player a1 = ready(s1, "A", 0, 200, 200);
            Player b1 = ready(s1, "B", 0, 226, 200);
            check(s1);
            Simulation s2 = sim(FRAME, 20, 20, "__");
            Player a2 = ready(s2, "A", 0, 200, 200);
            Player b2 = ready(s2, "B", 0, 225, 200);
            check(s2);
            Simulation s3 = sim(FRAME + "teamPlay: yes\nreserveRobotTeam: no\n", 20, 20, "00");
            Player a3 = ready(s3, "A", 0, 200, 200);
            Player b3 = ready(s3, "B", 0, 210, 200);
            check(s3);
            Simulation s4 = sim(FRAME + "teamPlay: yes\nreserveRobotTeam: no\nteamImmunity: no\n", 20, 20, "00");
            Player a4 = ready(s4, "A", 0, 200, 200);
            Player b4 = ready(s4, "B", 0, 210, 200);
            check(s4);
            Simulation s5 = sim(FRAME, 20, 20, "__");
            Player a5 = ready(s5, "A", 0, 200, 200);
            Player b5 = ready(s5, "B", 0, 210, 200);
            b5.pseudoTeam = a5.pseudoTeam;
            check(s5);
            Simulation s6 = sim(FRAME, 20, 20, "__");
            Player a6 = ready(s6, "A", 0, 200, 200);
            Player b6 = ready(s6, "B", 0, 210, 200);
            a6.used |= Has.PHASING_DEVICE;
            check(s6);
            Simulation s7 = sim(FRAME, 20, 20, "__");
            Player a7 = ready(s7, "A", 0, 200, 200);
            Player b7 = ready(s7, "B", 0, 210, 200);
            b7.used |= Has.PHASING_DEVICE;
            check(s7);
            Simulation s8 = sim(FRAME, 20, 20, "__");
            Player a8 = ready(s8, "A", 0, 200, 200);
            Player b8 = ready(s8, "B", 0, 210, 200);
            b8.status |= Obj.PAUSE;
            check(s8);
            assertAll(
                () -> assertFalse(killed(a1) || killed(b1), "ships 26 apart (2*SHIP_SZ-6) do not collide"),
                () -> assertTrue(killed(a2) && killed(b2), "ships 25 apart collide"),
                () -> assertFalse(killed(a3) || killed(b3), "team mates are immune with teamImmunity"),
                () -> assertTrue(killed(a4) && killed(b4), "team mates collide without teamImmunity"),
                () -> assertFalse(killed(a5) || killed(b5), "ships of one pseudo team do not collide"),
                () -> assertFalse(killed(a6) || killed(b6), "a phased first ship does not collide"),
                () -> assertFalse(killed(a7) || killed(b7), "a phased second ship does not collide"),
                () -> assertFalse(killed(a8), "a paused ship is not run into"));
        }

        @Test
        @DisplayName("a ship outside the world dies and loses Rate(WALL_SCORE)")
        void outsideWorld() {
            Simulation s = sim(FRAME, 20, 20, "______");
            World w = s.world;
            Player a = ready(s, "A", 0, -5, 200);
            Player b = ready(s, "B", 0, 700, 200);
            Player c = ready(s, "C", 0, 699, 699);
            Player d = ready(s, "D", 0, 300, 700);
            Player e = ready(s, "E", 0, 0, 400);
            Player f = ready(s, "F", 0, 400, 0);
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertTrue(killed(a), "x < 0 is outside"),
                () -> assertTrue(killed(b), "x = width is outside"),
                () -> assertFalse(killed(c), "the last pixel is inside"),
                () -> assertTrue(killed(d), "y = height is outside"),
                () -> assertFalse(killed(e), "x = 0 is inside"),
                () -> assertFalse(killed(f), "y = 0 is inside"),
                () -> assertEquals(List.of("A left the known universe.", "B left the known universe.",
                    "D left the known universe."), messages(w, seq), "announces each"),
                () -> assertEquals(List.of("2:" + a.id + ":-1", "2:" + b.id + ":-1", "2:" + d.id + ":-1"), gameEvents(w, seq),
                    "emits CRASH for each"),
                () -> assertEquals(-rate(2000, 0), a.score, EPS, "loses Rate(WALL_SCORE, score)"));
        }

        @Test
        @DisplayName("an outside ship does not crash into another one at the same spot")
        void outsideSkipsShips() {
            Simulation s = sim(FRAME, 20, 20, "__");
            Player a = ready(s, "A", 0, -5, 200);
            Player b = ready(s, "B", 0, -5, 200);
            b.used |= Has.SHIELD;
            check(s);
            assertAll(
                () -> assertEquals(FUEL0, b.fuel, EPS, "no bounce with a ship that left the world"),
                () -> assertTrue(killed(b), "the other ship left the world too"));
        }
    }

    // ================================================================ shots

    @Nested
    @DisplayName("killing shots (Player_collides_with_killing_shot)")
    class ShotHits {
        static final String MULTS = "shotKillScoreMult: 2\nclusterKillScoreMult: 3\n";

        Simulation s;
        World w;
        Player v;
        Player k;

        void setup(String extra, String bases) {
            s = sim(FRAME + MULTS + extra, 20, 20, bases);
            w = s.world;
            v = ready(s, "V", 0, 200, 200);
            k = ready(s, "K", bases.length() > 1 && bases.charAt(1) != '_' ? bases.charAt(1) - '0' : 0, 400, 400);
            v.score = 100;
            k.score = 50;
        }

        int shot(int owner, double vx) {
            return obj(w, Obj.SHOT, owner, 200, 200, vx, 0, 0.1, 10);
        }

        @Test
        @DisplayName("another player's shot kills: the killer gains Rate(killer, victim) * shotKillScoreMult")
        void killedByPlayer() {
            setup("", "__");
            int h = shot(k.id, 21);
            long seq = w.events.seq();
            check(s);
            float sc = rate(50, 100) * 2;
            assertAll(
                () -> assertTrue(killed(v), "the victim dies"),
                () -> assertEquals(List.of("V was killed by a shot from K."), messages(w, seq), "names the killer"),
                () -> assertEquals(List.of("1:" + k.id + ":" + v.id), gameEvents(w, seq), "emits KILL(K, V)"),
                () -> assertEquals(List.of(k.id + ":" + v.id + ":" + Events.KILL_WEAPON + ":" + Obj.NO_ID),
                    killCauses(w, seq), "tagged as a weapon kill (C Robot_war)"),
                () -> assertEquals(1, k.kills, "the killer gets a kill"),
                () -> assertEquals(50 + sc, k.score, EPS, "the killer gains the score"),
                () -> assertEquals(100 - sc, v.score, EPS, "the victim loses it"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the shot is used up"),
                () -> assertEquals(0.0, v.vx, EPS, "a dead ship gets no momentum"));
        }

        @Test
        @DisplayName("own shot after its fuse: self kill with Rate(0) * selfKillScoreMult; before: immune")
        void ownShot() {
            setup("", "__");
            int h = shot(v.id, 21);
            w.objects.fuseLife[h] = 9;
            check(s);
            boolean aliveBeforeFuse = !killed(v);
            double lifeBeforeFuse = w.objects.life[h];
            w.objects.fuseLife[h] = 10;
            long seq = w.events.seq();
            check(s);
            float sc = rate(0, 100) * 0.5f * 2;
            assertAll(
                () -> assertTrue(aliveBeforeFuse, "the owner is immune while life > fuse life"),
                () -> assertEquals(10.0, lifeBeforeFuse, EPS, "the shot flies on through its owner"),
                () -> assertTrue(killed(v), "the owner dies once life <= fuse life"),
                () -> assertEquals(List.of("V was killed by a shot from V.  How strange!"), messages(w, seq),
                    "a self kill is strange"),
                () -> assertEquals(List.of("V"), scoreTexts(w, seq), "the score label is the own name"),
                () -> assertEquals(List.of("1:" + v.id + ":" + v.id), gameEvents(w, seq), "emits KILL(V, V)"),
                () -> assertEquals(0, v.kills, "no kill for shooting yourself"),
                () -> assertEquals(100 - sc, v.score, EPS, "loses Rate(0, score) * selfKillScoreMult * shot mult"));
        }

        @Test
        @DisplayName("owner immunity only covers sparks: an own OWNERIMMUNE shot still kills")
        void ownerImmuneShot() {
            setup("", "__");
            int h = shot(v.id, 21);
            w.objects.status[h] = Obj.OWNERIMMUNE;
            check(s);
            assertTrue(killed(v), "an own shot marked OWNERIMMUNE kills its owner");
        }

        @Test
        @DisplayName("the fuse only covers shots: own debris hits even while life > fuse life")
        void fuseOnlyForShots() {
            setup("", "__");
            int h = obj(w, Obj.DEBRIS, v.id, 200, 200, 30, 40, 4.5, 10);
            w.objects.fuseLife[h] = 5;
            check(s);
            assertEquals(FUEL0 - 450, v.fuel, EPS, "own young debris drains fuel");
        }

        @Test
        @DisplayName("selfImmunity protects from own shots after the fuse")
        void selfImmunity() {
            setup("selfImmunity: yes\n", "__");
            int h = shot(v.id, 21);
            check(s);
            assertAll(
                () -> assertFalse(killed(v), "own shots are harmless"),
                () -> assertEquals(10.0, w.objects.life[h], EPS, "the shot is not used up"));
        }

        @Test
        @DisplayName("an unowned shot (or one whose owner left) costs Rate(0) * unownedKillScoreMult")
        void unowned() {
            setup("", "__");
            shot(Obj.NO_ID, 21);
            long seq = w.events.seq();
            check(s);
            float sc = rate(0, 100) * 0.5f * 2;
            assertAll(
                () -> assertTrue(killed(v), "an unowned shot kills"),
                () -> assertEquals(List.of("V was killed by a shot."), messages(w, seq), "no killer named"),
                () -> assertEquals(List.of(""), scoreTexts(w, seq), "an empty score label"),
                () -> assertEquals(List.of("1:-1:" + v.id), gameEvents(w, seq), "emits KILL(NO_ID, V)"),
                () -> assertEquals(100 - sc, v.score, EPS, "loses Rate(0, score) * unowned mult * shot mult"),
                () -> assertEquals(50.0, k.score, EPS, "nobody else scores"));
            setup("", "__");
            shot(77, 21);
            long seq2 = w.events.seq();
            check(s);
            assertEquals(List.of("V was killed by a shot."), messages(w, seq2), "a departed owner counts as unowned");
        }

        @Test
        @DisplayName("cannon fire costs Rate(CANNON_SCORE)/4 * mult and clears the flak CLUSTER bit")
        void cannonFire() {
            setup("", "__");
            int h = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 200, 200, 0, 0, 0.4, 10);
            w.objects.status[h] = Obj.FROMCANNON;
            w.objects.mods[h] = Obj.MOD_CLUSTER;
            w.objects.team[h] = 3;
            long seq = w.events.seq();
            check(s);
            // Rate(-1436, 100) = 20 - 10240/2560 = 16; /4 = 4; * shotKillScoreMult 2
            assertAll(
                () -> assertTrue(killed(v), "cannon fire kills"),
                () -> assertEquals(List.of("V was hit by cannonfire."), messages(w, seq), "blames cannonfire"),
                () -> assertEquals(List.of("1:-1:" + v.id), gameEvents(w, seq), "emits KILL(NO_ID, V)"),
                () -> assertEquals(100 - 8.0, v.score, EPS, "loses Rate(CANNON_SCORE, score)/4 * shotKillScoreMult"),
                () -> assertEquals(0, w.objects.mods[h] & Obj.MOD_CLUSTER, "a direct flak hit does not explode as cluster"),
                () -> assertEquals(0.0, w.teamScore[3], EPS, "no team score outside team play"));
        }

        @Test
        @DisplayName("cannon cluster fragments use clusterKillScoreMult")
        void cannonCluster() {
            setup("", "__");
            int h = obj(w, Obj.SHOT, Obj.NO_ID, 200, 200, 0, 0, 0.3, 10);
            w.objects.status[h] = Obj.FROMCANNON;
            w.objects.mods[h] = Obj.MOD_CLUSTER;
            check(s);
            assertEquals(100 - 4.0 * 3, v.score, EPS, "loses Rate(CANNON_SCORE, score)/4 * clusterKillScoreMult");
        }

        @Test
        @DisplayName("an owned cluster fragment is 'cluster debris' and uses clusterKillScoreMult")
        void ownedCluster() {
            setup("", "__");
            int h = shot(k.id, 21);
            w.objects.mods[h] = Obj.MOD_CLUSTER;
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertEquals(List.of("V was killed by cluster debris from K."), messages(w, seq),
                    "describes the fragment as C Describe_shot does"),
                () -> assertEquals(100 - rate(50, 100) * 3, v.score, EPS, "uses clusterKillScoreMult"));
        }

        @Test
        @DisplayName("team play: cannon fire scores for the cannon's team unless it is the victim's")
        void cannonTeamScore() {
            setup("teamPlay: yes\nreserveRobotTeam: no\nteamImmunity: no\n", "01");
            int h = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 200, 200, 0, 0, 0.4, 10);
            w.objects.status[h] = Obj.FROMCANNON;
            w.objects.team[h] = 1;
            check(s);
            double t0 = w.teamScore[0];
            double t1 = w.teamScore[1];
            setup("teamPlay: yes\nreserveRobotTeam: no\nteamImmunity: no\n", "01");
            int h2 = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 200, 200, 0, 0, 0.4, 10);
            w.objects.status[h2] = Obj.FROMCANNON;
            w.objects.team[h2] = 0;
            check(s);
            assertAll(
                () -> assertEquals(-8.0, t0, EPS, "the victim's team loses the score"),
                () -> assertEquals(8.0, t1, EPS, "the cannon's team gains it"),
                () -> assertEquals(-8.0, w.teamScore[0], EPS, "own-team cannon fire still costs the victim's team"),
                () -> assertEquals(0.0, w.teamScore[1], EPS, "and gives nothing to anyone"));
        }

        @Test
        @DisplayName("shielded: kinetic fuel drain, momentum, no death")
        void shieldedKinetic() {
            setup("", "__");
            v.used |= Has.SHIELD;
            float m = v.mass;
            int h = shot(k.id, 10);
            check(s);
            // drain = (long)(ED_SHOT_HIT * rel^2 * mass / (shotSpeed^2 * shotMass)) = (long)(-6400 * 100/441)
            assertAll(
                () -> assertFalse(killed(v), "shields absorb the shot"),
                () -> assertEquals(FUEL0 - 1451, v.fuel, EPS, "drains (long)(-6400 * 100 / 441) = -1451 fuel"),
                () -> assertTrue((v.used & Has.SHIELD) != 0, "shields stay up with fuel left"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the shot is used up"),
                // Delta_mv: (0 * m + 10 * 0.1) / (m + 0.1)
                () -> assertEquals((0f * m + 10f * 0.1f) / (m + 0.1f), v.vx, "the ship takes the shot's momentum"),
                () -> assertEquals((0f * m + 10f * 0.1f) / (m + 0.1f), w.objects.vx[h],
                    "the shot ends with the common velocity"),
                () -> assertEquals(0, k.kills, "no kill"));
        }

        @Test
        @DisplayName("the kinetic drain uses the speed relative to the moving ship")
        void shieldedKineticMoving() {
            setup("", "__");
            v.used |= Has.SHIELD;
            v.vx = 4;
            v.vy = -2;
            obj(w, Obj.SHOT, k.id, 200, 200, 10, 3, 0.1, 10);
            check(s);
            // rel = (4 - 10, -2 - 3): rel^2 = 61; drain = (long)(-6400 * 61 * 0.1 / (441 * 0.1))
            assertEquals(FUEL0 - (long) (6400 * 61 / 441.0), v.fuel, EPS, "drains by the relative speed squared");
        }

        @Test
        @DisplayName("shielded without kinetic drain: a flat 25 fuel; fully shielded: none; empty tank drops shields")
        void shieldedFlat() {
            setup("", "__");
            // shotHitFuelDrainUsesKineticEnergy is not a map option (F.DEF|F.CMD): set the resolved parameter
            w.params.shotHitFuelDrainUsesKineticEnergy = false;
            v.used |= Has.SHIELD;
            shot(k.id, 10);
            check(s);
            double flat = v.fuel;
            setup("", "__");
            v.used |= Has.BOTH_SHIELDS;
            shot(k.id, 10);
            check(s);
            double full = v.fuel;
            setup("", "__");
            v.used |= Has.SHIELD;
            v.fuel = 1000;
            shot(k.id, 21);
            check(s);
            assertAll(
                () -> assertEquals(FUEL0 - 6400, flat, EPS, "drains ED_SHOT_HIT = 25*256"),
                () -> assertEquals(FUEL0, full, EPS, "a fully shielded ship pays nothing"),
                () -> assertEquals(0.0, v.fuel, EPS, "the drain empties a small tank"),
                () -> assertEquals(0, v.used & Has.SHIELD, "shields drop at zero fuel"),
                () -> assertEquals(0, v.items[Item.ARMOR.ordinal()], "a ship without armor uses none"),
                () -> assertFalse(killed(v), "the ship survives this hit"));
        }

        @Test
        @DisplayName("armor absorbs a shot for an unshielded ship and is used up")
        void armor() {
            setup("", "__");
            v.have |= Has.ARMOR;
            v.items[Item.ARMOR.ordinal()] = 1;
            shot(k.id, 21);
            check(s);
            assertAll(
                () -> assertFalse(killed(v), "armor saves the ship"),
                () -> assertEquals(FUEL0 - 6400, v.fuel, EPS, "the hit still drains (relative speed = shotSpeed)"),
                () -> assertEquals(0, v.have & Has.ARMOR, "the last armor is gone"));
        }

        @Test
        @DisplayName("immunities: team mates' shots, own-team unowned objects, paused owners")
        void immunities() {
            setup("teamPlay: yes\nreserveRobotTeam: no\n", "00");
            int h1 = shot(k.id, 21);
            check(s);
            boolean teamShot = killed(v);
            double life1 = w.objects.life[h1];
            setup("teamPlay: yes\nreserveRobotTeam: no\nteamImmunity: no\n", "00");
            shot(k.id, 21);
            check(s);
            boolean teamShotNoImm = killed(v);
            setup("teamPlay: yes\nreserveRobotTeam: no\n", "01");
            int h2 = shot(Obj.NO_ID, 21);
            w.objects.team[h2] = 0;
            check(s);
            boolean ownTeamUnowned = killed(v);
            setup("teamPlay: yes\nreserveRobotTeam: no\n", "01");
            int h3 = shot(Obj.NO_ID, 21);
            w.objects.team[h3] = 1;
            check(s);
            boolean otherTeamUnowned = killed(v);
            setup("", "__");
            k.status |= Obj.PAUSE;
            shot(k.id, 21);
            check(s);
            boolean paused = killed(v);
            assertAll(
                () -> assertFalse(teamShot, "a team mate's shot is harmless with teamImmunity"),
                () -> assertEquals(10.0, life1, EPS, "and is not used up"),
                () -> assertTrue(teamShotNoImm, "without teamImmunity a team mate's shot kills"),
                () -> assertFalse(ownTeamUnowned, "an unowned object of the own team is harmless"),
                () -> assertTrue(otherTeamUnowned, "an unowned object of another team kills"),
                () -> assertFalse(paused, "a paused owner's shots are harmless"));
        }

        @Test
        @DisplayName("an object hits within SHIP_SZ + pl_range; dead objects are ignored")
        void range() {
            setup("", "__");
            int far = obj(w, Obj.SPARK, k.id, 216, 200, 5, 0, 1, 10);
            int near = obj(w, Obj.SPARK, k.id, 200, 215, 5, 0, 1, 10);
            int ranged = obj(w, Obj.SPARK, k.id, 223, 200, 5, 0, 1, 10);
            w.objects.plRange[ranged] = 8;
            int dead = obj(w, Obj.SHOT, k.id, 200, 200, 5, 0, 1, 0);
            check(s);
            assertAll(
                () -> assertEquals(10.0, w.objects.life[far], EPS, "16 px away misses (range 16, strict)"),
                () -> assertEquals(0.0, w.objects.life[near], EPS, "15 px away hits"),
                () -> assertEquals(0.0, w.objects.life[ranged], EPS, "23 px away hits with pl_range 8"),
                () -> assertFalse(killed(v), "a dead shot does not kill"));
        }

        @Test
        @DisplayName("a ship killed in a crash this frame collides with no objects")
        void killedShipSkipsObjects() {
            setup("", "__");
            k.initPixels(210, 200);
            int h = shot(Obj.NO_ID, 21);
            long seq = w.events.seq();
            check(s);
            assertAll(
                () -> assertTrue(killed(v), "the crash kills the ship"),
                () -> assertEquals(List.of("V and K crashed."), messages(w, seq), "only the crash is announced"),
                () -> assertEquals(10.0, w.objects.life[h], EPS, "the shot is not consumed by a dead ship"));
        }

        @Test
        @DisplayName("Describe_shot names")
        void describe() {
            assertAll(
                () -> assertEquals("a shot", Collisions.describe(Obj.SHOT, 0), "plain shot"),
                () -> assertEquals("cluster debris", Collisions.describe(Obj.SHOT, Obj.MOD_CLUSTER), "cluster fragment"),
                () -> assertEquals("cluster flak", Collisions.describe(Obj.CANNON_SHOT, Obj.MOD_CLUSTER), "flak"),
                () -> assertEquals("a shot", Collisions.describe(Obj.CANNON_SHOT, 0), "plain cannon shot"));
        }
    }

    // ================================================================ debris and sparks

    @Nested
    @DisplayName("debris (Player_collides_with_debris) and sparks (Delta_mv)")
    class DebrisAndSparks {
        static final String MULTS = "explosionKillScoreMult: 0.4\n";

        Simulation s;
        World w;
        Player v;
        Player k;

        void setup(String extra) {
            s = sim(FRAME + MULTS + extra, 20, 20, "__");
            w = s.world;
            v = ready(s, "V", 0, 200, 200);
            k = ready(s, "K", 0, 400, 400);
            v.score = 100;
            k.score = 50;
        }

        int debris(int owner) {
            return obj(w, Obj.DEBRIS, owner, 200, 200, 30, 40, 4.5, 10);
        }

        @Test
        @DisplayName("debris drains |(long)(2 m |v|)| fuel and shares momentum")
        void drain() {
            setup("");
            float m = v.mass;
            int h = debris(k.id);
            check(s);
            assertAll(
                () -> assertEquals(FUEL0 - 450, v.fuel, EPS, "drains (long)(2 * 4.5 * 50) = 450"),
                () -> assertFalse(killed(v), "the ship survives with fuel left"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the debris is used up"),
                () -> assertEquals((0f * m + 30f * 4.5f) / (m + 4.5f), v.vx, "inelastic x velocity (C floats)"),
                () -> assertEquals((0f * m + 40f * 4.5f) / (m + 4.5f), v.vy, "inelastic y velocity"),
                () -> assertEquals(v.vy, w.objects.vy[h], EPS, "the debris shares the velocity"));
        }

        @Test
        @DisplayName("negative mass and velocity still drain the absolute cost")
        void absoluteCost() {
            setup("");
            obj(w, Obj.DEBRIS, k.id, 200, 200, -30, -40, -4.5, 10);
            check(s);
            assertEquals(FUEL0 - 450, v.fuel, EPS, "drains |(long)(2 * -4.5 * 50)| = 450");
        }

        @Test
        @DisplayName("debris that empties the tank kills: owner gets Rate * explosionKillScoreMult")
        void killByOther() {
            setup("");
            v.fuel = 450;
            int h = debris(k.id);
            long seq = w.events.seq();
            check(s);
            float sc = rate(50, 100) * 0.4f;
            assertAll(
                () -> assertTrue(killed(v), "an empty tank means death"),
                () -> assertEquals(List.of("V succumbed to an explosion from K."), messages(w, seq), "names the owner"),
                () -> assertEquals(List.of("1:" + k.id + ":" + v.id), gameEvents(w, seq), "emits KILL(K, V)"),
                () -> assertEquals(List.of(k.id + ":" + v.id + ":" + Events.KILL_EXPLOSION + ":" + Obj.NO_ID),
                    killCauses(w, seq), "tagged as an explosion (no Robot_war in C)"),
                () -> assertEquals(1, k.kills, "the owner gets the kill"),
                () -> assertEquals(50 + sc, k.score, EPS, "the owner gains Rate(K, V) * explosionKillScoreMult"),
                () -> assertEquals(100 - sc, v.score, EPS, "the victim loses it"),
                () -> assertEquals(0.0, w.objects.life[h], EPS, "the debris is used up"),
                () -> assertEquals(0.0, v.vx, EPS, "no momentum for a dead ship"));
        }

        @Test
        @DisplayName("own or unowned debris kills with Rate(0) * explosion * selfKill multipliers")
        void selfAndUnowned() {
            setup("");
            v.fuel = 100;
            debris(v.id);
            long seq = w.events.seq();
            check(s);
            List<String> own = messages(w, seq);
            List<String> ownEv = gameEvents(w, seq);
            List<String> ownCause = killCauses(w, seq);
            List<String> ownLabels = scoreTexts(w, seq);
            double ownScore = v.score;
            setup("");
            v.fuel = 100;
            debris(Obj.NO_ID);
            long seq2 = w.events.seq();
            check(s);
            float sc = rate(0, 100) * 0.4f * 0.5f;
            assertAll(
                () -> assertEquals(List.of("V succumbed to an explosion from V.  How strange!"), own, "own debris is strange"),
                () -> assertEquals(List.of("1:-1:" + v.id), ownEv, "own debris: KILL(NO_ID, V)"),
                () -> assertEquals(List.of("-1:" + v.id + ":" + Events.KILL_EXPLOSION + ":-1"), ownCause,
                    "own debris is an explosion kill too"),
                () -> assertEquals(100 - sc, ownScore, EPS, "own debris costs Rate(0) * explosion * selfKill"),
                () -> assertEquals(List.of("V succumbed to an explosion."), messages(w, seq2), "unowned names nobody"),
                () -> assertEquals(List.of("V"), ownLabels, "own debris: labelled with the own name"),
                () -> assertEquals(List.of("[Explosion]"), scoreTexts(w, seq2), "unowned: [Explosion]"),
                () -> assertEquals(100 - sc, v.score, EPS, "unowned debris costs the same"),
                () -> assertEquals(0, k.kills, "nobody gets a kill"));
        }

        @Test
        @DisplayName("fully shielded: no drain, but an already empty tank still kills")
        void fullyShielded() {
            setup("");
            v.used |= Has.BOTH_SHIELDS;
            debris(k.id);
            check(s);
            double fuel = v.fuel;
            boolean dead = killed(v);
            setup("");
            v.used |= Has.BOTH_SHIELDS;
            v.fuel = 0;
            debris(k.id);
            check(s);
            assertAll(
                () -> assertEquals(FUEL0, fuel, EPS, "no fuel drain when fully shielded"),
                () -> assertFalse(dead, "survives with fuel"),
                () -> assertTrue(killed(v), "dies with an empty tank (C fuel.sum == 0)"));
        }

        @Test
        @DisplayName("sparks push: others' sparks and own sparks without owner immunity")
        void sparks() {
            setup("");
            float m = v.mass;
            int other = obj(w, Obj.SPARK, k.id, 200, 200, 10, 0, 0.7, 10);
            w.objects.status[other] = Obj.OWNERIMMUNE;
            check(s);
            double vxOther = v.vx;
            double lifeOther = w.objects.life[other];
            setup("");
            int ownImm = obj(w, Obj.SPARK, v.id, 200, 200, 10, 0, 0.7, 10);
            w.objects.status[ownImm] = Obj.OWNERIMMUNE;
            check(s);
            double vxOwnImm = v.vx;
            double lifeOwnImm = w.objects.life[ownImm];
            setup("");
            int own = obj(w, Obj.SPARK, v.id, 200, 200, 10, 0, 0.7, 10);
            check(s);
            assertAll(
                () -> assertEquals((0f * m + 10f * 0.7f) / (m + 0.7f), vxOther, "another ship's spark pushes (Delta_mv)"),
                () -> assertEquals(0.0, lifeOther, EPS, "and is used up"),
                () -> assertEquals(0.0, vxOwnImm, EPS, "own owner-immune sparks do not push"),
                () -> assertEquals(10.0, lifeOwnImm, EPS, "and are not used up"),
                () -> assertEquals((0f * m + 10f * 0.7f) / (m + 0.7f), v.vx, "own sparks without immunity push"),
                () -> assertEquals(FUEL0, v.fuel, EPS, "sparks cost no fuel"),
                () -> assertFalse(killed(v), "sparks do not kill"));
        }

        @Test
        @DisplayName("a COLLISIONSHOVE object records its owner as a shover (not oneself, not unowned)")
        void collisionShove() {
            setup("");
            int h = obj(w, Obj.SPARK, k.id, 200, 200, 10, 0, 0.7, 10);
            w.objects.status[h] = Obj.COLLISIONSHOVE;
            w.frames = 7;
            check(s);
            int pusher = v.shovePusher[0];
            double time = v.shoveTime[0];
            setup("");
            obj(w, Obj.SPARK, k.id, 200, 200, 10, 0, 0.7, 10);
            check(s);
            assertAll(
                () -> assertEquals(k.id, pusher, "the owner is recorded as shover"),
                () -> assertEquals(7.0, time, EPS, "at the current frame"),
                () -> assertEquals(Obj.NO_ID, v.shovePusher[0], "no record without COLLISIONSHOVE"));
        }

        @Test
        @DisplayName("own and unowned COLLISIONSHOVE objects record nothing")
        void collisionShoveSelf() {
            setup("");
            int h2 = obj(w, Obj.SPARK, v.id, 200, 200, 10, 0, 0.7, 10);
            w.objects.status[h2] = Obj.COLLISIONSHOVE;
            int h3 = obj(w, Obj.SPARK, Obj.NO_ID, 200, 200, 10, 0, 0.7, 10);
            w.objects.status[h3] = Obj.COLLISIONSHOVE;
            check(s);
            assertAll(
                () -> assertEquals(0.0, w.objects.life[h2], EPS, "the own spark hit"),
                () -> assertEquals(0.0, w.objects.life[h3], EPS, "the unowned spark hit"),
                () -> assertEquals(Obj.NO_ID, v.shovePusher[0], "no shove recorded"));
        }

        @Test
        @DisplayName("Delta_mv uses |mass| for the total")
        void deltaMvNegativeMass() {
            setup("");
            int h = obj(w, Obj.SPARK, k.id, 200, 200, 10, 5, -0.7, 10);
            v.vx = 2;
            v.vy = 3;
            float m = v.mass;
            s.collisions.deltaMv(v, h);
            assertAll(
                () -> assertEquals((2f * m + 10f * -0.7f) / (m + 0.7f), v.vx, "vx = (v m + v_o m_o) / (m + |m_o|)"),
                () -> assertEquals((3f * m + 5f * -0.7f) / (m + 0.7f), v.vy, "vy = (v m + v_o m_o) / (m + |m_o|)"));
        }
    }
}
