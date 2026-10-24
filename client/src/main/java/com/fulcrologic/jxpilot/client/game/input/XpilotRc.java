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
package com.fulcrologic.jxpilot.client.game.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parser for the C client's resource file format ({@code ~/.xpilotrc}, X resource syntax): one
 * {@code [xpilot.]name: value} per line. Pure (text in, entries and problems out).
 * <ul>
 *   <li>Lines starting with {@code !} are comments; so is the rest of a line from a {@code !} that starts
 *       the line or follows whitespace (keysyms never contain {@code !});</li>
 *   <li>a line ending in a backslash continues on the next line;</li>
 *   <li>the resource name may carry the {@code xpilot.} / {@code xpilot*} / {@code XPilot.} / {@code *}
 *       prefix; other application prefixes are reported and skipped;</li>
 *   <li>the value is everything after the first colon, trimmed; it may be empty.</li>
 * </ul>
 */
public final class XpilotRc {
    private XpilotRc() {}

    /** One {@code name: value} line ({@code line} is 1-based, the first line of a continued entry). */
    public record Entry(String name, String value, int line) {}

    /** A line that could not be used. */
    public record Problem(int line, String message) {}

    /** Parsed file. */
    public record Result(List<Entry> entries, List<Problem> problems) {}

    /**
     * Parse a whole file.
     * <p>
     * BEHAVIOR: blank and comment lines are skipped; {@code #} lines (cpp directives) are reported as
     * unsupported; a line without a colon or with an empty name is reported; entries keep file order
     * (later entries for the same name win when applied).
     */
    public static Result parse(String text) {
        List<Entry> entries = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int first = i + 1;
            StringBuilder sb = new StringBuilder(lines[i]);
            while (endsWithContinuation(sb) && i + 1 < lines.length) {
                sb.setLength(sb.length() - 1);
                sb.append(lines[++i]);
            }
            String line = stripComment(sb.toString()).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '#') {
                problems.add(new Problem(first, "'#' directives are not supported: " + line));
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                problems.add(new Problem(first, "expected 'resource: value', got: " + line));
                continue;
            }
            String rawName = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            String name = stripPrefix(rawName);
            if (name == null) {
                problems.add(new Problem(first, "not an xpilot resource (ignored): " + rawName));
                continue;
            }
            if (name.isEmpty()) {
                problems.add(new Problem(first, "missing resource name: " + line));
                continue;
            }
            entries.add(new Entry(name, value, first));
        }
        return new Result(entries, problems);
    }

    private static boolean endsWithContinuation(CharSequence s) {
        return s.length() > 0 && s.charAt(s.length() - 1) == '\\';
    }

    /** BEHAVIOR: drops everything from a '!' at the start of the line or after whitespace. */
    static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '!' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /**
     * BEHAVIOR: "name", "xpilot.name", "xpilot*name", "XPilot.name", "*name" and ".name" give "name"
     * (the application part is case-insensitive); any other prefix gives null.
     */
    static String stripPrefix(String name) {
        int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('*'));
        if (cut < 0) {
            return name;
        }
        String app = name.substring(0, cut).replace("*", "").replace(".", "").toLowerCase(Locale.ROOT);
        if (!app.isEmpty() && !app.equals("xpilot")) {
            return null;
        }
        return name.substring(cut + 1).trim();
    }
}
