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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.net.ClientConnection;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.xp.NativeGameConnection;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * The whole client frame minus GL allocates nothing in steady state: connection update (receive and
 * decode snapshots and reliable messages), game frame (interpolation, sparks, debris), INPUT and flush,
 * scene and radar fill, HUD layout, HUD extras, score list and key routing, against the scripted server
 * on a real map, with a robot leaving and rejoining (custom shape) and the lock target changing inside the
 * measured window (R7), after a warm-up that waits for the JIT to settle. A map change is not scripted: it
 * reconfigures the world and allocates by design. Runs in its own surefire execution with escape analysis off.
 */
class AppAllocationTest {
    /** Delegates to a replaceable transport (setup over an allocating link, steady state over a ring). */
    static final class Switch implements Transport {
        Transport target;

        @Override public boolean send(ByteBuffer p) throws java.io.IOException { return target.send(p); }

        @Override public int receive(ByteBuffer b) throws java.io.IOException { return target.receive(b); }

        @Override public void close() { }
    }

    /** Preloaded datagrams delivered by frame; sends are swallowed. Allocation free. */
    static final class Ring implements Transport {
        final byte[][] packets;
        final int[] dueFrame;
        int next;
        int frame;

        Ring(List<byte[]> ps, List<Integer> due) {
            packets = ps.toArray(new byte[0][]);
            dueFrame = new int[packets.length];
            for (int i = 0; i < packets.length; i++) {
                dueFrame[i] = due.get(i);
            }
        }

        @Override
        public boolean send(ByteBuffer p) {
            p.position(p.limit());
            return true;
        }

        @Override
        public int receive(ByteBuffer b) {
            if (next >= packets.length || dueFrame[next] > frame) {
                return 0;
            }
            b.put(packets[next]);
            return packets[next++].length;
        }

        @Override public void close() { }
    }

    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    /** Most warm-up frames before measuring (the recorded server stream covers them). */
    static final int WARM_MAX = 30_000;

