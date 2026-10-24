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

import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reliable stream packets (C {@code reliable_tbl}), built with the server's encoders, through the
 * translator into the native messages the client model receives.
 */
class XpReliableDecoderTest {
    Recorder rec;
    XpTranslator tr;
    XpReliableDecoder dec;

    static final String[] ROWS = {"_ _ _", "     ", "  #  "};

    @BeforeEach
    void setUp() {
        rec = new Recorder();
        tr = new XpTranslator(rec);
        dec = new XpReliableDecoder(tr, rec);
        tr.configure(XpMap.build(XpMapTest.setupFor(ROWS, 0, 12, "n", true)), 0, XpProto.MY_VERSION);
    }

    void configure(int mode, int version) {
        tr.configure(XpMap.build(XpMapTest.setupFor(ROWS, mode, 12, "n", true)), mode, version);
        dec.setVersion(version);
    }

    /** Decode a whole stream; returns the results. */
    List<Integer> decode(CPackets p) {
        byte[] a = p.bytes();
        XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
        java.util.ArrayList<Integer> res = new java.util.ArrayList<>();
        while (r.has(1)) {
            int n = dec.decode(r);
            res.add(n);
            if (n <= 0) {
                break;
            }
        }
        return res;
    }

    /** Every strict prefix is INCOMPLETE and leaves the position. */
    void prefixesIncomplete(CPackets p) {
        byte[] a = p.bytes();
        for (int n = 1; n < a.length; n++) {
            // an exact-length copy: a decoder reading past the limit fails instead of reading the rest
            XpReader r = new XpReader().wrap(ByteBuffer.wrap(java.util.Arrays.copyOf(a, n)), 0, n);
            assertEquals(XpReliableDecoder.INCOMPLETE, dec.decode(r), "prefix " + n + " of type " + a[0]);
            assertEquals(0, r.pos(), "position kept");
        }
    }

    @Nested
    @DisplayName("connection packets")
    class Connection {
        @Test
        void replyMagicQuitTalkAck() {
            List<Integer> res = decode(new CPackets().reply(XpProto.PKT_VERIFY, XpProto.PKT_SUCCESS).magic(0x12345678)
                .talkAck(3).quit("bye"));
            assertAll(
                () -> assertEquals(List.of(3, 5, 5, 5), res, "bytes consumed per packet"),
                () -> assertEquals(List.of("reply 1 102", "magic 12345678", "talkack 3", "quit bye"), rec.events,
                    "control calls"));
            prefixesIncomplete(new CPackets().reply(1, 2));
            prefixesIncomplete(new CPackets().magic(1));
            prefixesIncomplete(new CPackets().talkAck(1));
            prefixesIncomplete(new CPackets().quit("x"));
        }

        @Test
        void quitWithAnOverlongReasonSaysUnknown() {
            decode(new CPackets().c(XpProto.PKT_QUIT).raw("y".repeat(XpProto.MAX_CHARS).getBytes()).c(0));
            assertEquals(List.of("quit unknown reason"), rec.events, "C uses \"unknown reason\"");
        }

        @Test
        void unknownTypesAreBad() {
            List<Integer> res = decode(new CPackets().c(99).c(1));
            assertAll(
                () -> assertEquals(List.of(XpReliableDecoder.BAD), res, "bad"),
                () -> assertEquals(99, dec.lastBad(), "type remembered"),
                () -> assertEquals(-1, new XpReliableDecoder(tr, rec).lastBad(), "none initially"));
        }
    }

    @Nested
    @DisplayName("players")
    class Players {
        @Test
        void theFirstPlayerAfterExpectSelfIsUs() {
            tr.expectSelf();
            decode(new CPackets().player(3, 255, ' ', "Pilot", "real", "host", "(SH: 1,0 -1,1 -1,-1)", "(EN: -1,0)")
                .player(4, 255, 'R', "Robby", "robot", "host", "", "").player(3, 255, ' ', "Pilot", "r", "h", "", ""));
            assertAll(
                () -> assertEquals(List.of(
                    "accepted 3",
                    "join id=3 team=255 flags=" + Msg.PlayerJoin.FLAG_SELF + " name=Pilot shape=(SH: 1,0 -1,1 -1,-1)(EN: -1,0)",
                    "join id=4 team=255 flags=" + Msg.PlayerJoin.FLAG_ROBOT + " name=Robby shape=",
                    "join id=3 team=255 flags=" + Msg.PlayerJoin.FLAG_SELF + " name=Pilot shape="), rec.events,
                    "self accepted first and flagged; shape parts joined"),
                () -> assertEquals(3, tr.selfId(), "self id"));
        }

