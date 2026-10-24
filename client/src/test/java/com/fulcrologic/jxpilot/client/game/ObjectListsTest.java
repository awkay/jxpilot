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

import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.client.game.ObjectLists.*;
import static com.fulcrologic.jxpilot.client.game.WorldFixture.at;
import static org.junit.jupiter.api.Assertions.*;

class ObjectListsTest {
    static final float NO_ID = 65535f;

    @Nested
    @DisplayName("lists")
    class Lists {
        @Test
        @DisplayName("add up to capacity per kind, fields by index, clear and copy")
        void basics() {
            ObjectLists o = new ObjectLists(2);
            boolean a = o.add(ITEM, 1, 2, 3, 0, 0, 0);
            boolean b = o.add(ITEM, 4, 5, 6, 0, 0, 0);
            boolean c = o.add(ITEM, 7, 8, 9, 0, 0, 0);
            o.add(MINE, 10, 11, 2, 7, 0, 0);
            ObjectLists copy = new ObjectLists(2);
            copy.add(ECM, 1, 1, 1, 0, 0, 0);
            copy.copyFrom(o);
            o.clear();
            assertAll(
                () -> assertTrue(a && b, "two fit"),
                () -> assertFalse(c, "the third is refused"),
                () -> assertEquals(2, copy.count(ITEM), "copied items"),
                () -> assertEquals(6f, copy.get(ITEM, 1, 2), "second item's type"),
                () -> assertEquals(7f, copy.get(MINE, 0, 3), "mine owner"),
                () -> assertEquals(0, copy.count(ECM), "the copy replaced the old contents"),
                () -> assertEquals(0, o.count(ITEM), "cleared"),
                () -> assertEquals(2, o.capacity(), "capacity"));
        }
    }

    /** Associate a with b over one frame, then move a copy of a by t (and dt frames when unlinked). */
    static ObjectLists moved(ObjectLists a, ObjectLists b, double t, double dt, double world, boolean wrap) {
        a.associate(b, new Tracker(64), new ObjectLists.Scratch(64), 1, world, world, wrap);
        ObjectLists out = new ObjectLists(a.capacity());
        out.copyFrom(a);
        out.moveTracked(a, b, t, dt, world, world, wrap);
        return out;
    }

    @Nested
    @DisplayName("tracked objects (associate + moveTracked)")
    class Moving {
        @Test
        @DisplayName("balls continue by id, missiles without id by the unambiguous nearest, unmatched stay; headings turn short")
        void interpolate() {
            ObjectLists a = new ObjectLists(8);
            ObjectLists b = new ObjectLists(8);
            a.add(BALL, 100, 100, 1, NO_ID, 5, 0);
            a.add(BALL, 300, 100, 1, NO_ID, 6, 0);
            b.add(BALL, 320, 100, 1, NO_ID, 6, 0);
            b.add(BALL, 110, 120, 1, NO_ID, 5, 0);
            a.add(MISSILE, 50, 50, (float) (0.1), 0, 10, NO_ID);
            a.add(MISSILE, 500, 500, 0, 0, 10, NO_ID);
            b.add(MISSILE, 54, 50, (float) (2 * Math.PI - 0.1), 0, 10, NO_ID);
            ObjectLists o = moved(a, b, 0.5, 0.5, 2000, true);
            assertAll(
                () -> assertEquals(105f, o.get(BALL, 0, 0), 1e-4f, "ball 5 halfway to its id match (x)"),
                () -> assertEquals(110f, o.get(BALL, 0, 1), 1e-4f, "ball 5 halfway (y)"),
                () -> assertEquals(310f, o.get(BALL, 1, 0), 1e-4f, "ball 6 halfway"),
                () -> assertEquals(52f, o.get(MISSILE, 0, 0), 1e-4f, "the missile takes its continuation"),
                () -> assertEquals(0.0, com.fulcrologic.jxpilot.common.math.XMath.angleDiff(0, o.get(MISSILE, 0, 2)), 1e-4,
                    "heading 0.1 to -0.1 the short way: 0 (or its equal 2 pi)"),
                () -> assertEquals(500f, o.get(MISSILE, 1, 0), 1e-4f, "far missile: nothing within reach, stays"));
        }

