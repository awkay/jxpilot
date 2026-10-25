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

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.OPEN;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.WALL;
import static com.fulcrologic.jxpilot.client.game.predict.PredictFx.options;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ProvisionalShots}: own bullets on the predicted timeline, moved as the server, confirmed by snapshots. */
class ProvisionalShotsTest {
    static final byte OWN = (byte) Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.OWN);
    static final byte ENEMY = (byte) Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.ENEMY);

    /** Shots on a map: no gravity, unless the extra options say otherwise. */
    static ProvisionalShots shots(String extra, String... rows) {
        Options o = options("gravity: 0\n" + extra, rows);
        ProvisionalShots s = new ProvisionalShots();
        s.configure(KernelWorld.of(o), o);
        return s;
    }

    /** The click-exact pixel position after {@code n} free-flight ticks from (x, y) at (vx, vy). */
    static float after(float x, float vx, int n) {
        return (Click.fromPixels(x) + n * Click.fromPixels(vx)) / (float) Click.CLICK;
    }

    @Nested
    @DisplayName("motion and life")
    class Motion {
        @Test
        @DisplayName("a final shot moves once per tick from its spawn (clicks of C's float pixels)")
        void freeFlight() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100.3f, 200.7f, 5.5f, -2.25f, false);
            s.advanceTo(12);
            s.draw(12, 0);
            assertAll(
                () -> assertEquals(1, s.count(), "drawn"),
                () -> assertEquals(after(100.3f, 5.5f, 3), s.x(0), 1e-6, "ticks 10, 11, 12: three moves in x"),
                () -> assertEquals(after(200.7f, -2.25f, 3), s.y(0), 1e-6, "and in y"),
                () -> assertEquals(10, s.firedTick(0), "fire tick"),
                () -> assertEquals(0, s.firedOrdinal(0), "ordinal"));
        }

        @Test
        @DisplayName("shotsGravity adds the block's gravity to the velocity before each move; without it, none")
        void gravity() {
            Options o = options("gravity: -0.5\ngravityAngle: 90", OPEN);
            KernelWorld w = KernelWorld.of(o);
            ProvisionalShots g = new ProvisionalShots();
            g.configure(w, o);
            g.fired(1, 0, 200, 200, 0, 0, false);
            g.advanceTo(2);
            g.draw(2, 0);
            Options off = options("gravity: -0.5\ngravityAngle: 90\nshotsGravity: no", OPEN);
            ProvisionalShots n = new ProvisionalShots();
            n.configure(KernelWorld.of(off), off);
            n.fired(1, 0, 200, 200, 0, 0, false);
            n.advanceTo(2);
            n.draw(2, 0);
            float gy = w.gravityY(5, 5);
            assertAll(
                () -> assertEquals((Click.fromPixels(200) + Click.fromPixels(gy) + Click.fromPixels(2 * gy))
                    / (float) Click.CLICK, g.y(0), 1e-6, "pulled by g, then 2g"),
                () -> assertEquals(200, n.y(0), 1e-6, "no shotsGravity: straight on"));
        }

        @Test
        @DisplayName("a shot gone shotLife ticks after its fire tick: the state after f + shotLife - 1 has none")
        void life() {
            ProvisionalShots s = shots("shotLife: 3", OPEN);
            s.fired(10, 0, 200, 200, 1, 0, false);
            s.advanceTo(11);
            s.draw(11, 0);
            int at11 = s.count();
            s.advanceTo(12);
            s.draw(12, 0);
            assertAll(
                () -> assertEquals(1, at11, "alive after tick 11 (f + 1)"),
                () -> assertEquals(0, s.count(), "gone after tick 12 (f + shotLife - 1)"),
                () -> assertEquals(0, s.crashed(), "not a crash"));
        }

        @Test
        @DisplayName("a shot into a wall ends there (a crash); with shotsWallBounce it bounces and slows")
        void wall() {
            ProvisionalShots s = shots("", WALL);
            s.fired(1, 0, 270, 200, 20, 0, false);
            s.advanceTo(3);
            s.draw(3, 0);
            ProvisionalShots b = shots("shotsWallBounce: yes", WALL);
            b.fired(1, 0, 270, 200, 20, 0, false);
            b.advanceTo(3);
            b.draw(3, 0);
            assertAll(
                () -> assertEquals(0, s.count(), "the wall at x = 280 ends it"),
                () -> assertEquals(1, s.crashed(), "counted as a crash"),
                () -> assertEquals(1, b.count(), "bounced: still flying"),
                () -> assertEquals(true, b.x(0) < 280, "back on the near side: " + b.x(0)));
        }

        @Test
        @DisplayName("on an edge-wrapped map a shot wraps across the edge")
        void wraps() {
            ProvisionalShots s = shots("edgeWrap: yes", OPEN);
            s.fired(1, 0, 418, 200, 5, 0, false);
            s.advanceTo(1);
            s.draw(1, 0);
            assertEquals((Click.fromPixels(418f) + Click.fromPixels(5f) - 420 * Click.CLICK) / (float) Click.CLICK,
                s.x(0), 1e-6, "418 + 5 wraps to 3");
        }
    }

    @Nested
    @DisplayName("drawing and replays")
    class Drawing {
        @Test
        @DisplayName("drawn between its state after the head tick and its next, by alpha; speculative from its spawn")
        void alpha() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 8, 0, false);
            s.advanceTo(10);
            s.fired(11, 0, 50, 50, 4, 0, true);
            s.draw(10, 0.25);
            assertAll(
                () -> assertEquals(2, s.count(), "both drawn"),
                () -> assertEquals(108 + 0.25 * 8, s.x(0), 1e-6, "a quarter of the way from 108 to 116"),
                () -> assertEquals(50 + 0.25 * 4, s.x(1), 1e-6, "the speculative one a quarter from its spawn"));
        }

        @Test
        @DisplayName("stepping a tick again drops its shots; a speculative step drops only the speculative ones")
        void replays() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 1, 0, false);
            s.fired(11, 0, 200, 100, 1, 0, false);
            s.fired(12, 0, 300, 100, 1, 0, true);
            s.beginStep(11, false);
            s.advanceTo(11);
            s.draw(11, 0);
            int afterReplay = s.count();
            s.beginStep(12, true);
            s.draw(11, 0);
            assertAll(
                () -> assertEquals(2, afterReplay, "tick 11's dropped: tick 10's and the speculative one stay"),
                () -> assertEquals(1, s.count(), "the speculative step dropped the speculative one"),
                () -> assertEquals(10, s.firedTick(0), "tick 10's is left"));
        }

        @Test
        @DisplayName("without a world nothing is kept or drawn; clear drops everything")
        void noWorld() {
            ProvisionalShots s = new ProvisionalShots();
            s.fired(1, 0, 1, 1, 1, 1, false);
            s.draw(1, 0);
            int none = s.count();
            ProvisionalShots t = shots("", OPEN);
            t.fired(1, 0, 100, 100, 1, 0, false);
            t.clear();
            t.advanceTo(1);
            t.draw(1, 0);
            assertAll(
                () -> assertEquals(0, none, "no world: nothing"),
                () -> assertEquals(0, t.count(), "cleared"),
                () -> assertEquals(0, s.fired(), "not counted without a world"));
        }
    }

    @Nested
    @DisplayName("confirmation")
    class Confirmation {
        @Test
        @DisplayName("an own SHOT within 4 px of where the shot is at the snapshot's tick confirms it; within 1 px"
            + " nothing moves, farther the shot adopts the server's position")
        void confirms() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 8, 0, false);
            s.fired(10, 1, 100, 300, 8, 0, false);
            s.advanceTo(14);
            float x11 = after(100, 8, 2);
            s.confirm(11, 3, new float[] {x11 + 0.5f, x11 + 3, x11}, new float[] {100, 300, 300},
                new byte[] {OWN, OWN, ENEMY});
            s.draw(14, 0);
            assertAll(
                () -> assertEquals(2, s.confirmedShots(), "both confirmed"),
                () -> assertEquals(1, s.adopted(), "one adopted"),
                () -> assertEquals(after(100, 8, 5), s.x(0), 1e-6, "0.5 px off: not moved"),
                () -> assertEquals(after(100, 8, 5) + 3, s.x(1), 1e-6, "3 px off: moved by 3 px"),
                () -> assertEquals(3, s.maxAdoptPx(), 1e-4, "the largest adoption"),
                () -> assertEquals(3, s.maxMatchPx(), 1e-4, "the largest match"));
        }

        @Test
        @DisplayName("never matched in two complete snapshots after its fire tick: unconfirmed and dropped")
        void unconfirmed() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 8, 0, false);
            s.advanceTo(13);
            s.confirm(10, 0, new float[0], new float[0], new byte[0]);
            s.confirm(10, 0, new float[0], new float[0], new byte[0]);
            s.draw(13, 0);
            int atFireTick = s.count();
            s.confirm(11, 1, new float[] {100}, new float[] {100}, new byte[] {ENEMY});
            s.draw(13, 0);
            int oneMiss = s.count();
            s.confirm(12, 0, new float[0], new float[0], new byte[0]);
            s.draw(13, 0);
            assertAll(
                () -> assertEquals(1, atFireTick, "misses at its own fire tick do not count"),
                () -> assertEquals(1, oneMiss, "one miss (an enemy shot there is not ours): kept"),
                () -> assertEquals(0, s.count(), "two: dropped"),
                () -> assertEquals(1, s.unconfirmed(), "counted"));
        }

        @Test
        @DisplayName("a confirmed shot missing from two complete snapshots in a row ended on the server; a later"
            + " match resets the misses; a shot fired after the snapshot's tick is not judged")
        void ended() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 8, 0, false);
            s.fired(20, 0, 300, 300, 1, 0, false);
            s.advanceTo(20);
            s.confirm(11, 1, new float[] {after(100, 8, 2)}, new float[] {100}, new byte[] {OWN});
            s.confirm(12, 0, new float[0], new float[0], new byte[0]);
            s.confirm(13, 1, new float[] {after(100, 8, 4)}, new float[] {100}, new byte[] {OWN});
            s.confirm(14, 0, new float[0], new float[0], new byte[0]);
            s.draw(20, 0);
            int alive = s.count();
            s.confirm(15, 0, new float[0], new float[0], new byte[0]);
            s.draw(20, 0);
            assertAll(
                () -> assertEquals(2, alive, "a miss, a match, a miss: still there"),
                () -> assertEquals(1, s.count(), "two misses in a row: gone; the shot of tick 20 is not judged"),
                () -> assertEquals(1, s.endedOnServer(), "counted as ended"),
                () -> assertEquals(0, s.unconfirmed(), "not unconfirmed"));
        }
    }

    @Nested
    @DisplayName("the server's mover details")
    class MoverDetails {
        /** Open map with an UP_GRAV block at block (5, 5) (row 6 from the top). */
        static final String[] GRAV = {
            "            ", "            ", "            ", "            ", "            ", "      +     ",
            "     i      ", "            ", "            ", "            ", "            ", "            "};
        /** A wall column at x = 1 (pixels 35..70). */
        static final String[] EDGE_WALL = {
            " x          ", " x          ", " x          ", " x          ", " x          ", " x          ",
            " x          ", " x          ", " x          ", " x          ", " x          ", " x          "};

        @Test
        @DisplayName("gravity is the shot's own block's (its pixel over the block size), each tick from where it is")
        void blockGravity() {
            Options o = options("gravity: 0", GRAV);
            KernelWorld w = KernelWorld.of(o);
            ProvisionalShots s = new ProvisionalShots();
            s.configure(w, o);
            s.fired(1, 0, 190, 190, 0, 0, false);
            s.advanceTo(1);
            s.draw(1, 0);
            float gx = w.gravityX(5, 5);
            float gy = w.gravityY(5, 5);
            int cy = Click.fromPixels(190f) + Click.fromPixels(gy);
            int cx = Click.fromPixels(190f) + Click.fromPixels(gx);
            ProvisionalShots c = new ProvisionalShots();
            c.configure(w, o);
            c.fired(1, 0, 190, 190, 0, 0, false);
            c.confirm(1, 1, new float[] {cx / (float) Click.CLICK}, new float[] {cy / (float) Click.CLICK},
                new byte[] {OWN});
            assertAll(
                () -> assertTrue(gx != w.gravityX(0, 0) && gx != w.gravityX(11, 11) && gx != w.gravityX(5, 11)
                    && gx != w.gravityX(11, 5), "and in x: " + gx),
                () -> assertTrue(gy != w.gravityY(0, 0) && gy != w.gravityY(11, 11) && gy != w.gravityY(5, 11)
                    && gy != w.gravityY(11, 5), "the block's gravity differs from the others' here: " + gy),
                () -> assertEquals(cx / (float) Click.CLICK, s.x(0), 1e-6, "x moved by its block's gravity"),
                () -> assertEquals(cy / (float) Click.CLICK, s.y(0), 1e-6, "y moved by its block's gravity"),
                () -> assertEquals(1, c.confirmedShots(), "confirm steps it the same way (matched at 0 px)"),
                () -> assertEquals(0, c.maxMatchPx(), 1e-6, "exactly"));
        }

        @Test
        @DisplayName("traced next to a wall, a shot still wraps across the map edge")
        void tracedWrap() {
            ProvisionalShots s = shots("edgeWrap: yes", EDGE_WALL);
            s.fired(1, 0, 10, 200, -15, 0, false);
            s.advanceTo(1);
            s.draw(1, 0);
            ProvisionalShots u = shots("edgeWrap: yes", EDGE_WALL);
            u.fired(1, 0, 10, 10, 0, -15, false);
            u.advanceTo(1);
            u.draw(1, 0);
            ProvisionalShots d = shots("edgeWrap: yes", EDGE_WALL);
            d.fired(1, 0, 10, 410, 0, 15, false);
            d.advanceTo(1);
            d.draw(1, 0);
            ProvisionalShots r = shots("edgeWrap: yes", " x         x", " x         x", " x         x", " x         x",
                " x         x", " x         x", " x         x", " x         x", " x         x", " x         x",
                " x         x", " x         x");
            assertAll(
                () -> assertEquals((Click.fromPixels(10f) + Click.fromPixels(-15f) + 420 * Click.CLICK)
                    / (float) Click.CLICK, s.x(0), 1e-6, "left across x = 0: 415"),
                () -> assertEquals((Click.fromPixels(10f) + Click.fromPixels(-15f) + 420 * Click.CLICK)
                    / (float) Click.CLICK, u.y(0), 1e-6, "down across y = 0"),
                () -> assertEquals((Click.fromPixels(410f) + Click.fromPixels(15f) - 420 * Click.CLICK)
                    / (float) Click.CLICK, d.y(0), 1e-6, "up across the top"),
                () -> assertEquals(0, r.count(), "nothing fired"));
        }

        @Test
        @DisplayName("traced next to a wall, a shot that crosses the right edge wraps to the left")
        void tracedWrapRight() {
            ProvisionalShots s = shots("edgeWrap: yes", "          x ", "          x ", "          x ", "          x ",
                "          x ", "          x ", "          x ", "          x ", "          x ", "          x ",
                "          x ", "          x ");
            s.fired(1, 0, 410, 200, 15, 0, false);
            s.advanceTo(1);
            s.draw(1, 0);
            assertEquals((Click.fromPixels(410f) + Click.fromPixels(15f) - 420 * Click.CLICK) / (float) Click.CLICK,
                s.x(0), 1e-6, "right across x = 420: 5");
        }

        @Test
        @DisplayName("traced next to a wall, a shot ending exactly on the far edge wraps to 0 (and moves on)")
        void exactEdge() {
            String r = "          x ";
            ProvisionalShots s = shots("edgeWrap: yes", r, r, r, r, r, r, r, r, r, r, r, r);
            s.fired(1, 0, 410, 200, 10, 0, false);
            s.advanceTo(1);
            s.draw(1, 0);
            float x1 = s.x(0);
            s.advanceTo(2);
            s.draw(2, 0);
            String c = " x          ";
            ProvisionalShots d = shots("edgeWrap: yes", c, c, c, c, c, c, c, c, c, c, c, c);
            d.fired(1, 0, 10, 10, 0, -10, false);
            d.advanceTo(2);
            d.draw(2, 0);
            assertAll(
                () -> assertEquals(0, x1, 1e-6, "410 + 10 = 420 is x 0"),
                () -> assertEquals(10, s.x(0), 1e-6, "then 10"),
                () -> assertEquals(410, d.y(0), 1e-6, "10 - 20 wraps to 410"));
        }

        @Test
        @DisplayName("a bounce costs life by objectWallBounceLifeFactor; faster than maxObjectWallBounceSpeed it ends")
        void bounceRules() {
            ProvisionalShots life = shots("shotsWallBounce: yes\nshotLife: 10\nobjectWallBounceLifeFactor: 0.5", WALL);
            life.fired(1, 0, 270, 200, 20, 0, false);
            life.advanceTo(4);
            life.draw(4, 0);
            int at4 = life.count();
            life.advanceTo(5);
            life.draw(5, 0);
            ProvisionalShots full = shots("shotsWallBounce: yes\nshotLife: 10", WALL);
            full.fired(1, 0, 270, 200, 20, 0, false);
            full.advanceTo(5);
            full.draw(5, 0);
            ProvisionalShots fast = shots("shotsWallBounce: yes\nmaxObjectWallBounceSpeed: 15", WALL);
            fast.fired(1, 0, 270, 200, 20, 0, false);
            fast.advanceTo(2);
            fast.draw(2, 0);
            ProvisionalShots slow = shots("shotsWallBounce: yes\nobjectWallBounceBrakeFactor: 0.5", WALL);
            slow.fired(1, 0, 270, 200, 20, 0, false);
            slow.advanceTo(3);
            slow.draw(3, 0);
            float x3 = slow.x(0);
            slow.advanceTo(4);
            slow.draw(4, 0);
            assertAll(
                () -> assertEquals(1, at4, "life 10 halved by the bounce of tick 1 (9 * 0.5 = 4): still there after 4"),
                () -> assertEquals(0, life.count(), "gone after tick 5"),
                () -> assertEquals(1, full.count(), "without the factor it lives on"),
                () -> assertEquals(0, fast.count(), "20 px per tick is over 15: the bounce ends it"),
                () -> assertEquals(-10, slow.x(0) - x3, 0.02, "half its speed after the bounce, going back"));
        }
    }

    @Nested
    @DisplayName("bookkeeping")
    class Bookkeeping {
        @Test
        @DisplayName("y is drawn by alpha too, wrapped on an edge-wrapped map; a shot ending in the next step is drawn"
            + " where it is, a speculative one that ends at once is not drawn")
        void drawDetails() {
            ProvisionalShots s = shots("edgeWrap: yes", OPEN);
            s.fired(5, 0, 100, 418, 0, 4, false);
            s.advanceTo(4);
            s.draw(4, 0.75);
            float wrapped = s.y(0);
            ProvisionalShots w = shots("", WALL);
            w.fired(5, 0, 270, 200, 20, 0, false);
            w.fired(6, 0, 270, 100, 20, 0, true);
            w.advanceTo(4);
            w.draw(4, 0.5);
            assertAll(
                () -> assertEquals(418 + 0.75 * 4 - 420, wrapped, 1e-4, "three quarters across the top: 1"),
                () -> assertEquals(1, w.count(), "the final one at its spawn; the speculative one ends at once"),
                () -> assertEquals(270, w.x(0), 1e-4, "not moved toward the wall it hits"));
        }

        @Test
        @DisplayName("x is wrapped when drawn across the right edge; an adopted shot is stepped from its moved spawn")
        void wrapXAndAdoptedSpawn() {
            ProvisionalShots s = shots("edgeWrap: yes", OPEN);
            s.fired(5, 0, 418, 100, 4, 0, false);
            s.advanceTo(4);
            s.draw(4, 0.75);
            float wrapped = s.x(0);
            ProvisionalShots a = shots("", OPEN);
            a.fired(10, 0, 100, 100, 8, 0, false);
            a.advanceTo(12);
            a.confirm(11, 1, new float[] {after(100, 8, 2) + 3}, new float[] {100}, new byte[] {OWN});
            a.confirm(12, 1, new float[] {after(100, 8, 3) + 3}, new float[] {100}, new byte[] {OWN});
            assertAll(
                () -> assertEquals(418 + 0.75 * 4 - 420, wrapped, 1e-4, "1 past the edge"),
                () -> assertEquals(1, a.adopted(), "the second snapshot matches the moved shot exactly"),
                () -> assertEquals(3, a.maxAdoptPx(), 1e-4, "3 px once"));
        }

        @Test
        @DisplayName("bullets are counted and numbered; a freed slot is reused; at most CAP shots")
        void slots() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(1, 0, 100, 100, 0, 0, false);
            s.fired(2, 3, 100, 120, 0, 0, false);
            s.fired(3, 0, 100, 140, 0, 0, false);
            s.beginStep(2, false);
            s.fired(4, 0, 100, 160, 0, 0, false);
            s.advanceTo(4);
            s.draw(4, 0);
            int three = s.count();
            float reused = s.y(1);
            ProvisionalShots full = shots("", OPEN);
            for (int k = 0; k < ProvisionalShots.CAP + 5; k++) {
                full.fired(1, k, 100, 100, 0, 0, false);
            }
            full.advanceTo(1);
            full.draw(1, 0);
            ProvisionalShots o = shots("", OPEN);
            o.fired(2, 3, 100, 120, 0, 0, false);
            o.advanceTo(2);
            o.draw(2, 0);
            assertAll(
                () -> assertEquals(3, three, "three left"),
                () -> assertEquals(160, reused, 1e-4, "the dropped slot 1 holds the newest"),
                () -> assertEquals(4, s.fired(), "four reported"),
                () -> assertEquals(ProvisionalShots.CAP, full.count(), "no more than CAP"),
                () -> assertEquals(3, o.firedOrdinal(0), "ordinal kept"));
        }

        @Test
        @DisplayName("configure drops the shots of the old world")
        void configureClears() {
            Options opt = options("gravity: 0", OPEN);
            ProvisionalShots s = new ProvisionalShots();
            s.configure(KernelWorld.of(opt), opt);
            s.fired(1, 0, 100, 100, 0, 0, false);
            s.configure(KernelWorld.of(opt), opt);
            s.advanceTo(1);
            s.draw(1, 0);
            assertAll(
                () -> assertEquals(0, s.count(), "dropped"),
                () -> assertEquals(0, s.endingAt(60), "its end no longer counts"));
        }

        @Test
        @DisplayName("confirmation: speculative shots, shots of later ticks and shots that ended are not judged; the"
            + " nearest own SHOT wins; adoption moves y too")
        void confirmDetails() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 0, 8, false);
            s.fired(11, 0, 300, 300, 0, 0, true);
            s.advanceTo(12);
            float y11 = after(100, 8, 2);
            s.confirm(11, 3, new float[] {100, 100, 300}, new float[] {y11 + 3.5f, y11 + 2, 300},
                new byte[] {OWN, OWN, OWN});
            s.draw(12, 0);
            ProvisionalShots life = shots("shotLife: 2", OPEN);
            life.fired(10, 0, 100, 100, 0, 8, false);
            life.confirm(13, 0, new float[0], new float[0], new byte[0]);
            life.confirm(14, 0, new float[0], new float[0], new byte[0]);
            assertAll(
                () -> assertEquals(1, s.confirmedShots(), "only the final shot of tick 10"),
                () -> assertEquals(2, s.maxAdoptPx(), 1e-4, "matched to the nearer (2 px), not 3.5 px"),
                () -> assertEquals(after(100, 8, 3) + 2, s.y(0), 1e-4, "moved 2 px in y"),
                () -> assertEquals(100, s.x(0), 1e-4, "x as it was"),
                () -> assertEquals(0, life.unconfirmed(), "a shot whose own life ended before the tick is not judged"));
        }

        @Test
        @DisplayName("a match exactly MATCH_PX away still counts; an adoption of exactly ADOPT_PX does not move")
        void boundaries() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 0, 0, false);
            s.fired(10, 1, 200, 100, 0, 0, false);
            s.advanceTo(10);
            s.confirm(10, 2, new float[] {100 + (float) ProvisionalShots.MATCH_PX, 200 + (float) ProvisionalShots.ADOPT_PX},
                new float[] {100, 100}, new byte[] {OWN, OWN});
            s.draw(10, 0);
            assertAll(
                () -> assertEquals(2, s.confirmedShots(), "both matched"),
                () -> assertEquals(1, s.adopted(), "only the 4 px one moved"),
                () -> assertEquals(201 - 1, s.x(1), 1e-4, "1 px off: not moved"));
        }
    }

    @Nested
    @DisplayName("shot count give-back")
    class GiveBack {
        @Test
        @DisplayName("a final shot ends at f + shotLife - 1, or in the tick it crashes; still counted after it ended")
        void ends() {
            ProvisionalShots s = shots("shotLife: 3", WALL);
            s.fired(10, 0, 100, 200, 1, 0, false);
            s.fired(10, 1, 270, 200, 20, 0, false);
            s.fired(11, 0, 100, 100, 1, 0, true);
            s.advanceTo(20);
            assertAll(
                () -> assertEquals(1, s.endingAt(12), "life 3: gone after tick 12"),
                () -> assertEquals(1, s.endingAt(10), "the one into the wall ends in tick 10"),
                () -> assertEquals(0, s.endingAt(13), "a speculative shot is not counted"),
                () -> assertEquals(0, s.endingAt(11), "nothing else"));
        }

        @Test
        @DisplayName("a replayed tick's shots and unconfirmed shots no longer count; a confirmed one gone from the"
            + " snapshots (a hit, or out of their view) still does")
        void uncounted() {
            ProvisionalShots s = shots("", OPEN);
            s.fired(10, 0, 100, 100, 0, 0, false);
            s.fired(11, 0, 100, 200, 0, 0, false);
            s.fired(12, 0, 100, 300, 0, 0, false);
            int end = 10 + 60 - 1;
            s.beginStep(10, false);
            s.advanceTo(12);
            s.confirm(12, 1, new float[] {100}, new float[] {300}, new byte[] {OWN});
            s.confirm(12, 1, new float[] {100}, new float[] {300}, new byte[] {OWN});
            ProvisionalShots h = shots("", OPEN);
            h.fired(10, 0, 100, 100, 0, 0, false);
            h.confirm(10, 1, new float[] {100}, new float[] {100}, new byte[] {OWN});
            h.confirm(11, 0, new float[0], new float[0], new byte[0]);
            h.confirm(12, 0, new float[0], new float[0], new byte[0]);
            assertAll(
                () -> assertEquals(1, s.endingAt(end + 2), "tick 12's, confirmed, still counts"),
                () -> assertEquals(0, s.endingAt(end), "tick 10's was dropped by the replay"),
                () -> assertEquals(0, s.endingAt(end + 1), "tick 11's was never confirmed"),
                () -> assertEquals(1, h.endedOnServer(), "confirmed, then missing twice: ended on the server"),
                () -> assertEquals(1, h.endingAt(end), "still counted (most often only out of the view)"));
        }
    }

    @Nested
    @DisplayName("KernelShotMotion")
    class Motion2 {
        @Test
        @DisplayName("a cannon's shot flies through cannons outside team play; a player's shot crashes into one")
        void cannonShot() throws java.io.IOException {
            // doggy.xp's bottom-row cannons: a shot just fired from one starts inside its block
            Options o = new Options();
            com.fulcrologic.jxpilot.common.config.ConfigParser.parse(
                java.nio.file.Files.readString(java.nio.file.Path.of("..", "maps", "doggy.xp"),
                    java.nio.charset.StandardCharsets.ISO_8859_1), "doggy", com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP, o);
            KernelShotMotion m = new KernelShotMotion(KernelWorld.of(o), o);
            ShotStep out = new ShotStep();
            int x = Click.fromPixels(787f);
            int y = Click.fromPixels(81f);
            boolean cannon = m.tick(x, y, 6, 20, 100, true, out);
            float cannonY = out.y / (float) Click.CLICK;
            boolean player = m.tick(x, y, 6, 20, 100, false, out);
            assertAll(
                () -> assertTrue(cannon, "the cannon's shot leaves its cannon"),
                () -> assertTrue(cannonY > 95, "moving up: " + cannonY),
                () -> assertTrue(!player, "a player's shot there crashes into the cannon"));
        }
    }
}
