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

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;

/**
 * The whole client frame in steady state (receive and decode snapshots and reliable messages, the tick
 * clock, interpolation, sparks and debris, particles, score objects, scoreboard, messages, HUD layout,
 * key input, INPUT and reliable sends with acks) allocates nothing.
 * <p>
 * Client and {@link FakeServer} talk in a closed loop over allocation-free datagram queues, so acks flow
 * and reliable traffic never stalls. The server is stepped outside the measured region; each client frame
 * is measured on its own. After {@value #WARMUP} warm-up frames (with frequent server events, so even rarely
 * run paths are compiled), {@value #MEASURED} frames are measured:
 * at most {@value #MAX_NONZERO_FRAMES} frames may allocate (at most {@value #MAX_FRAME_BYTES} bytes each;
 * the JIT occasionally allocates on the running thread when it installs code), and none in the second
 * half, so a periodic leak (even one every 10k frames) fails. Runs in its own surefire execution with
 * escape analysis off.
 */
class ClientNetAllocationTest {
    static final int WARMUP = 60_000;
    static final int MEASURED = 100_000;
    static final int MAX_NONZERO_FRAMES = 3;
    static final long MAX_FRAME_BYTES = 1024;

    /** One direction of a datagram link: a ring of preallocated buffers. Allocation free. */
    static final class Queue {
        final ByteBuffer[] slots = new ByteBuffer[1024];
        int head;
        int tail;

        Queue() {
            for (int i = 0; i < slots.length; i++) {
                slots[i] = ByteBuffer.allocate(2048);
            }
        }

        boolean put(ByteBuffer b) {
            if (tail - head >= slots.length) {
                b.position(b.limit());
                return true; // full: dropped like UDP
            }
            ByteBuffer s = slots[tail++ & (slots.length - 1)];
            s.clear();
            s.put(b);
            s.flip();
            return true;
        }

        int take(ByteBuffer into) {
            if (head == tail) {
                return 0;
            }
            ByteBuffer s = slots[head++ & (slots.length - 1)];
            int n = s.remaining();
            into.put(s);
            return n;
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
        Queue toServer = new Queue();
        Queue toClient = new Queue();
        Transport client = new Transport() {
            @Override
            public boolean send(ByteBuffer p) {
                return toServer.put(p);
            }

            @Override
            public int receive(ByteBuffer b) {
                return toClient.take(b);
            }

            @Override
            public void close() {
            }
        };
        FakeServer server = new FakeServer(new FakeServer.Port() {
            @Override
            public boolean send(ByteBuffer b) {
                return toClient.put(b);
            }

            @Override
            public int receive(ByteBuffer b) {
                return toServer.take(b);
            }
        });
        ClientSim sim = new ClientSim(client, "fake", "alloc", 0, 3);
        InputState input = new InputState(KeyBindings.defaults());
        sim.setKeys(f -> input.keys());
        Hud hud = new Hud();
        hud.fuelMeter = true;
        HudFrame hudFrame = new HudFrame();
        TalkLine talk = new TalkLine();
        talk.open();
        talk.type('x');
        Mono metrics = new Mono();
        String[] chat = new String[64];
        for (int i = 0; i < chat.length; i++) {
            chat[i] = "chat line " + i + " [Robby]";
        }
        long frameNs = 1_000_000_000L / 60;
        long now = 1_000_000_000L;
        sim.start(now);

        int total = WARMUP + MEASURED;
        int nonZero = 0;
        long worst = 0;
        int lastNonZero = -1;
        long sum = 0;
        for (int f = 0; f < total; f++) {
            now += frameNs;
            // ---- server, not measured: one 60 Hz tick per frame, events now and then
            server.poll(now);
            // events every 2 s; every 2nd frame during warm-up, so the rarely run paths (scoreboard rebuild,
            // message log, score objects) pass the JIT's compile thresholds (~15k calls) before measuring:
            // the JVM allocates a few bytes on this thread once when it compiles some of them
            int period = f < WARMUP ? 2 : 120;
            if (server.ready) {
                if (f % 100 == 0) {
                    server.burstAtSnapshot = server.snapshotsSent;
                }
                if (f % period == 0) {
                    server.message(Msg.Text.KIND_CHAT, chat[(f / period) & 63]);
                    server.score(FakeServer.OTHER_ID, f / 10f);
                }
                if (f % (2 * period) == period / 2) {
                    server.gameEvent(Msg.GameEvent.KILL, FakeServer.PLAYER_ID, FakeServer.OTHER_ID);
                    server.score(FakeServer.PLAYER_ID, (f / (2 * period)) % 7 + 1);
                }
            }
            server.step(now);
            // ---- client frame, measured
            long a = mx.getCurrentThreadAllocatedBytes();
            if (f % 30 == 0) {
                input.press(GLFW_KEY_RIGHT_SHIFT);
                input.press(GLFW_KEY_A);
                input.press(GLFW_KEY_ENTER);
            } else if (f % 30 == 15) {
                input.release(GLFW_KEY_RIGHT_SHIFT);
                input.release(GLFW_KEY_A);
                input.release(GLFW_KEY_ENTER);
            }
            sim.step(now);
            hud.build(hudFrame, sim.game(), talk, 1280, 800, metrics, 14.0 / 60);
            hud.scoreboard(hudFrame, sim.game().scoreboard(), 0, 0, 300, metrics);
            long d = mx.getCurrentThreadAllocatedBytes() - a;
            if (f >= WARMUP && d != 0) {
                nonZero++;
                sum += d;
                worst = Math.max(worst, d);
                lastNonZero = f;
            }
        }
        int fNonZero = nonZero;
        long fWorst = worst;
        int fLast = lastNonZero;
        String detail = nonZero + " frames allocated " + sum + " B, worst " + worst + " B, last at frame " + lastNonZero;
        assertAll(
            () -> assertEquals(ConnectionState.PLAYING, sim.connection().state(), "still playing"),
            () -> assertTrue(sim.connection().snapshotsReceived() > total / 2 - 100, "snapshots flowed"),
            () -> assertTrue(server.inputPackets > 0, "INPUT reached the server"),
            () -> assertTrue(sim.game().world().particles().count() > 0, "particles alive"),
            () -> assertTrue(sim.game().messages().talkCount() > 0, "chat arrived"),
            () -> assertTrue(hudFrame.textCount() > 3, "HUD built"),
            () -> assertTrue(server.channel.unacked() < 8, "the server's reliable messages are acked"),
            () -> assertTrue(sim.game().scoreObjects().text(0).length() > 0, "own score objects were made"),
            () -> assertTrue(fNonZero <= MAX_NONZERO_FRAMES, "at most 3 allocating frames: " + detail),
            () -> assertTrue(fWorst <= MAX_FRAME_BYTES, "each at most 1 KB: " + detail),
            () -> assertTrue(fLast < WARMUP + MEASURED / 2, "none in the second half: " + detail));
    }
}
