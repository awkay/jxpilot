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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Pure command-line helpers for {@code ServerMain}. */
public final class ServerArgs {
    private ServerArgs() {}

    /**
     * Make a list option repeatable on the command line ({@code --bind a --bind b}), since
     * {@code Options.parseCommandLine} keeps only the last value.
     * <p>
     * BEHAVIOR: every {@code -name value}/{@code --name value} of {@code opt} (any of its names, any
     * case) is merged into one comma-joined value at the first occurrence; other arguments keep
     * their order.
     * BEHAVIOR: a trailing flag without a value is left in place (Options warns about it).
     */
    public static String[] joinRepeated(String[] args, Opt opt) {
        List<String> out = new ArrayList<>();
        StringBuilder values = null;
        int valueSlot = -1;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (i + 1 < args.length && isFlag(a, opt)) {
                if (values == null) {
                    values = new StringBuilder(args[i + 1]);
                    out.add(a);
                    valueSlot = out.size();
                    out.add("");
                } else {
                    values.append(',').append(args[i + 1]);
                }
                i++;
            } else {
                out.add(a);
            }
        }
        if (values != null) {
            out.set(valueSlot, values.toString());
        }
        return out.toArray(new String[0]);
    }

    /**
     * The positional server config file among the arguments {@code Options.parseCommandLine} did not
     * consume.
     * <p>
     * BEHAVIOR: no arguments gives null; one non-option argument is the config file path.
     * BEHAVIOR: an unknown option is a warning ("Unknown option '-x'") and is otherwise ignored, as C
     * (parser.c:411-418) does.
     *
     * @throws IllegalArgumentException for more than one positional argument
     */
    public static Path configFile(List<String> rest, Options opts) {
        Path file = null;
        for (String a : rest) {
            if (a.length() > 1 && (a.charAt(0) == '-' || a.charAt(0) == '+')) {
                opts.warn("Unknown option '" + a + "'");
                continue;
            }
            if (file != null) {
                throw new IllegalArgumentException("more than one config file: " + file + ", " + a);
            }
            file = Path.of(a);
        }
        return file;
    }

    /**
     * The bind selectors of the options: {@code bind}, or C's {@code serverHost} (server.c:163-171: the contact
     * socket binds to that host's address) when only serverHost is set.
     * <p>
     * BEHAVIOR: an explicitly set bind wins; else a non-empty serverHost is the one selector; else bind's
     * default ({@code all}).
     */
    public static List<String> bindList(Options o) {
        String host = o.getString(Opt.SERVER_HOST);
        if (!o.isSet(Opt.BIND) && host != null && !host.isBlank()) {
            return List.of(host.strip());
        }
        return o.getList(Opt.BIND);
    }

    private static boolean isFlag(String a, Opt opt) {
        if (a.length() < 2 || a.charAt(0) != '-') {
            return false;
        }
        String n = a.startsWith("--") ? a.substring(2) : a.substring(1);
        return Opt.find(n) == opt;
    }
}
