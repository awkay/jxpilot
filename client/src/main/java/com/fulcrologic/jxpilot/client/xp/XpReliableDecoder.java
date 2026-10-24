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

/**
 * Decodes the packets of the reliable data stream after the setup (port of the {@code reliable_tbl}
 * handlers and the {@code cbuf} loop of {@code Net_packet} in {@code client/netclient.c}; formats from
 * the {@code Send_*} functions writing {@code connp->c} in {@code server/netserver.c}). Game events go
 * to the {@link XpTranslator}; connection-level packets (reply, magic, quit, talk ack) to a
 * {@link Control}. Allocation free.
 */
public final class XpReliableDecoder {
    /** Connection-level reliable packets. */
    public interface Control {
        /** {@code PKT_REPLY %c%c}: reply to a request type with a result (PKT_SUCCESS / PKT_FAILURE). */
        void reply(int replyTo, int result);

        /** {@code PKT_MAGIC %u}: the connection magic after a successful verify. */
        void magic(int magic);

        /** {@code PKT_QUIT %s}: the server ends the connection. */
        void quit(CharSequence reason);

        /** {@code PKT_TALK_ACK %ld}. */
        void talkAck(int seq);
    }

    /** {@link #decode} result: a packet needs more stream data (nothing consumed). */
    public static final int INCOMPLETE = 0;
    /** {@link #decode} result: unknown type or over-long string; C treats this as fatal. */
    public static final int BAD = -1;

    private final XpTranslator events;
    private final Control control;
    private final StringBuilder s1 = new StringBuilder(XpProto.MSG_LEN);
    private final StringBuilder s2 = new StringBuilder(XpProto.MSG_LEN);
    private final StringBuilder shape = new StringBuilder(2 * XpProto.MSG_LEN);
    private int version = XpProto.MY_VERSION;
    private long ignored;
    private int lastBad = -1;

    public XpReliableDecoder(XpTranslator events, Control control) {
        this.events = events;
        this.control = control;
    }

    /** Protocol version: scores were 16-bit integers before 0x4500. */
    public void setVersion(int v) {
        version = v;
    }

    /** Packets decoded but not used by the client model (MOTD, strings). */
    public long ignored() { return ignored; }

    /** Type of the last BAD packet, -1 if none. */
    public int lastBad() { return lastBad; }

    /**
     * Decode one packet at the reader's position.
     * <p>
     * BEHAVIOR: returns the bytes consumed; {@link #INCOMPLETE} (position unchanged) when the stream
     * ends inside the packet; {@link #BAD} for unknown types and strings without a NUL in their limit.
     */
    public int decode(XpReader r) {
        int start = r.pos();
        int type = r.peekU8();
        int res = switch (type) {
            case XpProto.PKT_REPLY -> reply(r);
            case XpProto.PKT_MAGIC -> magic(r);
            case XpProto.PKT_MESSAGE -> message(r);
            case XpProto.PKT_TEAM_SCORE -> teamScore(r);
            case XpProto.PKT_PLAYER -> player(r);
            case XpProto.PKT_SCORE -> score(r);
            case XpProto.PKT_TIMING -> timing(r);
            case XpProto.PKT_LEAVE -> leave(r);
            case XpProto.PKT_WAR -> war(r);
            case XpProto.PKT_SEEK -> seek(r);
            case XpProto.PKT_BASE -> base(r);
            case XpProto.PKT_QUIT -> quit(r);
            case XpProto.PKT_STRING -> fixed(r, 6);
            case XpProto.PKT_SCORE_OBJECT -> scoreObject(r);
            case XpProto.PKT_TALK_ACK -> talkAck(r);
            case XpProto.PKT_MOTD -> motd(r);
            default -> BAD;
        };
        if (res == BAD) {
            lastBad = type;
            r.pos(start);
            return BAD;
        }
        if (res == INCOMPLETE) {
            r.pos(start);
            return INCOMPLETE;
        }
        return r.pos() - start;
    }

