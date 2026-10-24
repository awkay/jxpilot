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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The server defaults file: C {@code Parser}'s "Read local defaults file" step (parser.c:419-429).
 * <p>
 * C reads {@code LIBDIR/defaults} (or the file named by {@code -defaultsFileName}/{@code -defaults}) after
 * the command line and before the password and map files, as origin {@code OPT_DEFAULTS}. JXPilot ships
 * XPilot's {@code lib/defaults} unmodified as the classpath resource {@link #RESOURCE} and uses it in its
 * place. The precedence rules of {@link Options#setValue} then give C's result: the command line beats
 * everything, the map beats the defaults file, and a defaults value with {@code \override:} beats the map.
 */
public final class DefaultsFile {
    /** Classpath resource holding XPilot 4.5.5's {@code lib/defaults}. */
    public static final String RESOURCE = "/xpilot/defaults";
    /** {@code --defaults none}: read no defaults file at all. */
    public static final String NONE = "none";

    private DefaultsFile() {}

    /** The text of the bundled {@code lib/defaults} (Latin-1, as XPilot files are). */
    public static String bundledText() {
        try (InputStream in = DefaultsFile.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Read the defaults file into {@code opts} with origin DEFAULTS.
     * <p>
     * BEHAVIOR: {@code defaultsFileName} unset: the bundled {@code lib/defaults}.
     * BEHAVIOR: {@code defaultsFileName} = "none" (any case): nothing is read.
     * BEHAVIOR: any other value is a file path that is read instead of the bundled file.
     *
     * @throws IOException when the named file cannot be read (C ignores a missing file silently; a file named
     *                     on purpose that is not there is an error here, see DEVIATIONS-server.md)
     */
    public static void apply(Options opts) throws IOException {
        String name = opts.getString(Opt.DEFAULTS_FILE_NAME);
        if (name == null || name.isEmpty()) {
            applyBundled(opts);
        } else if (!name.equalsIgnoreCase(NONE)) {
            try {
                ConfigParser.parseFile(Path.of(name), OptionOrigin.DEFAULTS, opts);
            } catch (IOException e) {
                throw new IOException("cannot read defaults file " + name + " (" + e + ")", e);
            }
        }
    }

    /** BEHAVIOR: parses the bundled {@code lib/defaults} into {@code opts} with origin DEFAULTS. */
    public static void applyBundled(Options opts) {
        ConfigParser.parse(bundledText(), "defaults", OptionOrigin.DEFAULTS, opts);
    }
}