        @Test
        @DisplayName("an id without a match stays; across the seam the short way, wrapped")
        void idAndSeam() {
            ObjectLists a = new ObjectLists(4);
            ObjectLists b = new ObjectLists(4);
            a.add(BALL, 690, 10, 0, NO_ID, 1, 0);
            a.add(BALL, 200, 10, 0, NO_ID, 2, 0);
            b.add(BALL, 10, 10, 0, NO_ID, 1, 0);
            b.add(BALL, 205, 10, 0, NO_ID, 3, 0);
            ObjectLists o = moved(a, b, 0.75, 0.75, 700, true);
            assertAll(
                () -> assertEquals(5f, o.get(BALL, 0, 0), 1e-4f, "690 -> 710: 705 wraps to 5"),
                () -> assertEquals(200f, o.get(BALL, 1, 0), 1e-4f, "id 2 has no continuation: stays (id 3 is not it)"));
        }

        @Test
        @DisplayName("items, mines, asteroids and wreckage are tracked too; a known velocity carries an unlinked object")
        void fastItems() {
            ObjectLists a = new ObjectLists(4);
            ObjectLists b = new ObjectLists(4);
            ObjectLists c = new ObjectLists(4);
            a.add(ITEM, 100, 100, 3, 0, 0, 0);
            b.add(ITEM, 160, 100, 3, 0, 0, 0);
            c.add(ITEM, 220, 100, 3, 0, 0, 0);
            a.add(ASTEROID, 400, 400, 2, 0, 0, 0);
            b.add(ASTEROID, 430, 400, 2, 0, 0, 0);
            a.add(WRECK, 600, 600, 1, 4, 0, 0);
            b.add(WRECK, 600, 620, 1, 4, 0, 0);
            a.add(MINE, 800, 800, 0, 3, 0, 0);
            b.add(MINE, 805, 800, 0, 3, 0, 0);
            ObjectLists ab = moved(a, b, 0.5, 0.5, 4000, false);
            // b's velocities came from the a->b link; c's frame then goes missing: b's item is extrapolated
            ObjectLists bOut = new ObjectLists(4);
            bOut.copyFrom(b);
            bOut.moveTracked(b, null, 0, 0.5, 4000, 4000, false);
            b.associate(c, new Tracker(64), new ObjectLists.Scratch(64), 1, 4000, 4000, false);
            ObjectLists bc = new ObjectLists(4);
            bc.copyFrom(b);
            bc.moveTracked(b, c, 0.25, 0.25, 4000, 4000, false);
            assertAll(
                () -> assertEquals(130f, ab.get(ITEM, 0, 0), 1e-4f, "a 60 px/frame item is followed (U1)"),
                () -> assertEquals(415f, ab.get(ASTEROID, 0, 0), 1e-4f, "asteroid halfway"),
                () -> assertEquals(610f, ab.get(WRECK, 0, 1), 1e-4f, "wreck halfway"),
                () -> assertEquals(802.5f, ab.get(MINE, 0, 0), 1e-4f, "mine halfway"),
                () -> assertEquals(190f, bOut.get(ITEM, 0, 0), 1e-4f, "no later frame: by its 60 px/frame velocity"),
                () -> assertEquals(175f, bc.get(ITEM, 0, 0), 1e-4f, "known velocity: the next link is found tightly"),
                () -> assertEquals(Tracker.KNOWN, b.state[ITEM][0], "the item's velocity is known after a->b"));
        }

        @Test
        @DisplayName("an item of another type is not a continuation")
        void types() {
            ObjectLists a = new ObjectLists(4);
            ObjectLists b = new ObjectLists(4);
            a.add(ITEM, 100, 100, 3, 0, 0, 0);
            b.add(ITEM, 110, 100, 4, 0, 0, 0);
            ObjectLists o = moved(a, b, 0.5, 0.5, 4000, false);
            assertEquals(100f, o.get(ITEM, 0, 0), 1e-4f, "different item type: stays");
        }
    }

