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
package com.fulcrologic.jxpilot.server.net;

import java.nio.ByteBuffer;

/** Test helper: reads a REJECT datagram the way a client of a given protocol version does. */
public final class RejectReader {
    private RejectReader() {
    }

    /**
     * A REJECT as a client of protocol {@code version} reads it, the v1.1.1 way (PacketHeader.read: the JX magic and
     * exactly its own version byte; then Msg.Reject: u32 nonce, u8 reason, str8 text): "nonce reason text", or null
     * when that client would drop the datagram.
     */
    public static String rejectAs(int version, ByteBuffer datagram) {
        ByteBuffer d = datagram.duplicate();
        int p = d.position();
        if (d.remaining() < 8 || (d.getShort(p) & 0xFFFF) != 0x4A58 || (d.get(p + 2) & 0xFF) != version
            || (d.get(p + 3) & 0xFF) != 3) {
            return null;
        }
        d.position(p + 8);
        int nonce = d.getInt();
        int reason = d.get() & 0xFF;
        int n = d.get() & 0xFF;
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < n; i++) {
            t.append((char) (d.get() & 0xFF));
        }
        return Integer.toHexString(nonce) + " " + reason + " " + t;
    }
}