        @Test
        void mycharFlagsAndTeams() {
            decode(new CPackets().player(5, 2, 'T', "Tank", "r", "h", "", "").player(6, 3, 'P', "Paul", "r", "h", "", "")
                .player(7, 1, 'W', "Wait", "r", "h", "", ""));
            assertEquals(List.of(
                "join id=5 team=2 flags=" + Msg.PlayerJoin.FLAG_TANK + " name=Tank shape=",
                "join id=6 team=3 flags=" + Msg.PlayerJoin.FLAG_PAUSED + " name=Paul shape=",
                "join id=7 team=1 flags=0 name=Wait shape="), rec.events, "T tank, P paused, others none");
        }

        @Test
        void longNicksAreCutLikeC() {
            decode(new CPackets().player(5, 0, ' ', "A".repeat(20), "r", "h", "", ""));
            assertEquals("join id=5 team=0 flags=0 name=" + "A".repeat(15) + " shape=", rec.events.get(0),
                "MAX_NAME_LEN - 1 chars");
        }

        @Test
        void oldVersionsHaveNoShapeExtension() {
            configure(0, 0x3200);
            decode(new CPackets().c(XpProto.PKT_PLAYER).hd(5).c(0).c(' ').s("N").s("r").s("h").s("(SH: 1,0)")
                .message("next"));
            assertEquals(List.of("join id=5 team=0 flags=0 name=N shape=(SH: 1,0)", "message kind=4 from=65535 next"),
                rec.events, "no %S extension before 0x3201");
        }

        @Test
        void truncatedOrOverlongPlayers() {
            prefixesIncomplete(new CPackets().player(1, 0, ' ', "N", "r", "h", "s", "e"));
            assertAll(
                () -> assertEquals(List.of(XpReliableDecoder.BAD),
                    decode(new CPackets().c(XpProto.PKT_PLAYER).hd(1).c(0).c(' ').raw("n".repeat(80).getBytes()).c(0)),
                    "nick overflow"),
                () -> assertEquals(List.of(XpReliableDecoder.BAD),
                    decode(new CPackets().c(XpProto.PKT_PLAYER).hd(1).c(0).c(' ').s("n").raw("r".repeat(80).getBytes())
                        .c(0)), "real name overflow"),
                () -> assertEquals(List.of(XpReliableDecoder.BAD),
                    decode(new CPackets().c(XpProto.PKT_PLAYER).hd(1).c(0).c(' ').s("n").s("r").raw("h".repeat(80)
                        .getBytes()).c(0)), "host overflow"),
                () -> assertEquals(List.of(XpReliableDecoder.BAD),
                    decode(new CPackets().c(XpProto.PKT_PLAYER).hd(1).c(0).c(' ').s("n").s("r").s("h")
                        .raw("s".repeat(256).getBytes()).c(0)), "shape overflow"),
                () -> assertEquals(List.of(XpReliableDecoder.BAD),
                    decode(new CPackets().c(XpProto.PKT_PLAYER).hd(1).c(0).c(' ').s("n").s("r").s("h").s("s")
                        .raw("e".repeat(256).getBytes()).c(0)), "extension overflow"));
        }

        @Test
        void leaveSaysGoodbyeForHumansOnly() {
            decode(new CPackets().player(5, 0, ' ', "Human", "r", "h", "", "").player(6, 0, 'R', "Robot", "r", "h", "", "")
                .player(7, 0, 'T', "Tank", "r", "h", "", ""));
            rec.events.clear();
            decode(new CPackets().leave(5).leave(6).leave(7).leave(9));
            assertEquals(List.of("message kind=4 from=65535 Human left this world.", "leave id=5", "leave id=6",
                "leave id=7", "leave id=9"), rec.events, "C Handle_leave");
            prefixesIncomplete(new CPackets().leave(1));
        }

        @Test
        void leavingSelfForgetsTheSelfId() {
            tr.expectSelf();
            decode(new CPackets().player(5, 0, ' ', "Me", "r", "h", "", "").leave(5));
            assertEquals(com.fulcrologic.jxpilot.common.net.Protocol.NO_ID, tr.selfId(), "self gone");
        }

