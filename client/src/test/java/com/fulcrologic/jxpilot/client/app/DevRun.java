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
 * 160), {@code circle} (thrust from frame 60 with periodic left turns and fire), {@code none}.
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
        int grabFrom = Integer.parseInt(take(rest, "--grab-from", "-1"));
        int grabCount = Integer.parseInt(take(rest, "--grab-count", "36"));
        java.util.List<byte[]> grabs = new java.util.ArrayList<>();
        int[] grabSize = new int[2];
        AppOptions o = AppOptions.parse(rest.toArray(new String[0]), System.getProperty("user.name"));
        ClientGame game = new ClientGame();
        FrameTrace trace = traceFile == null ? null : new FrameTrace(game);
        GameConnection conn = GameConnector.open(o.target(), o.bind(), trace != null ? trace : game, o.name(),
            o.team(), o.shape(), (int) System.nanoTime());
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
                if (base[0] < 0 || grabFrom < 0 || k < grabFrom || k >= grabFrom + grabCount) {
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
                return base[0] >= 0 && frame - base[0] >= Math.max(last, grabFrom + grabCount);
            }
        };
        new GameApp(o, game, conn, o.target().toString(), s).run();
        if (trace != null) {
            trace.write(Path.of(traceFile));
        }
        for (int i = 0; i < grabs.size(); i++) {
            java.nio.file.Files.createDirectories(out);
            java.nio.ByteBuffer px = org.lwjgl.system.MemoryUtil.memAlloc(grabs.get(i).length);
            px.put(grabs.get(i)).flip();
            org.lwjgl.stb.STBImageWrite.stbi_flip_vertically_on_write(true);
            org.lwjgl.stb.STBImageWrite.stbi_write_png(out.resolve(String.format("grab-%02d.png", i)).toString(),
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
            default -> { }
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
