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

import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Boundaries of the translator (exact limits, initial state, name table bookkeeping). */
class XpTranslatorEdgeTest {
    static final double PX = 100 * 35.0;
    Recorder rec;
    XpTranslator tr;
    XpFrameDecoder dec;
    XpFrame f;
    XpMap.Built built;

    @BeforeEach
    void setUp() {
        rec = new Recorder();
        tr = new XpTranslator(rec);
        dec = new XpFrameDecoder(new XpKeyboard());
        f = new XpFrame();
        built = XpMap.build(XpMapTest.setupFor(XpTranslatorTest.rows(), XpProto.MODE_WRAP_PLAY, 12, "T", true));
        tr.configure(built, XpProto.MODE_WRAP_PLAY, XpProto.MY_VERSION);
        dec.setMap(100, 100);
        tr.expectSelf();
        tr.player(3, 255, ' ', "Me", "");
    }

    List<String> frame(CPackets p, double w, double h, boolean wrap) {
        byte[] a = p.bytes();
        XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
        while (r.has(1)) {
            assertEquals(XpFrameDecoder.OK, dec.decode(r, f), "fixture decodes");
        }
        tr.frame(f, 0);
        return SnapDump.dump(rec.snapshots.get(rec.snapshots.size() - 1), w, h, wrap);
    }

    List<String> frame(CPackets p) {
        return frame(p, PX, PX, true);
    }

    static CPackets c(int loops) {
        return new CPackets().start(loops, 0);
    }

    @Test
    void viewStartsAtTheWorldCentreAndSnapshotsAreCounted() {
        List<String> s = frame(c(1).damaged(1).end(1));
        frame(c(2).end(2));
        assertAll(
            () -> assertTrue(s.get(0).endsWith("view=1750.0,1750.0"), "no own ship yet: world centre"),
            () -> assertEquals(2, tr.snapshots(), "two snapshots"),
            () -> assertEquals(XpProto.MY_VERSION, tr.version(), "version"));
    }

    @Test
    void lockIdZeroIsAPlayer() {
        List<String> s = frame(c(1).self(10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 768, 768, 0,
            XpProto.STATUS_PLAYING, 0).end(1));
        assertTrue(SnapDump.only(s, "SELF").get(0).contains("lock=0 "), "only negative ids are no lock");
    }

    @Test
    void shipsAndPausedShipsShareOneSection() {
        List<String> s = frame(c(1).self(1000, 1000).ship(1100, 1000, 4, 0).paused(1200, 1000, 1).end(1));
        assertEquals(2, SnapDump.only(s, "SHIP").size(), "one ship and one paused ship");
    }

    @Test
    void hudTimersBecomeATimersRecord() {
        List<String> with = frame(c(1).self(1000, 1000).damaged(9).countMax(XpProto.PKT_ROUNDDELAY, 28, 40)
            .timeLeft(300).end(1));
        List<String> without = frame(c(2).self(1000, 1000).end(2));
        List<String> noSelf = frame(c(3).damaged(4).end(3));
        assertAll(
            () -> assertEquals(List.of("TIMERS damaged=9 roundDelay=28 timeLeft=300"), SnapDump.only(with, "TIMERS"),
                "damaged, round delay and time left of this frame"),
            () -> assertEquals(List.of("TIMERS damaged=0 roundDelay=0 timeLeft=-1"), SnapDump.only(without, "TIMERS"),
                "nothing reported: 0, 0 (no round delay) and -1"),
            () -> assertEquals(List.of(), SnapDump.only(noSelf, "TIMERS"), "only with PKT_SELF"));
    }

    @Test
    void exactlyTeleportDistanceStillHasVelocity() {
        frame(c(1).self(1000, 1000).ship(1000, 1000, 4, 0).end(1));
        List<String> s = frame(c(2).self(1000, 1000).ship(1000 + XpTranslator.TELEPORT_PX, 1000, 4, 0).end(2));
        assertEquals("SHIP id=4 flags=0 pos=1350.0,1000.0 h=0.000 vel=350.0,0.0", SnapDump.only(s, "SHIP").get(0),
            "350 px is not more than TELEPORT_PX");
    }

    @Test
    void theFirstTeamShotTypeIsATeamShot() {
        // type DEBRIS_TYPES: team shot, colour 0 (black, drawn white), area 0
        List<String> s = frame(c(1).self(1000, 1000).fastshot(XpProto.DEBRIS_TYPES, 4, 5).end(1));
        assertEquals(List.of("SHOT kind=0 color=" + Records.ObjColor.WHITE + " pos=620.0,621.0 vel=0,0 harmless"),
            SnapDump.only(s, "SHOT"), "area 0 of the team range: a team shot, so harmless");
    }

