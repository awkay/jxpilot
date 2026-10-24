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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.xp.GameConnection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Dev harness (not a test): the real {@link GameApp} with scripted keys and a series of screenshots, so a
 * run against a live server self-terminates and leaves PNGs to look at.
 * <pre>
 *   DevRun [ClientMain --connect options...] --out DIR --start F --every N --count K [--script NAME]
 * </pre>
 * Screenshots {@code DIR/shot-NN.png} are taken after frames F, F+N, ... (K of them); the run ends after
 * the last. {@code --scripted-server MAP} first starts a {@link ScriptedServer} for MAP on loopback UDP and
 * connects to it. Scripts: {@code fly} (from frame 60: thrust, turn left, fire in bursts), {@code talk} (opens the
 * talk line at frame 40 and types a line, leaves it open), {@code chat} (as talk, then sends it at frame
 * 160), {@code circle} (thrust from frame 60 with periodic left turns and fire), {@code play} (pointer control
 * steering, F2 macro, ID mode, Print), {@code none}.
 * Frame numbers count from the first frame with the own ship; {@code --cap-fps N} (default 120) paces
 * frames when vsync does not (hidden windows). {@code --grab-from F --grab-count K} reads back K consecutive
 * frames (the view centre) without stalling and writes {@code DIR/grab-NN.png} at the end. {@code --trace FILE} writes per-frame timing and positions and snapshot arrivals ({@link FrameTrace}).
 */
public final class DevRun {
    private DevRun() {}

    public static void main(String[] args) throws Exception {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        Path out = Path.of(take(rest, "--out", "target/screens/app"));
        int start = Integer.parseInt(take(rest, "--start", "120"));
        int every = Integer.parseInt(take(rest, "--every", "10"));
        int count = Integer.parseInt(take(rest, "--count", "1"));
        String script = take(rest, "--script", "none");
        String scripted = take(rest, "--scripted-server", null);
        if (scripted != null) {
            int port = ScriptedServer.startUdp(Path.of(scripted));
            rest.add("--connect");
            rest.add("127.0.0.1:" + port);
        }
        String traceFile = take(rest, "--trace", null);
        String capFps = take(rest, "--cap-fps", "120");
        int[] grabStarts = Arrays.stream(take(rest, "--grab-from", "-1").split(",")).mapToInt(Integer::parseInt).toArray();
        int grabCount = Integer.parseInt(take(rest, "--grab-count", "36"));
        java.util.List<byte[]> grabs = new java.util.ArrayList<>();
        int[] grabSize = new int[2];
        AppOptions o = AppOptions.parse(rest.toArray(new String[0]), System.getProperty("user.name"));
        com.fulcrologic.jxpilot.client.game.input.KeyConfig keys = AppLauncher.keyConfig(o,
            Path.of(System.getProperty("user.home", ".")), System.err);
        ClientGame game = new ClientGame();
        FrameTrace trace = traceFile == null ? null : new FrameTrace(game);
        GameConnection conn = GameConnector.open(o.target(), o.bind(), trace != null ? trace : game, o.name(),
            o.team(), o.shape(), o.sound() && AppLauncher.maxVolume(o, keys) > 0, (int) System.nanoTime());
        int last = start + every * (count - 1);
        long cap = 1_000_000_000L / Integer.parseInt(capFps);
        int[] base = {-1};
        long[] lastFrame = {0};
        GameApp.Script s = new GameApp.Script() {
            @Override
            public void beforeFrame(int frame, ClientFrame f, long now) {
                long wait = lastFrame[0] + cap - System.nanoTime();
                if (wait > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(wait); // vsync stand-in when hidden
                }
                lastFrame[0] = System.nanoTime();
                if (base[0] < 0 && f.game().world().hasSelf()) {
                    base[0] = frame;
                }
                if (base[0] >= 0) {
                    run(script, frame - base[0], f, now);
                }
            }

            @Override
            public void afterFrame(int frame, ClientFrame f, long now) {
                if (trace != null && base[0] >= 0) {
                    trace.frame(frame - base[0], f, now);
                }
            }

            @Override
            public int[] grabAfter(int frame, int fbW, int fbH) {
                int k = frame - base[0];
                boolean in = false;
                for (int g : grabStarts) {
                    in |= g >= 0 && k >= g && k < g + grabCount;
                }
                if (base[0] < 0 || !in) {
                    return null;
                }
                int w = Math.min(1200, fbW - 516);
                int h = Math.min(800, fbH);
                return new int[] {516 + (fbW - 516 - w) / 2, (fbH - h) / 2, w, h};
            }

            @Override
            public void grabbed(int frame, java.nio.ByteBuffer rgba, int w, int h) {
                byte[] b = new byte[rgba.remaining()];
                rgba.get(b);
                grabs.add(b);
                grabSize[0] = w;
                grabSize[1] = h;
            }

            @Override
            public Path screenshotAfter(int frame) {
                int k = frame - base[0];
                if (base[0] < 0 || k < start || (k - start) % every != 0 || k > last) {
                    return null;
                }
                return out.resolve(String.format("shot-%02d.png", (k - start) / every));
            }

            @Override
            public boolean done(int frame) {
                return base[0] >= 0 && frame - base[0] >= Math.max(last, grabStarts[grabStarts.length - 1] + grabCount);
            }
        };
        new GameApp(o, game, conn, o.target().toString(), s, keys).run();
        if (trace != null) {
            trace.write(Path.of(traceFile));
        }
        for (int i = 0; i < grabs.size(); i++) {
            java.nio.file.Files.createDirectories(out);
            java.nio.ByteBuffer px = org.lwjgl.system.MemoryUtil.memAlloc(grabs.get(i).length);
            px.put(grabs.get(i)).flip();
            org.lwjgl.stb.STBImageWrite.stbi_flip_vertically_on_write(true);
            org.lwjgl.stb.STBImageWrite.stbi_write_png(out.resolve(String.format("grab-%03d.png", i)).toString(),
                grabSize[0], grabSize[1], 4, px, grabSize[0] * 4);
            org.lwjgl.system.MemoryUtil.memFree(px);
        }
        System.exit(0);
    }

