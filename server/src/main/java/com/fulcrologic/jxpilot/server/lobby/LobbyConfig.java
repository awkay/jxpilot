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
package com.fulcrologic.jxpilot.server.lobby;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The lobby config file: server config text ({@code name: value} lines, as any XPilot config file) for every map
 * before the first {@code [section]}, then {@code [mapname]} sections holding options for that map alone. The
 * options of a section override the map file (as if each had {@code \override:}) and the lines before the first
 * section; only the command line beats them.
 */
public final class LobbyConfig {
    /** No config: no server-wide lines and no sections. */
    public static final LobbyConfig EMPTY = new LobbyConfig("", Map.of());

    private final String top;
    private final Map<String, String> sections;

    private LobbyConfig(String top, Map<String, String> sections) {
        this.top = top;
        this.sections = sections;
    }

    /**
     * Split config text into the server-wide part and the sections.
     * <p>
     * BEHAVIOR: a line that is {@code [name]} (blanks around the name and a trailing {@code # comment} allowed)
     * starts section {@code name}; every other line belongs to the current section, or to the server-wide part
     * before the first header.
     * BEHAVIOR: section names are compared ignoring case and a trailing {@code .xp}/{@code .map}; a name that
     * appears twice collects the lines of both.
     * BEHAVIOR: lines of a {@code \multiline:} value are never taken for headers.
     * BEHAVIOR: an empty section name is reported to {@code warn} and its lines are dropped.
     */
    public static LobbyConfig parse(String text, Consumer<String> warn) {
        StringBuilder top = new StringBuilder();
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        StringBuilder current = top;
        String delimiter = null;
        int lineNo = 0;
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            lineNo++;
            if (delimiter != null) {
                current.append(line).append('\n');
                if (line.equals(delimiter)) {
                    delimiter = null;
                }
                continue;
            }
            String name = header(line);
            if (name != null) {
                if (name.isEmpty()) {
                    warn.accept("lobby config line " + lineNo + ": empty section name");
                    current = new StringBuilder();
                } else {
                    current = sections.computeIfAbsent(LobbyCatalog.baseName(name).toLowerCase(Locale.ROOT),
                        k -> new StringBuilder());
                }
                continue;
            }
            current.append(line).append('\n');
            delimiter = multilineDelimiter(line);
        }
        Map<String, String> out = new LinkedHashMap<>();
        sections.forEach((k, v) -> out.put(k, v.toString()));
        return new LobbyConfig(top.toString(), out);
    }

    /** Read a config file (Latin-1, like every XPilot file). */
    public static LobbyConfig read(Path file, Consumer<String> warn) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1), warn);
    }

    /** The server-wide config text (everything before the first section). */
    public String top() {
        return top;
    }

    /** The names of the sections, lower case without extension. */
    public Set<String> sectionNames() {
        return sections.keySet();
    }

    /**
     * The section of a map with every option marked {@code \override:}, or "" when there is none.
     * <p>
     * BEHAVIOR: the name is matched ignoring case and a {@code .xp}/{@code .map} suffix.
     */
    public String sectionFor(String mapName) {
        String s = sections.get(LobbyCatalog.baseName(mapName).toLowerCase(Locale.ROOT));
        return s == null ? "" : forceOverride(s);
    }

    /**
     * BEHAVIOR: {@code name: value} becomes {@code name: \override: value}; a value that already starts with
     * {@code \override:}, blank lines, comment lines and lines without a colon are kept as they are; the lines of
     * a {@code \multiline:} value, up to its delimiter line, are copied verbatim.
     */
    static String forceOverride(String section) {
        StringBuilder out = new StringBuilder();
        String delimiter = null;
        for (String line : section.split("\n", -1)) {
            if (delimiter != null) {
                out.append(line).append('\n');
                if (line.equals(delimiter)) {
                    delimiter = null;
                }
                continue;
            }
            String t = line.strip();
            int colon = t.indexOf(':');
            if (t.isEmpty() || t.charAt(0) == '#' || colon < 0) {
                out.append(line).append('\n');
                continue;
            }
            String value = t.substring(colon + 1).stripLeading();
            out.append(t, 0, colon + 1).append(' ');
            if (!value.startsWith("\\override:")) {
                out.append("\\override: ");
            }
            out.append(value).append('\n');
            delimiter = multilineDelimiter(t);
        }
        return out.toString();
    }

    private static String header(String line) {
        String t = line.strip();
        int hash = t.indexOf('#');
        if (hash >= 0) {
            t = t.substring(0, hash).stripTrailing();
        }
        if (t.length() >= 2 && t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']') {
            return t.substring(1, t.length() - 1).strip();
        }
        return null;
    }

    /** The delimiter if the line starts a {@code \multiline:} value, else null. */
    private static String multilineDelimiter(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String v = line.substring(colon + 1).strip();
        for (String prefix : new String[] {"\\override:", "\\noeol:"}) {
            if (v.startsWith(prefix)) {
                v = v.substring(prefix.length()).stripLeading();
            }
        }
        if (!v.startsWith("\\multiline:")) {
            return null;
        }
        String d = v.substring("\\multiline:".length()).strip();
        return d.isEmpty() ? null : d;
    }
}
