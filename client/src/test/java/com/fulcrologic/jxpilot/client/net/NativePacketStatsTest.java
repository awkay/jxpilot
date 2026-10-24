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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.PacketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.client.net.ClientConnectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClientConnection packet meters, in snapshot slots")
class NativePacketStatsTest {
    final Scripted t = new Scripted();
    final ClientConnection c = new ClientConnection(t, new Rec(), "srv:1", 99);
    final ReliableChannel server = new ReliableChannel();

    /** Handshake to PLAYING with WELCOME simHz 60, snapshotHz 30: one slot per 2 ticks, 30 slots a second. */
    void playing() {
        c.start(0);
        int nonce = c.connect.nonce;
        t.in.add(pkt(PacketType.ACCEPT, SESSION, 0, b -> {
            Msg.Accept a = new Msg.Accept();
            a.nonce = nonce;
            a.playerId = 7;
            a.write(b);
        }));
        c.update(0);
        Msg.Welcome w = new Msg.Welcome();
        w.simHz = 60;
        w.snapshotHz = 30;
        w.serverName.append("S");
        com.fulcrologic.jxpilot.common.net.ConfigTransfer.Blob blob = blob();
        blob.fill(w);
        w.send(server);
        new com.fulcrologic.jxpilot.common.net.ConfigTransfer.Sender(blob).pump(server, 0);
        while (true) {
            int[] n = new int[1];
            byte[] p = pkt(PacketType.RELIABLE, SESSION, 0, b -> n[0] = server.write(b, 0));
            if (n[0] == 0) {
                break;
            }
            t.in.add(p);
        }
        c.update(0);
        assertEquals(ConnectionState.PLAYING, c.state(), "fixture reaches PLAYING");
    }

    void snap(int tick, int seq) {
        t.in.add(snapshot(tick, seq));
        c.update(MS);
    }

    @Test
    @DisplayName("loss, drop, size and lag over snapshot slots")
    void stats() {
        playing();
        PacketStats st = c.packetStats();
        int rate = st.rate();
        for (int tick = 60; tick <= 178; tick += 2) {   // slot 30 starts the window; slot 60 counts the first
            if (tick != 130 && tick != 132) {            // (nothing, as C); slots 65 and 66 are lost
                snap(tick, 0);
            }
        }
        snap(130, 0);                         // a late copy of the lost slot 65
        snap(180, 0);                         // slot 90: counts the window of slots 60..89
        int loss = st.loss();
        int drop = st.drop();
        int seq = c.sendInput(1, 0, 2 * MS);  // sent at slot 90
        snap(182, 0);
        snap(184, 0);
        snap(186, seq);                       // acked while slot 92 was the newest
        assertAll(
            () -> assertEquals(30, rate, "the window is snapshotHz slots"),
            () -> assertEquals(1, loss, "slot 66 never arrived"),
            () -> assertEquals(1, drop, "slot 65 arrived late: a drop"),
            () -> assertEquals(snapshot(186, seq).length, st.size(), "the snapshot datagram size"),
            () -> assertEquals(2, st.lag(), "sent at slot 90, acked at 92: two snapshot intervals"),
            () -> assertSame(st, new com.fulcrologic.jxpilot.client.xp.NativeGameConnection(c, null).packetStats(),
                "the GameConnection wrapper exposes the same stats"));
    }
}
