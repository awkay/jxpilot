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

import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.WorldFixture.at;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shots and point particles sent without velocity (the XPilot path) are linked across frames and
 * interpolated. Fixture: 60 Hz ticks, 0.25 original frames per tick, render delay 3.2 ticks; frames 2
 * ticks apart are 0.5 original frames apart, so the tracking radius is 32 px.
 */
class ClientWorldTrackingTest {
    static final double EPS = 1e-3;
    static final int TEAM = Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.TEAM);
    static final int ENEMY = Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.ENEMY);

    /** One snapshot with shots {x, y} (no velocity) of one variant. */
    static void shots(WorldFixture f, int tick, int variant, double... xy) {
        f.begin(tick);
        f.w.beginSection(Section.SHOT, variant);
        for (int i = 0; i < xy.length; i += 2) {
            Records.Shot.put(f.w, xy[i], xy[i + 1], 0, 0);
        }
        f.w.endSection();
        f.ingestAtTick(tick);
    }

    static List<String> drawn(ClientWorld w) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < w.shotCount(); i++) {
            out.add(String.format("%.2f,%.2f", w.shotX(i), w.shotY(i)));
        }
        return out;
    }

    @Nested
    @DisplayName("shots")
    class Shots {
        @Test
        @DisplayName("a shot is interpolated to its continuation in the next frame")
        void interpolated() {
            WorldFixture f = new WorldFixture(true);
            shots(f, 100, TEAM, 100, 50);
            shots(f, 102, TEAM, 110, 54);
            f.world.update(at(104.2));
            List<String> mid = drawn(f.world);
            f.world.update(at(104.7));
            assertAll(
                () -> assertEquals(List.of("105.00,52.00"), mid, "render tick 101: halfway"),
                () -> assertEquals(List.of("107.50,53.00"), drawn(f.world), "render tick 101.5: three quarters"));
        }

        @Test
        @DisplayName("across the wrap seam the short way, wrapped into the world")
        void seam() {
            WorldFixture f = new WorldFixture(true);
            shots(f, 100, TEAM, 690, 50);
            shots(f, 102, TEAM, 10, 50);
            f.world.update(at(104.7));
            assertEquals(List.of("5.00,50.00"), drawn(f.world), "690 -> 710 (= 10): three quarters is 705, i.e. 5");
        }

        @Test
        @DisplayName("crossing shots keep their identity thanks to the velocity estimated from the frame before")
        void crossing() {
            WorldFixture f = new WorldFixture(true);
            shots(f, 98, TEAM, 70, 50, 170, 50);
            shots(f, 100, TEAM, 100, 50, 140, 50);
            shots(f, 102, TEAM, 130, 50, 110, 50);
            f.world.update(at(102.2));
            List<String> first = drawn(f.world);
            f.world.update(at(104.2));
            assertAll(
                () -> assertEquals(List.of("85.00,50.00", "155.00,50.00"), first, "render tick 99: both halfway"),
                () -> assertEquals(List.of("115.00,50.00", "125.00,50.00"), drawn(f.world),
                    "render tick 101: still flying apart-wards, not swapped (nearest without prediction would swap)"));
        }

        @Test
        @DisplayName("shots link only to the same variant; too far is no link")
        void variantAndRange() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100);
            f.w.beginSection(Section.SHOT, TEAM);
            Records.Shot.put(f.w, 100, 50, 0, 0);
            f.w.endSection();
            f.w.beginSection(Section.SHOT, ENEMY);
            Records.Shot.put(f.w, 300, 50, 0, 0);
            f.w.endSection();
            f.ingestAtTick(100);
            f.begin(102);
            f.w.beginSection(Section.SHOT, ENEMY);
            Records.Shot.put(f.w, 104, 50, 0, 0);
            Records.Shot.put(f.w, 340, 50, 0, 0);
            f.w.endSection();
            f.ingestAtTick(102);
            f.world.update(at(104.2));
            assertEquals(List.of("100.00,50.00", "300.00,50.00"), drawn(f.world),
                "the team shot does not take the enemy shot 4 px away; the enemy shot 40 px away (> 32) is not linked;"
                    + " unlinked shots without velocity stay put");
        }

        @Test
        @DisplayName("an unlinked shot moves on with its estimated velocity; with only an earlier frame all do")
        void unlinked() {
            WorldFixture f = new WorldFixture(true);
            shots(f, 98, TEAM, 100, 50);
            shots(f, 100, TEAM, 110, 50);
            shots(f, 102, TEAM);
            f.world.update(at(102.2));
            f.world.update(at(104.2));
            List<String> dying = drawn(f.world);
            f.world.update(at(106.2));
            assertAll(
                () -> assertEquals(List.of("115.00,50.00"), dying,
                    "render tick 101: gone in 102, so 110 + 20 px/frame * 0.25 frames"),
                () -> assertEquals(0, f.world.shotCount(), "render tick 103: the frame without it is the base"));
        }

        @Test
        @DisplayName("a shot with wire velocity extrapolates with it when unlinked; new shots appear at the later frame")
        void wireAndNew() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).shot(100, 50, 8, 0).ingestAtTick(100);
            f.world.update(at(102.2));
            List<String> onlyLater = drawn(f.world);
            f.world.update(at(104.2));
            assertAll(
                () -> assertEquals(List.of("100.00,50.00"), onlyLater, "render tick 99, only frame 100: drawn there"),
                () -> assertEquals(List.of("102.00,50.00"), drawn(f.world), "render tick 101: 8 px/frame * 0.25"));
        }
    }

    @Nested
    @DisplayName("point particles")
    class Points {
        DebrisBurst point(double x, double y) {
            DebrisBurst d = new DebrisBurst();
            d.x = (float) x;
            d.y = (float) y;
            d.count = 1;
            return d;
        }

        @Test
        @DisplayName("one-particle bursts without speed or life are tracked points, interpolated per colour")
        void tracked() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).burst(point(200, 100), Records.BurstKind.SPARK, ClientWorld.POINT_LEVEL_BASE + 2, 0)
                .burst(point(220, 100), Records.BurstKind.SPARK, Records.ObjColor.RED, 0).ingestAtTick(100);
            f.begin(102).burst(point(210, 104), Records.BurstKind.SPARK, ClientWorld.POINT_LEVEL_BASE + 2, 0)
                .burst(point(224, 100), Records.BurstKind.SPARK, Records.ObjColor.RED, 0).ingestAtTick(102);
            f.world.update(at(104.2));
            ClientWorld w = f.world;
            assertAll(
                () -> assertEquals(0, w.particles().count(), "not in the particle pool"),
                () -> assertEquals(2, w.pointCount(), "two points"),
                () -> assertEquals(205f, w.pointX(0), EPS, "level-2 point halfway x (not the closer red one)"),
                () -> assertEquals(102f, w.pointY(0), EPS, "halfway y"),
                () -> assertEquals(ClientWorld.POINT_LEVEL_BASE + 2, w.pointColor(0), "colour code kept"),
                () -> assertEquals(222f, w.pointX(1), EPS, "red point halfway"),
                () -> assertEquals(Records.ObjColor.RED, w.pointColor(1), "red"));
        }

        @Test
        @DisplayName("a burst with speed or life is still expanded into the particle pool; isPoint rules")
        void notPoints() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).burst(WorldFixture.burst(), Records.BurstKind.DEBRIS, Records.ObjColor.WHITE, 0).ingestAtTick(100);
            DebrisBurst two = point(0, 0);
            two.count = 2;
            DebrisBurst fast = point(0, 0);
            fast.maxSpeed = 1;
            DebrisBurst lives = point(0, 0);
            lives.maxLife = 1;
            assertAll(
                () -> assertEquals(12, f.world.particles().count(), "pool particles"),
                () -> assertTrue(ClientWorld.isPoint(point(0, 0)), "1 particle, no speed, no life"),
                () -> assertFalse(ClientWorld.isPoint(two), "2 particles"),
                () -> assertFalse(ClientWorld.isPoint(fast), "speed"),
                () -> assertFalse(ClientWorld.isPoint(lives), "life"));
        }

        @Test
        @DisplayName("before the render tick reaches any frame points are drawn where the later frame has them")
        void onlyLater() {
            WorldFixture f = new WorldFixture(true);
            f.begin(100).burst(point(200, 100), Records.BurstKind.SPARK, Records.ObjColor.WHITE, 0).ingestAtTick(100);
            f.world.update(at(102));
            assertAll(
                () -> assertEquals(1, f.world.pointCount(), "one point"),
                () -> assertEquals(200f, f.world.pointX(0), EPS, "at frame 100's position"));
        }
    }
}
