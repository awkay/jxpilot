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

import java.nio.ByteBuffer;

/**
 * Outgoing talk messages (C {@code Net_talk}, {@code Send_talk}, {@code Receive_talk_ack} in
 * {@code client/netclient.c}): {@code PKT_TALK %ld seq %s text} rides on outgoing datagrams, resent
 * every {@value #TALK_RETRY} frames until the server's reliable {@code PKT_TALK_ACK} carries a sequence
 * at least as new. The C client keeps one pending line; this one queues up to {@value #QUEUE} and sends
 * them in order. Allocation free.
 */
public final class XpTalk {
    /** C {@code TALK_RETRY}: frames between resends. */
    public static final int TALK_RETRY = 2;
    /** Lines queued at most; further lines are refused. */
    public static final int QUEUE = 8;

    private final StringBuilder[] lines = new StringBuilder[QUEUE];
    private int head;
    private int count;
    private int sequence;
    private int pending;
    private int lastSend;

    public XpTalk() {
        for (int i = 0; i < QUEUE; i++) {
            lines[i] = new StringBuilder(XpProto.MAX_CHARS);
        }
    }

    /** BEHAVIOR: drops queued lines and restarts sequence numbers (new connection). */
    public void reset() {
        head = 0;
        count = 0;
        sequence = 0;
        pending = 0;
    }

    /** Lines waiting for an ack (the first is being sent). */
    public int queued() { return count; }

    /** Sequence number of the line being sent, 0 when idle. */
    public int pending() { return pending; }

    /**
     * Queue a line (C keeps at most {@code MAX_CHARS - 1} chars).
     * <p>
     * BEHAVIOR: returns false for empty text or a full queue; the first queued line gets the next
     * sequence number and is due at once.
     */
    public boolean add(CharSequence text, int lastLoops) {
        if (text.length() == 0 || count >= QUEUE) {
            return false;
        }
        StringBuilder sb = lines[(head + count) % QUEUE];
        sb.setLength(0);
        sb.append(text, 0, Math.min(text.length(), XpProto.MAX_CHARS - 1));
        count++;
        if (count == 1) {
            startNext(lastLoops);
        }
        return true;
    }

    private void startNext(int lastLoops) {
        pending = ++sequence;
        lastSend = lastLoops - TALK_RETRY;
    }

    /**
     * C {@code Receive_talk_ack}.
     * <p>
     * BEHAVIOR: an ack at or after the pending sequence completes that line and makes the next one due;
     * older acks and acks while idle change nothing.
     */
    public void onAck(int seq, int lastLoops) {
        if (pending == 0 || seq - pending < 0) {
            return;
        }
        head = (head + 1) % QUEUE;
        count--;
        pending = 0;
        if (count > 0) {
            startNext(lastLoops);
        }
    }

    /** BEHAVIOR: true when a line is pending and {@value #TALK_RETRY} frames passed since its last send. */
    public boolean due(int lastLoops) {
        return pending != 0 && lastLoops - lastSend >= TALK_RETRY;
    }

    /** Bytes {@link #write} needs for the pending line. */
    public int size() {
        return 1 + 4 + XpWriter.stringSize(lines[head], XpProto.MAX_CHARS);
    }

    /** C {@code Send_talk}: BEHAVIOR: writes the pending line and remembers the send frame. */
    public void write(ByteBuffer out, int lastLoops) {
        XpWriter.u8(out, XpProto.PKT_TALK);
        XpWriter.s32(out, pending);
        XpWriter.string(out, lines[head], XpProto.MAX_CHARS);
        lastSend = lastLoops;
    }
}
