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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** Every frame packet, built with the C server's encoders (CPackets = netserver.c Send_*). */
class XpFrameDecoderTest {
    XpKeyboard kb;
    XpFrameDecoder dec;
    XpFrame f;
    ByteBuffer out;

    @BeforeEach
    void setUp() {
        kb = new XpKeyboard();
        dec = new XpFrameDecoder(kb);
        f = new XpFrame();
        out = ByteBuffer.allocate(1024);
        dec.setOut(out);
        dec.setMap(100, 50);
    }

    /** Decode all packets; returns the last result. */
    int decodeAll(byte[] a) {
        XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
        int res = XpFrameDecoder.OK;
        while (r.has(1)) {
            res = dec.decode(r, f);
            if (res != XpFrameDecoder.OK) {
                return res;
            }
        }
        return res;
    }

    /** Decode one packet and check it consumed exactly its bytes. */
    void one(CPackets p) {
        byte[] a = p.bytes();
        XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
        assertEquals(XpFrameDecoder.OK, dec.decode(r, f), "decodes");
        assertEquals(a.length, r.pos(), "consumes exactly the packet");
    }

    /** Every strict prefix of the packet is INCOMPLETE. */
    void truncatedIsIncomplete(CPackets p) {
        byte[] a = p.bytes();
        for (int n = 1; n < a.length; n++) {
            XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, n);
            assertEquals(XpFrameDecoder.INCOMPLETE, dec.decode(r, new XpFrame()), "prefix of " + n + " bytes");
        }
    }

    @Nested
    @DisplayName("frame bracket")
    class Bracket {
        @Test
        void startClearsAndRecordsLoopsAndAck() {
            kb.setKeys(1);
            f.ships = 3;
            one(new CPackets().start(10, 1));
            assertAll(
                () -> assertTrue(f.started, "started"),
                () -> assertEquals(10, f.loops, "loops"),
                () -> assertEquals(1, f.keyAck, "key ack"),
                () -> assertEquals(0, f.ships, "lists cleared"),
                () -> assertEquals(10, dec.lastLoops(), "last loops"),
                () -> assertEquals(1, kb.ack(), "keyboard ack taken"));
        }

        @Test
        void olderOrEqualFramesAreStale() {
            one(new CPackets().start(10, 0));
            assertAll(
                () -> assertEquals(XpFrameDecoder.STALE, decodeAll(new CPackets().start(10, 0).bytes()), "same loops"),
                () -> assertEquals(XpFrameDecoder.STALE, decodeAll(new CPackets().start(9, 0).bytes()), "older"),
                () -> assertEquals(10, dec.lastLoops(), "last loops unchanged"),
                () -> assertEquals(XpFrameDecoder.OK, decodeAll(new CPackets().start(11, 0).bytes()), "newer ok"));
        }

        @Test
        void prematureKeyboardAckDropsTheFrameButTakesTheLoops() {
            f.loops = 77;
            int res = decodeAll(new CPackets().start(10, 5).bytes());
            assertAll(
                () -> assertEquals(XpFrameDecoder.PREMATURE_ACK, res, "ack beyond the last change"),
                () -> assertEquals(10, dec.lastLoops(), "C sets last_loops before checking the ack"),
                () -> assertEquals(77, f.loops, "frame untouched"),
                () -> assertEquals(0, kb.ack(), "ack not taken"));
        }

        @Test
        void endMarksTheFrameComplete() {
            one(new CPackets().end(10));
            assertTrue(f.ended, "ended");
        }

        @Test
        void resetForgetsTheLastFrame() {
            one(new CPackets().start(10, 0));
            dec.reset();
            assertAll(
                () -> assertEquals(0, dec.lastLoops(), "reset"),
                () -> assertEquals(XpFrameDecoder.OK, decodeAll(new CPackets().start(1, 0).bytes()), "1 is new again"));
        }

        @Test
        void unknownTypes() {
            assertAll(
                () -> assertEquals(XpFrameDecoder.UNKNOWN, decodeAll(new byte[] {60}), "unused 60"),
                () -> assertEquals(XpFrameDecoder.UNKNOWN, decodeAll(new byte[] {(byte) 0}), "PKT_UNDEFINED"),
                () -> assertEquals(XpFrameDecoder.UNKNOWN, decodeAll(new byte[] {XpProto.PKT_REPLY}),
                    "reply is not a frame packet"),
                () -> assertEquals(XpFrameDecoder.UNKNOWN, decodeAll(new byte[] {82}), "unused 82"),
                () -> assertEquals(XpFrameDecoder.UNKNOWN, decodeAll(new byte[] {127}), "127 is just below debris"));
        }

        @Test
        void truncatedBracketPackets() {
            truncatedIsIncomplete(new CPackets().start(1, 0));
            truncatedIsIncomplete(new CPackets().end(1));
        }
    }

    @Nested
    @DisplayName("own ship and HUD")
    class Self {
        @Test
        void selfDecodesEveryField() {
            f.items[3] = 9;
            one(new CPackets().self(100, -200, -3, 4, 96, 55, 20, 31, 7, 300, 12, 2, 1, 900, 2600, 1024, 900, 0,
                XpProto.STATUS_PLAYING | XpProto.STATUS_PAUSE, 1));
            assertAll(
                () -> assertTrue(f.hasSelf, "has self"),
                () -> assertEquals(100, f.selfX, "x"),
                () -> assertEquals(-200, f.selfY, "y signed"),
                () -> assertEquals(-3, f.selfVx, "vx"),
                () -> assertEquals(4, f.selfVy, "vy"),
                () -> assertEquals(96, f.selfDir, "dir"),
                () -> assertEquals(55, f.selfPower, "power"),
                () -> assertEquals(20, f.selfTurnspeed, "turnspeed"),
                () -> assertEquals(31, f.selfTurnresistance, "turnresistance"),
                () -> assertEquals(7, f.selfLockId, "lock id"),
                () -> assertEquals(300, f.selfLockDist, "lock dist"),
                () -> assertEquals(12, f.selfLockDir, "lock dir"),
                () -> assertEquals(2, f.selfCheck, "check"),
                () -> assertEquals(1, f.selfCurrentTank, "current tank"),
                () -> assertEquals(900, f.selfFuelSum, "fuel"),
                () -> assertEquals(2600, f.selfFuelMax, "fuel max"),
                () -> assertEquals(1024, f.viewWidth, "view width"),
                () -> assertEquals(900, f.viewHeight, "view height"),
                () -> assertEquals(3, f.selfStat, "status"),
                () -> assertEquals(1, f.autopilotLight, "autopilot"),
                () -> assertEquals(0, f.items[3], "items reset by PKT_SELF"));
            truncatedIsIncomplete(new CPackets().self(1, 2));
        }

        @Test
        void selfItemsFollowTheMask() {
            int[] items = new int[XpProto.NUM_ITEMS];
            items[0] = 3;
            items[7] = 2;
            items[20] = 1;
            one(new CPackets().selfItems(items));
            assertArrayEquals(items, f.items, "counts per set bit, in order");
        }

        @Test
        void selfItemsBeyondKnownItemsAreSkippedAndMissingCountsAreZero() {
            byte[] beyond = new CPackets().c(XpProto.PKT_SELF_ITEMS).d((1 << 22) | 2).c(5).c(6).bytes();
            byte[] cut = new CPackets().c(XpProto.PKT_SELF_ITEMS).d(3).c(4).bytes();
            int r1 = decodeAll(beyond);
            int item1 = f.items[1];
            f.items[1] = 0;
            int r2 = decodeAll(cut);
            assertAll(
                () -> assertEquals(XpFrameDecoder.OK, r1, "ok"),
                () -> assertEquals(5, item1, "bit 1 gets the first count"),
                () -> assertEquals(XpFrameDecoder.OK, r2, "a short packet is accepted like C"),
                () -> assertEquals(4, f.items[0], "available count read"),
                () -> assertEquals(0, f.items[1], "missing count is 0"));
            truncatedIsIncomplete(new CPackets().c(XpProto.PKT_SELF_ITEMS).d(0));
        }

        @Test
        void modifiers() {
            one(new CPackets().modifiers("FN V2"));
            assertEquals("FN V2", f.modifiers.toString(), "modifier string");
            truncatedIsIncomplete(new CPackets().modifiers("AB"));
        }

        @Test
        void hudTimers() {
            one(new CPackets().damaged(5));
            one(new CPackets().destruct(30));
            one(new CPackets().countMax(XpProto.PKT_SHUTDOWN, 100, 600));
            one(new CPackets().countMax(XpProto.PKT_THRUSTTIME, 7, 8));
            one(new CPackets().countMax(XpProto.PKT_SHIELDTIME, 9, 10));
            one(new CPackets().countMax(XpProto.PKT_PHASINGTIME, 11, 12));
            one(new CPackets().countMax(XpProto.PKT_ROUNDDELAY, 13, 14));
            one(new CPackets().timeLeft(345));
            one(new CPackets().eyes(4));
            one(new CPackets().audio(1, 2));
            one(new CPackets().loseItem(6));
            assertAll(
                () -> assertEquals(5, f.damaged, "damaged"),
                () -> assertEquals(30, f.destruct, "destruct"),
                () -> assertEquals(100, f.shutdownCount, "shutdown count"),
                () -> assertEquals(600, f.shutdownDelay, "shutdown delay"),
                () -> assertEquals(7, f.thrustTime, "thrust time"),
                () -> assertEquals(8, f.thrustMax, "thrust max"),
                () -> assertEquals(9, f.shieldTime, "shield time"),
                () -> assertEquals(10, f.shieldMax, "shield max"),
                () -> assertEquals(11, f.phasingTime, "phasing time"),
                () -> assertEquals(12, f.phasingMax, "phasing max"),
                () -> assertEquals(13, f.roundDelay, "round delay"),
                () -> assertEquals(14, f.roundDelayMax, "round delay max"),
                () -> assertEquals(345, f.timeLeft, "time left"),
                () -> assertEquals(4, f.eyes, "eyes"),
                () -> assertEquals(1, f.audioCount, "audio counted"),
                () -> assertEquals(6, f.loseItem, "lose item"));
            truncatedIsIncomplete(new CPackets().damaged(5));
            truncatedIsIncomplete(new CPackets().destruct(5));
            truncatedIsIncomplete(new CPackets().countMax(XpProto.PKT_THRUSTTIME, 1, 2));
            truncatedIsIncomplete(new CPackets().timeLeft(1));
            truncatedIsIncomplete(new CPackets().eyes(1));
            truncatedIsIncomplete(new CPackets().audio(1, 1));
            truncatedIsIncomplete(new CPackets().loseItem(1));
        }

        @Test
        void clearResetsHudTimers() {
            one(new CPackets().countMax(XpProto.PKT_THRUSTTIME, 7, 8));
            f.clear();
            assertAll(
                () -> assertEquals(-1, f.thrustTime, "not sent"),
                () -> assertEquals(-1, f.shutdownCount, "no shutdown"),
                () -> assertEquals(0, f.damaged, "not damaged"),
                () -> assertFalse(f.hasSelf, "no self"));
        }
    }

    @Nested
    @DisplayName("objects")
    class Objects {
        @Test
        void ships() {
            one(new CPackets().ship(10, -20, 3, 64, true, false, true, false, true));
            one(new CPackets().ship(5, 6, 4, 0));
            assertAll(
                () -> assertEquals(2, f.ships, "two ships"),
                () -> assertEquals(10, f.shipX[0], "x"),
                () -> assertEquals(-20, f.shipY[0], "y"),
                () -> assertEquals(3, f.shipId[0], "id"),
                () -> assertEquals(64, f.shipDir[0], "dir"),
                () -> assertEquals(1 | 4 | 16, f.shipFlags[0], "shield, emergency shield, deflector bits"),
                () -> assertEquals(1, f.findShip(4), "find by id"),
                () -> assertEquals(-1, f.findShip(9), "absent id"));
            truncatedIsIncomplete(new CPackets().ship(1, 2, 3, 4));
        }

        @Test
        void pausedRefuelConnectorLaserMissileBall() {
            one(new CPackets().paused(1, 2, 3));
            one(new CPackets().refuel(4, 5, 6, 7));
            one(new CPackets().connector(8, 9, 10, 11, 1));
            one(new CPackets().laser(XpProto.RED, 12, 13, 140, 96));
            one(new CPackets().missile(15, 16, 25, 33));
            one(new CPackets().ball(17, 18, 2));
            assertAll(
                () -> assertArrayEquals(new int[] {1, 2, 3}, new int[] {f.pausedX[0], f.pausedY[0], f.pausedCount[0]},
                    "paused"),
                () -> assertArrayEquals(new int[] {4, 5, 6, 7},
                    new int[] {f.refuelX0[0], f.refuelY0[0], f.refuelX1[0], f.refuelY1[0]}, "refuel"),
                () -> assertArrayEquals(new int[] {8, 9, 10, 11, 1},
                    new int[] {f.connX0[0], f.connY0[0], f.connX1[0], f.connY1[0], f.connTractor[0]}, "connector"),
                () -> assertArrayEquals(new int[] {XpProto.RED, 12, 13, 140, 96},
                    new int[] {f.laserColor[0], f.laserX[0], f.laserY[0], f.laserLen[0], f.laserDir[0]}, "laser"),
                () -> assertArrayEquals(new int[] {15, 16, 25, 33},
                    new int[] {f.missileX[0], f.missileY[0], f.missileLen[0], f.missileDir[0]}, "missile"),
                () -> assertArrayEquals(new int[] {17, 18, 2}, new int[] {f.ballX[0], f.ballY[0], f.ballId[0]}, "ball"),
                () -> assertArrayEquals(new int[] {1, 1, 1, 1, 1, 1},
                    new int[] {f.paused, f.refuels, f.connectors, f.lasers, f.missiles, f.balls}, "one of each"));
            truncatedIsIncomplete(new CPackets().paused(1, 2, 3));
            truncatedIsIncomplete(new CPackets().refuel(1, 2, 3, 4));
            truncatedIsIncomplete(new CPackets().connector(1, 2, 3, 4, 0));
            truncatedIsIncomplete(new CPackets().laser(1, 2, 3, 4, 5));
            truncatedIsIncomplete(new CPackets().missile(1, 2, 3, 4));
            truncatedIsIncomplete(new CPackets().ball(1, 2, 3));
        }

        @Test
        void mineItemEcmTransWreckageAsteroidWormhole() {
            one(new CPackets().mine(1, 2, 1, 4096));
            one(new CPackets().item(3, 4, 20));
            one(new CPackets().ecm(5, 6, 70));
            one(new CPackets().trans(7, 8, 9, 10));
            one(new CPackets().wreckage(11, 12, 0x81, 2, 40));
            one(new CPackets().asteroid(13, 14, 1, 3, 50));
            one(new CPackets().wormhole(15, 16));
            assertAll(
                () -> assertArrayEquals(new int[] {1, 2, 1, 4096},
                    new int[] {f.mineX[0], f.mineY[0], f.mineTeam[0], f.mineId[0]}, "mine"),
                () -> assertArrayEquals(new int[] {3, 4, 20}, new int[] {f.itemX[0], f.itemY[0], f.itemType[0]}, "item"),
                () -> assertArrayEquals(new int[] {5, 6, 70}, new int[] {f.ecmX[0], f.ecmY[0], f.ecmSize[0]}, "ecm"),
                () -> assertArrayEquals(new int[] {7, 8, 9, 10},
                    new int[] {f.transX1[0], f.transY1[0], f.transX2[0], f.transY2[0]}, "transporter"),
                () -> assertArrayEquals(new int[] {11, 12, 0x81, 2, 40},
                    new int[] {f.wreckX[0], f.wreckY[0], f.wreckType[0], f.wreckSize[0], f.wreckRot[0]}, "wreckage"),
                () -> assertArrayEquals(new int[] {13, 14, 1, 3, 50},
                    new int[] {f.astX[0], f.astY[0], f.astType[0], f.astSize[0], f.astRot[0]},
                    "asteroid type and size from the shared byte"),
                () -> assertArrayEquals(new int[] {15, 16}, new int[] {f.wormX[0], f.wormY[0]}, "wormhole"));
            truncatedIsIncomplete(new CPackets().mine(1, 2, 0, 3));
            truncatedIsIncomplete(new CPackets().item(1, 2, 3));
            truncatedIsIncomplete(new CPackets().ecm(1, 2, 3));
            truncatedIsIncomplete(new CPackets().trans(1, 2, 3, 4));
            truncatedIsIncomplete(new CPackets().wreckage(1, 2, 3, 4, 5));
            truncatedIsIncomplete(new CPackets().asteroid(1, 2, 3, 4, 5));
            truncatedIsIncomplete(new CPackets().wormhole(1, 2));
        }

        @Test
        void itemsWithUnknownTypesAreIgnored() {
            one(new CPackets().item(3, 4, XpProto.NUM_ITEMS));
            assertEquals(0, f.itemObjs, "type 21 dropped like C");
        }

        @Test
        void fullListsCountOverflowAndKeepDecoding() {
            CPackets p = new CPackets();
            for (int i = 0; i <= XpFrame.SHIP_CAP; i++) {
                p.ship(i, i, i, 0);
            }
            p.end(1);
            int res = decodeAll(p.bytes());
            assertAll(
                () -> assertEquals(XpFrameDecoder.OK, res, "decoding continues"),
                () -> assertEquals(XpFrame.SHIP_CAP, f.ships, "capacity kept"),
                () -> assertEquals(1, f.overflow, "one dropped"),
                () -> assertTrue(f.ended, "packets after the overflow are decoded"));
        }

        @Test
        void everyListOverflowsTheSameWay() {
            CPackets p = new CPackets();
            for (int i = 0; i <= XpFrame.OBJ_CAP; i++) {
                p.connector(0, 0, 0, 0, 0).laser(1, 0, 0, 0, 0).missile(0, 0, 0, 0).ball(0, 0, 0).mine(0, 0, 0, 0)
                    .item(0, 0, 0).ecm(0, 0, 0).trans(0, 0, 0, 0).wreckage(0, 0, 0, 0, 0).asteroid(0, 0, 0, 0, 0)
                    .wormhole(0, 0).radar(0, 0, 3);
            }
            for (int i = 0; i <= XpFrame.SHIP_CAP; i++) {
                p.paused(0, 0, 0).refuel(0, 0, 0, 0);
            }
            assertEquals(XpFrameDecoder.OK, decodeAll(p.bytes()), "ok");
            assertAll(
                () -> assertEquals(12 + 2, f.overflow, "one per list"),
                () -> assertEquals(XpFrame.OBJ_CAP, f.radar, "radar capped"),
                () -> assertEquals(XpFrame.SHIP_CAP, f.paused, "paused capped"));
        }
    }

    @Nested
    @DisplayName("radar")
    class Radar {
        @Test
        void slowRadarIsInWorldPixels() {
            one(new CPackets().radar(1000, 600, 0x83));
            assertAll(
                () -> assertEquals(1000f, f.radarX[0], "x"),
                () -> assertEquals(600f, f.radarY[0], "y"),
                () -> assertEquals(0x83, f.radarSize[0], "size byte"));
            truncatedIsIncomplete(new CPackets().radar(1, 2, 3));
        }

        @Test
        void fastRadarUnpacksTenBitYAndFlags() {
            // map 100x50 blocks: 3500x1750 px, radar height 256 * 50 / 100 = 128
            one(new CPackets().fastRadar(10, 100, 3, 255, 127, 0x83));
            assertAll(
                () -> assertEquals(2, f.radar, "two blips"),
                () -> assertEquals((10 + 0.5) * 3500 / 256, f.radarX[0], 1e-3, "x cell centre"),
                () -> assertEquals((100 + 0.5) * 1750 / 128, f.radarY[0], 1e-3, "y cell centre"),
                () -> assertEquals(3, f.radarSize[0], "size"),
                () -> assertEquals(0x83, f.radarSize[1], "friendly bit 0x20 becomes 0x80"),
                () -> assertEquals((255 + 0.5) * 3500 / 256, f.radarX[1], 1e-3, "last column"));
        }

        @Test
        void fastRadarHighYBits() {
            dec.setMap(10, 50); // radar height 1280
            one(new CPackets().fastRadar(0, 1000, 2));
            assertEquals((1000 + 0.5) * 1750 / 1280, f.radarY[0], 1e-3, "y bits 8 and 9 from the flag byte");
        }

        @Test
        void truncatedFastRadar() {
            assertAll(
                () -> assertEquals(XpFrameDecoder.INCOMPLETE, decodeAll(new byte[] {XpProto.PKT_FASTRADAR}), "no count"),
                () -> assertEquals(XpFrameDecoder.INCOMPLETE,
                    decodeAll(new byte[] {XpProto.PKT_FASTRADAR, 2, 1, 2, 3, 4}), "second blip short"));
        }
    }

    @Nested
    @DisplayName("debris and fast shots")
    class Particles {
        @Test
        void debrisKeepsTypeAndBytePositions() {
            one(new CPackets().debris(37, 1, 2, 200, 255));
            assertAll(
                () -> assertEquals(2, f.debris, "two particles"),
                () -> assertEquals(37, f.debrisType[0], "type = packet - PKT_DEBRIS"),
                () -> assertEquals(1, f.debrisX[0] & 0xFF, "x"),
                () -> assertEquals(2, f.debrisY[0] & 0xFF, "y"),
                () -> assertEquals(200, f.debrisX[1] & 0xFF, "x unsigned"),
                () -> assertEquals(255, f.debrisY[1] & 0xFF, "y unsigned"));
        }

        @Test
        void lastDebrisType() {
            one(new CPackets().debris(XpProto.DEBRIS_TYPES - 1, 3, 4));
            assertEquals(XpProto.DEBRIS_TYPES - 1, f.debrisType[0], "type 127 is debris");
        }

        @Test
        void fastshotsKeepTheTeamshotOffset() {
            one(new CPackets().fastshot(XpProto.DEBRIS_TYPES + 5, 7, 8));
            assertAll(
                () -> assertEquals(1, f.fastshots, "one"),
                () -> assertEquals(XpProto.DEBRIS_TYPES + 5, f.fastshotType[0], "type with team offset"),
                () -> assertEquals(7, f.fastshotX[0], "x"),
                () -> assertEquals(8, f.fastshotY[0], "y"));
        }

        @Test
        void truncatedParticlePacketsAreIncompleteLikeC() {
            assertAll(
                () -> assertEquals(XpFrameDecoder.INCOMPLETE, decodeAll(new byte[] {(byte) 130, 0}),
                    "C needs more than 2 bytes even for 0 particles"),
                () -> assertEquals(XpFrameDecoder.INCOMPLETE, decodeAll(new byte[] {(byte) 130, 2, 1, 2, 3}),
                    "second pair short"),
                () -> assertEquals(XpFrameDecoder.INCOMPLETE, decodeAll(new byte[] {XpProto.PKT_FASTSHOT, 1, 0}),
                    "fastshot header short"),
                () -> assertEquals(XpFrameDecoder.INCOMPLETE,
                    decodeAll(new byte[] {XpProto.PKT_FASTSHOT, 1, 2, 1, 2, 3}), "fastshot pair short"));
        }

        @Test
        void particleCapacityOverflows() {
            CPackets p = new CPackets();
            int pairs = 0;
            while (pairs <= XpFrame.PARTICLE_CAP) {
                int[] xy = new int[2 * 255];
                p.debris(0, xy).fastshot(0, xy);
                pairs += 255;
            }
            decodeAll(p.bytes());
            int sent = pairs;
            assertAll(
                () -> assertEquals(XpFrame.PARTICLE_CAP, f.debris, "debris capped"),
                () -> assertEquals(XpFrame.PARTICLE_CAP, f.fastshots, "fast shots capped"),
                () -> assertEquals(2 * (sent - XpFrame.PARTICLE_CAP), f.overflow, "every extra counted"));
        }
    }

    @Nested
    @DisplayName("map objects and their acks")
    class MapObjects {
        @Test
        void fuelCannonTargetAreStoredAndAckedWithTheFrameNumber() {
            one(new CPackets().start(42, 0));
            one(new CPackets().fuel(3, 250 << 8));
            one(new CPackets().cannon(4, 99));
            one(new CPackets().target(5, 0, 12345));
            byte[] acks = java.util.Arrays.copyOf(out.array(), out.position());
            assertAll(
                () -> assertArrayEquals(new int[] {3, 250}, new int[] {f.fuelNum[0], f.fuelUnits[0]}, "fuel units"),
                () -> assertArrayEquals(new int[] {4, 99}, new int[] {f.cannonNum[0], f.cannonDeadTime[0]}, "cannon"),
                () -> assertArrayEquals(new int[] {5, 0, 12345},
                    new int[] {f.targetNum[0], f.targetDeadTime[0], f.targetDamage[0]}, "target"),
                () -> assertArrayEquals(new CPackets().c(XpProto.PKT_ACK_FUEL).d(42).hd(3)
                    .c(XpProto.PKT_ACK_CANNON).d(42).hd(4).c(XpProto.PKT_ACK_TARGET).d(42).hd(5).bytes(), acks,
                    "C acks: type, last_loops, number"));
            truncatedIsIncomplete(new CPackets().fuel(1, 0));
            truncatedIsIncomplete(new CPackets().cannon(1, 0));
            truncatedIsIncomplete(new CPackets().target(1, 0, 0));
        }

        @Test
        void acksStopAtMaxMapAckLen() {
            out.position(XpFrameDecoder.MAX_MAP_ACK_LEN - 1);
            one(new CPackets().fuel(1, 0));
            int after1 = out.position();
            one(new CPackets().cannon(1, 0));
            assertAll(
                () -> assertEquals(XpFrameDecoder.MAX_MAP_ACK_LEN - 1 + 7, after1, "below the limit: acked"),
                () -> assertEquals(after1, out.position(), "at or above the limit: not acked"),
                () -> assertEquals(1, f.cannonUpdates, "still stored"));
        }

        @Test
        void noOutputBufferMeansNoAcks() {
            dec.setOut(null);
            one(new CPackets().fuel(1, 0));
            assertEquals(1, f.fuelUpdates, "decoded without an ack buffer");
        }

        @Test
        void mapUpdateCapacity() {
            CPackets p = new CPackets();
            for (int i = 0; i <= XpFrame.MAP_CAP; i++) {
                p.fuel(i, 0).cannon(i, 0).target(i, 0, 0);
            }
            dec.setOut(null);
            decodeAll(p.bytes());
            assertAll(
                () -> assertEquals(XpFrame.MAP_CAP, f.fuelUpdates, "fuel capped"),
                () -> assertEquals(XpFrame.MAP_CAP, f.cannonUpdates, "cannons capped"),
                () -> assertEquals(XpFrame.MAP_CAP, f.targetUpdates, "targets capped"),
                () -> assertEquals(3, f.overflow, "one each"));
        }
    }
}