    @Test
    void threeDebrisColoursAreTemperatures() {
        List<String> s = frame(c(1).self(1000, 1000, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 1, 768, 768, 3,
            XpProto.STATUS_PLAYING, 0).debris(XpProto.BLUE * 9, 0, 0).end(1));
        assertTrue(SnapDump.only(s, "BURST").get(0).contains("color="
                + (com.fulcrologic.jxpilot.client.game.ClientWorld.POINT_LEVEL_BASE + XpProto.BLUE)),
            "3 colours: the colour index is a temperature level, sent as POINT_LEVEL_BASE + level");
    }

    @Test
    void validObjectNumbersAreNotCountedAsBad() {
        frame(c(1).self(500, 500).cannon(0, 0).fuel(0, 0).target(0, 0, 0).end(1));
        assertEquals(0, tr.badIndex(), "index 0 is valid");
    }

    @Test
    void radarWithOnlyBlipsOrOnlyTheOwnShip() {
        List<String> blipsOnly = frame(c(1).self(1750, 1750).radar(35, 35, 3).end(1));
        List<String> selfOnly = frame(c(2).self(1750, 1750).ship(1750, 1750, 3, 0).end(2));
        assertAll(
            () -> assertEquals(1, SnapDump.only(blipsOnly, "RADAR").size(), "blip without own ship"),
            () -> assertEquals(List.of("RADAR cell=128,128 flags=" + Records.Radar.FLAG_SELF),
                SnapDump.only(selfOnly, "RADAR"), "own ship without blips"));
    }

    @Test
    void nonWrappingMapsKeepPositionsOutsideTheWorld() {
        XpMap.Built flat = XpMap.build(XpMapTest.setupFor(XpTranslatorTest.rows(), 0, 12, "Flat", true));
        tr.configure(flat, 0, XpProto.MY_VERSION);
        List<String> s = frame(c(1).self(100, 100).debris(XpProto.WHITE * 9, 0, 0).end(1), PX, PX, false);
        assertTrue(SnapDump.only(s, "BURST").get(0).contains("pos=-284.0,-284.0"), "not wrapped");
    }

    @Test
    void baseOwnerTableStartsEmptyForPlayerZero() {
        tr.base(0, 1);
        assertEquals(List.of("accepted 3", "join id=3 team=255 flags=4 name=Me shape=",
            "base index=" + built.baseIndex()[1] + " player=0"), rec.events, "no other base released for id 0");
    }

    @Test
    void renamedPlayersLeaveWithTheNewName() {
        tr.player(5, 0, ' ', "Longname", "");
        tr.player(5, 0, ' ', "Bo", "");
        rec.events.clear();
        tr.leave(5);
        assertEquals("message kind=4 from=65535 Bo left this world.", rec.events.get(0), "name replaced");
    }

    @Test
    void scoreMycharUpdatesTheLeaveRule() {
        XpTranslator t = new XpTranslator(rec);
        t.configure(built, 0, XpProto.MY_VERSION);
        t.player(5, 0, ' ', "Tank", "");
        t.score(5, 0, 0, 'T');
        rec.events.clear();
        t.leave(5);
        assertEquals(List.of("leave id=5"), rec.events, "a player that became a tank leaves silently");
    }

    @Test
    void leavingKeepsTheOtherNames() {
        tr.player(1, 0, 'R', "Robby", "");
        tr.player(2, 0, ' ', "Ann", "");
        tr.leave(3);
        tr.leave(1);
        rec.events.clear();
        tr.player(7, 0, 'R', "Rex", "");
        tr.war(7, 2);
        tr.seek(2, 7, 2);
        assertAll(
            () -> assertEquals(List.of("join id=7 team=0 flags=1 name=Rex shape=",
                "message kind=4 from=65535 Rex declares war on Ann.",
                "message kind=4 from=65535 Ann has programmed Rex to seek Ann."), rec.events.subList(0, 3),
                "names survive removals"),
            () -> assertEquals(Protocol.NO_ID, tr.selfId(), "self left"));
    }

    @Test
    void warAndSeekNeedEveryName() {
        tr.player(1, 0, 'R', "Robby", "");
        rec.events.clear();
        tr.war(3, 1);
        tr.war(1, 3);
        tr.war(3, 9);
        tr.seek(3, 1, 3);
        tr.seek(1, 3, 3);
        tr.seek(3, 3, 9);
        assertEquals(List.of("message kind=4 from=65535 Me declares war on Robby.",
            "message kind=4 from=65535 Robby declares war on Me.",
            "message kind=4 from=65535 Me has programmed Robby to seek Me.",
            "message kind=4 from=65535 Robby has programmed Me to seek Me."), rec.events,
            "slot 0 is a valid name; unknown ids stay silent");
    }
}