        @Test
        void warAndSeekMessages() {
            decode(new CPackets().player(1, 0, 'R', "Robby", "r", "h", "", "").player(2, 0, ' ', "Ann", "r", "h", "", "")
                .player(3, 0, ' ', "Bob", "r", "h", "", ""));
            rec.events.clear();
            decode(new CPackets().war(1, 2).war(1, -1).war(9, 2).war(1, 9).seek(3, 1, 2).seek(9, 1, 2).seek(3, 9, 2)
                .seek(3, 1, 9));
            assertEquals(List.of("message kind=4 from=65535 Robby declares war on Ann.",
                "message kind=4 from=65535 Bob has programmed Robby to seek Ann."), rec.events,
                "C texts; unknown ids and killer -1 say nothing");
            prefixesIncomplete(new CPackets().war(1, 2));
            prefixesIncomplete(new CPackets().seek(1, 2, 3));
        }

        @Test
        void manyPlayersOverflowTheNameTableQuietly() {
            CPackets p = new CPackets();
            for (int i = 0; i <= XpTranslator.NAME_CAP; i++) {
                p.player(i, 0, ' ', "P" + i, "r", "h", "", "");
            }
            decode(p);
            rec.events.clear();
            decode(new CPackets().leave(XpTranslator.NAME_CAP));
            assertEquals(List.of("leave id=" + XpTranslator.NAME_CAP), rec.events, "untracked player: leave only");
        }
    }

    @Nested
    @DisplayName("scores and messages")
    class Scores {
        @Test
        void scoresWithTwoDecimals() {
            decode(new CPackets().player(5, 0, ' ', "A", "r", "h", "", "").score(5, 12.34, 3, 'P', '+')
                .score(5, -2.5, 0, 'W', ' ').score(5, 1, 0, 'D', ' ').score(5, 0, 0, ' ', ' '));
            assertEquals(List.of("join id=5 team=0 flags=0 name=A shape=",
                "score id=5 score=12.34 lives=-1 status=" + Msg.Score.STATUS_PAUSED,
                "score id=5 score=-2.5 lives=-1 status=" + Msg.Score.STATUS_WAITING,
                "score id=5 score=1.0 lives=-1 status=" + Msg.Score.STATUS_OUT_OF_LIVES,
                "score id=5 score=0.0 lives=-1 status=0"), rec.events, "score / 100, status chars, unlimited lives");
            prefixesIncomplete(new CPackets().score(5, 1, 1, ' ', ' '));
        }

        @Test
        void livesOnlyWithLimitedLives() {
            configure(XpProto.MODE_LIMITED_LIVES, XpProto.MY_VERSION);
            decode(new CPackets().score(5, 1, 3, ' ', ' '));
            assertEquals(List.of("score id=5 score=1.0 lives=3 status=0"), rec.events, "lives reported");
        }

        @Test
        void oldScoreFormat() {
            configure(0, 0x4401);
            decode(new CPackets().scoreOld(5, 42, 2, ' ').c(XpProto.PKT_SCORE_OBJECT).hd(-3).hd(4).hd(0xFFFE).s("x")
                .message("m"));
            assertEquals(List.of("score id=5 score=42.0 lives=-1 status=0",
                    "scoreobject score=-3.0 at=4,65534 label=x", "message kind=4 from=65535 m"), rec.events,
                "16-bit integer scores and score objects (%hd whole points, %hu blocks) before 0x4500");
            prefixesIncomplete(new CPackets().scoreOld(5, 1, 1, ' '));
            prefixesIncomplete(new CPackets().c(XpProto.PKT_SCORE_OBJECT).hd(3).hd(0).hd(0).s("x"));
        }

        @Test
        void teamScores() {
            decode(new CPackets().teamScore(2, 7.25));
            assertEquals(List.of("teamscore team=2 score=7.25"), rec.events, "team score / 100");
            prefixesIncomplete(new CPackets().teamScore(2, 1));
        }

