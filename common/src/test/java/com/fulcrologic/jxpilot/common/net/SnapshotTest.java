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
package com.fulcrologic.jxpilot.common.net;

import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Snapshot encode/decode")
class SnapshotTest {
    /** Angles are quantized to 2pi/65536. */
    static final float ANG_TOL = (float) (Math.PI / 65536.0);
    /** Velocities in 1/16 px/frame. */
    static final float VEL_TOL = 1f / 32f;

    static SnapshotReader readerFor(ByteBuffer b) {
        b.flip();
        SnapshotReader r = new SnapshotReader();
        r.begin(b);
        return r;
    }

    @Nested
    @DisplayName("header and relative positions")
    class Header {
        static int slack(int v) {
            ByteBuffer b = ByteBuffer.allocate(64);
            new SnapshotWriter().begin(b, 1, 1, v, 0, 1, 0, 0, 0);
            b.flip();
            SnapshotReader r = new SnapshotReader();
            r.begin(b);
            return r.inputSlack();
        }

        @Test
        @DisplayName("inputSlack: signed 1/16 ticks clamped to -127..127, NO_SLACK kept and the default")
        void inputSlack() {
            ByteBuffer b = ByteBuffer.allocate(64);
            new SnapshotWriter().begin(b, 1, 1, 0, 1, 0, 0, 0);
            b.flip();
            SnapshotReader r = new SnapshotReader();
            r.begin(b);
            assertAll(
                () -> assertEquals(8, slack(8), "half a tick ahead"),
                () -> assertEquals(-5, slack(-5), "late arrivals are negative"),
                () -> assertEquals(127, slack(1000), "clamped to 127"),
                () -> assertEquals(-127, slack(-1000), "clamped to -127, never NO_SLACK"),
                () -> assertEquals(SnapshotWriter.NO_SLACK, slack(SnapshotWriter.NO_SLACK), "NO_SLACK is kept"),
                () -> assertEquals(SnapshotWriter.NO_SLACK, r.inputSlack(), "the overload without slack writes NO_SLACK"));
        }

        @Test
        @DisplayName("header fields and wrap-aware relative positions")
        void header() {
            ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(1000, 800, true);
            w.begin(b, 123456, 77, 1, 3, SnapshotWriter.FLAG_LAST_PART, 990.3, 10.0);
            int relWrapX = w.relX(5.0);
            int relWrapY = w.relY(795.0);
            SnapshotWriter big = new SnapshotWriter();
            big.setWorld(40000, 40000, false);
            big.begin(ByteBuffer.allocate(100), 0, 0, 0, 1, 0, 0, 0);
            Throwable tooFar = assertThrows(IllegalArgumentException.class, () -> big.relX(16383.5));
            SnapshotWriter shifted = new SnapshotWriter();
            shifted.setWorld(40000, 40000, false);
            shifted.begin(ByteBuffer.allocate(100), 0, 0, 0, 1, 0, 1000, 1000);
            ByteBuffer withHeader = ByteBuffer.allocate(PacketHeader.SIZE + SnapshotWriter.HEADER_SIZE);
            withHeader.position(PacketHeader.SIZE);
            SnapshotReader offs = new SnapshotReader();
            boolean offsetOk = offs.begin(withHeader);
            ByteBuffer shortWithHeader = ByteBuffer.allocate(PacketHeader.SIZE + SnapshotWriter.HEADER_SIZE - 1);
            shortWithHeader.position(PacketHeader.SIZE);
            boolean offsetShort = offs.begin(shortWithHeader);
            Throwable tooLow = assertThrows(IllegalArgumentException.class, () -> big.relY(-16384));
            int edge = big.relX(16383);
            SnapshotWriter flat = new SnapshotWriter();
            flat.setWorld(1000, 800, false);
            flat.begin(ByteBuffer.allocate(100), 0, 0, 0, 1, 0, 990.3, 10.0);
            int relFlat = flat.relX(5.0);
            SnapshotReader r = new SnapshotReader();
            b.flip();
            boolean headerOk = r.begin(b);
            assertAll(
                () -> assertEquals(123456, r.tick(), "carries the tick"),
                () -> assertEquals(77, r.inputTick(), "carries the tick whose input was applied"),
                () -> assertEquals(1, r.part(), "carries the part index"),
                () -> assertEquals(3, r.partCount(), "carries the part count"),
                () -> assertEquals(SnapshotWriter.FLAG_LAST_PART, r.flags(), "carries the flags"),
                () -> assertEquals(990.5f, r.viewX(), "view centre is quantized to half pixels"),
                () -> assertEquals(10.0f, r.viewY(), "view centre y"),
                () -> assertEquals(29, relWrapX, "x=5 is 14.5 px right of 990.5 across the wrap (29 half px)"),
                () -> assertEquals(-30, relWrapY, "y=795 is 15 px below y=10 across the wrap"),
                () -> assertEquals("relative offset out of range", tooFar.getMessage(),
                    "x offsets beyond 16383 px are rejected, never clamped"),
                () -> assertEquals("relative offset out of range", tooLow.getMessage(), "y offsets below -16383 px are rejected"),
                () -> assertEquals(32766, edge, "an offset of exactly 16383 px encodes"),
                () -> assertTrue(big.inView(16383, -16383), "inView accepts offsets of exactly +-16383 px"),
                () -> assertTrue(big.inView(-16383, 16383), "inView accepts -16383 in x and +16383 in y"),
                () -> assertFalse(big.inView(16383.5, 0), "inView rejects x beyond +16383 px"),
                () -> assertFalse(big.inView(-16383.5, 0), "inView rejects x beyond -16383 px"),
                () -> assertFalse(big.inView(0, 16383.5), "inView rejects y beyond +16383 px"),
                () -> assertFalse(big.inView(0, -16383.5), "inView rejects y beyond -16383 px"),
                () -> assertTrue(w.inView(5.0, 795.0), "in a small wrapping world everything is in view"),
                () -> assertTrue(shifted.inView(17383, 1000), "inView measures from the view centre (1000 + 16383)"),
                () -> assertFalse(shifted.inView(17384, 1000), "1 px beyond the range from a shifted centre is out"),
                () -> assertTrue(shifted.inView(1000, 17383), "y is measured from the view centre too"),
                () -> assertFalse(shifted.inView(1000, 17384), "1 px beyond in y is out"),
                () -> assertTrue(headerOk, "begin accepts a well formed header"),
                () -> assertFalse(offsetShort, "begin measures the length from the buffer position"),
                () -> assertTrue(offsetOk, "begin accepts a full header after a packet header"),
                () -> assertEquals(-1971, relFlat, "without wrap the offset goes straight (-985.5 px)"));
        }
    }