    @Nested
    @DisplayName("shared ids, exclusivity and lines")
    class Gating {
        @Test
        @DisplayName("two balls sharing an id each take their own continuation; a taken ball is not reused")
        void sharedIds() {
            ObjectLists a = new ObjectLists(4);
            ObjectLists b = new ObjectLists(4);
            a.add(BALL, 100, 100, 0, NO_ID, 9, 0);
            a.add(BALL, 400, 100, 0, NO_ID, 9, 0);
            b.add(BALL, 410, 100, 0, NO_ID, 9, 0);
            b.add(BALL, 110, 100, 0, NO_ID, 9, 0);
            ObjectLists o = moved(a, b, 0.5, 0.5, 2000, false);
            ObjectLists c = new ObjectLists(4);
            ObjectLists d = new ObjectLists(4);
            c.add(BALL, 100, 100, 0, NO_ID, NO_ID, 0);
            c.add(BALL, 140, 100, 0, NO_ID, NO_ID, 0);
            d.add(BALL, 110, 100, 0, NO_ID, NO_ID, 0);
            ObjectLists e = moved(c, d, 0.5, 0.5, 2000, false);
            assertAll(
                () -> assertEquals(105f, o.get(BALL, 0, 0), 1e-4f, "the first id-9 ball does not jump to the far one"),
                () -> assertEquals(405f, o.get(BALL, 1, 0), 1e-4f, "the second takes the far one"),
                () -> assertEquals(105f, e.get(BALL, 0, 0), 1e-4f, "free balls: the nearer takes the only later one"),
                () -> assertEquals(140f, e.get(BALL, 1, 0), 1e-4f, "the other finds it taken and stays"));
        }

        @Test
        @DisplayName("connectors follow the same-kind line nearest at both ends; beams too; out of reach stays")
        void lines() {
            ObjectLists a = new ObjectLists(4);
            ObjectLists b = new ObjectLists(4);
            a.add(CONNECTOR, 100, 100, 200, 100, 0, 0);
            a.add(CONNECTOR, 100, 300, 200, 300, 3, 0);
            a.add(CONNECTOR, 500, 500, 600, 500, 0, 0);
            b.add(CONNECTOR, 110, 300, 210, 300, 0, 0);
            b.add(CONNECTOR, 120, 100, 220, 110, 0, 0);
            b.add(CONNECTOR, 104, 300, 204, 300, 3, 0);
            a.add(TRANSPORTER, 10, 10, 40, 40, 0, 0);
            b.add(TRANSPORTER, 20, 10, 50, 40, 0, 0);
            a.interpolateLines(b, 0.5, 30, 2000, 2000, false);
            assertAll(
                () -> assertEquals(110f, a.get(CONNECTOR, 0, 0), 1e-4f, "ball connector start halfway"),
                () -> assertEquals(210f, a.get(CONNECTOR, 0, 2), 1e-4f, "end x halfway"),
                () -> assertEquals(105f, a.get(CONNECTOR, 0, 3), 1e-4f, "end y halfway"),
                () -> assertEquals(102f, a.get(CONNECTOR, 1, 0), 1e-4f, "the refuel line follows its own kind"),
                () -> assertEquals(500f, a.get(CONNECTOR, 2, 0), 1e-4f, "nothing within reach: stays"),
                () -> assertEquals(15f, a.get(TRANSPORTER, 0, 0), 1e-4f, "transporter beam start"),
                () -> assertEquals(45f, a.get(TRANSPORTER, 0, 2), 1e-4f, "transporter beam end"));
        }
    }

