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
import com.fulcrologic.jxpilot.client.game.hud.FontRole;
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

    /** Per-role fonts as GameApp has them (two baked fonts shared by the roles), at pixel ratio 2. */
    static RoleFontSet roleFonts() {
        com.fulcrologic.jxpilot.client.render.Font serif = Fixtures.font();
        com.fulcrologic.jxpilot.client.render.Font mono = Fixtures.font();
        FontRole[] roles = FontRole.values();
        com.fulcrologic.jxpilot.client.render.Font[] fonts = new com.fulcrologic.jxpilot.client.render.Font[roles.length];
        com.fulcrologic.jxpilot.client.render.FontSpec[] specs = new com.fulcrologic.jxpilot.client.render.FontSpec[roles.length];
        for (FontRole r : roles) {
            specs[r.ordinal()] = RoleFonts.defaultSpec(r);
            fonts[r.ordinal()] = specs[r.ordinal()].face().family
                == com.fulcrologic.jxpilot.client.render.FontSpec.Family.MONO ? mono : serif;
        }
        RoleFontSet s = new RoleFontSet();
        s.set(specs, fonts, 2);
        return s;
    }

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
        RoleFontSet m = roleFonts();
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
        int frames = 26000;
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
            }
            ui(cf, f);
            cf.step(t[0], 1200, 800, 1, m, 256, 200);
            ring.frame++;
        };
        // R7: warm up for 20000 frames
        int warm = 0;
        // The whole warm-up always runs: stopping as soon as the JIT settles left stream events (joins, leaves,
        // first lock changes) unplayed, and their call sites then linked (allocating) inside the measured window.
        while (warm < 20_000) {
            for (int i = 0; i < 1000; i++) {
                frame.run();
            }
            warm += 1000;
        }
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
            () -> assertTrue(cf.viewHud().textCount() > 3, "HUD built"),
            () -> assertTrue(cf.panelHud().textCount() >= 5, "score list built"),
            () -> assertTrue(lockSeen[0] && lockSeen[1], "R7: the lock target changed while measured"),
            () -> assertTrue(reliable >= 9, "R7: robot leaves and rejoins were received while measured (" + reliable
                + " reliable messages)"),
            () -> assertTrue(uzi >= 0 && game.players().shape(uzi).numPoints() == 4,
                "the rejoining robot has its custom shape"),
            () -> assertEquals(0, nz, "no frame allocates in steady state, joins, leaves and lock changes included"
                + " (worst " + w + " bytes)"));
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

    /**
     * client-ui: the C windows every 400 frames: MENU held and moved onto CONFIG, released (CONFIG opens), NEXT and
     * PREV, ABOUT opened, paged (NEXT) and closed (CLOSE), CONFIG closed, a selection dragged over the messages and
     * cleared.
     */
    static void ui(ClientFrame cf, int f) {
        int top = cf.ui().bar().top();
        int bh = cf.ui().bar().buttonHeight();
        int vx = GameApp.PANEL_W + GameApp.PANEL_GAP;
        int entry = top + 1 + bh + (bh + 1) + 5;
        int next = 800 - 6 - 4;
        int about = (1200 - (com.fulcrologic.jxpilot.client.game.ui.AboutWindow.WIDTH + 4)) / 2 + 2;
        int aboutButtons = (800 - (com.fulcrologic.jxpilot.client.game.ui.AboutWindow.HEIGHT + 4)) / 2 + 2 + 665 + 5;
        switch (f % 400) {
            case 0 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 200, top + 5, vx);
            case 5 -> cf.mouseMove(200, entry, vx);
            case 10 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 200, entry, vx);
            case 20 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 128, next, vx);
            case 21 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 128, next, vx);
            case 25 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 230, next, vx);
            case 26 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 230, next, vx);
            case 30 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 128, top + 5, vx);
            case 31 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 128, top + 5, vx);
            case 40 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, vx + about + 282 + 5, aboutButtons, vx);
            case 41 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, vx + about + 282 + 5, aboutButtons, vx);
            case 60 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, vx + about + 10 + 5, aboutButtons, vx);
            case 61 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, vx + about + 10 + 5, aboutButtons, vx);
            case 100 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 10, next, vx);
            case 101 -> cf.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 10, next, vx);
            case 150 -> cf.viewMouse(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 12, 800 - 20);
            case 200 -> cf.viewMouse(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 600, 400);
            default -> {
                if (f % 400 > 150 && f % 400 < 200) {
                    cf.viewMouseMove(12 + (f % 400 - 150), 800 - 20);
                }
            }
        }
    }
}
