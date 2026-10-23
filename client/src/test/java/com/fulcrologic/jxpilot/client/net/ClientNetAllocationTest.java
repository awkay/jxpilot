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

import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.input.KeyBindings;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;

/**
 * The whole client frame in steady state (receive and decode snapshots and reliable messages,
 * interpolate, sparks and debris, particles, scoreboard, messages, HUD layout, key input, INPUT and
 * reliable sends) allocates nothing. Runs in its own surefire execution with escape analysis off.
 */
class ClientNetAllocationTest {
    /** Delegates to a replaceable transport (setup over an allocating link, steady state over a ring). */
    static final class Switch implements Transport {
        Transport target;

        @Override
        public boolean send(ByteBuffer p) throws java.io.IOException {
            return target.send(p);
        }

        @Override
        public int receive(ByteBuffer b) throws java.io.IOException {
            return target.receive(b);
        }

        @Override
        public void close() {
        }
    }

    /** Preloaded datagrams delivered by schedule; sends are swallowed. Allocation free. */
    static final class Ring implements Transport {
        final byte[][] packets;
        final int[] lengths;
        final int[] dueFrame;
        int next;
        int frame;
        long sent;

        Ring(List<byte[]> ps, List<Integer> due) {
            packets = ps.toArray(new byte[0][]);
            lengths = new int[packets.length];
            dueFrame = new int[packets.length];
            for (int i = 0; i < packets.length; i++) {
                lengths[i] = packets[i].length;
                dueFrame[i] = due.get(i);
            }
        }

        @Override
        public boolean send(ByteBuffer p) {
            sent += p.remaining();
            p.position(p.limit());
            return true;
        }

        @Override
        public int receive(ByteBuffer b) {
            if (next >= packets.length || dueFrame[next] > frame) {
                return 0;
            }
            b.put(packets[next], 0, lengths[next]);
            return lengths[next++];
        }

        @Override
        public void close() {
        }
    }

    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    @Test
    void steadyStateClientFrameAllocatesZeroBytes() throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long frameNs = 1_000_000_000L / 60;
        FakeLink link = new FakeLink(1);
        FakeServer server = new FakeServer(link.server());
        Switch sw = new Switch();
        sw.target = link.client();
        ClientSim sim = new ClientSim(sw, "fake", "alloc", 0, 3);
        sim.setKeys(f -> 0L);
        long now = 1_000_000_000L;
        sim.start(now);
        for (int i = 0; i < 120; i++) {
            now += frameNs;
            server.poll(now);
            server.step(now);
            sim.step(now);
        }
        assertEquals(ConnectionState.PLAYING, sim.connection().state(), "joined during setup");

        // Pre-generate the steady-state stream: one server tick per client frame, with a debris burst every
        // 100 ticks and, during the measured part, a chat message and a score change every 120 ticks (30
        // messages: they fit the reliable window, which never gets acks from this capture).
        List<byte[]> packets = new ArrayList<>();
        List<Integer> due = new ArrayList<>();
        int[] frameOf = new int[1];
        FakeServer.Port capture = new FakeServer.Port() {
            @Override
            public boolean send(ByteBuffer b) {
                byte[] a = new byte[b.remaining()];
                b.get(a);
                packets.add(a);
                due.add(frameOf[0]);
                return true;
            }

            @Override
            public int receive(ByteBuffer b) {
                return 0;
            }
        };
        FakeServer gen = server.withPort(capture);
        int frames = 6000;
        long genNow = now;
        for (int f = 0; f < frames; f++) {
            frameOf[0] = f;
            genNow += frameNs;
            if (f >= 3600 && f < 5400 && f % 120 == 0) {
                gen.message(Msg.Text.KIND_CHAT, "chat line " + f + " [Robby]");
                gen.score(FakeServer.OTHER_ID, f / 10f);
            }
            if (f % 100 == 0) {
                gen.burstAtSnapshot = gen.snapshotsSent;
            }
            gen.step(genNow);
        }
        Ring ring = new Ring(packets, due);
        sw.target = ring;

        InputState input = new InputState(KeyBindings.defaults());
        Hud hud = new Hud();
        hud.fuelMeter = true;
        HudFrame hudFrame = new HudFrame();
        TalkLine talk = new TalkLine();
        talk.open();
        talk.type('x');
        Mono metrics = new Mono();
        long[] t = {now};
        Runnable frame = () -> {
            t[0] += frameNs;
            if (ring.frame % 30 == 0) {
                input.press(GLFW_KEY_RIGHT_SHIFT);
                input.press(GLFW_KEY_A);
            } else if (ring.frame % 30 == 15) {
                input.release(GLFW_KEY_RIGHT_SHIFT);
                input.release(GLFW_KEY_A);
            }
            sim.step(t[0]);
            sim.connection().sendInput(input.keys(), 0, t[0]);
            hud.build(hudFrame, sim.game(), talk, 1280, 800, metrics, 14.0 / 60);
            hud.scoreboard(hudFrame, sim.game().scoreboard(), 0, 0, 300, metrics);
            ring.frame++;
        };
        for (int i = 0; i < 4000; i++) {
            frame.run();
        }
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1500; i++) {
            frame.run();
        }
        long after = mx.getCurrentThreadAllocatedBytes();
        assertAll(
            () -> assertEquals(ConnectionState.PLAYING, sim.connection().state(), "still playing"),
            () -> assertTrue(sim.connection().snapshotsReceived() > 2500, "snapshots flowed"),
            () -> assertTrue(sim.game().world().particles().count() > 0, "particles alive"),
            () -> assertTrue(sim.game().messages().talkCount() > 0, "chat arrived"),
            () -> assertTrue(hudFrame.textCount() > 3, "HUD built"),
            () -> assertEquals(0, after - before, "zero bytes allocated per client frame in steady state"));
    }
}