    private static final int OK = 1;

    /** C {@code Receive_timing}: {@code %c%hd%hu}, timing = round * MAX_CHECKS + check. */
    private int timing(XpReader r) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int id = r.s16();
        int t = r.u16();
        events.timing(id, t % XpProto.MAX_CHECKS, t / XpProto.MAX_CHECKS);
        return OK;
    }

    /** Skips a fixed-size packet the model does not use (PKT_STRING). */
    private int fixed(XpReader r, int size) {
        if (!r.has(size)) {
            return INCOMPLETE;
        }
        r.skip(size);
        ignored++;
        return OK;
    }

    /** C {@code Receive_reply}: {@code %c%c%c}. */
    private int reply(XpReader r) {
        if (!r.has(3)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int to = r.u8();
        int result = r.u8();
        control.reply(to, result);
        return OK;
    }

    /** C {@code Receive_magic}: {@code %c%u}. */
    private int magic(XpReader r) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        control.magic(r.s32());
        return OK;
    }

    /** Maps a string scan result: OVERFLOW is BAD, INCOMPLETE stays INCOMPLETE. */
    private static int str(int n) {
        return n == XpReader.OVERFLOW ? BAD : INCOMPLETE;
    }

    /** C {@code Receive_message}: {@code %c%S}. */
    private int message(XpReader r) {
        r.skip(1);
        int n = r.string(s1, XpProto.MSG_LEN);
        if (n <= 0) {
            return str(n);
        }
        events.message(s1);
        return OK;
    }

    /** C {@code Receive_team_score}: {@code %c%hd%d} team, score * 100. */
    private int teamScore(XpReader r) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int team = r.s16();
        int sc = r.s32();
        events.teamScore(team, sc / 100.0);
        return OK;
    }

    /**
     * C {@code Receive_player}: {@code %c%hd%c%c%s%s%s%S%S} id, team, mychar, nick, real name, host,
     * shape, shape extension (the extension since version 0x3200).
     * <p>
     * BEHAVIOR: name, real name and host are cut like C (MAX_NAME_LEN - 1, MAX_NAME_LEN - 1,
     * MAX_HOST_LEN - 1 chars); real name and host are not used.
     */
    private int player(XpReader r) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int id = r.s16();
        int team = r.u8();
        int mychar = r.u8();
        int n = r.string(s1, XpProto.MAX_CHARS);
        if (n <= 0) {
            return str(n);
        }
        n = r.skipString(XpProto.MAX_CHARS);
        if (n <= 0) {
            return str(n);
        }
        n = r.skipString(XpProto.MAX_CHARS);
        if (n <= 0) {
            return str(n);
        }
        n = r.string(s2, XpProto.MSG_LEN);
        if (n <= 0) {
            return str(n);
        }
        shape.setLength(0);
        shape.append(s2);
        if (version > 0x3200) {
            n = r.string(s2, XpProto.MSG_LEN);
            if (n <= 0) {
                return str(n);
            }
            shape.append(s2);
        }
        if (s1.length() > XpProto.MAX_NAME_LEN - 1) {
            s1.setLength(XpProto.MAX_NAME_LEN - 1);
        }
        events.player(id, team, mychar, s1, shape);
        return OK;
    }

    /**
     * C {@code Receive_score}: since 0x4500 {@code %c%hd%d%hd%c%c} (score * 100, alliance char),
     * before {@code %c%hd%hd%hd%c}.
     */
    private int score(XpReader r) {
        boolean newer = version >= 0x4500;
        if (!r.has(newer ? 11 : 8)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int id = r.s16();
        double sc = newer ? r.s32() / 100.0 : r.s16();
        int life = r.s16();
        int mychar = r.u8();
        int alliance = newer ? r.u8() : ' ';
        events.score(id, sc, life, mychar);
        events.alliance(id, (char) alliance);
        return OK;
    }

    /** C {@code Receive_leave}: {@code %c%hd}. */
    private int leave(XpReader r) {
        if (!r.has(3)) {
            return INCOMPLETE;
        }
        r.skip(1);
        events.leave(r.s16());
        return OK;
    }

    /** C {@code Receive_war}: {@code %c%hd%hd}. */
    private int war(XpReader r) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int robot = r.s16();
        int killer = r.s16();
        events.war(robot, killer);
        return OK;
    }

    /** C {@code Receive_seek}: {@code %c%hd%hd%hd}. */
    private int seek(XpReader r) {
        if (!r.has(7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int p = r.s16();
        int robot = r.s16();
        int sought = r.s16();
        events.seek(p, robot, sought);
        return OK;
    }

    /** C {@code Receive_base}: {@code %c%hd%hu} id, base number. */
    private int base(XpReader r) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int id = r.s16();
        int num = r.u16();
        events.base(id, num);
        return OK;
    }

    /** C {@code Receive_quit} from the stream: {@code %c%s}; a missing reason is "unknown reason". */
    private int quit(XpReader r) {
        r.skip(1);
        int n = r.string(s1, XpProto.MAX_CHARS);
        if (n == XpReader.INCOMPLETE) {
            return INCOMPLETE;
        }
        if (n < 0) {
            s1.setLength(0);
            s1.append("unknown reason");
        }
        control.quit(s1);
        return OK;
    }

    /**
     * C {@code Receive_score_object}: since 0x4500 {@code %c%d%hu%hu%s} (score * 100), before
     * {@code %c%hd%hu%hu%s} (whole points); block x, block y, label.
     * <p>
     * BEHAVIOR: hands score, block and label to {@link XpTranslator#scoreObject}.
     */
    private int scoreObject(XpReader r) {
        boolean newer = version >= 0x4500;
        if (!r.has(newer ? 9 : 7)) {
            return INCOMPLETE;
        }
        r.skip(1);
        double sc = newer ? r.s32() / 100.0 : r.s16();
        int x = r.u16();
        int y = r.u16();
        int n = r.string(s1, XpProto.MAX_CHARS);
        if (n <= 0) {
            return str(n);
        }
        events.scoreObject(sc, x, y, s1);
        return OK;
    }

    /** C {@code Receive_talk_ack}: {@code %c%ld}. */
    private int talkAck(XpReader r) {
        if (!r.has(5)) {
            return INCOMPLETE;
        }
        r.skip(1);
        control.talkAck(r.s32());
        return OK;
    }

    /**
     * C {@code Receive_motd}: {@code %c%ld%hd%ld} offset, length, size, then length bytes.
     * <p>
     * BEHAVIOR: chunks in order are collected (Latin-1, up to {@link XpProto#MAX_MOTD_SIZE}); a chunk at
     * another offset is skipped; when the collected text reaches the size (or an empty chunk ends it) it goes
     * to {@link XpTranslator#motd} once.
     */
    private int motd(XpReader r) {
        if (!r.has(11)) {
            return INCOMPLETE;
        }
        r.skip(1);
        int off = r.s32();
        int len = r.s16();
        int size = r.s32();
        if (len < 0) {
            return BAD;
        }
        if (!r.has(len)) {
            return INCOMPLETE;
        }
        boolean inOrder = off == motd.length() && !motdDone;
        for (int i = 0; i < len; i++) {
            int c = r.u8();
            if (inOrder && motd.length() < XpProto.MAX_MOTD_SIZE) {
                motd.append((char) c);
            }
        }
        if (inOrder && (len == 0 || motd.length() >= Math.min(size, XpProto.MAX_MOTD_SIZE))) {
            motdDone = true;
            events.motd(motd);
        }
        return OK;
    }

    private final StringBuilder motd = new StringBuilder(1024);
    private boolean motdDone;
}
