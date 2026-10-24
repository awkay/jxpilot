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
package com.fulcrologic.jxpilot.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parser for XPilot map/defaults files, a port of {@code src/server/fileparser.c}
 * ({@code parseLine}/{@code getMultilineValue}) as described in {@code doc/README.MAPS}.
 * <ul>
 *   <li>{@code name: value} per line; names start with a letter, internal whitespace is ignored,
 *       matching is case-insensitive.</li>
 *   <li>{@code #} starts a comment (outside multiline values); values are trimmed.</li>
 *   <li>{@code \override:} prefix sets the override flag; {@code \multiline: DELIM} reads following
 *       lines verbatim (each keeping its newline) up to a line equal to DELIM, or EOF.</li>
 *   <li>{@code define: NAME value} stores a macro, {@code expand: NAME} parses a macro's value as
 *       config lines (with the macro's origin).</li>
 *   <li>Files are Latin-1. CR characters before LF are dropped (deliberate: makes CRLF files work).</li>
 * </ul>
 * JXPilot extension (only produced by {@link Options#toConfigText()}): {@code \noeol:} before
 * {@code \multiline:} drops the value's final newline, so values like "Map #2" round-trip exactly.
 * <p>
 * Deliberate deviation from C: a line with an empty value (or a value that is only a comment) is an
 * error and does not swallow the following line (C would read the next line as the value).
 */
public final class ConfigParser {
    private final String text;
    private final String fileName;
    private final Options opts;
    private int p;
    private int line = 1;

    private ConfigParser(String text, String fileName, Options opts) {
        this.text = text;
        this.fileName = fileName;
        this.opts = opts;
    }

    /** Parse a file (Latin-1) into {@code opts} with the given origin. Thin I/O wrapper around {@link #parse}. */
    public static void parseFile(Path file, OptionOrigin origin, Options opts) throws IOException {
        parseBytes(Files.readAllBytes(file), file.getFileName().toString(), origin, opts);
    }

    /**
     * Parse the raw bytes of a file, e.g. a bundled map resource.
     * <p>
     * BEHAVIOR: the bytes are decoded as Latin-1 (every byte is one char, as C reads them) and handed to
     * {@link #parse}.
     */
    public static void parseBytes(byte[] bytes, String fileName, OptionOrigin origin, Options opts) {
        parse(new String(bytes, StandardCharsets.ISO_8859_1), fileName, origin, opts);
    }

    /**
     * Parse config text into {@code opts}.
     * <p>
     * BEHAVIOR: text starting with a digit (old v1.x format) is refused with a warning.
     * BEHAVIOR: CRLF line ends are treated as LF.
     * BEHAVIOR: each line is handled as described in the class doc; errors are reported as
     * "file line N: ..." warnings and parsing continues with the next line.
     *
     * @param text     file contents
     * @param fileName name used in warnings
     * @param origin   origin of all values in this text
     * @param opts     destination
     */
    public static void parse(String text, String fileName, OptionOrigin origin, Options opts) {
        String t = text.indexOf('\r') >= 0 ? text.replace("\r\n", "\n") : text;
        if (!t.isEmpty() && Character.isDigit(t.charAt(0))) {
            opts.warn(fileName + " is in old (v1.x) format, which is not supported");
            return;
        }
        ConfigParser cp = new ConfigParser(t, fileName, opts);
        while (cp.p < cp.text.length()) {
            cp.parseLine(origin);
        }
    }

    /**
     * Port of C {@code expandKeyword}: parse the value of a defined macro as config lines.
     * <p>
     * BEHAVIOR: lines are applied with the macro's own origin.
     * BEHAVIOR: an undefined macro produces a warning and nothing else.
     */
    public static void expand(Options opts, String keyword) {
        String v = opts.getRaw(keyword);
        if (v == null) {
            opts.warn("Can't expand `" + keyword + "' because it has not been defined.");
            return;
        }
        OptionOrigin o = opts.getOrigin(keyword);
        ConfigParser cp = new ConfigParser(v, "expand " + keyword, opts);
        while (cp.p < cp.text.length()) {
            cp.parseLine(o == null ? OptionOrigin.MAP : o);
        }
    }

    private static boolean isSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }

    private static boolean isAsciiAlpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Next char or 0 at end (C treats the NUL terminator as end of input). */
    private int next() {
        if (p >= text.length()) {
            return 0;
        }
        return text.charAt(p++);
    }

    private void toEol() {
        while (p < text.length()) {
            char c = text.charAt(p++);
            if (c == '\n') {
                line++;
                return;
            }
        }
    }

    private int skipSpace() {
        while (p < text.length()) {
            char c = text.charAt(p++);
            if (c == '\n') {
                line++;
                return c;
            }
            if (!isSpace(c)) {
                return c;
            }
        }
        return 0;
    }

    private void error(String msg) {
        opts.warn(fileName + " line " + line + ": " + msg);
    }

    private String multilineValue(String delimiter) {
        StringBuilder sb = new StringBuilder(4096);
        int bol = 0;
        while (true) {
            int c = next();
            if (c == 0) {
                return sb.toString();
            }
            if (c == '\n') {
                if (sb.length() - bol == delimiter.length() && sb.indexOf(delimiter, bol) == bol) {
                    sb.setLength(bol);
                    return sb.toString();
                }
                bol = sb.length() + 1;
                line++;
            }
            sb.append((char) c);
        }
    }

    private void parseLine(OptionOrigin origin) {
        int ich = next();
        if (ich == '\n') {
            line++;
            return;
        }
        if (isSpace(ich)) {
            ich = skipSpace();
            if (ich == '\n') {
                return;
            }
        }
        if (ich == '#') {
            toEol();
            return;
        }
        if (ich == 0) {
            return;
        }
        if (!isAsciiAlpha(ich)) {
            error("Names must start with an alphabetic.");
            toEol();
            return;
        }
        StringBuilder name = new StringBuilder();
        name.append((char) ich);
        while (true) {
            ich = next();
            if (ich == '\n' || ich == '#' || ich == 0) {
                error("No colon found on line.");
                if (ich == '#') {
                    toEol();
                } else if (ich == '\n') {
                    line++;
                }
                return;
            }
            if (isSpace(ich)) {
                continue;
            }
            if (ich == ':') {
                break;
            }
            name.append((char) ich);
        }

        // Value: up to '#', newline or end, then trimmed.
        StringBuilder val = new StringBuilder();
        while (true) {
            ich = next();
            if (ich == 0 || ich == '#' || ich == '\n') {
                break;
            }
            val.append((char) ich);
        }
        if (ich == '\n') {
            line++;
        } else if (ich == '#') {
            toEol();
        }
        String value = val.toString().strip();
        String nm = name.toString();

        if (nm.equals("define")) {
            int k = 0;
            while (k < value.length() && !isSpace(value.charAt(k))) {
                k++;
            }
            nm = value.substring(0, k);
            value = value.substring(k).stripLeading();
        }
        boolean override = false;
        if (value.startsWith("\\override:")) {
            override = true;
            value = value.substring(10).stripLeading();
        }
        boolean noeol = false;
        if (value.startsWith("\\noeol:")) {
            noeol = true;
            value = value.substring(7).stripLeading();
        }
        boolean multiline = false;
        if (value.startsWith("\\multiline:")) {
            multiline = true;
            value = value.substring(11).stripLeading();
        }
        if (value.isEmpty() || nm.isEmpty()) {
            error("no value specified.");
            return;
        }
        if (multiline) {
            value = multilineValue(value);
            if (noeol && value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
        }
        if (nm.equals("expand")) {
            expand(opts, value);
        } else {
            opts.setValue(nm, value, override, origin);
        }
    }
}