    @Nested
    @DisplayName("records round trip")
    class RoundTrip {
        @Test
        @DisplayName("every record type survives with documented quantization")
        void allRecords() {
            ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(31500, 31500, false);
            w.begin(b, 1, 2, 0, 1, 0, 1000, 2000);
            w.beginSection(Section.SELF);
            Records.Self.put(w, 5, Records.ShipFlags.SHIELD, 100.25f, 200.5f, 1.5f, -2.5f, 3.0f, 900f, 1000f,
                Protocol.NO_ID, 2, 1, 70000, 44.6, 300, 0.12, 70000, 130, 3,
                Records.Self.MOD_NUCLEAR | 3 << Records.Self.MOD_VELOCITY_SHIFT | Records.Self.MOD_LASER_BLIND);
            w.endSection();
            w.beginSection(Section.SHIP);
            Records.Ship.put(w, 9, Records.ShipFlags.THRUSTING | Records.ShipFlags.FRIENDLY, 1200.2, 1500.0, 6.2, -3.3,
                12.07);
            Records.Ship.put(w, 10, 0, 1017.5, 2035.0, 0.0, 0.0, 0.0);
            w.endSection();
            w.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.CANNON_SHOT, Records.ObjColor.ENEMY));
            Records.Shot.put(w, 1050.0, 1960.5, 21.4, -200.0);
            w.endSection();
            DebrisBurst d = new DebrisBurst();
            d.x = 1010.3f;
            d.y = 1990.7f;
            d.vx = 1.03f;
            d.vy = -2.0f;
            d.seed = 0xDEADBEEF;
            d.count = 70000;
            d.minDir = 130;
            d.dirSpan = 40;
            d.minSpeed = 1.0f;
            d.maxSpeed = 12.34f;
            d.minLife = 3;
            d.maxLife = 70000;
            Records.Burst.quantize(d);
            w.beginSection(Section.BURST);
            Records.Burst.put(w, d, Records.BurstKind.DEBRIS, Records.ObjColor.WHITE, 1);
            w.endSection();
            w.beginSection(Section.CANNON);
            Records.CannonState.put(w, 513, Records.CannonState.FLAG_DEAD);
            w.endSection();
            w.beginSection(Section.FUEL);
            Records.FuelState.put(w, 7, 0.5);
            w.endSection();
            w.beginSection(Section.TARGET);
            Records.TargetState.put(w, 3, Records.TargetState.FLAG_DEAD, 0.25);
            w.endSection();
            w.beginSection(Section.RADAR);
            Records.Radar.put(w, 31000, 20, Records.Radar.FLAG_FRIENDLY);
            Records.Radar.put(w, 100, 15750, 0);
            w.endSection();
            w.beginSection(Section.ITEM);
            Records.ItemObj.put(w, 1010, 2020, 9);
            w.endSection();
            w.beginSection(Section.MINE);
            Records.Mine.put(w, 1011, 2021, Records.Mine.FLAG_ARMED, 42);
            w.endSection();
            w.beginSection(Section.MISSILE);
            Records.Missile.put(w, 8, 1012, 2022, Math.PI, Records.Missile.KIND_HEAT | Records.Missile.KIND_NUCLEAR, 15);
            w.endSection();
            w.beginSection(Section.BALL);
            Records.Ball.put(w, 2, 1013, 2023, 4, 9);
            w.endSection();
            w.beginSection(Section.LASER);
            Records.Laser.put(w, 1014, 2024, Math.PI / 2, 300, 1);
            w.endSection();
            w.beginSection(Section.ASTEROID);
            Records.Asteroid.put(w, 1015, 2025, 3, Math.PI / 4);
            w.endSection();
            w.beginSection(Section.WRECKAGE);
            Records.Wreckage.put(w, 1016, 2026, 2, 5, Math.PI / 8);
            w.endSection();
            w.beginSection(Section.ECM);
            Records.Ecm.put(w, 1017, 2027, 140);
            w.endSection();
            w.beginSection(Section.CONNECTOR);
            Records.Connector.put(w, 1001, 2002, 1003, 2004, Records.Connector.KIND_PRESSOR);
            w.endSection();
            w.beginSection(Section.TRANSPORTER);
            Records.Transporter.put(w, 1005, 2006, 1007, 2008);
            w.endSection();
            w.beginSection(Section.SELF_ITEMS);
            int[] items = new int[20];
            items[9] = 4;
            items[19] = 300;
            items[0] = -2;
            Records.SelfItems.put(w, items);
            w.endSection();
            w.beginSection(Section.TREASURE);
            Records.TreasureState.put(w, 1, Records.TreasureState.FLAG_HAS_BALL);
            w.endSection();
            w.beginSection(Section.TEMP_WORMHOLE);
            Records.TempWormhole.put(w, 899, 3, 2);
            w.endSection();
            w.beginSection(Section.TIMERS);
            Records.Timers.put(w, 70, 41, -1, 90, 70000, 400, 12, 300, 7, 50, 40000, -5);
            w.endSection();
            com.fulcrologic.jxpilot.common.sim.ShipBody pb = new com.fulcrologic.jxpilot.common.sim.ShipBody();
            pb.setClicks(64 * 1000, 64 * 2000 + 5);
            pb.vx = -2.5f;
            pb.prevKeys = 6;
            pb.lastReceivedKeys = 7;
            pb.power = 33.5f;
            pb.items[3] = 4;
            pb.shotTime = 4_000_000_000L - 10;
            w.beginSection(Section.PREDICT);
            Records.Predict.put(w, pb, 4_000_000_000L, 70_000, 6, 200, 300);
            w.endSection();
            w.beginSection(Section.PREDICT_RARE);
            Records.PredictRare.put(w, pb, 200, -7);
            w.endSection();
            w.beginSection(Section.PREDICT_KEYS);
            Records.PredictKeys.put(w, pb);
            w.endSection();
            w.beginSection(Section.PREDICT_ROUND);
            Records.PredictRound.put(w, 70_000);
            Records.PredictRound.put(w, 0);
            w.endSection();
            w.beginSection(Section.SOUND);
            Records.Sound.put(w, SoundEvent.ASTEROID_BREAK.ordinal(), SoundEvent.MAX_VOLUME);
            Records.Sound.put(w, SoundEvent.START.ordinal(), SoundEvent.MIN_VOLUME);
            w.endSection();
            SnapshotReader r = readerFor(b);
            r.setWorld(31500, 31500, false);

