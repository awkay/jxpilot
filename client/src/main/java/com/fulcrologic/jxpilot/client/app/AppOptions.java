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

import com.fulcrologic.jxpilot.client.game.input.KeySyms;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.sound.SoundPlayer;
import com.fulcrologic.jxpilot.client.viewer.LaunchOptions;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Protocol;

/**
 * Parsed command line of {@code ClientMain}: which launch mode, and its settings.
 * <pre>
 *   --view-map FILE ...        the map viewer (all other arguments go to {@link LaunchOptions})
 *   --connect TARGET           play on a server: host[:port], [v6addr]:port, xp://host:port, jx://host:port
 *   --protocol P               xpilot or jxpilot (default jxpilot; a TARGET scheme wins)
 *   --local --map FILE         play on an embedded server for FILE (loopback only)
 *   --bind ADDR[:PORT]         local source address for --connect
 *   --name NAME                player name (default: the user name)
 *   --team T                   team 0..9 (default: none, the server picks)
 *   --shape SHAPE              ship shape string (default: the standard ship)
 *   --size WxH                 window size in logical pixels (default 1280x800)
 *   --scale-factor F           C scaleFactor: zoom 1/F (default 1.0)
 *   --alt-scale-factor F       C altScaleFactor, the zoom the swap key switches to (default 2.0)
 *   --swap-scale-key KEYSYM    bind C keySwapScaleFactor (unbound by default, as in C)
 *   --font FILE, --font-size PX text font (default: bundled, 13)
 *   --screenshot FILE.png      save a PNG after --frames N frames (default 300) and exit
 *   --no-vsync                 render as fast as possible
 *   --sound, --no-sound        play sound effects (default on; JXPilot only: C chose this when it was built)
 *   --volume N                 C maxVolume: the loudest a sound plays, 0..255 percent (0 = no sound); default:
 *                              the key file's maxVolume, else 100
 *   --keys FILE                key bindings and control settings in ~/.xpilotrc format (default: ~/.xpilotrc)
 *   --list-keys                print the effective key bindings and exit
 * </pre>
 *
 * @param viewer     the viewer's options in {@link Mode#VIEW_MAP}, else null
 * @param target     server for {@link Mode#CONNECT}, else null
 * @param bind       local address, or null for any
 * @param team       0..9 or {@link Protocol#NO_TEAM}
 * @param shape      ship shape string, "" for the default ship
 * @param localMap   map file for {@link Mode#LOCAL}, else null
 * @param screenshot PNG path, or null
 * @param swapScaleKey X11 keysym bound to the scale-factor swap, or null
 * @param fontFile   TrueType file, or null for the bundled font
 * @param sound      play sound effects
 * @param maxVolume  {@code --volume} (C {@code maxVolume}), 0..{@value SoundPlayer#MAX_MAX_VOLUME}, or {@link #VOLUME_UNSET}
 * @param keysFile   key file given with {@code --keys}, or null for {@code ~/.xpilotrc}
 */