        @Test
        void messagesKeepTheirText() {
            decode(new CPackets().message("hello [Robby]").message("Robby was killed by a shot."));
            assertEquals(List.of("message kind=4 from=65535 hello [Robby]",
                "message kind=4 from=65535 Robby was killed by a shot."), rec.events, "server messages");
            prefixesIncomplete(new CPackets().message("x"));
            assertEquals(List.of(XpReliableDecoder.BAD),
                decode(new CPackets().c(XpProto.PKT_MESSAGE).raw("m".repeat(256).getBytes()).c(0)), "overflow");
        }

        @Test
        void ignoredPacketsAreConsumedAndCounted() {
            List<Integer> res = decode(new CPackets().timing(5, 3, 2).string(1, 2, 3).motd(0, "abc".getBytes(), 3));
            assertAll(
                () -> assertEquals(List.of(5, 6, 14), res, "sizes"),
                () -> assertEquals(2, dec.ignored(), "string and MOTD ignored"),
                () -> assertEquals(List.of("timing id=5 check=3 round=2"), rec.events,
                    "PKT_TIMING becomes TIMING: round * 26 + check unpacked (C Receive_timing)"));
            prefixesIncomplete(new CPackets().timing(1, 2, 3));
            prefixesIncomplete(new CPackets().string(1, 2, 3));
            prefixesIncomplete(new CPackets().motd(0, "ab".getBytes(), 2));
        }

        @Test
        @DisplayName("PKT_SCORE_OBJECT since 0x4500 becomes SCORE_OBJECT: score / 100, block, label (C Receive_score_object)")
        void scoreObjects() {
            List<Integer> res = decode(new CPackets().scoreObject(12.5, 40000, 65000, "Robby").scoreObject(-0.25, 3, 4, ""));
            assertAll(
                () -> assertEquals(List.of(15, 10), res, "sizes: 9-byte head plus the NUL-terminated label"),
                () -> assertEquals(List.of("scoreobject score=12.5 at=40000,65000 label=Robby",
                    "scoreobject score=-0.25 at=3,4 label="), rec.events,
                    "delivered in order: score / 100, %hu blocks (40000 stays positive), empty label kept empty"),
                () -> assertEquals(0, dec.ignored(), "not ignored"));
            prefixesIncomplete(new CPackets().scoreObject(1, 2, 3, "s"));
            configure(0, 0x4500);
            rec.events.clear();
            decode(new CPackets().scoreObject(1.5, 7, 8, "v"));
            assertEquals(List.of("scoreobject score=1.5 at=7,8 label=v"), rec.events,
                "version 0x4500 itself already has the two-decimal format");
        }

        @Test
        void badScoreObjectAndMotd() {
            assertAll(
                () -> assertEquals(List.of(XpReliableDecoder.BAD), decode(new CPackets().c(XpProto.PKT_SCORE_OBJECT)
                    .d(0).hd(0).hd(0).raw("t".repeat(80).getBytes()).c(0)), "score object text overflow"),
                () -> assertEquals(List.of(XpReliableDecoder.BAD),
                    decode(new CPackets().c(XpProto.PKT_MOTD).d(0).hd(-1).d(0)), "negative MOTD length"));
        }
    }

    @Nested
    @DisplayName("bases")
    class Bases {
        @Test
        void ownershipMovesAndOldBasesAreReleased() {
            XpMap.Built b = XpMap.build(XpMapTest.setupFor(ROWS, 0, 12, "n", true));
            decode(new CPackets().base(7, 0).base(8, 2).base(7, 1).base(7, 1));
            int b0 = b.baseIndex()[0];
            int b1 = b.baseIndex()[1];
            int b2 = b.baseIndex()[2];
            assertEquals(List.of("base index=" + b0 + " player=7", "base index=" + b2 + " player=8",
                "base index=" + b0 + " player=65535", "base index=" + b1 + " player=7", "base index=" + b1 + " player=7"),
                rec.events, "C numbers mapped; a player's previous base released (C Handle_base)");
            prefixesIncomplete(new CPackets().base(1, 2));
        }

        @Test
        void badBaseNumbersAreCounted() {
            decode(new CPackets().base(7, 3).base(7, 65535));
            assertAll(
                () -> assertEquals(List.of(), rec.events, "ignored"),
                () -> assertEquals(2, tr.badIndex(), "counted"));
        }

        @Test
        void basesBeforeTheMapAreCounted() {
            XpTranslator fresh = new XpTranslator(rec);
            fresh.base(1, 0);
            assertEquals(1, fresh.badIndex(), "no map yet");
        }
    }
}