            List<Integer> types = new ArrayList<>();
            List<Runnable> checks = new ArrayList<>();
            while (r.nextSection()) {
                types.add(r.type());
                assertTrue(r.known(), "knows every section it wrote");
                switch (r.type()) {
                    case Section.SELF -> {
                        Records.Self s = new Records.Self().wrap(r, 0);
                        assertAll("self",
                            () -> assertEquals(5, s.id(), "keeps id"),
                            () -> assertEquals(Records.ShipFlags.SHIELD, s.flags(), "keeps flags"),
                            () -> assertEquals(100.25f, s.x(), "keeps absolute x at full precision"),
                            () -> assertEquals(200.5f, s.y(), "keeps absolute y at full precision"),
                            () -> assertEquals(1.5f, s.vx(), "keeps vx"),
                            () -> assertEquals(-2.5f, s.vy(), "keeps vy"),
                            () -> assertEquals(3.0f, s.heading(), "keeps heading"),
                            () -> assertEquals(900f, s.fuel(), "keeps fuel"),
                            () -> assertEquals(1000f, s.fuelMax(), "keeps fuelMax"),
                            () -> assertEquals(Protocol.NO_ID, s.lockId(), "keeps lock id"),
                            () -> assertEquals(2, s.numTanks(), "keeps tank count"),
                            () -> assertEquals(1, s.currentTank(), "keeps current tank"),
                            () -> assertEquals(0xFFFF, s.respawnFrames(), "clamps respawn frames to u16"),
                            () -> assertEquals(45, s.power(), "rounds power"),
                            () -> assertEquals(255, s.turnSpeed(), "clamps turn speed to a byte"),
                            () -> assertEquals(31, s.turnResistance255(), "sends turn resistance as round(r * 255)"),
                            () -> assertEquals(0xFFFF, s.lockDist(), "clamps lock distance to u16"),
                            () -> assertEquals(2, s.lockDir(), "takes the lock direction mod RES"),
                            () -> assertEquals(3, s.check(), "keeps the checkpoint"),
                            () -> assertEquals(Records.Self.MOD_NUCLEAR | 3 << Records.Self.MOD_VELOCITY_SHIFT
                                | Records.Self.MOD_LASER_BLIND, s.mods(), "keeps the modifier bits"));
                    }
                    case Section.SHIP -> {
                        Records.Ship s = new Records.Ship().wrap(r, 0);
                        assertAll("ship 0",
                            () -> assertEquals(2, r.count(), "has both ships"),
                            () -> assertEquals(9, s.id(), "keeps id"),
                            () -> assertEquals(Records.ShipFlags.THRUSTING | Records.ShipFlags.FRIENDLY, s.flags(),
                                "keeps all 16 flag bits"),
                            () -> assertEquals(1200.0f, s.x(), "relative x rounds to the nearest half pixel"),
                            () -> assertEquals(1500.0f, s.y(), "relative y decodes to world y"),
                            () -> assertEquals(6.2f, s.heading(), ANG_TOL, "keeps heading"),
                            () -> assertEquals(-3.3f, s.vx(), VEL_TOL, "keeps negative vx"),
                            () -> assertEquals(12.07f, s.vy(), VEL_TOL, "keeps vy"));
                        Records.Ship s1 = new Records.Ship().wrap(r, 1);
                        assertAll("ship 1",
                            () -> assertEquals(10, s1.id(), "second record is the second ship"),
                            () -> assertEquals(1017.5f, s1.x(), "half pixels are exact"),
                            () -> assertEquals(2035.0f, s1.y(), "whole pixels are exact"));
                    }
                    case Section.SHOT -> {
                        Records.Shot s = new Records.Shot().wrap(r, 0);
                        assertAll("shot",
                            () -> assertEquals(Records.ShotKind.CANNON_SHOT, Records.ShotKind.kindOf(r.variant()),
                                "kind is in the section variant"),
                            () -> assertEquals(Records.ObjColor.ENEMY, Records.ShotKind.colorOf(r.variant()),
                                "colour is in the section variant"),
                            () -> assertEquals(1050f, s.x(), "keeps x"),
                            () -> assertEquals(1960.5f, s.y(), "keeps y"),
                            () -> assertEquals(21, s.vx(), "vx rounds to whole px/frame"),
                            () -> assertEquals(-127, s.vy(), "vy clamps to -127"));
                    }
                    case Section.BURST -> {
                        Records.Burst s = new Records.Burst().wrap(r, 0);
                        DebrisBurst got = new DebrisBurst();
                        s.read(got);
                        assertAll("burst",
                            () -> assertEquals(d.x, got.x, "x matches the quantized burst"),
                            () -> assertEquals(1010.5f, got.x, "quantize rounds x to half pixels"),
                            () -> assertEquals(d.y, got.y, "y matches"),
                            () -> assertEquals(1990.5f, d.y, "quantize rounds y to half pixels"),
                            () -> assertEquals(d.vx, got.vx, "vx matches the quantized burst"),
                            () -> assertEquals(1.0f, got.vx, "quantize rounds vx to 1/16"),
                            () -> assertEquals(-2.0f, got.vy, "vy matches"),
                            () -> assertEquals(0xDEADBEEF, got.seed, "seed survives all 32 bits"),
                            () -> assertEquals(65535, got.count, "count is u16 and clamps to 65535"),
                            () -> assertEquals(2, got.minDir, "minDir wraps into 0..127"),
                            () -> assertEquals(40, got.dirSpan, "dirSpan survives"),
                            () -> assertEquals(1.0f, got.minSpeed, "minSpeed survives"),
                            () -> assertEquals(12.3125f, got.maxSpeed, "maxSpeed rounds to 1/16"),
                            () -> assertEquals(3, got.minLife, "minLife survives"),
                            () -> assertEquals(0xFFFF, got.maxLife, "maxLife clamps to u16"),
                            () -> assertEquals(Records.BurstKind.DEBRIS, s.kind(), "kind survives"),
                            () -> assertEquals(Records.ObjColor.WHITE, s.color(), "colour survives"),
                            () -> assertEquals(1, s.age(), "age survives"));
                    }
                    case Section.CANNON -> {
                        Records.CannonState s = new Records.CannonState().wrap(r, 0);
                        assertAll("cannon",
                            () -> assertEquals(513, s.index(), "keeps index above 255"),
                            () -> assertEquals(Records.CannonState.FLAG_DEAD, s.flags(), "keeps flags"));
                    }
                    case Section.FUEL -> {
                        Records.FuelState s = new Records.FuelState().wrap(r, 0);
                        assertAll("fuel",
                            () -> assertEquals(7, s.index(), "keeps index"),
                            () -> assertEquals(0.5f, s.fraction(), 1e-4f, "keeps fraction within 1/65535"));
                    }
                    case Section.TARGET -> {
                        Records.TargetState s = new Records.TargetState().wrap(r, 0);
                        assertAll("target",
                            () -> assertEquals(3, s.index(), "keeps index"),
                            () -> assertEquals(Records.TargetState.FLAG_DEAD, s.flags(), "keeps flags"),
                            () -> assertEquals(0.25f, s.damageLeft(), 1e-4f, "keeps damage within 1/65535"));
                    }
                    case Section.RADAR -> {
                        Records.Radar s = new Records.Radar().wrap(r, 0);
                        assertAll("radar",
                            () -> assertEquals(251, s.cellX(), "radar x is the 1/256 world cell floor(31000/31500*256)"),
                            () -> assertEquals(0, s.cellY(), "radar y cell"),
                            () -> assertEquals(Records.Radar.SIZE, r.recordSize(), "the transmitted record size is 3"),
                            () -> assertEquals(31500 * 251.5f / 256, s.x(), 1e-2f,
                                "radar x decodes to the cell centre (tolerance: float)"),
                            () -> assertEquals(31500 * 0.5f / 256, s.y(), 1e-3f, "radar y decodes to the cell centre"),
                            () -> assertEquals(Records.Radar.FLAG_FRIENDLY, s.flags(), "keeps flags"),
                            () -> assertEquals(128, new Records.Radar().wrap(r, 1).cellY(), "y 15750 of 31500 is cell 128"),
                            () -> assertEquals(0, new Records.Radar().wrap(r, 1).cellX(), "x 100 is cell 0"));
                    }
                    case Section.ITEM -> {
                        Records.ItemObj s = new Records.ItemObj().wrap(r, 0);
                        assertAll("item",
                            () -> assertEquals(1010f, s.x(), "keeps x"),
                            () -> assertEquals(2020f, s.y(), "keeps y"),
                            () -> assertEquals(9, s.itemType(), "keeps type"));
                    }
                    case Section.MINE -> {
                        Records.Mine s = new Records.Mine().wrap(r, 0);
                        assertAll("mine",
                            () -> assertEquals(1011f, s.x(), "keeps x"),
                            () -> assertEquals(2021f, s.y(), "keeps y"),
                            () -> assertEquals(Records.Mine.FLAG_ARMED, s.flags(), "keeps flags"),
                            () -> assertEquals(42, s.ownerId(), "keeps owner"));
                    }
                    case Section.MISSILE -> {
                        Records.Missile s = new Records.Missile().wrap(r, 0);
                        assertAll("missile",
                            () -> assertEquals(8, s.id(), "keeps id"),
                            () -> assertEquals(1012f, s.x(), "keeps x"),
                            () -> assertEquals(2022f, s.y(), "keeps y"),
                            () -> assertEquals((float) Math.PI, s.heading(), ANG_TOL, "keeps heading"),
                            () -> assertEquals(Records.Missile.KIND_HEAT | Records.Missile.KIND_NUCLEAR, s.kind(),
                                "keeps kind with the nuclear bit"),
                            () -> assertEquals(15, s.length(), "keeps length"));
                    }
                    case Section.BALL -> {
                        Records.Ball s = new Records.Ball().wrap(r, 0);
                        assertAll("ball",
                            () -> assertEquals(2, s.id(), "keeps id"),
                            () -> assertEquals(1013f, s.x(), "keeps x"),
                            () -> assertEquals(2023f, s.y(), "keeps y"),
                            () -> assertEquals(4, s.team(), "keeps team"),
                            () -> assertEquals(9, s.connectedPlayer(), "keeps connected player"));
                    }
                    case Section.LASER -> {
                        Records.Laser s = new Records.Laser().wrap(r, 0);
                        assertAll("laser",
                            () -> assertEquals(1014f, s.x(), "keeps x"),
                            () -> assertEquals(2024f, s.y(), "keeps y"),
                            () -> assertEquals((float) (Math.PI / 2), s.heading(), ANG_TOL, "keeps heading"),
                            () -> assertEquals(300, s.length(), "keeps length"),
                            () -> assertEquals(1, s.color(), "keeps colour"));
                    }
                    case Section.ASTEROID -> {
                        Records.Asteroid s = new Records.Asteroid().wrap(r, 0);
                        assertAll("asteroid",
                            () -> assertEquals(1015f, s.x(), "keeps x"),
                            () -> assertEquals(2025f, s.y(), "keeps y"),
                            () -> assertEquals(3, s.size(), "keeps size"),
                            () -> assertEquals((float) (Math.PI / 4), s.rotation(), ANG_TOL, "keeps rotation"));
                    }
                    case Section.WRECKAGE -> {
                        Records.Wreckage s = new Records.Wreckage().wrap(r, 0);
                        assertAll("wreckage",
                            () -> assertEquals(1016f, s.x(), "keeps x"),
                            () -> assertEquals(2026f, s.y(), "keeps y"),
                            () -> assertEquals(2, s.shape(), "keeps shape"),
                            () -> assertEquals(5, s.size(), "keeps size"),
                            () -> assertEquals((float) (Math.PI / 8), s.rotation(), ANG_TOL, "keeps rotation"));
                    }
                    case Section.ECM -> {
                        Records.Ecm s = new Records.Ecm().wrap(r, 0);
                        assertAll("ecm",
                            () -> assertEquals(1017f, s.x(), "keeps x"),
                            () -> assertEquals(2027f, s.y(), "keeps y"),
                            () -> assertEquals(140, s.radius(), "keeps radius"));
                    }
                    case Section.CONNECTOR -> {
                        Records.Connector s = new Records.Connector().wrap(r, 0);
                        assertAll("connector",
                            () -> assertEquals(1001f, s.x0(), "keeps x0"),
                            () -> assertEquals(2002f, s.y0(), "keeps y0"),
                            () -> assertEquals(1003f, s.x1(), "keeps x1"),
                            () -> assertEquals(2004f, s.y1(), "keeps y1"),
                            () -> assertEquals(Records.Connector.KIND_PRESSOR, s.kind(), "keeps kind"));
                    }
                    case Section.TRANSPORTER -> {
                        Records.Transporter s = new Records.Transporter().wrap(r, 0);
                        assertAll("transporter",
                            () -> assertEquals(1005f, s.x0(), "keeps x0"),
                            () -> assertEquals(2006f, s.y0(), "keeps y0"),
                            () -> assertEquals(1007f, s.x1(), "keeps x1"),
                            () -> assertEquals(2008f, s.y1(), "keeps y1"));
                    }
                    case Section.SELF_ITEMS -> {
                        Records.SelfItems s = new Records.SelfItems().wrap(r, 0);
                        assertAll("items",
                            () -> assertEquals(4, s.count(9), "keeps a count"),
                            () -> assertEquals(255, s.count(19), "clamps counts above 255"),
                            () -> assertEquals(0, s.count(0), "clamps negative counts to 0"),
                            () -> assertEquals(0, s.count(20), "missing entries are 0"));
                    }
                    case Section.TREASURE -> {
                        Records.TreasureState s = new Records.TreasureState().wrap(r, 0);
                        assertAll("treasure",
                            () -> assertEquals(1, s.index(), "keeps index"),
                            () -> assertEquals(Records.TreasureState.FLAG_HAS_BALL, s.flags(), "keeps flags"));
                    }
                    case Section.TEMP_WORMHOLE -> {
                        Records.TempWormhole s = new Records.TempWormhole().wrap(r, 0);
                        assertAll("temp wormhole",
                            () -> assertEquals(899, s.bx(), "keeps bx"),
                            () -> assertEquals(3, s.by(), "keeps by"),
                            () -> assertEquals(2, s.type(), "keeps type"));
                    }
                    case Section.TIMERS -> {
                        Records.Timers s = new Records.Timers().wrap(r, 0);
                        assertAll("timers",
                            () -> assertEquals(70, s.damaged(), "keeps damaged frames"),
                            () -> assertEquals(41, s.roundDelay(), "keeps round delay frames"),
                            () -> assertEquals(-1, s.timeLeft(), "keeps a negative (none) time left"),
                            () -> assertEquals(90, s.destruct(), "keeps the self-destruct count"),
                            () -> assertEquals(0xFFFF, s.thrustLeft(), "clamps thrust left to u16"),
                            () -> assertEquals(400, s.thrustMax(), "keeps thrust max"),
                            () -> assertEquals(12, s.shieldLeft(), "keeps shield left"),
                            () -> assertEquals(300, s.shieldMax(), "keeps shield max"),
                            () -> assertEquals(7, s.phasingLeft(), "keeps phasing left"),
                            () -> assertEquals(50, s.phasingMax(), "keeps phasing max"),
                            () -> assertEquals(32767, s.shutdownCount(), "clamps the shutdown count to i16"),
                            () -> assertEquals(0, s.shutdownDelay(), "clamps a negative shutdown delay to 0"));
                    }
                    case Section.PREDICT -> {
                        Records.Predict s = new Records.Predict().wrap(r, 0);
                        com.fulcrologic.jxpilot.common.sim.ShipBody pd = new com.fulcrologic.jxpilot.common.sim.ShipBody();
                        s.read(pd, 4_000_000_000L, 6);
                        assertAll("predict",
                            () -> assertTrue(s.roundDelaySet(), "a nonzero roundDelay sets the flag"),
                            () -> assertEquals(6, s.appliedHash(), "the applied keys' hash"),
                            () -> assertEquals(200, s.rareVersion(), "keeps the rare version"),
                            () -> assertEquals(300 & 0xFF, s.kernelVersion(), "the kernel version mod 256"),
                            () -> assertEquals(64 * 2000 + 5, pd.cy, "decodes the core"),
                            () -> assertEquals(-2.5f, pd.vx, "vx"),
                            () -> assertEquals(4_000_000_000L - 10, pd.shotTime, "shotTime from the age and frames"),
                            () -> assertEquals(6L, pd.prevKeys, "prevKeys equals the applied keys: from the flag"));
                    }
                    case Section.PREDICT_ROUND -> {
                        Records.PredictRound s = new Records.PredictRound();
                        int big = s.wrap(r, 0).roundDelay();
                        int zero = s.wrap(r, 1).roundDelay();
                        assertAll("predict round",
                            () -> assertEquals(65535, big, "clamps roundDelay to u16"),
                            () -> assertEquals(1, zero, "and to at least 1 (it is sent only when nonzero)"));
                    }
                    case Section.PREDICT_RARE -> {
                        Records.PredictRare s = new Records.PredictRare().wrap(r, 0);
                        com.fulcrologic.jxpilot.common.sim.ShipBody pd = new com.fulcrologic.jxpilot.common.sim.ShipBody();
                        s.read(pd);
                        assertAll("predict rare",
                            () -> assertEquals(200, s.version(), "keeps the version"),
                            () -> assertEquals(-7, s.frameOffset(), "keeps a signed frame offset"),
                            () -> assertEquals(33.5f, pd.power, "decodes the rare block"),
                            () -> assertEquals(4, pd.items[3], "items"));
                    }
                    case Section.PREDICT_KEYS -> {
                        com.fulcrologic.jxpilot.common.sim.ShipBody pd = new com.fulcrologic.jxpilot.common.sim.ShipBody();
                        new Records.PredictKeys().wrap(r, 0).read(pd);
                        assertAll("predict keys",
                            () -> assertEquals(6L, pd.prevKeys, "prevKeys"),
                            () -> assertEquals(7L, pd.lastReceivedKeys, "lastReceivedKeys"));
                    }
                    case Section.SOUND -> {
                        Records.Sound s0 = new Records.Sound().wrap(r, 0);
                        Records.Sound s1 = new Records.Sound().wrap(r, 1);
                        assertAll("sound",
                            () -> assertEquals(2, r.count(), "keeps both sound events"),
                            () -> assertEquals(SoundEvent.ASTEROID_BREAK.ordinal(), s0.event(), "keeps the highest event id"),
                            () -> assertEquals(SoundEvent.MAX_VOLUME, s0.volume(), "keeps the full volume"),
                            () -> assertEquals(SoundEvent.START.ordinal(), s1.event(), "keeps event id 0"),
                            () -> assertEquals(SoundEvent.MIN_VOLUME, s1.volume(), "keeps the minimum volume"));
                    }
                    default -> throw new AssertionError("unexpected section " + r.type());
                }
            }
            List<Integer> expected = new ArrayList<>();
            for (int t = 1; t <= Section.MAX_TYPE; t++) {
                if (Section.recordSize(t) > 0) {
                    expected.add(t);
                }
            }
            assertAll(
                () -> assertEquals(expected, types, "visits every written section once, in order"),
                () -> assertFalse(r.malformed(), "reports a clean end"));
        }

        @Test
        @DisplayName("record sizes are as documented")
        void sizes() {
            assertAll(
                () -> assertEquals(6, Records.Shot.SIZE, "shots are 6 bytes"),
                () -> assertEquals(27, Records.Burst.SIZE, "bursts are 27 bytes"),
                () -> assertEquals(3, Records.Radar.SIZE, "radar blips are 3 bytes"),
                () -> assertEquals(14, Records.Ship.SIZE, "ships are 14 bytes"),
                () -> assertEquals(2, Records.Sound.SIZE, "sound events are 2 bytes"),
                () -> assertEquals(5, Section.HEADER_SIZE, "section headers are 5 bytes"),
                () -> assertEquals(16, SnapshotWriter.HEADER_SIZE, "snapshot headers are 16 bytes"),
                () -> assertEquals(0, Section.recordSize(0), "type 0 is unassigned"),
                () -> assertEquals(0, Section.recordSize(-1), "negative types are unknown"),
                () -> assertEquals(0, Section.recordSize(256), "types above 255 are unknown"));
        }
    }

    @Nested
    @DisplayName("wire size budget")
    class WireSize {
        /** Writes a scene and returns its encoded size: packet header + snapshot header + sections. */
        int scene(int ships, int shots, int bursts, int radar) {
            return scene(ships, shots, bursts, radar, false, false);
        }

        /** As above, with the owner's PREDICT core (protocol v2), and optionally its rare block too. */
        int scene(int ships, int shots, int bursts, int radar, boolean predict, boolean rare) {
            ByteBuffer b = ByteBuffer.allocate(64 * 1024);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(7000, 7000, true);
            PacketHeader.write(b, PacketType.SNAPSHOT, 1, 0);
            w.begin(b, 1, 1, 0, 1, SnapshotWriter.FLAG_LAST_PART, 3500, 3500);
            w.beginSection(Section.SELF);
            Records.Self.put(w, 0, Records.ShipFlags.THRUSTING, 3500, 3500, 0, 0, 0, 500, 1000, Protocol.NO_ID, 1, 0, 0);
            w.endSection();
            if (predict) {
                com.fulcrologic.jxpilot.common.sim.ShipBody body = new com.fulcrologic.jxpilot.common.sim.ShipBody();
                w.beginSection(Section.PREDICT);
                Records.Predict.put(w, body, 1000, 0, 0, 1, 1);
                w.endSection();
                if (rare) {
                    w.beginSection(Section.PREDICT_RARE);
                    Records.PredictRare.put(w, body, 1, 0);
                    w.endSection();
                }
            }
            w.beginSection(Section.SHIP);
            for (int i = 0; i < ships; i++) {
                // every ship is thrusting: its sparks cost nothing on the wire (client generates them)
                Records.Ship.put(w, i, Records.ShipFlags.THRUSTING, 3000 + i * 30, 3200, 0.1 * i, 2, 2);
            }
            w.endSection();
            int[] groups = {Records.ObjColor.OWN, Records.ObjColor.TEAM, Records.ObjColor.ENEMY};
            for (int g = 0; g < groups.length; g++) {
                w.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.SHOT, groups[g]));
                for (int i = g; i < shots; i += groups.length) {
                    Records.Shot.put(w, 3000 + i * 5, 3300 + i, 20, -5);
                }
                w.endSection();
            }
            DebrisBurst d = new DebrisBurst();
            d.count = 80;
            d.dirSpan = 128;
            d.maxSpeed = 30;
            d.maxLife = 35;
            w.beginSection(Section.BURST);
            for (int i = 0; i < bursts; i++) {
                d.x = 3400 + i;
                d.y = 3400;
                d.seed = i;
                Records.Burst.put(w, d, Records.BurstKind.DEBRIS, Records.ObjColor.RED, 0);
            }
            w.endSection();
            w.beginSection(Section.RADAR);
            for (int i = 0; i < radar; i++) {
                Records.Radar.put(w, i * 20, i * 20, 0);
            }
            w.endSection();
            return b.position();
        }

        @Test
        @DisplayName("fight, typical and radar-heavy scenes stay within their budgets")
        void budget() {
            // Fight: 15 thrusting ships, 150 shots in 3 colour groups, 400 debris = 5 ship explosions
            // (C Explode_fighter makes ~60-90 debris each) as 5 bursts, 15 radar blips.
            int fight = scene(15, 150, 5, 15);
            int fightNoRadar = scene(15, 150, 5, 0);
            int typical = scene(4, 30, 2, 8);
            // 32 players + 300 radar objects: 332 blips; 8 ships in view.
            int radarHeavy = scene(8, 0, 0, 332);
            int radarHeavyAll = scene(31, 0, 0, 332);
            int typicalPredict = scene(4, 30, 2, 8, true, false);
            int typicalRare = scene(4, 30, 2, 8, true, true);
            int fightPredict = scene(15, 150, 5, 15, true, false);
            System.out.printf("[wire size] typical %d, + PREDICT %d, + rare %d; fight %d, + PREDICT %d%n", typical,
                typicalPredict, typicalRare, fight, fightPredict);
            assertAll(
                () -> assertEquals(8 + 16 + (5 + 47) + (5 + 15 * 14) + 3 * 5 + 150 * 6 + (5 + 5 * 27) + (5 + 15 * 3),
                    fight, "fight = 1396 bytes"),
                () -> assertEquals(1396, fight, "fight size in bytes"),
                () -> assertTrue(fight <= 1800, "the fight with thrusting ships is within the 1.8 KB target"),
                () -> assertEquals(1346, fightNoRadar, "the fight without radar (2 of 3 snapshots) is 1346 bytes"),
                () -> assertEquals(420, typical, "typical scene is 420 bytes"),
                () -> assertTrue(typical <= Protocol.MAX_PACKET, "a typical scene fits one datagram"),
                () -> assertEquals(8 + 16 + 52 + (5 + 8 * 14) + (5 + 332 * 3), radarHeavy,
                    "radar-heavy: 332 blips at 3 bytes plus 8 ships = 1194 bytes"),
                () -> assertTrue(radarHeavy <= Protocol.MAX_PACKET, "radar-heavy with 8 ships in view fits one datagram"),
                () -> assertEquals(1516, radarHeavyAll, "radar-heavy with all 31 ships in view is 1516 bytes (2 parts)"),
                () -> assertEquals(66, Records.Predict.SIZE,
                    "PREDICT: the 63-byte core, u8 appliedHash, u8 rareVersion, u8 kernelVersion"),
                () -> assertEquals(64, Records.PredictRare.SIZE,
                    "PREDICT_RARE: u8 version, i32 frameOffset, the 59-byte rare block"),
                () -> assertEquals(16, Records.PredictKeys.SIZE, "PREDICT_KEYS: two u64 masks"),
                () -> assertEquals(2, Records.PredictRound.SIZE, "PREDICT_ROUND: u16 roundDelay"),
                () -> assertEquals(420 + 5 + 66, typicalPredict, "the owner's typical snapshot carries PREDICT: 491 bytes"),
                () -> assertEquals(491 + 5 + 64, typicalRare, "and with the rare block 560 bytes"),
                () -> assertEquals(1396 + 71, fightPredict, "the fight with PREDICT: 1467 bytes"),
                () -> assertTrue(fightPredict + 69 + 21 + 7 <= 1800,
                    "with every PREDICT block (rare, keys, round) still within the 1.8 KB target"),
                () -> assertTrue(typicalRare + 21 + 7 <= Protocol.MAX_PACKET,
                    "a typical scene with every block fits one datagram"));
        }
    }

    @Nested
    @DisplayName("wrapping view")
    class WrappingView {
        @Test
        @DisplayName("positions stay consistent with SELF when the view centre crosses the seam")
        void seam() {
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(1000, 800, true);
            SnapshotReader r = new SnapshotReader();
            r.setWorld(1000, 800, true);
            Records.Self self = new Records.Self();
            Records.Ship ship = new Records.Ship();
            float[] got = new float[8];
            // snapshot 1: self at x=10 (view 10), other ship at x=990 (left across the seam)
            // snapshot 2: self at x=995 (crossed the seam), other ship at x=5
            double[][] frames = {{10, 990}, {995, 5}};
            for (int f = 0; f < 2; f++) {
                ByteBuffer b = ByteBuffer.allocate(200);
                w.begin(b, f, 0, 0, 1, 0, frames[f][0], 400);
                w.beginSection(Section.SELF);
                Records.Self.put(w, 1, 0, (float) frames[f][0], 400, 0, 0, 0, 0, 0, Protocol.NO_ID, 0, 0, 0);
                w.endSection();
                w.beginSection(Section.SHIP);
                Records.Ship.put(w, 2, 0, frames[f][1], 795, 0, 0, 0);
                w.endSection();
                b.flip();
                r.begin(b);
                r.nextSection();
                self.wrap(r, 0);
                got[f * 4] = self.x();
                r.nextSection();
                ship.wrap(r, 0);
                got[f * 4 + 1] = ship.x();
                got[f * 4 + 2] = ship.y();
                got[f * 4 + 3] = (float) com.fulcrologic.jxpilot.common.math.XMath.wrapDelta(ship.x() - self.x(), 1000, true);
            }
            SnapshotReader flat = new SnapshotReader();
            flat.setWorld(1000, 800, false);
            float overshoot;
            {
                ByteBuffer ob = ByteBuffer.allocate(100);
                SnapshotWriter ow = new SnapshotWriter();
                ow.setWorld(1000, 800, false);
                ow.begin(ob, 0, 0, 0, 1, 0, 990, 10);
                ow.beginSection(Section.SHIP);
                Records.Ship.put(ow, 2, 0, 999.9, 10, 0, 0, 0);
                ow.endSection();
                ob.flip();
                flat.begin(ob);
                flat.nextSection();
                overshoot = new Records.Ship().wrap(flat, 0).x();
            }
            float wrappedY;
            {
                ByteBuffer yb = ByteBuffer.allocate(100);
                w.begin(yb, 0, 0, 0, 1, 0, 500, 795);
                w.beginSection(Section.SHIP);
                Records.Ship.put(w, 2, 0, 500, 5, 0, 0, 0);
                w.endSection();
                yb.flip();
                r.begin(yb);
                r.nextSection();
                wrappedY = new Records.Ship().wrap(r, 0).y();
            }
            ByteBuffer fb = ByteBuffer.allocate(100);
            SnapshotWriter fw = new SnapshotWriter();
            fw.setWorld(1000, 800, false);
            fw.begin(fb, 0, 0, 0, 1, 0, 10, 10);
            fw.beginSection(Section.SHIP);
            Records.Ship.put(fw, 2, 0, 5, 10, 0, 0, 0);
            fw.endSection();
            fb.flip();
            flat.begin(fb);
            flat.nextSection();
            float flatX = new Records.Ship().wrap(flat, 0).x();
            assertAll(
                () -> assertEquals(990f, got[1], "frame 1: the ship left of the seam decodes to 990, inside [0,W)"),
                () -> assertEquals(795f, got[2], "frame 1: y below the top seam decodes to 795"),
                () -> assertEquals(-20f, got[3], "frame 1: the ship is 20 px left of SELF (wrapDelta)"),
                () -> assertEquals(995f, got[4], "frame 2: SELF crossed the seam"),
                () -> assertEquals(5f, got[5], "frame 2: the ship decodes to 5, inside [0,W)"),
                () -> assertEquals(10f, got[7], "frame 2: the ship is 10 px right of SELF across the seam"),
                () -> assertEquals(-985f, (float) com.fulcrologic.jxpilot.common.math.XMath.wrapDelta(got[5] - got[1], 1000,
                    false), "a naive difference jumps by a world width..."),
                () -> assertEquals(15f, (float) com.fulcrologic.jxpilot.common.math.XMath.wrapDelta(got[5] - got[1], 1000,
                    true), "...so interpolation must use wrapDelta: the ship moved 15 px"),
                () -> assertEquals(5f, flatX, "without wrap, decoding is view centre + offset"),
                () -> assertEquals(1000f, overshoot, "a flat world never wraps: 999.9 rounds to 1000.0 and stays"),
                () -> assertEquals(5f, wrappedY, "y wraps across the top seam (805 -> 5)"));
        }
    }

    @Nested
    @DisplayName("SnapshotWriter sections")
    class WriterSections {
        @Test
        @DisplayName("empty sections vanish, full buffers refuse, counts and variants are written, misuse throws")
        void sectionRules() {
            ByteBuffer b = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + Section.HEADER_SIZE + 2 * Records.Ship.SIZE);
            SnapshotWriter w = new SnapshotWriter();
            w.begin(b, 1, 2, 0, 1, 0, 0, 0);
            int afterHeader = b.position();
            boolean opened = w.beginSection(Section.SHOT);
            int emptyCount = w.endSection();
            int posAfterEmpty = b.position();
            w.beginSection(Section.SHIP, 7);
            Records.Ship.put(w, 1, 0, 0, 0, 0, 0, 0);
            boolean roomAfterOne = w.hasRoom();
            Records.Ship.put(w, 2, 0, 0, 0, 0, 0, 0);
            boolean roomAfterTwo = w.hasRoom();
            int shipCount = w.endSection();
            w.setPartCount(5);
            w.setFlags(9);
            boolean refused = w.beginSection(Section.SHOT);
            assertAll(
                () -> assertTrue(opened, "opens a section when header+record fit"),
                () -> assertEquals(0, emptyCount, "an empty section reports zero records"),
                () -> assertEquals(afterHeader, posAfterEmpty, "an empty section is removed from the buffer"),
                () -> assertTrue(roomAfterOne, "has room while a record still fits"),
                () -> assertFalse(roomAfterTwo, "has no room once the buffer is full"),
                () -> assertEquals(2, shipCount, "counts the records written"),
                () -> assertEquals(Section.SHIP, b.get(afterHeader), "writes the section type"),
                () -> assertEquals(Records.Ship.SIZE, b.get(afterHeader + 1), "writes the record size"),
                () -> assertEquals(7, b.get(afterHeader + 2), "writes the variant"),
                () -> assertEquals(2, b.getShort(afterHeader + 3), "patches the count into the section header"),
                () -> assertEquals(5, b.get(SnapshotWriter.PART_COUNT_OFFSET), "setPartCount patches the header"),
                () -> assertEquals(9, b.get(SnapshotWriter.FLAGS_OFFSET), "setFlags patches the header"),
                () -> assertFalse(refused, "refuses to open a section that cannot hold one record"),
                () -> assertThrows(IllegalStateException.class, w::endSection, "endSection without a section throws"),
                () -> assertThrows(IllegalArgumentException.class, () -> {
                    SnapshotWriter w2 = new SnapshotWriter();
                    w2.begin(ByteBuffer.allocate(100), 0, 0, 0, 1, 0, 0, 0);
                    w2.beginSection(200);
                }, "unknown section types are rejected"));
        }

        @Test
        @DisplayName("double open and partial records are rejected")
        void misuse() {
            ByteBuffer b = ByteBuffer.allocate(200);
            SnapshotWriter w = new SnapshotWriter();
            w.begin(b, 1, 2, 0, 1, 0, 0, 0);
            w.beginSection(Section.SHIP);
            Throwable twice = assertThrows(IllegalStateException.class, () -> w.beginSection(Section.SHOT));
            b.put((byte) 1);
            Throwable partial = assertThrows(IllegalStateException.class, w::endSection);
            assertAll(
                () -> assertEquals("section already open", twice.getMessage(), "a second open section is rejected"),
                () -> assertEquals("partial record in section", partial.getMessage(), "a partial record is rejected"));
        }

        @Test
        @DisplayName("hasRoom stops at 65535 records")
        void countLimit() {
            ByteBuffer b = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + Section.HEADER_SIZE + 70000 * 3);
            SnapshotWriter w = new SnapshotWriter();
            w.begin(b, 1, 2, 0, 1, 0, 0, 0);
            w.beginSection(Section.CANNON);
            int n = 0;
            while (w.hasRoom()) {
                Records.CannonState.put(w, n++, 0);
            }
            int count = w.endSection();
            assertEquals(65535, count, "stops when the u16 count is full");
        }
    }

    @Nested
    @DisplayName("SnapshotReader robustness")
    class ReaderRobustness {
        @Test
        @DisplayName("truncated and malformed data is detected, unknown sections are skippable")
        void malformed() {
            SnapshotReader r = new SnapshotReader();
            boolean shortOk = r.begin(ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE - 1));
            boolean shortMalformed = r.malformed();

            ByteBuffer b = ByteBuffer.allocate(64);
            SnapshotWriter w = new SnapshotWriter();
            w.begin(b, 1, 1, 0, 1, 0, 0, 0);
            b.put((byte) 250).put((byte) 3).put((byte) 0).putShort((short) 2).put(new byte[6]);
            b.put((byte) Section.SHIP).put((byte) 4).put((byte) 0).putShort((short) 1).put(new byte[4]);
            b.put((byte) Section.SHOT).put((byte) Records.Shot.SIZE).put((byte) 0).putShort((short) 3);
            b.flip();
            r.begin(b);
            boolean first = r.nextSection();
            int firstType = r.type();
            boolean firstKnown = r.known();
            int stride = r.recordOffset(1) - r.recordOffset(0);
            boolean second = r.nextSection();
            boolean secondKnown = r.known();
            boolean third = r.nextSection();
            boolean malformed = r.malformed();

            ByteBuffer half = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + 2);
            w.begin(half, 1, 1, 0, 1, 0, 0, 0);
            half.putShort((short) 1);
            half.flip();
            r.begin(half);
            boolean halfHeader = r.nextSection();
            boolean halfMalformed = r.malformed();

            ByteBuffer zero = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + Section.HEADER_SIZE);
            w.begin(zero, 1, 1, 0, 1, 0, 0, 0);
            zero.put((byte) Section.SHIP).put((byte) 0).put((byte) 0).putShort((short) 0);
            zero.flip();
            r.begin(zero);
            boolean zeroSize = r.nextSection();

            ByteBuffer emptySec = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + Section.HEADER_SIZE);
            w.begin(emptySec, 1, 1, 0, 1, 0, 0, 0);
            emptySec.put((byte) Section.SHOT).put((byte) Records.Shot.SIZE).put((byte) 0).putShort((short) 0);
            emptySec.flip();
            r.begin(emptySec);
            boolean emptySectionOk = r.nextSection();
            ByteBuffer fit = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + Section.HEADER_SIZE + Records.Shot.SIZE);
            SnapshotWriter fw = new SnapshotWriter();
            fw.begin(fit, 0, 0, 0, 1, 0, 0, 0);
            boolean exactFit = fw.beginSection(Section.SHOT);
            ByteBuffer bigger = ByteBuffer.allocate(SnapshotWriter.HEADER_SIZE + Section.HEADER_SIZE + 8);
            w.begin(bigger, 1, 1, 0, 1, 0, 0, 0);
            bigger.put((byte) Section.SHOT).put((byte) 8).put((byte) 0).putShort((short) 1).put(new byte[8]);
            bigger.flip();
            r.begin(bigger);
            r.nextSection();
            boolean biggerKnown = r.known();

            assertAll(
                () -> assertFalse(shortOk, "rejects a buffer shorter than the header"),
                () -> assertTrue(shortMalformed, "flags a short header as malformed"),
                () -> assertTrue(first, "reads an unknown section"),
                () -> assertEquals(250, firstType, "reports the unknown type"),
                () -> assertFalse(firstKnown, "marks the unknown section as not known"),
                () -> assertEquals(3, stride, "uses the transmitted record size as stride"),
                () -> assertTrue(second, "reads a section with small records"),
                () -> assertFalse(secondKnown, "marks records smaller than expected as not known"),
                () -> assertFalse(third, "stops at a truncated section"),
                () -> assertTrue(malformed, "flags the truncated section as malformed"),
                () -> assertFalse(halfHeader, "stops at a partial section header"),
                () -> assertTrue(halfMalformed, "flags the partial section header as malformed"),
                () -> assertFalse(zeroSize, "rejects a zero record size"),
                () -> assertTrue(biggerKnown, "accepts records larger than expected (appended fields)"),
                () -> assertTrue(emptySectionOk, "a section header with count 0 at the very end is valid"),
                () -> assertTrue(exactFit, "a section fits when header + one record exactly fill the buffer"));
        }
    }

    @Nested
    @DisplayName("older, shorter records")
    class ShortRecords {
        /** A snapshot with one SELF of 38 bytes and one TIMERS of 8 bytes (the sizes before fields were appended). */
        ByteBuffer oldSnapshot() {
            ByteBuffer b = ByteBuffer.allocate(256);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(31500, 31500, false);
            w.begin(b, 1, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, 1000, 2000);
            b.put((byte) Section.SELF).put((byte) 38).put((byte) 0).putShort((short) 1);
            b.putShort((short) 5).putShort((short) 0);
            for (int i = 0; i < 7; i++) {
                b.putFloat(1);
            }
            b.putShort((short) 7).put((byte) 0).put((byte) 0).putShort((short) 0);
            b.put((byte) Section.TIMERS).put((byte) 8).put((byte) 0).putShort((short) 1);
            b.putShort((short) 3).putShort((short) 0).putInt(-1);
            return b;
        }

        @Test
        @DisplayName("appended fields read as their 'none' values from a shorter record")
        void appendedFieldsDefault() {
            SnapshotReader r = readerFor(oldSnapshot());
            r.setWorld(31500, 31500, false);
            assertTrue(r.nextSection(), "has the SELF section");
            Records.Self s = new Records.Self().wrap(r, 0);
            int lockId = s.lockId();
            int power = s.power();
            int turn = s.turnSpeed();
            int res = s.turnResistance255();
            int dist = s.lockDist();
            int dir = s.lockDir();
            int check = s.check();
            int mods = s.mods();
            assertTrue(r.nextSection(), "has the TIMERS section");
            Records.Timers t = new Records.Timers().wrap(r, 0);
            assertAll(
                () -> assertEquals(7, lockId, "reads the old fields"),
                () -> assertEquals(0, power, "power is 0"),
                () -> assertEquals(0, turn, "turn speed is 0"),
                () -> assertEquals(0, res, "turn resistance is 0"),
                () -> assertEquals(0, dist, "lock distance is 0 (no visible lock)"),
                () -> assertEquals(0, dir, "lock direction is 0"),
                () -> assertEquals(0, check, "checkpoint is 0"),
                () -> assertEquals(0, mods, "no modifiers"),
                () -> assertEquals(3, t.damaged(), "reads the old timer fields"),
                () -> assertEquals(0, t.destruct(), "no self-destruct"),
                () -> assertEquals(0, t.thrustLeft(), "no thrust left"),
                () -> assertEquals(0, t.thrustMax(), "no thrust meter"),
                () -> assertEquals(0, t.shieldLeft(), "no shield left"),
                () -> assertEquals(0, t.shieldMax(), "no shield meter"),
                () -> assertEquals(0, t.phasingLeft(), "no phasing left"),
                () -> assertEquals(0, t.phasingMax(), "no phasing meter"),
                () -> assertEquals(-1, t.shutdownCount(), "no shutdown"),
                () -> assertEquals(0, t.shutdownDelay(), "no shutdown delay"));
        }

        /** One record of {@code stride} bytes in a section of {@code type}, followed by 0xFF bytes in the buffer. */
        SnapshotReader cutAt(int type, int stride) {
            ByteBuffer b = ByteBuffer.allocate(256);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(31500, 31500, false);
            w.begin(b, 1, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, 1000, 2000);
            b.put((byte) type).put((byte) stride).put((byte) 0).putShort((short) 1);
            while (b.hasRemaining()) {
                b.put((byte) 0xFF);
            }
            SnapshotReader r = readerFor(b);
            r.setWorld(31500, 31500, false);
            r.nextSection();
            return r;
        }

        @Test
        @DisplayName("a field cut off by the record's end reads 'none', even when the bytes after the record are set")
        void cutField() {
            Records.Self s = new Records.Self();
            Records.Timers t = new Records.Timers();
            List<String> got = new ArrayList<>();
            got.add("power " + s.wrap(cutAt(Section.SELF, 38), 0).power());
            got.add("turnSpeed " + s.wrap(cutAt(Section.SELF, 39), 0).turnSpeed());
            got.add("turnRes " + s.wrap(cutAt(Section.SELF, 40), 0).turnResistance255());
            got.add("lockDist " + s.wrap(cutAt(Section.SELF, 42), 0).lockDist());
            got.add("lockDir " + s.wrap(cutAt(Section.SELF, 43), 0).lockDir());
            got.add("check " + s.wrap(cutAt(Section.SELF, 44), 0).check());
            got.add("mods " + s.wrap(cutAt(Section.SELF, 46), 0).mods());
            got.add("destruct " + t.wrap(cutAt(Section.TIMERS, 9), 0).destruct());
            got.add("thrustLeft " + t.wrap(cutAt(Section.TIMERS, 11), 0).thrustLeft());
            got.add("thrustMax " + t.wrap(cutAt(Section.TIMERS, 13), 0).thrustMax());
            got.add("shieldLeft " + t.wrap(cutAt(Section.TIMERS, 15), 0).shieldLeft());
            got.add("shieldMax " + t.wrap(cutAt(Section.TIMERS, 17), 0).shieldMax());
            got.add("phasingLeft " + t.wrap(cutAt(Section.TIMERS, 19), 0).phasingLeft());
            got.add("phasingMax " + t.wrap(cutAt(Section.TIMERS, 21), 0).phasingMax());
            got.add("shutdownCount " + t.wrap(cutAt(Section.TIMERS, 23), 0).shutdownCount());
            got.add("shutdownDelay " + t.wrap(cutAt(Section.TIMERS, 25), 0).shutdownDelay());
            assertEquals(List.of("power 0", "turnSpeed 0", "turnRes 0", "lockDist 0", "lockDir 0", "check 0", "mods 0",
                "destruct 0", "thrustLeft 0", "thrustMax 0", "shieldLeft 0", "shieldMax 0", "phasingLeft 0",
                "phasingMax 0", "shutdownCount -1", "shutdownDelay 0"), got);
        }

        @Test
        @DisplayName("the short puts write the appended fields as their 'none' values")
        void shortPuts() {
            ByteBuffer b = ByteBuffer.allocate(256);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(31500, 31500, false);
            w.begin(b, 1, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, 1000, 2000);
            w.beginSection(Section.SELF);
            Records.Self.put(w, 5, 0, 1, 1, 0, 0, 0, 0, 0, 9, 0, 0, 0);
            w.endSection();
            w.beginSection(Section.TIMERS);
            Records.Timers.put(w, 4, 0, -1);
            w.endSection();
            SnapshotReader r = readerFor(b);
            r.setWorld(31500, 31500, false);
            r.nextSection();
            Records.Self s = new Records.Self().wrap(r, 0);
            int sSize = r.recordSize();
            int power = s.power();
            int dist = s.lockDist();
            int mods = s.mods();
            r.nextSection();
            Records.Timers t = new Records.Timers().wrap(r, 0);
            assertAll(
                () -> assertEquals(Records.Self.SIZE, sSize, "SELF records are full size"),
                () -> assertEquals(0, power, "power 0"),
                () -> assertEquals(0, dist, "lock distance 0"),
                () -> assertEquals(0, mods, "modifiers 0"),
                () -> assertEquals(Records.Timers.SIZE, r.recordSize(), "TIMERS records are full size"),
                () -> assertEquals(4, t.damaged(), "keeps damaged"),
                () -> assertEquals(0, t.thrustMax(), "no thrust meter"),
                () -> assertEquals(-1, t.shutdownCount(), "no shutdown"));
        }
    }

    @Nested
    @DisplayName("PREDICT's applied-input hash")
    class PredictHash {
        @Test
        @DisplayName("an xor of the mask's 8 bytes: each byte counts, a one-byte change always shows")
        void hash() {
            assertAll(
                () -> assertEquals(0, Records.Predict.hash(0), "no keys: 0"),
                () -> assertEquals(0x5a, Records.Predict.hash(0x5aL), "the low byte"),
                () -> assertEquals(0x81, Records.Predict.hash(0x81L << 56), "the high byte"),
                () -> assertEquals(0x12 ^ 0x34, Records.Predict.hash(0x34L << 32 | 0x12L << 8), "bytes 1 and 4 fold"),
                () -> assertEquals(0x7 ^ 0x9, Records.Predict.hash(0x9L << 24 | 0x7L << 16), "bytes 2 and 3 fold"),
                () -> assertEquals(0x3 ^ 0x6, Records.Predict.hash(0x6L << 48 | 0x3L << 40), "bytes 5 and 6 fold"),
                () -> assertTrue(Records.Predict.hash(0x0100L) != Records.Predict.hash(0x0300L),
                    "one byte changed: different hashes"));
        }
    }

    @Test
    @DisplayName("header starvation counter: bits 1-7 of the flags, mod 128, the other bits kept")
    void starveCounter() {
        int f = SnapshotWriter.starveFlags(SnapshotWriter.FLAG_LAST_PART, 130);
        int cleared = SnapshotWriter.starveFlags(0xFF, 0);
        assertAll(
            () -> assertEquals(2, SnapshotWriter.starvations(f), "130 mod 128 = 2"),
            () -> assertEquals(SnapshotWriter.FLAG_LAST_PART, f & SnapshotWriter.FLAG_LAST_PART, "the last-part bit kept"),
            () -> assertEquals(1 | 2 << 1, f, "the whole byte"),
            () -> assertEquals(1, cleared, "a zero count clears bits 1-7 and keeps bit 0"),
            () -> assertEquals(127, SnapshotWriter.starvations(0xFF), "the counter of a full byte"));
    }
}