public record AppOptions(Mode mode, LaunchOptions viewer, ConnectTarget target, Endpoint bind, String name, int team,
                         String shape, String localMap, String screenshot, int frames, int width, int height,
                         boolean vsync, double scaleFactor, double altScaleFactor, String swapScaleKey,
                         String fontFile, double fontSize, boolean sound, int maxVolume, String keysFile) {
    /** Launch modes. */
    public enum Mode {
        /** No mode chosen: print usage. */
        HELP,
        /** The map viewer ({@code --view-map}, optionally {@code --demo}). */
        VIEW_MAP,
        /** Play on a remote server ({@code --connect}). */
        CONNECT,
        /** Play on an embedded server ({@code --local --map}). */
        LOCAL,
        /** Print the effective key bindings ({@code --list-keys}). */
        LIST_KEYS
    }

    /** Longest player name accepted (the client keeps {@link Players#MAX_NAME} characters). */
    public static final int MAX_NAME = Players.MAX_NAME;
    /** Default frames before a screenshot (5 s at 60 Hz: enough to connect and receive the map). */
    public static final int DEFAULT_FRAMES = 300;
    /** {@link #maxVolume()} when {@code --volume} was not given (the key file or C's 100 decide). */
    public static final int VOLUME_UNSET = -1;
    /** Default font size in logical pixels. */
    public static final double DEFAULT_FONT_SIZE = 13;

    public static final String USAGE = """
        usage: ClientMain --connect HOST[:PORT] [--protocol xpilot|jxpilot] [--bind ADDR[:PORT]] [--name NAME]
                          [--team T] [--shape SHAPE] [--size WxH] [--scale-factor F] [--alt-scale-factor F]
                          [--swap-scale-key KEYSYM] [--font FILE] [--font-size PX] [--screenshot FILE.png]
                          [--frames N] [--no-vsync] [--sound | --no-sound] [--volume 0..255] [--keys FILE]
               ClientMain --local --map FILE [same options as --connect, except --protocol and --bind]
               ClientMain --list-keys [--keys FILE] [--swap-scale-key KEYSYM]
               ClientMain --view-map FILE [--demo] [viewer options: --screenshot --frames --size --zoom ...]""";

    /**
     * Parse arguments; {@code userName} supplies the default player name.
     * <p>
     * BEHAVIOR: any {@code --view-map} selects {@link Mode#VIEW_MAP} and hands every argument to
     * {@link LaunchOptions#parse} (its errors propagate); no arguments is {@link Mode#HELP}.
     * BEHAVIOR: {@code --connect} selects CONNECT, {@code --local} LOCAL (needs {@code --map}); both, or
     * neither with other arguments, is an error; {@code --map} without {@code --local} is an error.
     * BEHAVIOR: {@code --protocol} and {@code --bind} only with {@code --connect}; the protocol of a
     * scheme in the target wins over {@code --protocol}.
     * BEHAVIOR: the name defaults to {@link #defaultName(String)}; a given name must have 1..{@link #MAX_NAME}
     * characters; the team is 0..9; the shape must parse ({@link ShipShape#parse}); the swap key must be
     * a known keysym; frames, sizes, scale factors and the font size must be positive.
     * BEHAVIOR: {@code --keys FILE} names the key file; {@code --list-keys} selects {@link Mode#LIST_KEYS}
     * (keeping {@code --keys} and {@code --swap-scale-key}, ignoring the launch mode).
     * BEHAVIOR: sound is on unless {@code --no-sound} (the last of --sound/--no-sound wins); {@code --volume}
     * is an integer 0..{@value SoundPlayer#MAX_MAX_VOLUME}, {@link #VOLUME_UNSET} when not given.
     * BEHAVIOR: unknown options and missing values throw IllegalArgumentException naming the option.
     */
    public static AppOptions parse(String[] args, String userName) {
        for (String a : args) {
            if (a.equals("--view-map")) {
                return new AppOptions(Mode.VIEW_MAP, LaunchOptions.parse(args), null, null, null, Protocol.NO_TEAM,
                    "", null, null, DEFAULT_FRAMES, 1280, 800, true, 1, 2, null, null, DEFAULT_FONT_SIZE, true,
                    VOLUME_UNSET, null);
            }
        }
        if (args.length == 0) {
            return new AppOptions(Mode.HELP, null, null, null, null, Protocol.NO_TEAM, "", null, null,
                DEFAULT_FRAMES, 1280, 800, true, 1, 2, null, null, DEFAULT_FONT_SIZE, true, VOLUME_UNSET,
                null);
        }
        String connect = null;
        String protocol = null;
        boolean local = false;
        String map = null;
        Endpoint bind = null;
        String name = null;
        int team = Protocol.NO_TEAM;
        String shape = "";
        String screenshot = null;
        int frames = DEFAULT_FRAMES;
        int width = 1280;
        int height = 800;
        boolean vsync = true;
        double scale = 1;
        double alt = 2;
        String swapKey = null;
        String font = null;
        double fontSize = DEFAULT_FONT_SIZE;
        boolean sound = true;
        int maxVolume = VOLUME_UNSET;
        String keys = null;
        boolean listKeys = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--connect" -> connect = value(args, ++i, a);
                case "--protocol" -> protocol = value(args, ++i, a);
                case "--local" -> local = true;
                case "--map" -> map = value(args, ++i, a);
                case "--bind" -> bind = endpoint(value(args, ++i, a), a);
                case "--name" -> {
                    name = value(args, ++i, a);
                    if (name.isEmpty() || name.length() > MAX_NAME) {
                        throw new IllegalArgumentException(a + " needs 1.." + MAX_NAME + " characters: '" + name + "'");
                    }
                }
                case "--team" -> {
                    team = integer(value(args, ++i, a), a);
                    if (team < 0 || team > 9) {
                        throw new IllegalArgumentException(a + " must be 0..9: " + team);
                    }
                }
                case "--shape" -> {
                    shape = value(args, ++i, a);
                    if (ShipShape.parse(shape) == null) {
                        throw new IllegalArgumentException(a + " is not a valid ship shape: " + shape);
                    }
                }
                case "--screenshot" -> screenshot = value(args, ++i, a);
                case "--frames" -> frames = positiveInt(value(args, ++i, a), a);
                case "--size" -> {
                    String v = value(args, ++i, a);
                    int x = v.indexOf('x');
                    if (x < 0) {
                        throw new IllegalArgumentException(a + " expects WxH: " + v);
                    }
                    width = positiveInt(v.substring(0, x), a);
                    height = positiveInt(v.substring(x + 1), a);
                }
                case "--no-vsync" -> vsync = false;
                case "--scale-factor" -> scale = positive(value(args, ++i, a), a);
                case "--alt-scale-factor" -> alt = positive(value(args, ++i, a), a);
                case "--swap-scale-key" -> {
                    swapKey = value(args, ++i, a);
                    if (KeySyms.glfwKey(swapKey) == KeySyms.NONE) {
                        throw new IllegalArgumentException(a + ": unknown keysym " + swapKey);
                    }
                }
                case "--font" -> font = value(args, ++i, a);
                case "--font-size" -> fontSize = positive(value(args, ++i, a), a);
                case "--sound" -> sound = true;
                case "--no-sound" -> sound = false;
                case "--volume" -> {
                    maxVolume = integer(value(args, ++i, a), a);
                    if (maxVolume < 0 || maxVolume > SoundPlayer.MAX_MAX_VOLUME) {
                        throw new IllegalArgumentException(a + " must be 0.." + SoundPlayer.MAX_MAX_VOLUME + ": " + maxVolume);
                    }
                }
                case "--keys" -> keys = value(args, ++i, a);
                case "--list-keys" -> listKeys = true;
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        Mode mode;
        ConnectTarget target = null;
        if (listKeys) {
            return new AppOptions(Mode.LIST_KEYS, null, null, null, null, Protocol.NO_TEAM, "", null, null,
                DEFAULT_FRAMES, 1280, 800, true, 1, 2, swapKey, null, DEFAULT_FONT_SIZE, sound, maxVolume, keys);
        }
        if (connect != null && local) {
            throw new IllegalArgumentException("--connect and --local exclude each other");
        }
        if (connect != null) {
            mode = Mode.CONNECT;
            target = ConnectTarget.parse(connect, protocol);
            if (map != null) {
                throw new IllegalArgumentException("--map is only used with --local");
            }
        } else if (local) {
            mode = Mode.LOCAL;
            if (map == null) {
                throw new IllegalArgumentException("--local needs --map FILE");
            }
            if (protocol != null || bind != null) {
                throw new IllegalArgumentException("--protocol and --bind are only used with --connect");
            }
        } else {
            throw new IllegalArgumentException("nothing to do: give --connect, --local or --view-map");
        }
        return new AppOptions(mode, null, target, bind, name != null ? name : defaultName(userName), team, shape,
            map, screenshot, frames, width, height, vsync, scale, alt, swapKey, font, fontSize, sound, maxVolume, keys);
    }

    /**
     * The default player name from the login name.
     * <p>
     * BEHAVIOR: keeps ASCII letters, digits, '-', '_' and '.', at most {@link #MAX_NAME}; the first letter
     * upper-cased; "Pilot" when nothing is left (or the name is null).
     */
    public static String defaultName(String userName) {
        StringBuilder sb = new StringBuilder();
        if (userName != null) {
            for (int i = 0; i < userName.length() && sb.length() < MAX_NAME; i++) {
                char c = userName.charAt(i);
                if (c < 128 && Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                    sb.append(c);
                }
            }
        }
        if (sb.isEmpty()) {
            return "Pilot";
        }
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }

    private static Endpoint endpoint(String v, String flag) {
        try {
            return Endpoint.parse(v, 0);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(flag + ": " + e.getMessage());
        }
    }

    private static int integer(String v, String flag) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects an integer: " + v);
        }
    }

    private static int positiveInt(String v, String flag) {
        int n = integer(v, flag);
        if (n < 1) {
            throw new IllegalArgumentException(flag + " must be positive: " + n);
        }
        return n;
    }

    private static double positive(String v, String flag) {
        double d;
        try {
            d = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects a number: " + v);
        }
        if (!(d > 0)) {
            throw new IllegalArgumentException(flag + " must be positive: " + v);
        }
        return d;
    }
}
