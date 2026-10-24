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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionType;
import com.fulcrologic.jxpilot.common.map.BundledMaps;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The server's information requests, C {@code Parse_check_info_request} (parser.c): {@code -help}/{@code -h},
 * {@code -version}/{@code -v}, {@code -dump}, {@code -dumpMan}, {@code -dumpWindows} and {@code -dumpFlags}
 * (also with two dashes), plus JXPilot's {@code --list-maps}. The server prints the text and exits instead of
 * starting.
 */
public final class ServerInfo {
    private ServerInfo() {}

    /**
     * BEHAVIOR: the text for the first information request among the arguments, or null when there is none.
     * Arguments are scanned in order, so {@code --map X -help} still asks for help (C checks every argument).
     */
    public static String request(String[] args, String version) {
        for (String a : args) {
            String arg = a.startsWith("--") ? a.substring(1) : a;
            switch (arg) {
                case "-help", "-h" -> {
                    return help();
                }
                case "-dump" -> {
                    return dumpConfig() + dumpOptions();
                }
                case "-dumpMan", "-dumpWindows" -> {
                    return dumpOptions();
                }
                case "-dumpFlags" -> {
                    return dumpFlags();
                }
                case "-list-maps" -> {
                    return listMaps();
                }
                case "-version", "-v" -> {
                    return "JXPilot server " + version + " (a port of XPilot 4.5.5)\n";
                }
                default -> {
                    // not an information request
                }
            }
        }
        return null;
    }

    /** BEHAVIOR: {@code --list-maps}: {@link BundledMaps#listing} of every bundled map. */
    static String listMaps() {
        try {
            return BundledMaps.listing(BundledMaps.standard().infos());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The server version from the jar manifest, "dev" when not running from a jar. */
    public static String version() {
        String v = ServerInfo.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /** BEHAVIOR: C {@code Parse_help}: every option with its alias, value kind, help text and non-default flags. */
    static String help() {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("Usage: ServerMain [ options ]\n\n");
        sb.append("Option flags specify where an option can be used and whether it\n"
            + "is visible or not.  Default flags are \"any\" if not otherwise\n"
            + "specified by one or more of these keywords:\n\n");
        sb.append("command       May be specified on the command-line.\n"
            + "passwordfile  May be specified in the password file.\n"
            + "defaults      May be specified in the defaults file.\n"
            + "any           May be specified in the map file, defaults file,\n"
            + "              or on the command line.\n"
            + "invisible     Is not displayed when a list of options is\n"
            + "              requested from the server by an operator.\n\n");
        sb.append("The possible options include:\n\n");
        for (Opt o : Opt.values()) {
            sb.append("    ").append(o.type() == OptionType.BOOL ? "-/+" : "-").append(o.optionName());
            if (!o.alias().equalsIgnoreCase(o.optionName())) {
                sb.append(" or ").append(o.alias());
            }
            sb.append(' ').append(kind(o.type())).append('\n');
            sb.append('\t').append(o.help()).append('\n');
            int all = Opt.F.ANY | Opt.F.VIS;
            int f = o.flags();
            if ((f & all) != all && f != 0) {
                StringBuilder m = new StringBuilder("[ Flags: command, ");
                if ((f & Opt.F.PWD) != 0) {
                    m.append("passwordfile, ");
                }
                if ((f & (Opt.F.DEF | Opt.F.MAP)) == Opt.F.DEF) {
                    m.append("defaults, ");
                }
                if ((f & Opt.F.MAP) != 0) {
                    m.append("any, ");
                }
                if ((f & Opt.F.VIS) == 0) {
                    m.append("invisible, ");
                }
                m.setLength(m.length() - 2);
                sb.append('\t').append(m).append(" ]\n");
            }
            sb.append('\n');
        }
        sb.append("    \n"
            + "    The probabilities are in the range [0.0-1.0] and they refer to the\n"
            + "    probability that an event will occur in a block per second.\n"
            + "    Boolean options are turned off by using +<option>.\n");
        return sb.toString();
    }

    private static String kind(OptionType t) {
        return switch (t) {
            case INT -> "<integer>";
            case REAL -> "<real>";
            case STRING -> "<string>";
            case IPOS -> "<position>";
            case SEC -> "<seconds>";
            case PER_SEC -> "<per-second>";
            case LIST -> "<list>";
            default -> "";
        };
    }

    /** BEHAVIOR: C {@code Parser_dump_config}: the comment header of a dump. */
    static String dumpConfig() {
        return "\n# ServerMain option dump\n# \n# DEFAULTS_FILE_NAME = (bundled) xpilot/defaults\n# \n";
    }

    /**
     * BEHAVIOR: C {@code Parser_dump_options}: every non-void option as "name:" padded to column 41, then its
     * cmdline.c default (nothing when it has none), in defaults-file format, then an empty line.
     */
    static String dumpOptions() {
        StringBuilder sb = new StringBuilder(32 * 1024);
        for (Opt o : Opt.values()) {
            if (o.type() == OptionType.VOID) {
                continue;
            }
            pad(sb, o.optionName()).append(o.defaultValue() == null ? "" : o.defaultValue()).append('\n');
        }
        return sb.append('\n').toString();
    }

    /** BEHAVIOR: C {@code Parser_dump_flags}: every option's allowed origins and visibility. */
    static String dumpFlags() {
        StringBuilder sb = new StringBuilder(32 * 1024);
        for (Opt o : Opt.values()) {
            int f = o.flags();
            StringBuilder m = new StringBuilder("{");
            if ((f & Opt.F.CMD) != 0) {
                m.append("command, ");
            }
            if ((f & Opt.F.PWD) != 0) {
                m.append("passwordfile, ");
            }
            if ((f & Opt.F.DEF) != 0) {
                m.append("defaults, ");
            }
            if ((f & Opt.F.MAP) != 0) {
                m.append("map, ");
            }
            if ((f & Opt.F.VIS) != 0) {
                m.append("visible, ");
            }
            if (m.length() >= 2) {
                m.setLength(m.length() - 2);
            }
            pad(sb, o.optionName()).append(m.append('}')).append('\n');
        }
        return sb.append('\n').toString();
    }

    /** "name:" followed by spaces up to 40 - len (at least 1), as C's {@code "%s:%*s"}. */
    private static StringBuilder pad(StringBuilder sb, String name) {
        sb.append(name).append(':');
        int n = name.length() < 40 ? 40 - name.length() : 1;
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb;
    }
}
