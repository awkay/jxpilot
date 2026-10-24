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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Join, map transfer and play through a link that drops and reorders datagrams both ways. */
@DisplayName("ServerNet over a lossy, reordering link")
class LossyLinkTest {
    static final InetSocketAddress A = Harness.addr("10.0.0.1", 4000);

    /** Drops with probability {@code loss}; delays survivors by 0..maxDelay ticks (reordering them). */
    static final class LossyLink {
        record InFlight(long dueTick, ByteBuffer data) {}

        final SplittableRandom rng;
        final double loss;
        final int maxDelay;
        final List<InFlight> queue = new ArrayList<>();
        int dropped;
        int carried;

        LossyLink(long seed, double loss, int maxDelay) {
            this.rng = new SplittableRandom(seed);
            this.loss = loss;
            this.maxDelay = maxDelay;
        }

        void push(long tick, ByteBuffer b) {
            if (rng.nextDouble() < loss) {
                dropped++;
                return;
            }
            carried++;
            queue.add(new InFlight(tick + rng.nextInt(maxDelay + 1), b));
        }

        /** Datagrams due at {@code tick}, in a shuffled order. */
        List<ByteBuffer> due(long tick) {
            List<ByteBuffer> out = new ArrayList<>();
            for (Iterator<InFlight> it = queue.iterator(); it.hasNext(); ) {
                InFlight f = it.next();
                if (f.dueTick() <= tick) {
                    out.add(f.data());
                    it.remove();
                }
            }
            for (int i = out.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                ByteBuffer t = out.get(i);
                out.set(i, out.get(j));
                out.set(j, t);
            }
            return out;
        }
    }

    @Test
    @DisplayName("20% loss and reordering: map, chat, events and inputs all arrive correctly")
    void lossy() {
        String text = ServerNetTest.randomText(40_000, 11);
        Harness h = new Harness(new ServerNetConfig(), text);
        LossyLink up = new LossyLink(1, 0.2, 2);
        LossyLink down = new LossyLink(2, 0.2, 2);
        TestClient c = new TestClient(42);
        c.serverTick = h.net::currentTick;
        // Redundant copies only help while their tick is still ahead: with a lead of L ticks an entry rides in the
        // L packets sent before its tick, so a 6-tick lead over 20% loss and a 0..2-tick delay leaves ~0.05%
        // of ticks without any copy in time (a 3-tick lead leaves ~7%).
        c.lead = 6;
        long t;
        int sentInputs = 0;
        int chatSent = 0;
        for (t = 0; t < 2400; t++) {
            if (!c.accepted && t % 15 == 0) {
                up.push(t, c.connect("lossy", 255, ""));
            }
            if (c.hasMap() && !c.readySent) {
                c.ready();
            }
            if (c.readySent && t % 30 == 0 && chatSent < 20) {
                c.chat("line " + chatSent++);
            }
            if (t == 1200) {
                for (int i = 0; i < 10; i++) {
                    h.host.pendingChat.add("event " + i);
                }
            }
            for (ByteBuffer b : c.frame(h.now, t)) {
                up.push(t, b);
                sentInputs += b.get(3) == 5 ? 1 : 0;
            }
            for (ByteBuffer b : up.due(t)) {
                h.in(0, A, b);
            }
            h.tick();
            for (Harness.Sent s : h.take(A)) {
                down.push(t, s.data());
            }
            for (ByteBuffer b : down.due(t)) {
                c.onPacket(b, h.now);
            }
        }
        List<String> chats = h.host.callsStartingWith("msg 0 CHAT");
        List<String> expectedChats = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expectedChats.add("msg 0 CHAT line " + i);
        }
        List<String> events = new ArrayList<>();
        for (String s : c.reliableLog) {
            if (s.startsWith("MESSAGE")) {
                events.add(s);
            }
        }
        List<String> expectedEvents = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            expectedEvents.add("MESSAGE event " + i);
        }
        List<Long> ticks = new ArrayList<>();
        for (String s : h.host.callsStartingWith("input")) {
            ticks.add(Long.parseLong(s.substring(s.indexOf("tick=") + 5, s.indexOf(" keys"))));
        }
        boolean inc = true;
        for (int i = 1; i < ticks.size(); i++) {
            inc &= ticks.get(i) == ticks.get(i - 1) + 1;
        }
        boolean increasing = inc;
        long starved = h.net.inputStarvations(c.playerId);
        int inputsSent = sentInputs;
        int calls = ticks.size();
        int snapshots = c.snapshotTicks.size();
        assertAll(
            () -> assertTrue(up.dropped > 100 && down.dropped > 100, "the link really dropped packets both ways"),
            () -> assertEquals(text, c.configText, "the map arrives intact"),
            () -> assertEquals(1, h.host.callsStartingWith("msg 0 type=64").size(), "READY reaches the host exactly once"),
            () -> assertEquals(expectedChats, chats, "every chat line reaches the host once, in order"),
            () -> assertEquals(expectedEvents, events, "every server event reaches the client once, in order"),
            () -> assertTrue(increasing, "the host gets one input per tick, on consecutive ticks"),
            () -> assertTrue(calls > 2000 && inputsSent > 2000, "inputs flowed (" + calls + " calls, " + inputsSent + " sent)"),
            () -> assertTrue(starved <= 10, "the redundancy recovers nearly every entry (" + starved + " starved ticks)"),
            () -> assertTrue(snapshots > 600, "most snapshots arrive (" + snapshots + ")"),
            () -> assertEquals(1, h.net.connections(), "the session survives"),
            () -> assertEquals(0, h.net.malformed(), "nothing was considered malformed"));
    }
}
