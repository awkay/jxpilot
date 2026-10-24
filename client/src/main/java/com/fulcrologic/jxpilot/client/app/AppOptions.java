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

import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.game.input.KeySyms;
import com.fulcrologic.jxpilot.client.game.input.XpilotRc;
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
 *   --view-map MAP ...         the map viewer (all other arguments go to {@link LaunchOptions})
 *   --connect TARGET           play on a server: host[:port], [v6addr]:port, xp://host:port, jx://host:port
 *   --protocol P               xpilot or jxpilot (default jxpilot; a TARGET scheme wins)
 *   --local --map MAP          play on an embedded server for MAP (loopback only); MAP is a bundled map name,
 *                              or a file when it has a path in it (see MapLocator)
 *   --bind ADDR[:PORT]         local source address for --connect
 *   --lobby-map NAME           when the server is a lobby, pick this map by name at once instead of showing the list
 *   --name NAME                player name (default: the user name)
 *   --team T                   team 0..9 (default: none, the server picks)
 *   --shape SHAPE              ship shape string, or a name in the shape file (C shipShape; default: the
 *                              shipShape resource of ~/.xpilotrc, else the standard ship); alias --shipShape
 *   --shipShapeFile FILE       file of named shapes, one per line (C shipShapeFile; default: ~/.xpilotrc's)
 *   --size WxH                 window size in logical pixels (default 1280x800)
 *   --scale-factor F           C scaleFactor: zoom 1/F (default 1.0)
 *   --alt-scale-factor F       C altScaleFactor, the zoom the swap key switches to (default 2.0)
 *   --swap-scale-key KEYSYM    bind C keySwapScaleFactor (unbound by default, as in C)
 *   --font FILE|NAME           one font for every text, replacing the per-role C fonts (gameFont, messageFont,
 *                              scoreListFont, ...): a TrueType file, or a bundled name (FiraCode, LiberationSerif,
 *                              LiberationSans-Bold, ...), optionally with :SIZE; also any font resource value
 *   --font-size PX             the size of that one font (default 13); alone, it selects bundled Fira Code
 *   --screenshot FILE.png      save a PNG after --frames N frames (default 300) and exit
 *   --no-vsync                 render as fast as possible
 *   --sound, --no-sound        play sound effects (default on; JXPilot only: C chose this when it was built)
 *   --volume N                 C maxVolume: the loudest a sound plays, 0..255 percent (0 = no sound); default:
 *                              the key file's maxVolume, else 100
 *   --keys FILE                key bindings and control settings in ~/.xpilotrc format (default: ~/.xpilotrc)
 *   --list-keys                print the effective key bindings and exit
 *   --list-maps                print the bundled maps and exit
 *   --version                  print the client's version and exit (C -version)
 *   --RESOURCE VALUE           any C client resource (also -RESOURCE, C's form): the same as a line
 *                              "RESOURCE: VALUE" in ~/.xpilotrc, and winning over it (join, noLocalMotd, text, list,
 *                              mono and erase take no value, as in C)
 * </pre>
 *
 * @param viewer     the viewer's options in {@link Mode#VIEW_MAP}, else null
 * @param target     server for {@link Mode#CONNECT}, else null
 * @param bind       local address, or null for any
 * @param team       0..9 or {@link Protocol#NO_TEAM}
 * @param shape      ship shape string or shape name from the command line, or null when not given
 * @param localMap   map name or file for {@link Mode#LOCAL}, else null
 * @param screenshot PNG path, or null
 * @param swapScaleKey X11 keysym bound to the scale-factor swap, or null
 * @param fontFile   {@code --font}: a TrueType file or bundled font name, or null when not given
 * @param fontSize   {@code --font-size} in logical pixels, or {@link #FONT_SIZE_UNSET} when not given; with neither
 *                   font option the per-role C fonts apply ({@link #fontOverride()})
 * @param sound      play sound effects
 * @param maxVolume  {@code --volume} (C {@code maxVolume}), 0..{@value SoundPlayer#MAX_MAX_VOLUME}, or {@link #VOLUME_UNSET}
 * @param keysFile   key file given with {@code --keys}, or null for {@code ~/.xpilotrc}
 * @param shipShapeFile shape file given with {@code --shipShapeFile}, or null when not given
 * @param connectText the {@code --connect} argument as given (the port resource fills in a missing port), or null
 * @param lobbyMap   {@code --lobby-map}: the map to pick by itself when the server turns out to be a lobby, or null
 * @param resources  command-line resources in order, applied after the resource file: every
 *                   {@code --RESOURCE VALUE}, plus name, team, geometry (from {@code --size}), scaleFactor and
 *                   altScaleFactor for their JXPilot flags
 */
public record AppOptions(Mode mode, LaunchOptions viewer, ConnectTarget target, Endpoint bind, String name, int team,
                         String shape, String localMap, String screenshot, int frames, int width, int height,
                         boolean vsync, double scaleFactor, double altScaleFactor, String swapScaleKey,
                         String fontFile, double fontSize, boolean sound, int maxVolume, String keysFile,
                         String shipShapeFile, java.util.List<XpilotRc.Entry> resources, String connectText,
                         String lobbyMap) {
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
        LIST_KEYS,
        /** Print the bundled maps ({@code --list-maps}). */
        LIST_MAPS,
        /** Print the version ({@code --version}, C {@code -version}). */
        VERSION
    }

    /** Longest player name accepted (the client keeps {@link Players#MAX_NAME} characters). */
    public static final int MAX_NAME = Players.MAX_NAME;
    /** Default frames before a screenshot (5 s at 60 Hz: enough to connect and receive the map). */
    public static final int DEFAULT_FRAMES = 300;
    /** {@link #maxVolume()} when {@code --volume} was not given (the key file or C's 100 decide). */
    public static final int VOLUME_UNSET = -1;
    /** Size of the {@code --font} override when {@code --font-size} is not given, in logical pixels. */
    public static final double DEFAULT_FONT_SIZE = 13;
    /** {@link #fontSize()} when {@code --font-size} was not given. */
    public static final double FONT_SIZE_UNSET = 0;

    /** BEHAVIOR: true iff {@code --font} or {@code --font-size} was given: one font replaces every role's. */
    public boolean fontOverride() {
        return fontFile != null || fontSize > 0;
    }

    public static final String USAGE = """
        usage: ClientMain --connect HOST[:PORT] [--protocol xpilot|jxpilot] [--bind ADDR[:PORT]] [--name NAME]
                          [--team T] [--shape|--shipShape SHAPE|NAME] [--shipShapeFile FILE] [--lobby-map NAME]
                          [--size WxH] [--scale-factor F] [--alt-scale-factor F]
                          [--swap-scale-key KEYSYM] [--font FILE|NAME] [--font-size PX] [--screenshot FILE.png]
                          [--frames N] [--no-vsync] [--sound | --no-sound] [--volume 0..255] [--keys FILE]
                          [--RESOURCE VALUE ...]  (any ~/.xpilotrc resource, e.g. --showHUDRadar yes)
               ClientMain --local --map MAP [same options as --connect, except --protocol and --bind]
               ClientMain --list-keys [--keys FILE] [--swap-scale-key KEYSYM]
               ClientMain --list-maps
               ClientMain --version
               ClientMain --view-map MAP [--demo] [viewer options: --screenshot --frames --size --zoom ...]
        MAP is a bundled map name (see --list-maps), or a map file when it contains a path (maps/my.xp, ./my.xp).""";

    /**
     * Parse arguments; {@code userName} supplies the default player name.
     * <p>
     * BEHAVIOR: any {@code --view-map} selects {@link Mode#VIEW_MAP} and hands every argument to
     * {@link LaunchOptions#parse} (its errors propagate); no arguments is {@link Mode#HELP}.
     * BEHAVIOR: {@code --connect} selects CONNECT, {@code --local} LOCAL (needs {@code --map}); both, or
     * neither with other arguments, is an error; {@code --map} without {@code --local} is an error.
     * BEHAVIOR: {@code --lobby-map NAME} (only with {@code --connect}) is kept as {@link #lobbyMap()}.
     * BEHAVIOR: {@code --protocol} and {@code --bind} only with {@code --connect}; the protocol of a
     * scheme in the target wins over {@code --protocol}.
     * BEHAVIOR: the name defaults to {@link #defaultName(String)}; a given name must have 1..{@link #MAX_NAME}
     * characters; the team is 0..9; a shape holding '(' must parse ({@link ShipShape#parse}; without one it
     * is a name for the shape file, see {@link ShipShapeResource}); {@code --shipShape} is {@code --shape};
     * the shape and shape file stay null when not given; the swap key must be
     * a known keysym; frames, sizes, scale factors and the font size must be positive; the font and font size
     * stay null and {@link #FONT_SIZE_UNSET} when not given.
     * BEHAVIOR: {@code --keys FILE} names the key file; {@code --list-keys} selects {@link Mode#LIST_KEYS}
     * (keeping {@code --keys} and {@code --swap-scale-key}, ignoring the launch mode).
     * BEHAVIOR: {@code --list-maps} selects {@link Mode#LIST_MAPS}, ignoring the launch mode ({@code --list-keys}
     * wins when both are given); {@code --version} / {@code -version} selects {@link Mode#VERSION} before both.
     * BEHAVIOR: sound is on unless {@code --no-sound} (the last of --sound/--no-sound wins); {@code --volume}
     * is an integer 0..{@value SoundPlayer#MAX_MAX_VOLUME}, {@link #VOLUME_UNSET} when not given.
     * BEHAVIOR: {@code --RESOURCE VALUE} / {@code -RESOURCE VALUE} for a resource {@link KeyConfig#knownResource}
     * knows adds it to {@link #resources()} (a {@link #NO_ARG} resource takes no value and is "Yes"); --name,
     * --team, --size, --scale-factor and --alt-scale-factor also add name, team, geometry ("WxH"), scaleFactor and
     * altScaleFactor, so they win over the resource file.
     * BEHAVIOR: unknown options and missing values throw IllegalArgumentException naming the option.
     */
    public static AppOptions parse(String[] args, String userName) {
        for (String a : args) {
            if (a.equals("--view-map")) {
                return new AppOptions(Mode.VIEW_MAP, LaunchOptions.parse(args), null, null, null, Protocol.NO_TEAM,
                    "", null, null, DEFAULT_FRAMES, 1280, 800, true, 1, 2, null, null, FONT_SIZE_UNSET, true,
                    VOLUME_UNSET, null, null, java.util.List.of(), null, null);
            }
        }
        if (args.length == 0) {
            return new AppOptions(Mode.HELP, null, null, null, null, Protocol.NO_TEAM, "", null, null,
                DEFAULT_FRAMES, 1280, 800, true, 1, 2, null, null, FONT_SIZE_UNSET, true, VOLUME_UNSET,
                null, null, java.util.List.of(), null, null);
        }
        String connect = null;
        String lobbyMap = null;
        String protocol = null;
        boolean local = false;
        String map = null;
        Endpoint bind = null;
        String name = null;
        int team = Protocol.NO_TEAM;
        String shape = null;
        String shipShapeFile = null;
        String screenshot = null;
        int frames = DEFAULT_FRAMES;
        int width = 1280;
        int height = 800;
        boolean vsync = true;
        double scale = 1;
        double alt = 2;
        String swapKey = null;
        String font = null;
        double fontSize = FONT_SIZE_UNSET;
        boolean sound = true;
        int maxVolume = VOLUME_UNSET;
        String keys = null;
        boolean listKeys = false;
        boolean listMaps = false;
        boolean version = false;
        java.util.List<XpilotRc.Entry> resources = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--connect" -> connect = value(args, ++i, a);
                case "--protocol" -> protocol = value(args, ++i, a);
                case "--local" -> local = true;
                case "--map" -> map = value(args, ++i, a);
                case "--bind" -> bind = endpoint(value(args, ++i, a), a);
                case "--lobby-map" -> lobbyMap = value(args, ++i, a);
                case "--name" -> {
                    name = value(args, ++i, a);
                    if (name.isEmpty() || name.length() > MAX_NAME) {
                        throw new IllegalArgumentException(a + " needs 1.." + MAX_NAME + " characters: '" + name + "'");
                    }
                    resources.add(new XpilotRc.Entry("name", name, i + 1));
                }
                case "--team" -> {
                    team = integer(value(args, ++i, a), a);
                    if (team < 0 || team > 9) {
                        throw new IllegalArgumentException(a + " must be 0..9: " + team);
                    }
                    resources.add(new XpilotRc.Entry("team", Integer.toString(team), i + 1));
                }
                case "--shape", "--shipShape" -> {
                    shape = value(args, ++i, a);
                    if (shape.indexOf('(') >= 0 && ShipShape.parse(shape) == null) {
                        throw new IllegalArgumentException(a + " is not a valid ship shape: " + shape);
                    }
                }
                case "--shipShapeFile" -> shipShapeFile = value(args, ++i, a);
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
                    resources.add(new XpilotRc.Entry("geometry", width + "x" + height, i + 1));
                }
                case "--no-vsync" -> vsync = false;
                case "--scale-factor" -> {
                    scale = positive(value(args, ++i, a), a);
                    resources.add(new XpilotRc.Entry("scaleFactor", args[i], i + 1));
                }
                case "--alt-scale-factor" -> {
                    alt = positive(value(args, ++i, a), a);
                    resources.add(new XpilotRc.Entry("altScaleFactor", args[i], i + 1));
                }
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
                case "--list-maps" -> listMaps = true;
                case "--version", "-version" -> version = true;
                default -> {
                    String res = resourceName(a);
                    if (res == null) {
                        throw new IllegalArgumentException("Unknown option: " + a);
                    }
                    String v = NO_ARG.contains(res) ? "Yes" : value(args, ++i, a);
                    resources.add(new XpilotRc.Entry(res, v, i + 1));
                }
            }
        }
        Mode mode;
        ConnectTarget target = null;
        if (version) {
            return new AppOptions(Mode.VERSION, null, null, null, null, Protocol.NO_TEAM, "", null, null,
                DEFAULT_FRAMES, 1280, 800, true, 1, 2, null, null, DEFAULT_FONT_SIZE, true, VOLUME_UNSET, null, null,
                java.util.List.of(), null, null);
        }
        if (listKeys) {
            return new AppOptions(Mode.LIST_KEYS, null, null, null, null, Protocol.NO_TEAM, "", null, null,
                DEFAULT_FRAMES, 1280, 800, true, 1, 2, swapKey, null, FONT_SIZE_UNSET, sound, maxVolume, keys, null,
                java.util.List.copyOf(resources), null, null);
        }
        if (listMaps) {
            return new AppOptions(Mode.LIST_MAPS, null, null, null, null, Protocol.NO_TEAM, "", null, null,
                DEFAULT_FRAMES, 1280, 800, true, 1, 2, null, null, FONT_SIZE_UNSET, true, SoundPlayer.DEFAULT_MAX_VOLUME,
                null, null, java.util.List.of(), null, null);
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
        } else if (lobbyMap != null) {
            throw new IllegalArgumentException("--lobby-map is only used with --connect");
        } else if (local) {
            mode = Mode.LOCAL;
            if (map == null) {
                throw new IllegalArgumentException("--local needs --map MAP (a bundled name from --list-maps, or a file)");
            }
            if (protocol != null || bind != null) {
                throw new IllegalArgumentException("--protocol and --bind are only used with --connect");
            }
        } else {
            throw new IllegalArgumentException("nothing to do: give --connect, --local or --view-map");
        }
        return new AppOptions(mode, null, target, bind, name != null ? name : defaultName(userName), team, shape,
            map, screenshot, frames, width, height, vsync, scale, alt, swapKey, font, fontSize, sound, maxVolume, keys,
            shipShapeFile, java.util.List.copyOf(resources), connect, lobbyMap);
    }

    /** C's options that take no argument on the command line (default.c {@code "Yes"} noArg value). */
    static final java.util.Set<String> NO_ARG = java.util.Set.of("join", "noLocalMotd", "text", "list", "mono", "erase");

    /**
     * BEHAVIOR: {@code -resource} or {@code --resource} (C's command-line form of every resource) gives the
     * resource name when {@link KeyConfig#knownResource} knows it (the case of a {@link #NO_ARG} name is
     * normalised), else null.
     */
    static String resourceName(String arg) {
        String n = arg.startsWith("--") ? arg.substring(2) : arg.startsWith("-") ? arg.substring(1) : null;
        if (n == null || n.isEmpty() || !KeyConfig.knownResource(n)) {
            return null;
        }
        for (String s : NO_ARG) {
            if (s.equalsIgnoreCase(n)) {
                return s;
            }
        }
        return n;
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
