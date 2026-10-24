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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** C frames (built with the server's encoders) to JXPilot snapshots, read back with the native reader. */
class XpTranslatorTest {
    static final int W = 100;
    static final double PX = W * 35.0;
    Recorder rec;
    XpTranslator tr;
    XpKeyboard kb;
    XpFrameDecoder dec;
    XpFrame f;
    XpMap.Built built;

    /** 100x100 wrapping map: cannons at (10, 99) and (11, 99), fuel (20, 99), target (30, 99), bases (40, 99) and (50, 99). */
    static String[] rows() {
        String[] r = new String[W];
        for (int i = 0; i < W; i++) {
            r[i] = " ".repeat(W);
        }
        char[] top = r[0].toCharArray();
        top[10] = 'r';
        top[11] = 'r';
        top[20] = '#';
        top[30] = '!';
        top[40] = '_';
        top[50] = '_';
        r[0] = new String(top);
        return r;
    }

    @BeforeEach
    void setUp() {
        rec = new Recorder();
        tr = new XpTranslator(rec);
        kb = new XpKeyboard();
        dec = new XpFrameDecoder(kb);
        f = new XpFrame();
        built = XpMap.build(XpMapTest.setupFor(rows(), XpProto.MODE_WRAP_PLAY, 12, "T", true));
        tr.configure(built, XpProto.MODE_WRAP_PLAY, XpProto.MY_VERSION);
        dec.setMap(W, W);
        tr.expectSelf();
        tr.player(3, 255, ' ', "Me", "");
    }

    /** Decode one C frame and translate it; returns the snapshot lines. */
    List<String> frame(CPackets p) {
        byte[] a = p.bytes();
        XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
        while (r.has(1)) {
            assertEquals(XpFrameDecoder.OK, dec.decode(r, f), "fixture decodes");
        }
        assertTrue(f.ended, "fixture frame ends");
        int before = rec.snapshots.size();
        tr.frame(f, 1000L * f.loops);
        assertEquals(before + 1, rec.snapshots.size(), "one snapshot per frame");
        return SnapDump.dump(rec.snapshots.get(rec.snapshots.size() - 1), PX, PX, true);
    }

    static CPackets c(int loops) {
        return new CPackets().start(loops, 0);
    }

    @Nested
    @DisplayName("header and own ship")
    class Self {
        @Test
        void headerUsesTheFrameNumberAndTheOwnShipAsViewCentre() {
            List<String> s = frame(c(7).self(1000, 2000).end(7));
            assertEquals("tick=7 part=0/1 flags=" + com.fulcrologic.jxpilot.common.net.SnapshotWriter.FLAG_LAST_PART
                + " view=1000.0,2000.0", s.get(0), "header");
        }

        @Test
        void selfRecordFromPktSelfAndTheOwnShip() {
            int[] items = new int[XpProto.NUM_ITEMS];
            items[XpProto.ITEM_TANK] = 2;
            items[0] = 5;
            List<String> s = frame(c(1).self(1000, 2000, -3, 4, 32, 45, 35, 30, -1, 0, 0, 0, 1, 900, 2600, 768, 768, 0,
                    XpProto.STATUS_PLAYING, 0).selfItems(items).ship(1000, 2000, 3, 32, true, false, false, false, false)
                .end(1));
            assertAll(
                () -> assertEquals(List.of("SELF id=3 flags=" + Records.ShipFlags.SHIELD
                    + " pos=1000.0,2000.0 vel=-3.0,4.0 h=1.571 fuel=900.0/2600.0 lock=65535 tanks=2 cur=1"),
                    SnapDump.only(s, "SELF"), "own ship state; lock -1 is no lock; tanks = tank items"),
                () -> assertEquals(List.of("SELF_ITEMS 5 0 0 0 0 0 0 2 0 0 0 0 0 0 0 0 0 0 0 0 0"),
                    SnapDump.only(s, "SELF_ITEMS"), "item counts"),
                () -> assertEquals(List.of("SHIP id=3 flags=1 pos=1000.0,2000.0 h=1.571 vel=0.0,0.0"),
                    SnapDump.only(s, "SHIP"), "own ship also in the ship list"));
        }

        @Test
        void selfFlagsFromStatusAndVisibility() {
            List<String> dead = frame(c(1).self(10, 10, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 1, 2, 768, 768, 0,
                XpProto.STATUS_PAUSE | XpProto.STATUS_SELF_DESTRUCT, 1).end(1));
            List<String> destruct = frame(c(2).self(10, 10).destruct(5).ship(10, 10, 3, 0).end(2));
            assertAll(
                () -> assertEquals(List.of("SELF id=3 flags=" + (Records.ShipFlags.DEAD | Records.ShipFlags.PAUSED
                        | Records.ShipFlags.SELF_DESTRUCT | Records.ShipFlags.AUTOPILOT)
                        + " pos=10.0,10.0 vel=0.0,0.0 h=0.000 fuel=1.0/2.0 lock=7 tanks=0 cur=0"),
                    SnapDump.only(dead, "SELF"), "not in the ship list: dead; pause, self-destruct, autopilot"),
                () -> assertTrue(SnapDump.only(destruct, "SELF").get(0).startsWith(
                    "SELF id=3 flags=" + Records.ShipFlags.SELF_DESTRUCT + " "), "PKT_DESTRUCT counts too"));
        }

        @Test
        void statusOtherThanPlayingIsDeadEvenWithTheShipInView() {
            List<String> paused = frame(c(1).self(10, 10, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 2, 768, 768, 0,
                XpProto.STATUS_PLAYING | XpProto.STATUS_PAUSE, 0).ship(10, 10, 3, 0).end(1));
            List<String> over = frame(c(2).self(10, 10, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 2, 768, 768, 0,
                XpProto.STATUS_PLAYING | XpProto.STATUS_GAME_OVER, 0).ship(10, 10, 3, 0).end(2));
            List<String> playing = frame(c(3).self(10, 10, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 2, 768, 768, 0,
                XpProto.STATUS_PLAYING, 0).ship(10, 10, 3, 0).end(3));
            assertAll(
                () -> assertTrue(SnapDump.only(paused, "SELF").get(0).startsWith("SELF id=3 flags="
                    + (Records.ShipFlags.DEAD | Records.ShipFlags.PAUSED) + " "), "PLAYING|PAUSE: not in play"),
                () -> assertTrue(SnapDump.only(over, "SELF").get(0).startsWith("SELF id=3 flags="
                    + Records.ShipFlags.DEAD + " "), "PLAYING|GAME_OVER: not in play"),
                () -> assertTrue(SnapDump.only(playing, "SELF").get(0).startsWith("SELF id=3 flags=0 "),
                    "PLAYING alone: in play"));
        }

        @Test
        void noSelfIdYetIsNoId() {
            XpTranslator t2 = new XpTranslator(rec);
            t2.configure(built, 0, XpProto.MY_VERSION);
            XpFrameDecoder d2 = new XpFrameDecoder(new XpKeyboard());
            byte[] a = c(1).self(5, 5).end(1).bytes();
            XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
            while (r.has(1)) {
                d2.decode(r, f);
            }
            t2.frame(f, 0);
            List<String> s = SnapDump.dump(rec.snapshots.get(rec.snapshots.size() - 1), PX, PX, true);
            assertTrue(SnapDump.only(s, "SELF").get(0).startsWith("SELF id=" + Protocol.NO_ID + " flags="
                + Records.ShipFlags.DEAD), "unknown self: NO_ID and not visible");
        }

        @Test
        void framesWithoutSelfKeepTheLastViewAndHaveNoSelfSections() {
            frame(c(1).self(1000, 2000).end(1));
            List<String> s = frame(c(2).damaged(3).end(2));
            assertAll(
                () -> assertTrue(s.get(0).endsWith("view=1000.0,2000.0"), "previous view centre"),
                () -> assertEquals(List.of(), SnapDump.only(s, "SELF"), "no SELF"),
                () -> assertEquals(List.of(), SnapDump.only(s, "SELF_ITEMS"), "no SELF_ITEMS"));
        }

        @Test
        void beforeConfigureNothingIsSent() {
            XpTranslator t2 = new XpTranslator(rec);
            int before = rec.snapshots.size();
            t2.frame(f, 0);
            assertAll(
                () -> assertEquals(before, rec.snapshots.size(), "no snapshot without a map"),
                () -> assertEquals(0, t2.snapshots(), "not counted"));
        }
    }

    @Nested
    @DisplayName("sounds (PKT_AUDIO)")
    class Sounds {
        @Test
        @DisplayName("each PKT_AUDIO becomes a SOUND record after SELF, with or without PKT_SELF; none, no section")
        void audio() {
            List<String> withSelf = frame(c(1).self(1000, 2000).audio(5, 40).audio(23, 100).end(1));
            List<String> noSelf = frame(c(2).audio(82, 10).end(2));
            List<String> quiet = frame(c(3).self(1000, 2000).end(3));
            int self = withSelf.indexOf(SnapDump.only(withSelf, "SELF").get(0));
            assertAll(
                () -> assertEquals(List.of("SOUND event=5 volume=40", "SOUND event=23 volume=100"),
                    SnapDump.only(withSelf, "SOUND"), "type and volume as sent, in order"),
                () -> assertEquals("SOUND event=5 volume=40", withSelf.get(self + 2), "the SOUND section follows SELF (and its SELFCTL dump line)"),
                () -> assertEquals(List.of("SOUND event=82 volume=10"), SnapDump.only(noSelf, "SOUND"),
                    "a frame without PKT_SELF still carries its sounds"),
                () -> assertEquals(List.of(), SnapDump.only(quiet, "SOUND"), "a frame without PKT_AUDIO has no SOUND"));
        }
    }

    @Nested
    @DisplayName("ships")
    class Ships {
        @Test
        void flagBitsMapToShipFlags() {
            assertAll(
                () -> assertEquals(Records.ShipFlags.SHIELD, XpTranslator.shipFlags(1), "shield"),
                () -> assertEquals(Records.ShipFlags.CLOAKED, XpTranslator.shipFlags(2), "cloak"),
                () -> assertEquals(Records.ShipFlags.EMERGENCY_SHIELD, XpTranslator.shipFlags(4), "emergency shield"),
                () -> assertEquals(Records.ShipFlags.PHASING, XpTranslator.shipFlags(8), "phased"),
                () -> assertEquals(Records.ShipFlags.DEFLECTOR, XpTranslator.shipFlags(16), "deflector"),
                () -> assertEquals(0, XpTranslator.shipFlags(0), "none"));
        }

        @Test
        void velocityIsDerivedFromThePreviousFrame() {
            frame(c(1).self(1000, 1000).ship(1100, 1000, 4, 0).ship(1200, 1200, 5, 0).end(1));
            List<String> s = frame(c(3).self(1000, 1000).ship(1110, 994, 4, 64).ship(1200 + 400, 1200, 5, 0)
                .ship(900, 900, 6, 0).end(3));
            assertEquals(List.of("SHIP id=4 flags=0 pos=1110.0,994.0 h=3.142 vel=5.0,-3.0",
                "SHIP id=5 flags=0 pos=1600.0,1200.0 h=0.000 vel=0.0,0.0",
                "SHIP id=6 flags=0 pos=900.0,900.0 h=0.000 vel=0.0,0.0"), SnapDump.only(s, "SHIP"),
                "delta / 2 frames; a jump over TELEPORT_PX and a new ship have no velocity");
        }

        @Test
        void velocityAcrossTheWrapSeam() {
            frame(c(1).self(10, 10).ship((int) PX - 5, 10, 4, 0).end(1));
            List<String> s = frame(c(2).self(10, 10).ship(5, 10, 4, 0).end(2));
            assertEquals(List.of("SHIP id=4 flags=0 pos=5.0,10.0 h=0.000 vel=10.0,0.0"), SnapDump.only(s, "SHIP"),
                "+10 px across the seam, not -3490");
        }

        @Test
        void sameFrameNumberGivesNoVelocity() {
            frame(c(1).self(10, 10).ship(20, 10, 4, 0).end(1));
            f.loops = 1;
            f.ships = 1;
            f.shipX[0] = 30;
            tr.frame(f, 0);
            List<String> s = SnapDump.dump(rec.snapshots.get(rec.snapshots.size() - 1), PX, PX, true);
            assertEquals("SHIP id=4 flags=0 pos=30.0,10.0 h=0.000 vel=0.0,0.0", SnapDump.only(s, "SHIP").get(0),
                "no frames between: velocity 0");
        }

        @Test
        void refuelAndRepairBeamsMarkShips() {
            List<String> s = frame(c(1).self(1000, 1000).ship(1000, 1000, 3, 0).ship(1100, 1000, 4, 0)
                .refuel(700, 700, 1000, 1000).refuel(1100, 1000, 735, 735).end(1));
            assertEquals(List.of("SHIP id=3 flags=" + Records.ShipFlags.REFUELING + " pos=1000.0,1000.0 h=0.000 vel=0.0,0.0",
                "SHIP id=4 flags=" + Records.ShipFlags.REPAIRING + " pos=1100.0,1000.0 h=0.000 vel=0.0,0.0"),
                SnapDump.only(s, "SHIP"), "beam ending at a ship refuels it, starting at a ship repairs");
        }

        @Test
        void refuelPacketsBecomeBeamsWithTheirEndpoints() {
            List<String> s = frame(c(1).self(1000, 1000).ship(1000, 1000, 3, 0).ship(1100, 1000, 4, 0)
                .refuel(700, 700, 1000, 1000).refuel(1100, 1000, 735, 735).refuel(1000, 1000, 1100, 1000)
                .refuel(800, 800, 820, 820).end(1));
            assertEquals(List.of(
                    "CONNECTOR 700.0,700.0 1000.0,1000.0 kind=" + Records.Connector.KIND_REFUEL,
                    "CONNECTOR 1100.0,1000.0 735.0,735.0 kind=" + Records.Connector.KIND_REPAIR,
                    "CONNECTOR 1000.0,1000.0 1100.0,1000.0 kind=" + Records.Connector.KIND_REFUEL,
                    "CONNECTOR 800.0,800.0 820.0,820.0 kind=" + Records.Connector.KIND_REFUEL),
                SnapDump.only(s, "CONNECTOR"),
                "station-to-ship is REFUEL; ship-to-non-ship is REPAIR; ship-to-ship and unknown default to REFUEL");
        }

        @Test
        void pausedShipsHaveNoIdOnTheWire() {
            List<String> s = frame(c(1).self(1000, 1000).paused(1050, 1000, 3).end(1));
            assertEquals(List.of("SHIP id=" + Protocol.NO_ID + " flags=" + Records.ShipFlags.PAUSED
                + " pos=1050.0,1000.0 h=0.000 vel=3.0,0.0"), SnapDump.only(s, "SHIP"),
                "paused ship: no id, C's pause count (3) as vx");
        }
    }

    @Nested
    @DisplayName("shots and debris (view areas)")
    class Particles {
        // self (1000, 1000), view 768: origin (616, 616), 3 x 3 areas of 256 px, 9 areas per colour
        static int type(int color, int ax, int ay) {
            return color * 9 + ay * 3 + ax;
        }

        @Test
        void fastShotsAreGroupedByColourAndPlacedByArea() {
            List<String> s = frame(c(1).self(1000, 1000)
                .fastshot(type(XpProto.WHITE, 1, 2), 10, 20)
                .fastshot(type(XpProto.RED, 0, 0), 0, 0)
                .fastshot(XpProto.DEBRIS_TYPES + type(XpProto.BLUE, 2, 1), 255, 255)
                .fastshot(XpProto.DEBRIS_TYPES + type(XpProto.RED, 0, 0), 1, 1)
                .fastshot(type(XpProto.BLUE, 0, 0), 3, 3).end(1));
            assertEquals(List.of(
                "SHOT kind=0 color=" + Records.ObjColor.RED + " pos=617.0,617.0 vel=0,0",
                "SHOT kind=0 color=" + Records.ObjColor.BLUE + " pos=619.0,619.0 vel=0,0",
                "SHOT kind=0 color=" + Records.ObjColor.BLUE + " pos=1383.0,1127.0 vel=0,0 harmless",
                "SHOT kind=0 color=" + Records.ObjColor.WHITE + " pos=882.0,1148.0 vel=0,0",
                "SHOT kind=0 color=" + Records.ObjColor.WHITE + " pos=616.0,616.0 vel=0,0"),
                SnapDump.only(s, "SHOT"),
                "red normal shots are white (C Paint_fastshots); team shots keep colour; a blue team shot is harmless "
                    + "(C frame.c's teamshot), a red one (nuclear blink) is not; one section per colour and flag; "
                    + "origin + area * 256 + byte");
        }

        @Test
        void debrisBecomesOneFrameParticles() {
            List<String> s = frame(c(1).self(1000, 1000).debris(type(XpProto.RED, 2, 2), 5, 6)
                .debris(type(XpProto.WHITE, 0, 1), 0, 0).debris(type(XpProto.BLUE, 0, 0), 1, 1)
                .debris(type(XpProto.BLACK, 0, 0), 2, 2).end(1));
            assertEquals(List.of(
                "BURST kind=0 color=" + Records.ObjColor.RED + " pos=1133.0,1134.0 count=1 speed=0.0-0.0 life=0-0 vel=0.0,0.0 age=0",
                "BURST kind=0 color=" + Records.ObjColor.WHITE + " pos=616.0,872.0 count=1 speed=0.0-0.0 life=0-0 vel=0.0,0.0 age=0",
                "BURST kind=0 color=" + Records.ObjColor.BLUE + " pos=617.0,617.0 count=1 speed=0.0-0.0 life=0-0 vel=0.0,0.0 age=0",
                "BURST kind=0 color=" + Records.ObjColor.WHITE + " pos=618.0,618.0 count=1 speed=0.0-0.0 life=0-0 vel=0.0,0.0 age=0"),
                SnapDump.only(s, "BURST"), "one still particle per C debris byte pair");
        }

        @Test
        void temperatureColoursAreLevels() {
            List<String> s = frame(c(1).self(1000, 1000, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 1, 768, 768, 5,
                XpProto.STATUS_PLAYING, 0).debris(type(4, 0, 0), 0, 0).end(1));
            assertTrue(SnapDump.only(s, "BURST").get(0).startsWith("BURST kind=0 color="
                    + (com.fulcrologic.jxpilot.client.game.ClientWorld.POINT_LEVEL_BASE + 4) + " "),
                "debris colours >= 3 are temperature levels: POINT_LEVEL_BASE + level 4");
        }

        @Test
        void areasFollowTheViewSizeOfPktSelf() {
            // view 1024 x 400: 4 x 2 areas, 8 per colour; origin (488, 800)
            List<String> s = frame(c(1).self(1000, 1000, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 1, 1024, 400, 0,
                XpProto.STATUS_PLAYING, 0).fastshot(XpProto.WHITE * 8 + 1 * 4 + 3, 0, 0).end(1));
            assertEquals(List.of("SHOT kind=0 color=" + Records.ObjColor.WHITE + " pos=1256.0,1056.0 vel=0,0"),
                SnapDump.only(s, "SHOT"), "column 3, row 1");
        }

        @Test
        void particlesWrapAroundTheWorld() {
            List<String> s = frame(c(1).self(100, 100).debris(type(XpProto.WHITE, 0, 0), 0, 0).end(1));
            assertEquals(List.of("BURST kind=0 color=" + Records.ObjColor.WHITE + " pos=3216.0,3216.0 count=1 speed=0.0-0.0"
                + " life=0-0 vel=0.0,0.0 age=0"), SnapDump.only(s, "BURST"), "origin -284 wraps to 3216");
        }

        @Test
        void colourHelper() {
            assertAll(
                () -> assertEquals(Records.ObjColor.WHITE, XpTranslator.objColor(XpProto.WHITE), "white"),
                () -> assertEquals(Records.ObjColor.WHITE, XpTranslator.objColor(XpProto.BLACK), "black is white"),
                () -> assertEquals(Records.ObjColor.BLUE, XpTranslator.objColor(XpProto.BLUE), "blue"),
                () -> assertEquals(Records.ObjColor.RED, XpTranslator.objColor(XpProto.RED), "red"),
                () -> assertEquals(Records.ObjColor.WHITE, XpTranslator.objColor(7), "others white"));
        }
    }

    @Nested
    @DisplayName("map state and radar")
    class MapState {
        @Test
        void cannonFuelTargetUseWorldMapIndices() {
            List<String> s = frame(c(1).self(500, 3400).cannon(1, 30).cannon(0, 0).fuel(0, 250 << 8).target(0, 0, 6400)
                .target(0, 5, 0).end(1));
            assertAll(
                () -> assertEquals(List.of("CANNON " + built.cannonIndex()[1] + " flags="
                    + Records.CannonState.FLAG_DEAD, "CANNON " + built.cannonIndex()[0] + " flags=0"),
                    SnapDump.only(s, "CANNON"), "dead time > 0 is dead"),
                () -> assertEquals(List.of("FUEL " + built.fuelIndex()[0] + " frac=0.500"), SnapDump.only(s, "FUEL"),
                    "250 of 500 units"),
                () -> assertEquals(List.of("TARGET " + built.targetIndex()[0] + " flags=0 left=0.100",
                    "TARGET " + built.targetIndex()[0] + " flags=" + Records.TargetState.FLAG_DEAD + " left=0.000"),
                    SnapDump.only(s, "TARGET"), "damage / TARGET_DAMAGE"));
        }

        @Test
        void unknownObjectNumbersAreCounted() {
            List<String> s = frame(c(1).self(500, 500).cannon(9, 0).fuel(9, 0).target(9, 0, 0).end(1));
            assertAll(
                () -> assertEquals(List.of(), SnapDump.only(s, "CANNON"), "skipped"),
                () -> assertEquals(3, tr.badIndex(), "counted"));
        }

        @Test
        void radarFlagsFromCSize() {
            assertAll(
                () -> assertEquals(0, XpTranslator.radarFlags(3), "enemy ship"),
                () -> assertEquals(Records.Radar.FLAG_FRIENDLY, XpTranslator.radarFlags(0x83), "team mate"),
                () -> assertEquals(Records.Radar.FLAG_BALL, XpTranslator.radarFlags(2), "ball"),
                () -> assertEquals(Records.Radar.FLAG_MISSILE, XpTranslator.radarFlags(0), "missile or mine"),
                () -> assertEquals(Records.Radar.FLAG_ASTEROID, XpTranslator.radarFlags(0x81), "asteroid size 0"),
                () -> assertEquals(Records.Radar.FLAG_ASTEROID, XpTranslator.radarFlags(0x82), "asteroid size 1"));
        }

        @Test
        void radarBlipsAndTheOwnShip() {
            List<String> s = frame(c(1).self(1750, 1750).ship(1750, 1750, 3, 0).radar(35, 3465, 0x83).end(1));
            List<String> noShip = frame(c(2).self(1750, 1750).end(2));
            assertAll(
                () -> assertEquals(List.of("RADAR cell=2,253 flags=" + Records.Radar.FLAG_FRIENDLY,
                    "RADAR cell=128,128 flags=" + Records.Radar.FLAG_SELF), SnapDump.only(s, "RADAR"),
                    "blips in 1/256 of the world plus self (C Handle_ship)"),
                () -> assertEquals(List.of(), SnapDump.only(noShip, "RADAR"), "no blips and no visible self: none"));
        }
    }

    @Nested
    @DisplayName("other objects")
    class Objects {
        @Test
        void m2ObjectsAreMapped() {
            List<String> s = frame(c(1).self(1000, 1000).item(1001, 1002, 7).mine(1003, 1004, 1, 9)
                .mine(1005, 1006, 0, XpProto.EXPIRED_MINE_ID).mine(1, 2, 0, 0).missile(1007, 1008, 15, 32)
                .ball(1009, 1010, 4).laser(XpProto.BLUE, 1011, 1012, 100, 64).asteroid(1013, 1014, 1, 3, 32)
                .wreckage(1015, 1016, 0x82, 2, 64).ecm(1017, 1018, 70).connector(1019, 1020, 1021, 1022, 0)
                .connector(1, 2, 3, 4, 1).trans(1023, 1024, 1025, 1026).wormhole(1032, 1067).end(1));
            assertAll(
                () -> assertEquals(List.of("ITEM pos=1001.0,1002.0 type=7"), SnapDump.only(s, "ITEM"), "item"),
                () -> assertEquals(List.of("MINE pos=1003.0,1004.0 flags=" + Records.Mine.FLAG_FRIENDLY + " owner=9",
                    "MINE pos=1005.0,1006.0 flags=0 owner=" + Protocol.NO_ID,
                    "MINE pos=1.0,2.0 flags=0 owner=" + Protocol.NO_ID), SnapDump.only(s, "MINE"),
                    "team mine friendly; expired or unknown owner is NO_ID"),
                () -> assertEquals(List.of("MISSILE id=" + Protocol.NO_ID + " pos=1007.0,1008.0 h=1.571 kind="
                    + Records.Missile.KIND_HEAT + " len=15"),
                    SnapDump.only(s, "MISSILE"), "missile"),
                () -> assertEquals(List.of("BALL id=" + Protocol.NO_ID + " pos=1009.0,1010.0 team=" + Protocol.NO_TEAM
                    + " holder=4"), SnapDump.only(s, "BALL"), "ball: C's id is the connected player, the ball has none"),
                () -> assertEquals(List.of("LASER pos=1011.0,1012.0 h=3.142 len=100 color=" + Records.ObjColor.BLUE),
                    SnapDump.only(s, "LASER"), "laser"),
                () -> assertEquals(List.of("ASTEROID pos=1013.0,1014.0 size=3 rot=1.571"), SnapDump.only(s, "ASTEROID"),
                    "asteroid"),
                () -> assertEquals(List.of("WRECKAGE pos=1015.0,1016.0 shape=2 size=2 rot=3.142"),
                    SnapDump.only(s, "WRECKAGE"), "deadly bit dropped from the shape"),
                () -> assertEquals(List.of("ECM pos=1017.0,1018.0 radius=35"), SnapDump.only(s, "ECM"), "size is a diameter"),
                () -> assertEquals(List.of("CONNECTOR 1019.0,1020.0 1021.0,1022.0 kind=" + Records.Connector.KIND_BALL,
                    "CONNECTOR 1.0,2.0 3.0,4.0 kind=" + Records.Connector.KIND_TRACTOR), SnapDump.only(s, "CONNECTOR"),
                    "ball connector and tractor beam"),
                () -> assertEquals(List.of("TRANSPORTER 1023.0,1024.0 1025.0,1026.0"), SnapDump.only(s, "TRANSPORTER"),
                    "transporter"),
                () -> assertEquals(List.of("TEMP_WORMHOLE 29,30 type=0"), SnapDump.only(s, "TEMP_WORMHOLE"),
                    "block of the wormhole centre"));
        }

        @Test
        void missileKindsByLength() {
            assertAll(
                () -> assertEquals(Records.Missile.KIND_SMART, XpTranslator.missileKind(12), "smart"),
                () -> assertEquals(Records.Missile.KIND_HEAT, XpTranslator.missileKind(15), "heat / generic"),
                () -> assertEquals(Records.Missile.KIND_TORPEDO, XpTranslator.missileKind(18), "torpedo"),
                () -> assertEquals(Records.Missile.KIND_SMART, XpTranslator.missileKind(20), "other"));
        }

        @Test
        void objectsBeyondTheEncodableRangeAreCulledAndCounted() {
            String[] wide = {" ".repeat(900), " ".repeat(900)};
            XpMap.Built big = XpMap.build(XpMapTest.setupFor(wide, 0, 12, "Wide", true));
            tr.configure(big, 0, XpProto.MY_VERSION);
            byte[] a = c(1).self(100, 10).ship(30000, 10, 4, 0).item(30000, 10, 1).end(1).bytes();
            XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
            XpFrameDecoder d = new XpFrameDecoder(new XpKeyboard());
            while (r.has(1)) {
                d.decode(r, f);
            }
            tr.frame(f, 0);
            List<String> s = SnapDump.dump(rec.snapshots.get(rec.snapshots.size() - 1), 31500, 70, false);
            assertAll(
                () -> assertEquals(List.of(), SnapDump.only(s, "SHIP"), "ship 29900 px away dropped"),
                () -> assertEquals(List.of(), SnapDump.only(s, "ITEM"), "item dropped"),
                () -> assertEquals(2, tr.culled(), "counted"),
                () -> assertFalse(s.contains("MALFORMED"), "snapshot still valid"));
        }
    }

    @Nested
    @DisplayName("into the client model")
    class Model {
        @Test
        void clientGameInterpolatesCFramesAtTheServerRate() {
            ClientGame game = new ClientGame();
            XpTranslator t = new XpTranslator(game);
            com.fulcrologic.jxpilot.common.net.Msg.Welcome w = new com.fulcrologic.jxpilot.common.net.Msg.Welcome();
            w.simHz = 12;
            w.snapshotHz = 12;
            w.gameFps = 12;
            w.flags = com.fulcrologic.jxpilot.common.net.Msg.Welcome.FLAG_EDGE_WRAP;
            game.onConfig(w, built.options(), built.map());
            t.configure(built, XpProto.MODE_WRAP_PLAY, XpProto.MY_VERSION);
            t.expectSelf();
            t.player(3, 255, ' ', "Me", "");
            t.player(4, 255, 'R', "Robby", "");
            long frameNs = 1_000_000_000L / 12;
            XpFrameDecoder d = new XpFrameDecoder(new XpKeyboard());
            for (int i = 1; i <= 6; i++) {
                byte[] a = c(i).self(1000 + 12 * i, 1000).ship(1000 + 12 * i, 1000, 3, 0)
                    .ship(1100 + 24 * i, 1000, 4, 0).debris(XpProto.WHITE * 9, 0, 0).end(i).bytes();
                XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
                while (r.has(1)) {
                    d.decode(r, f);
                }
                t.frame(f, i * frameNs);
            }
            long now = 6 * frameNs + frameNs / 2;
            game.frame(now, 0);
            ClientWorld cw = game.world();
            assertAll(
                () -> assertEquals(3, game.players().selfId(), "self from the first PKT_PLAYER"),
                () -> assertEquals(6, cw.newestTick(), "C frame numbers are ticks"),
                () -> assertEquals(1.0f, cw.frameFraction(), 1e-6, "one original frame per tick"),
                () -> assertTrue(cw.hasSelf(), "own ship"),
                () -> assertEquals(1, cw.shipCount(), "the robot (own ship is not in the list)"),
                () -> assertEquals(4, cw.shipId(0), "robot id"),
                () -> assertTrue(cw.shipX(0) > 1100 + 24 * 4 && cw.shipX(0) < 1100 + 24 * 6,
                    "robot interpolated between frames: " + cw.shipX(0)),
                () -> assertTrue(cw.pointCount() >= 1, "debris tracked as point particles"));
        }
    }
}