    @Test
    void steadyStateFrameWithoutGlAllocatesZeroBytes() throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long frameNs = 1_000_000_000L / 60;
        ArrayDeque<byte[]> toServer = new ArrayDeque<>();
        ArrayDeque<byte[]> toClient = new ArrayDeque<>();
        ScriptedServer server = new ScriptedServer(Fixtures.MAPS.resolve("default.xp"), new ScriptedServer.Port() {
            @Override
            public boolean send(ByteBuffer b) {
                toClient.add(copy(b));
                return true;
            }

            @Override
            public int receive(ByteBuffer b) {
                return deliver(toServer, b);
            }
        });
        Switch sw = new Switch();
        sw.target = new Transport() {
            @Override
            public boolean send(ByteBuffer p) {
                toServer.add(copy(p));
                return true;
            }

            @Override
            public int receive(ByteBuffer b) {
                return deliver(toClient, b);
            }

            @Override public void close() { }
        };
        ClientGame game = new ClientGame();
        ClientConnection cc = GameConnector.nativeConnection(sw, "alloc", game, "Alloc", 0xFF, "");
        NativeGameConnection conn = new NativeGameConnection(cc, sw);
        ClientFrame cf = new ClientFrame(game, conn, new InputState(KeyRouter.bindings(null)), new SceneData(),
            new RadarBlips(1024));
        Mono m = new Mono();
        long serverPeriod = 1_000_000_000L / server.fps;
        long now = 1_000_000_000L;
        long nextTick = now;
        conn.start(now);
        for (int i = 0; i < 600; i++) {
            now += frameNs;
            server.poll(now);
            while (nextTick <= now) {
                server.step(now);
                nextTick += serverPeriod;
            }
            cf.step(now, 1200, 800, 1, m, 256, 200);
        }
        assertEquals(ConnectionState.PLAYING, conn.state(), "joined during setup");
        // capture the server's stream for the steady state, one entry per client frame
        List<byte[]> packets = new ArrayList<>();
        List<Integer> due = new ArrayList<>();
        int[] frameOf = new int[1];
        server.withPort(new ScriptedServer.Port() {
            @Override
            public boolean send(ByteBuffer b) {
                packets.add(copy(b));
                due.add(frameOf[0]);
                return true;
            }

            @Override
            public int receive(ByteBuffer b) {
                return 0;
            }
        });
        int frames = WARM_MAX + 1500 + 4500; // the warm-up, the measured frames and slack: the ring must not run dry
        long genNow = now;
        long genTick = nextTick;
        for (int f = 0; f < frames; f++) {
            frameOf[0] = f;
            genNow += frameNs;
            while (genTick <= genNow) {
                server.step(genNow);
                // the recorded client acks everything at once, so new reliable messages keep flowing
                server.channel.onAck(server.channel.nextSequence() & 0xFFFF, genNow);
                genTick += serverPeriod;
            }
        }
        Ring ring = new Ring(packets, due);
        sw.target = ring;
        // client-play: pointer control on (keyPointerControl), so pointer movement and buttons run every frame
        cf.key(GLFW_KEY_KP_ENTER, GLFW_PRESS, 0, now);
        cf.key(GLFW_KEY_KP_ENTER, GLFW_RELEASE, 0, now);
        long[] t = {now};
        Runnable frame = () -> {
            t[0] += frameNs;
            int f = ring.frame;
            if (f % 40 == 0) {
                cf.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, t[0]);
                cf.key(GLFW_KEY_A, GLFW_PRESS, 0, t[0]);
                cf.key(GLFW_KEY_ENTER, GLFW_PRESS, 0, t[0]);
            } else if (f % 40 == 20) {
                cf.key(GLFW_KEY_RIGHT_SHIFT, GLFW_RELEASE, 0, t[0]);
                cf.key(GLFW_KEY_A, GLFW_RELEASE, 0, t[0]);
                cf.key(GLFW_KEY_ENTER, GLFW_RELEASE, 0, t[0]);
            } else if (f % 40 == 5) {
                cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, t[0]);
                cf.key(GLFW_KEY_U, GLFW_PRESS, 0, t[0]); // keyIdMode: the score list rebuilds
            } else if (f % 40 == 25) {
                cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, t[0]);
                cf.key(GLFW_KEY_U, GLFW_RELEASE, 0, t[0]);
                cf.scroll(-1, t[0]);
            }
            cf.cursor(600 + (f % 9) * 7, 400 + (f % 5)); // pointer steering every frame
            cf.step(t[0], 1200, 800, 1, m, 256, 200);
            ring.frame++;
        };
        // R7: warm up until the JIT is done: no compilation (JFR jdk.Compilation) in 2 chunks of 1000 frames in a row
        // (see JitQuiet: a late C2 request resolves its class's String constants on this thread)
        int warm;
        try (com.fulcrologic.jxpilot.client.JitQuiet jit = new com.fulcrologic.jxpilot.client.JitQuiet()) {
            warm = jit.warm(frame, 1000, 4000, WARM_MAX, 2);
            System.out.println("AppAllocationTest: warm-up " + warm + " frames, " + jit.compiles() + " compilations");
        }
        int warmFrames = warm;
        long reliableBefore = game.reliableCount();
        long snapshotsBefore = conn.snapshotsReceived();
        // per frame (as XpNetAllocationTest): a bracket around the whole loop also catches the occasional
        // allocation of the thread MXBean / JIT between frames (152-byte multiples, not this code)
        int allocatingFrames = 0;
        long worst = 0;
        boolean[] lockSeen = new boolean[2];
        for (int i = 0; i < 1500; i++) {
            long b0 = mx.getCurrentThreadAllocatedBytes();
            frame.run();
            long d = mx.getCurrentThreadAllocatedBytes() - b0;
            if (d > 0) {
                allocatingFrames++;
                worst = Math.max(worst, d);
            }
            int lock = game.world().selfLockId();
            lockSeen[0] |= lock == ScriptedServer.ROBOTS[1];
            lockSeen[1] |= lock == ScriptedServer.ROBOTS[2];
        }
        long reliable = game.reliableCount() - reliableBefore;
        int uzi = game.players().slotOf(ScriptedServer.REJOINER);
        int nz = allocatingFrames;
        long w = worst;
        assertAll(
            () -> assertEquals(ConnectionState.PLAYING, conn.state(), "still playing"),
            () -> assertTrue(conn.snapshotsReceived() - snapshotsBefore >= 250, "snapshots flowed while measured (12 fps for 25 s)"),
            () -> assertTrue(cf.scene().shipCount >= 4, "ships in the scene"),
            () -> assertTrue(cf.scene().shotCount > 0, "shots in the scene"),
            () -> assertTrue(game.world().particles().count() > 0, "particles alive"),
            () -> assertTrue(cf.radar().count() > 0, "radar blips"),
            () -> assertTrue(cf.extras().lock, "lock shown"),
            () -> assertTrue(cf.play().pointer().on(), "pointer control was on while measured"),
            () -> assertTrue(cf.viewHud().textCount() > 3, "HUD built"),
            () -> assertTrue(cf.panelHud().textCount() >= 5, "score list built"),
            () -> assertTrue(lockSeen[0] && lockSeen[1], "R7: the lock target changed while measured"),
            () -> assertTrue(reliable >= 9, "R7: robot leaves and rejoins were received while measured (" + reliable
                + " reliable messages)"),
            () -> assertTrue(uzi >= 0 && game.players().shape(uzi).numPoints() == 4,
                "the rejoining robot has its custom shape"),
            () -> assertEquals(0, nz, "no frame allocates in steady state, joins, leaves and lock changes included"
                + " (worst " + w + " bytes, after " + warmFrames + " warm-up frames)"));
    }

    static byte[] copy(ByteBuffer b) {
        byte[] a = new byte[b.remaining()];
        b.get(a);
        return a;
    }

    static int deliver(ArrayDeque<byte[]> q, ByteBuffer into) {
        byte[] a = q.pollFirst();
        if (a == null) {
            return 0;
        }
        into.put(a);
        return a.length;
    }
}