    @Nested
    @DisplayName("in ClientWorld")
    class InWorld {
        @Test
        @DisplayName("every M2 section is decoded into the frame's objects and drawn from the earlier frame")
        void decoded() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100);
            f.w.beginSection(Section.ITEM);
            Records.ItemObj.put(f.w, 100, 50, 7);
            f.w.endSection();
            f.w.beginSection(Section.MINE);
            Records.Mine.put(f.w, 110, 50, Records.Mine.FLAG_FRIENDLY, 3);
            f.w.endSection();
            f.w.beginSection(Section.MISSILE);
            Records.Missile.put(f.w, 9, 120, 50, Math.PI / 2, Records.Missile.KIND_HEAT, 15);
            f.w.endSection();
            f.w.beginSection(Section.BALL);
            Records.Ball.put(f.w, 4, 130, 50, 2, 9);
            f.w.endSection();
            f.w.beginSection(Section.LASER);
            Records.Laser.put(f.w, 140, 50, Math.PI, 40, Records.ObjColor.RED);
            f.w.endSection();
            f.w.beginSection(Section.ASTEROID);
            Records.Asteroid.put(f.w, 150, 50, 3, Math.PI / 2);
            f.w.endSection();
            f.w.beginSection(Section.WRECKAGE);
            Records.Wreckage.put(f.w, 160, 50, 2, 8, Math.PI);
            f.w.endSection();
            f.w.beginSection(Section.ECM);
            Records.Ecm.put(f.w, 170, 50, 60);
            f.w.endSection();
            f.w.beginSection(Section.CONNECTOR);
            Records.Connector.put(f.w, 180, 50, 190, 60, Records.Connector.KIND_TRACTOR);
            f.w.endSection();
            f.w.beginSection(Section.TRANSPORTER);
            Records.Transporter.put(f.w, 200, 50, 210, 60);
            f.w.endSection();
            f.ingestAtTick(100);
            f.world.update(at(102));
            ObjectLists o = f.world.objects();
            assertAll(
                () -> assertEquals(7f, o.get(ITEM, 0, 2), "item type"),
                () -> assertEquals(100f, o.get(ITEM, 0, 0), "item x"),
                () -> assertEquals((float) Records.Mine.FLAG_FRIENDLY, o.get(MINE, 0, 2), "mine flags"),
                () -> assertEquals(3f, o.get(MINE, 0, 3), "mine owner"),
                () -> assertEquals((float) (Math.PI / 2), o.get(MISSILE, 0, 2), 1e-4f, "missile heading"),
                () -> assertEquals((float) Records.Missile.KIND_HEAT, o.get(MISSILE, 0, 3), "missile kind"),
                () -> assertEquals(15f, o.get(MISSILE, 0, 4), "missile length"),
                () -> assertEquals(9f, o.get(MISSILE, 0, 5), "missile id"),
                () -> assertEquals(9f, o.get(BALL, 0, 3), "ball holder"),
                () -> assertEquals(4f, o.get(BALL, 0, 4), "ball id"),
                () -> assertEquals(40f, o.get(LASER, 0, 3), "laser length"),
                () -> assertEquals((float) Records.ObjColor.RED, o.get(LASER, 0, 4), "laser colour"),
                () -> assertEquals(3f, o.get(ASTEROID, 0, 2), "asteroid size"),
                () -> assertEquals(2f, o.get(WRECK, 0, 2), "wreck shape"),
                () -> assertEquals(8f, o.get(WRECK, 0, 3), "wreck size"),
                () -> assertEquals(60f, o.get(ECM, 0, 2), "ECM radius"),
                () -> assertEquals(190f, o.get(CONNECTOR, 0, 2), "connector x1"),
                () -> assertEquals((float) Records.Connector.KIND_TRACTOR, o.get(CONNECTOR, 0, 4), "connector kind"),
                () -> assertEquals(60f, o.get(TRANSPORTER, 0, 3), "transporter y1"));
        }

        @Test
        @DisplayName("HUD timers from the newest TIMERS; time left counts down by render time; reset forgets them")
        void timers() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100);
            f.w.beginSection(Section.TIMERS);
            Records.Timers.put(f.w, 12, 30, 600);
            f.w.endSection();
            f.ingestAtTick(100);
            f.begin(102);
            f.w.beginSection(Section.TIMERS);
            Records.Timers.put(f.w, 10, 0, -1);
            f.w.endSection();
            f.ingestAtTick(102);
            f.world.update(at(103.2));
            int early = f.world.timeLeft();
            f.world.update(at(103.2 + 130));
            int later = f.world.timeLeft();
            int damaged = f.world.damaged();
            int delay = f.world.roundDelay();
            f.world.reset();
            assertAll(
                () -> assertEquals(10, damaged, "damaged from the newest"),
                () -> assertEquals(0, delay, "round delay from the newest"),
                () -> assertEquals(600, early, "render tick 100: as reported"),
                () -> assertEquals(598, later, "130 ticks at 60 Hz later: 2 whole seconds gone (-1 kept the report)"),
                () -> assertEquals(-1, f.world.timeLeft(), "reset: never reported"),
                () -> assertEquals(0, f.world.damaged(), "reset: not damaged"));
        }
    }
}