    static void run(String script, int frame, ClientFrame f, long now) {
        switch (script) {
            case "fly" -> {
                if (frame == 60) {
                    f.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, now);
                    f.key(GLFW_KEY_A, GLFW_PRESS, 0, now);
                }
                if (frame == 75) {
                    f.key(GLFW_KEY_A, GLFW_RELEASE, 0, now);
                }
                if (frame >= 60 && frame % 12 == 0) {
                    f.key(GLFW_KEY_ENTER, GLFW_PRESS, 0, now);
                }
                if (frame >= 60 && frame % 12 == 6) {
                    f.key(GLFW_KEY_ENTER, GLFW_RELEASE, 0, now);
                }
                if (frame == 140) {
                    f.key(GLFW_KEY_RIGHT_SHIFT, GLFW_RELEASE, 0, now);
                    f.key(GLFW_KEY_S, GLFW_PRESS, 0, now);
                }
                if (frame == 160) {
                    f.key(GLFW_KEY_S, GLFW_RELEASE, 0, now);
                    f.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, now);
                }
            }
            case "circle" -> {
                if (frame == 60) {
                    f.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS, 0, now);
                }
                if (frame >= 60 && frame % 30 == 0) {
                    f.key(GLFW_KEY_A, GLFW_PRESS, 0, now);
                }
                if (frame >= 60 && frame % 30 == 12) {
                    f.key(GLFW_KEY_A, GLFW_RELEASE, 0, now);
                }
                if (frame >= 60 && frame % 10 == 0) {
                    f.key(GLFW_KEY_ENTER, GLFW_PRESS, 0, now);
                }
                if (frame >= 60 && frame % 10 == 5) {
                    f.key(GLFW_KEY_ENTER, GLFW_RELEASE, 0, now);
                }
            }
            case "ui" -> {
                // client-ui: MENU held over CONFIG (shot at 35), CONFIG page 0 (45), NEXT (65), ABOUT (85)
                int top = f.ui().bar().top();
                int bh = f.ui().bar().buttonHeight();
                double viewX = GameApp.PANEL_W + GameApp.PANEL_GAP;
                if (frame == 30) {
                    f.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 200, top + 5, (int) viewX);
                }
                if (frame == 32) {
                    f.mouseMove(200, top + 1 + bh + (bh + 1) + 5, (int) viewX);
                }
                if (frame == 36) {
                    f.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 200, top + 1 + bh + (bh + 1) + 5, (int) viewX);
                }
                double nextY = 800 - 6 - 4;
                if (frame == 50) {
                    f.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 128, nextY, (int) viewX);
                }
                if (frame == 51) {
                    f.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 128, nextY, (int) viewX);
                }
                if (frame == 70) {
                    f.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 128, top + 5, (int) viewX);
                }
                if (frame == 71) {
                    f.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 128, top + 5, (int) viewX);
                }
            }
            case "talk", "chat" -> {
                if (frame == 40) {
                    f.key(GLFW_KEY_M, GLFW_PRESS, 0, now);
                    f.character('m');
                    f.key(GLFW_KEY_M, GLFW_RELEASE, 0, now);
                }
                String line = "hello from jxpilot";
                if (frame > 40 && frame <= 40 + line.length()) {
                    f.character(line.charAt(frame - 41));
                }
                if (script.equals("chat") && frame == 160) {
                    f.key(GLFW_KEY_ENTER, GLFW_PRESS, 0, now);
                }
            }
            case "turnspeed" -> {
                // U13: F10/F9 (turn speed) and F12/F11 (power) from the user's ~/.xpilotrc; the SELF values are the
                // server's (C displayedTurnspeed), so they show what the C server now uses.
                if (frame % 20 == 0 && frame <= 200) {
                    var w = f.game().world();
                    System.out.printf("[turnspeed] frame=%d server turnspeed=%d power=%d client turnSpeed=%.2f power=%.2f%n",
                        frame, w.selfTurnSpeed(), w.selfPower(), f.settings().turnSpeed, f.settings().power);
                }
                tap(f, frame, 40, GLFW_KEY_F10, now);
                tap(f, frame, 50, GLFW_KEY_F10, now);
                tap(f, frame, 60, GLFW_KEY_F10, now);
                tap(f, frame, 100, GLFW_KEY_F12, now);
                tap(f, frame, 140, GLFW_KEY_F9, now);
                tap(f, frame, 160, GLFW_KEY_F11, now);
            }
            case "mods" -> {
                // U11: toggle n c i v x z b l, one every 20 frames from frame 40; shots 18 frames after each (--start 58 --every 20)
                int[] keys = {GLFW_KEY_N, GLFW_KEY_C, GLFW_KEY_I, GLFW_KEY_V, GLFW_KEY_X, GLFW_KEY_Z, GLFW_KEY_B, GLFW_KEY_L};
                for (int k = 0; k < keys.length; k++) {
                    tap(f, frame, 40 + 20 * k, keys[k], now);
                }
                if (frame >= 58 && (frame - 58) % 20 == 0) {
                    com.fulcrologic.jxpilot.client.game.CharBuf m = new com.fulcrologic.jxpilot.client.game.CharBuf(64);
                    com.fulcrologic.jxpilot.client.game.Modifiers.format(f.game().world().selfMods(), m);
                    System.out.println("[mods] frame=" + frame + " modifiers='" + m + "'");
                }
            }
            case "help" -> tap(f, frame, 20, GLFW_KEY_F1, now);
            case "play" -> {
                // client-play H3: pointer control steers (KP_Enter, then the pointer moves right), F2 sends msg2,
                // u shows real names, Print dumps the messages to stdout
                var w = f.game().world();
                tap(f, frame, 30, GLFW_KEY_KP_ENTER, now);
                if (frame == 30) {
                    System.out.printf("[play] frame=%d pointer=%s heading=%.3f%n", frame, f.play().pointer().on(),
                        w.selfHeading());
                }
                if (frame > 30 && frame <= 60) {
                    f.cursor(f.play().pointer().mouseX() + 4, f.play().pointer().mouseY());
                }
                if (frame == 61 || frame == 90) {
                    System.out.printf("[play] frame=%d heading=%.3f%n", frame, w.selfHeading());
                }
                tap(f, frame, 70, GLFW_KEY_F2, now);
                tap(f, frame, 100, GLFW_KEY_U, now);
                tap(f, frame, 130, GLFW_KEY_PRINT_SCREEN, now);
            }
            case "refuel" -> {
                // U12: fly to the nearest fuel station, hold refuel (f) once within 80 px
                var w = f.game().world();
                var map = f.game().map();
                if (!w.hasSelf() || map == null || map.fuels().isEmpty()) {
                    return;
                }
                if (PILOT.targetX < 0) {
                    // the nearest station with three free blocks in a row beside it; aim at the first of them
                    double best = Double.MAX_VALUE;
                    int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
                    for (var fs : map.fuels()) {
                        for (int[] dd : dirs) {
                            boolean open = true;
                            for (int k = 1; k <= 3; k++) {
                                open &= map.blockAt(fs.bx() + dd[0] * k, fs.by() + dd[1] * k)
                                    == com.fulcrologic.jxpilot.common.map.BlockType.SPACE;
                            }
                            double tx = fs.x() + dd[0] * 35;
                            double ty = fs.y() + dd[1] * 35;
                            double d = Math.hypot(Pilot.dx(w, tx), Pilot.dy(w, ty));
                            if (open && d < best) {
                                best = d;
                                PILOT.targetX = tx;
                                PILOT.targetY = ty;
                                PILOT.stationX = fs.x();
                                PILOT.stationY = fs.y();
                            }
                        }
                    }
                }
                PILOT.steer(f, 0, now);
                double dist = Math.hypot(Pilot.dx(w, PILOT.stationX), Pilot.dy(w, PILOT.stationY));
                PILOT.hold(f, GLFW_KEY_F, dist < 80, now);
                if (frame % 20 == 0 || dist < 80) {
                    int beams = 0;
                    for (int i = 0; i < f.scene().linkCount; i++) {
                        if (f.scene().linkKind[i] == com.fulcrologic.jxpilot.client.paint.SceneData.LINK_REFUEL) {
                            beams++;
                        }
                    }
                    System.out.printf("[refuel] frame=%d dist=%.0f fuel=%.0f connectors=%d refuel beams=%d%n", frame,
                        dist, w.selfFuel(), w.objects().count(com.fulcrologic.jxpilot.client.game.ObjectLists.CONNECTOR),
                        beams);
                }
            }
            case "ball" -> {
                // U2/U5: fly to the nearest ball, hold the connector (Control_L), pull away until it attaches
                // (C: at ballConnectorLength), fly with it for 150 frames, then drop it (d)
                var w = f.game().world();
                if (!w.hasSelf()) {
                    return;
                }
                var o = w.objects();
                int kBall = com.fulcrologic.jxpilot.client.game.ObjectLists.BALL;
                int balls = o.count(kBall);
                int conns = o.count(com.fulcrologic.jxpilot.client.game.ObjectLists.CONNECTOR);
                boolean holding = false;
                double bx = -1;
                double by = -1;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < balls; i++) {
                    if ((int) o.get(kBall, i, 3) == w.selfId()) {
                        holding = true;
                    }
                    double d = Math.hypot(Pilot.dx(w, o.get(kBall, i, 0)), Pilot.dy(w, o.get(kBall, i, 1)));
                    if (d < best) {
                        best = d;
                        bx = o.get(kBall, i, 0);
                        by = o.get(kBall, i, 1);
                    }
                }
                if (BALL_DROP_FRAME[0] >= 0 && frame >= BALL_DROP_FRAME[0]) {
                    if (frame == BALL_DROP_FRAME[0]) {
                        PILOT.release(f, now);
                        f.key(GLFW_KEY_D, GLFW_PRESS, 0, now);
                        System.out.println("[ball] drop at frame " + frame);
                    } else if (frame == BALL_DROP_FRAME[0] + 3) {
                        f.key(GLFW_KEY_D, GLFW_RELEASE, 0, now);
                    }
                } else if (holding) {
                    if (!PILOT.attached) {
                        PILOT.attached = true;
                        BALL_DROP_FRAME[0] = frame + 150;
                        PILOT.targetX = w.selfX() - 500;
                        PILOT.targetY = w.selfY() + 200;
                        System.out.println("[ball] attached at frame " + frame);
                    }
                    PILOT.hold(f, GLFW_KEY_LEFT_CONTROL, false, now);
                    PILOT.steer(f, 0, now);
                } else if (bx >= 0) {
                    if (conns > 0 && best < 200) {
                        // pull away from the ball along the ball-to-ship line
                        double ux = -Pilot.dx(w, bx) / Math.max(1, best);
                        double uy = -Pilot.dy(w, by) / Math.max(1, best);
                        PILOT.targetX = bx + ux * 400;
                        PILOT.targetY = by + uy * 400;
                    } else {
                        PILOT.targetX = bx;
                        PILOT.targetY = by;
                    }
                    PILOT.steer(f, conns > 0 ? 0 : 60, now);
                    PILOT.hold(f, GLFW_KEY_LEFT_CONTROL, best < 110 || conns > 0, now);
                }
                if (frame % 10 == 0) {
                    System.out.printf("[ball] frame=%d balls=%d connectors=%d dist=%.0f holding=%s%n", frame, balls,
                        conns, best, holding);
                }
            }
            default -> { }
        }
    }

    static final Pilot PILOT = new Pilot();
    static final int[] BALL_DROP_FRAME = {-1};

    /** A crude autopilot for scripted runs: steers the own ship toward a point and slows down near it. */
    static final class Pilot {
        double targetX = -1;
        double targetY = -1;
        double stationX;
        double stationY;
        boolean attached;
        private boolean left;
        private boolean right;
        private boolean thrust;
        private final java.util.Map<Integer, Boolean> held = new java.util.HashMap<>();

        static double dx(com.fulcrologic.jxpilot.client.game.ClientWorld w, double x) {
            return com.fulcrologic.jxpilot.common.math.XMath.wrapDelta(x - w.selfX(), w.worldWidth(), w.wraps());
        }

        static double dy(com.fulcrologic.jxpilot.client.game.ClientWorld w, double y) {
            return com.fulcrologic.jxpilot.common.math.XMath.wrapDelta(y - w.selfY(), w.worldHeight(), w.wraps());
        }

        /** One frame of steering; returns the distance to the target. */
        double steer(ClientFrame f, double stop, long now) {
            var w = f.game().world();
            double ddx = dx(w, targetX);
            double ddy = dy(w, targetY);
            double dist = Math.hypot(ddx, ddy);
            double speed = Math.min(6, Math.max(0, (dist - stop) / 25));
            double vx = dist > 0 ? ddx / dist * speed : 0;
            double vy = dist > 0 ? ddy / dist * speed : 0;
            double ex = vx - w.selfVx();
            double ey = vy - w.selfVy();
            double need = Math.hypot(ex, ey);
            double want = need > 0.4 ? Math.atan2(ey, ex) : Math.atan2(ddy, ddx);
            double err = Math.IEEEremainder(want - w.selfHeading(), 2 * Math.PI);
            set(f, GLFW_KEY_A, err > 0.12, now);
            set(f, GLFW_KEY_S, err < -0.12, now);
            set(f, GLFW_KEY_RIGHT_SHIFT, need > 0.4 && Math.abs(err) < 0.4, now);
            return dist;
        }

        void hold(ClientFrame f, int key, boolean down, long now) {
            set(f, key, down, now);
        }

        void release(ClientFrame f, long now) {
            set(f, GLFW_KEY_A, false, now);
            set(f, GLFW_KEY_S, false, now);
            set(f, GLFW_KEY_RIGHT_SHIFT, false, now);
            set(f, GLFW_KEY_LEFT_CONTROL, false, now);
        }

        private void set(ClientFrame f, int key, boolean down, long now) {
            boolean was = held.getOrDefault(key, false);
            if (down != was) {
                f.key(key, down ? GLFW_PRESS : GLFW_RELEASE, 0, now);
                held.put(key, down);
            }
        }
    }

    /** Press at {@code at}, release 3 frames later. */
    static void tap(ClientFrame f, int frame, int at, int key, long now) {
        if (frame == at) {
            f.key(key, GLFW_PRESS, 0, now);
        }
        if (frame == at + 3) {
            f.key(key, GLFW_RELEASE, 0, now);
        }
    }

    private static String take(List<String> args, String flag, String dflt) {
        int i = args.indexOf(flag);
        if (i < 0) {
            return dflt;
        }
        String v = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return v;
    }
}
