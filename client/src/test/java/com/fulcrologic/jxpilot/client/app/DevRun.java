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
 * the last. Scripts: {@code fly} (from frame 60: thrust, turn left, fire in bursts), {@code talk} (opens the
 * talk line at frame 40 and types a line, leaves it open), {@code chat} (as talk, then sends it at frame
 * 160), {@code none}.
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
        AppOptions o = AppOptions.parse(rest.toArray(new String[0]), System.getProperty("user.name"));
        ClientGame game = new ClientGame();
        GameConnection conn = GameConnector.open(o.target(), o.bind(), game, o.name(), o.team(), o.shape(),
            (int) System.nanoTime());
        int last = start + every * (count - 1);
        GameApp.Script s = new GameApp.Script() {
            @Override
            public void beforeFrame(int frame, ClientFrame f, long now) {
                run(script, frame, f, now);
            }

            @Override
            public Path screenshotAfter(int frame) {
                if (frame < start || (frame - start) % every != 0 || frame > last) {
                    return null;
                }
                return out.resolve(String.format("shot-%02d.png", (frame - start) / every));
            }

            @Override
            public boolean done(int frame) {
                return frame >= last;
            }
        };
        new GameApp(o, game, conn, o.target().toString(), s).run();
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
