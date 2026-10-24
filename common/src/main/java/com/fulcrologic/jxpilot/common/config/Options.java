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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed option database, a port of {@code src/server/option.c}.
 * <p>
 * Every {@link Opt} starts at its cmdline.c default. Values are set with
 * {@link #setValue(String, String, boolean, OptionOrigin)} which applies the same origin precedence
 * rules as C {@code Option_change_node} (command line beats map/defaults, {@code \override:} lets the
 * defaults file beat the map, etc.). Unknown names are retained verbatim (they may be {@code define:}
 * macros or options this implementation ignores).
 * <p>
 * Reads are cheap array lookups ({@link #getDouble(Opt)} etc.) and allocation free.
 * Values that fail to parse produce a warning and fall back to the default, as in C.
 */
public final class Options {
    private static final Opt[] OPTS = Opt.values();
    private static final int N = OPTS.length;

    private final String[] raw = new String[N];
    private final OptionOrigin[] origin = new OptionOrigin[N];
    private final boolean[] override = new boolean[N];
    private final double[] num = new double[N];
    private final int[] posX = new int[N];
    private final int[] posY = new int[N];

    private final Map<String, Unknown> unknown = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /** An option name not in {@link Opt}, kept as a string. */
    public static final class Unknown {
        public final String name;
        public String value;
        public OptionOrigin origin;
        public boolean override;

        Unknown(String name, String value, OptionOrigin origin, boolean override) {
            this.name = name;
            this.value = value;
            this.origin = origin;
            this.override = override;
        }
    }

    /** Create an option set with all cmdline.c defaults. */
    public Options() {
        for (Opt o : OPTS) {
            int i = o.ordinal();
            raw[i] = o.defaultValue();
            origin[i] = OptionOrigin.INIT;
            convert(o, o.defaultValue(), false);
        }
    }

    // ------------------------------------------------------------------ setting

    /**
     * Set an option by any of its names, honouring origin precedence and per-option permissions.
     *
     * BEHAVIOR: a known option settable from {@code from} and allowed by {@link #allowChange} is stored
     * and converted; a forbidden origin is refused with a warning.
     * BEHAVIOR: unknown names are retained (created on first non-null value) and follow the same precedence.
     *
     * @param name     option name (case/whitespace-insensitive) or an unknown name to retain
     * @param value    the string value (already trimmed); null clears an unknown option's value
     * @param override true when the value had an {@code \override:} prefix
     * @param from     where the value comes from
     * @return true if the value was stored
     */
    public boolean setValue(String name, String value, boolean override, OptionOrigin from) {
        Opt o = Opt.find(name);
        if (o == null) {
            String key = Opt.normalize(name);
            Unknown u = unknown.get(key);
            if (u == null) {
                if (value == null) {
                    return false;
                }
                unknown.put(key, new Unknown(name, value, from, override));
                return true;
            }
            if (!allowChange(u.origin, u.override, from, override)) {
                return false;
            }
            u.value = value;
            u.origin = from;
            u.override = override;
            return true;
        }
        if (!o.allowedFrom(from)) {
            warn("Not allowed to change option '" + name + "' from " + from + ".");
            return false;
        }
        int i = o.ordinal();
        if (!allowChange(origin[i], this.override[i], from, override)) {
            return false;
        }
        raw[i] = value;
        origin[i] = from;
        this.override[i] = override;
        convert(o, value, true);
        return true;
    }

    /** BEHAVIOR: sets and converts the value, bypassing precedence, recorded as an overriding command-line value. */
    public void force(Opt o, String value) {
        int i = o.ordinal();
        raw[i] = value;
        origin[i] = OptionOrigin.COMMAND;
        override[i] = true;
        convert(o, value, true);
    }

    /**
     * Port of the precedence switch in C {@code Option_change_node}.
     * <p>
     * BEHAVIOR: command line and INIT always win; nothing beats the command line except itself.
     * BEHAVIOR: defaults replace defaults/INIT, and the map only with {@code \override:}.
     * BEHAVIOR: the map replaces INIT and non-override defaults/map values; never password values.
     * BEHAVIOR: the password file beats defaults and map; replaces password values without override.
     */
    static boolean allowChange(OptionOrigin cur, boolean curOverride, OptionOrigin from, boolean newOverride) {
        return switch (from) {
            case COMMAND, INIT -> true;
            case DEFAULTS -> switch (cur) {
                case COMMAND, PASSWORD -> false;
                case DEFAULTS -> !curOverride;
                case MAP -> newOverride;
                case INIT -> true;
            };
            case MAP -> switch (cur) {
                case COMMAND, PASSWORD -> false;
                case DEFAULTS, MAP -> !curOverride;
                case INIT -> true;
            };
            case PASSWORD -> switch (cur) {
                case COMMAND -> false;
                case PASSWORD -> !curOverride;
                case DEFAULTS, MAP, INIT -> true;
            };
        };
    }

    private void convert(Opt o, String value, boolean warnOnError) {
        int i = o.ordinal();
        String dflt = o.defaultValue();
        switch (o.type()) {
            case INT -> {
                long[] r = new long[1];
                if (value != null && CNum.strtol(value, r)) {
                    num[i] = (int) r[0];
                } else {
                    if (warnOnError && value != null) {
                        warn(o.optionName() + " value '" + value + "' not an integral number.");
                    }
                    num[i] = (dflt != null && CNum.strtol(dflt, r)) ? (int) r[0] : 0;
                }
            }
            case REAL, SEC, PER_SEC -> {
                double[] r = new double[1];
                if (value != null && CNum.strtod(value, r)) {
                    num[i] = r[0];
                } else {
                    if (warnOnError && value != null) {
                        warn(o.optionName() + " value '" + value + "' not a number.");
                    }
                    num[i] = (dflt != null && CNum.strtod(dflt, r)) ? r[0] : 0.0;
                }
            }
            case BOOL -> {
                int b = value == null ? -1 : CNum.bool(value);
                if (b < 0) {
                    if (warnOnError && value != null) {
                        warn(o.optionName() + " value '" + value + "' not a boolean.");
                    }
                    b = dflt == null ? 0 : Math.max(0, CNum.bool(dflt));
                }
                num[i] = b;
            }
            case IPOS -> {
                if (!parsePos(value, i)) {
                    if (warnOnError && value != null) {
                        warn(o.optionName() + " value '" + value + "' not a valid position.");
                    }
                    parsePos(dflt, i); // every IPOS default ("0,0") is valid
                }
            }
            default -> {
                // STRING, LIST, VOID: raw value only
            }
        }
    }

    private boolean parsePos(String value, int i) {
        if (value == null) {
            return false;
        }
        int comma = value.indexOf(',');
        if (comma < 0) {
            return false;
        }
        long[] a = new long[1];
        long[] b = new long[1];
        if (!CNum.strtol(value, a) || !CNum.strtol(value.substring(comma + 1), b)) {
            return false;
        }
        posX[i] = (int) a[0];
        posY[i] = (int) b[0];
        return true;
    }

    // ------------------------------------------------------------------ reading

    /** Integer value (INT, BOOL as 0/1, SEC/REAL truncated). */
    public int getInt(Opt o) {
        return (int) num[o.ordinal()];
    }

    /** Numeric value. For {@link OptionType#SEC} this is seconds as written. */
    public double getDouble(Opt o) {
        return num[o.ordinal()];
    }

    /** Boolean value (non-zero numeric). */
    public boolean getBool(Opt o) {
        return num[o.ordinal()] != 0.0;
    }

    /** Raw string value or default; null when none (e.g. mapData not given). */
    public String getString(Opt o) {
        return raw[o.ordinal()];
    }

    public int getPosX(Opt o) {
        return posX[o.ordinal()];
    }

    public int getPosY(Opt o) {
        return posY[o.ordinal()];
    }

    /**
     * A {@link OptionType#SEC} option converted to original XPilot frames exactly as C does:
     * {@code (int)(seconds * framesPerSecond)}.
     */
    public int getFrames(Opt o) {
        return (int) (num[o.ordinal()] * framesPerSecond());
    }

    /** A {@link OptionType#PER_SEC} option converted to per-frame: {@code value / framesPerSecond}. */
    public double getPerFrame(Opt o) {
        return num[o.ordinal()] / framesPerSecond();
    }

    /** Comma separated LIST option split as C {@code Convert_string_to_list}. */
    public List<String> getList(Opt o) {
        List<String> out = new ArrayList<>();
        String v = raw[o.ordinal()];
        if (v != null) {
            for (String s : v.split(",")) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Where the current value came from. */
    public OptionOrigin originOf(Opt o) {
        return origin[o.ordinal()];
    }

    /** True if the option was explicitly set (not the built-in default). */
    public boolean isSet(Opt o) {
        return origin[o.ordinal()] != OptionOrigin.INIT;
    }

    /** Raw value for any name: known options, then unknown/macro names; null if absent. */
    public String getRaw(String name) {
        Opt o = Opt.find(name);
        if (o != null) {
            return raw[o.ordinal()];
        }
        Unknown u = unknown.get(Opt.normalize(name));
        return u == null ? null : u.value;
    }

    /** Origin for any name; null if unknown and never set. */
    public OptionOrigin getOrigin(String name) {
        Opt o = Opt.find(name);
        if (o != null) {
            return origin[o.ordinal()];
        }
        Unknown u = unknown.get(Opt.normalize(name));
        return u == null ? null : u.origin;
    }

    /** Options not in {@link Opt}, keyed by normalized name (insertion ordered). */
    public Map<String, Unknown> unknownOptions() {
        return Collections.unmodifiableMap(unknown);
    }

    /** Warnings produced while setting values (and by the config parser). */
    public List<String> warnings() {
        return warnings;
    }

    /** Append a warning. */
    public void warn(String msg) {
        warnings.add(msg);
    }

    // ------------------------------------------------------------------ conveniences

    /** {@code framesPerSecond} (original game FPS); never less than 1. */
    public int framesPerSecond() {
        int f = getInt(Opt.FRAMES_PER_SECOND);
        return f <= 0 ? 1 : f;
    }

    /** The map data text or null. */
    public String mapData() {
        return getString(Opt.MAP_DATA);
    }

    // ------------------------------------------------------------------ command line

    /**
     * Parse XPilot-style command-line arguments: {@code -name value}, and for booleans
     * {@code -name} (true) / {@code +name} (false). Unknown arguments are returned. An {@code -expand}
     * list is only recorded; call {@link #finishParsing()} after reading all files.
     */
    public List<String> parseCommandLine(String[] args) {
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.length() > 1 && (a.charAt(0) == '-' || a.charAt(0) == '+')) {
                String n = a.startsWith("--") ? a.substring(2) : a.substring(1);
                Opt o = Opt.find(n);
                if (o == null) {
                    rest.add(a);
                    continue;
                }
                if (o.type() == OptionType.BOOL) {
                    setValue(o.optionName(), a.charAt(0) == '-' ? "true" : "false", true, OptionOrigin.COMMAND);
                } else if (o.type() != OptionType.VOID) {
                    if (i + 1 >= args.length) {
                        warn("Option '" + a + "' needs an argument");
                    } else {
                        setValue(o.optionName(), args[++i], true, OptionOrigin.COMMAND);
                    }
                }
            } else {
                rest.add(a);
            }
        }
        return rest;
    }

    /**
     * Final step after all sources (command line, defaults, password and map files) are read:
     * expands the {@code expand} list (C {@code Options_parse_expand}, run from {@code Options_parse}).
     * <p>
     * BEHAVIOR: each listed macro is parsed as config lines with the macro's own origin.
     * BEHAVIOR: macros may be defined in any source read before this call.
     * BEHAVIOR: does nothing when {@code expand} was not set.
     */
    public void finishParsing() {
        if (isSet(Opt.EXPAND)) {
            for (String kw : getList(Opt.EXPAND)) {
                ConfigParser.expand(this, kw);
            }
        }
    }

    // ------------------------------------------------------------------ serialization

    /**
     * Serialize all known options that are visible, map-settable and have a value (plus the map's unknown options
     * and mapData) as config-file text that {@link ConfigParser} reads back to the same values.
     * Used to ship the effective configuration and map to clients.
     */
    public String toConfigText() {
        return toConfigText(mapData());
    }

    /**
     * {@link #toConfigText()} with another map text in place of mapData (null: no map), e.g. the map as clients
     * may see it.
     * <p>
     * BEHAVIOR: identical to toConfigText() except for the mapData value.
     */
    public String toConfigText(String clientMapData) {
        StringBuilder sb = new StringBuilder(8192);
        for (Opt o : OPTS) {
            int i = o.ordinal();
            String v = raw[i];
            if (v == null || o == Opt.MAP_DATA || o.type() == OptionType.VOID || !o.visible()
                || !o.allowedFrom(OptionOrigin.MAP)) {
                continue;
            }
            appendValue(sb, o.optionName(), v);
        }
        for (Unknown u : unknown.values()) {
            // only the map's own unknown names: macros and settings of the defaults file, command line or
            // password file are the server's business
            if (u.value != null && u.origin == OptionOrigin.MAP && Character.isLetter(u.name.charAt(0))) {
                appendValue(sb, u.name, u.value);
            }
        }
        if (clientMapData != null) {
            appendValue(sb, Opt.MAP_DATA.optionName(), clientMapData);
        }
        return sb.toString();
    }

    /**
     * Append one {@code name: value} so that {@link ConfigParser} reads back exactly {@code v}.
     * <p>
     * BEHAVIOR: a value without newline, CR, '#', surrounding ASCII whitespace or leading backslash, and
     * not empty, is written on one line.
     * BEHAVIOR: other values use {@code \multiline:} with a delimiter not occurring as a line; a value
     * not ending in a newline gets the JXPilot {@code \noeol:} prefix so the reader drops the final newline;
     * each CR before a line feed is doubled because the reader turns CRLF into LF.
     */
    static void appendValue(StringBuilder sb, String name, String v) {
        sb.append(name).append(": ");
        if (!needsMultiline(v)) {
            sb.append(v).append('\n');
            return;
        }
        boolean noeol = !v.isEmpty() && v.charAt(v.length() - 1) != '\n';
        String body = noeol ? v + "\n" : v;
        body = body.replace("\r\n", "\r\r\n");
        String delim = "EndOfValue";
        int n = 0;
        while (containsLine(body, delim)) {
            delim = "EndOfValue" + (++n);
        }
        if (noeol) {
            sb.append("\\noeol: ");
        }
        sb.append("\\multiline: ").append(delim).append('\n');
        sb.append(body);
        sb.append(delim).append('\n');
    }

    /** BEHAVIOR: true iff a value cannot survive a single config line (see {@link #appendValue}). */
    static boolean needsMultiline(String v) {
        if (v.isEmpty() || v.charAt(0) == '\\') {
            return true;
        }
        if (isAsciiSpace(v.charAt(0)) || isAsciiSpace(v.charAt(v.length() - 1))) {
            return true;
        }
        return v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('#') >= 0;
    }

    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }

    private static boolean containsLine(String v, String line) {
        int from = 0;
        while (true) {
            int idx = v.indexOf(line, from);
            if (idx < 0) {
                return false;
            }
            boolean startOk = idx == 0 || v.charAt(idx - 1) == '\n';
            int end = idx + line.length();
            boolean endOk = end == v.length() || v.charAt(end) == '\n';
            if (startOk && endOk) {
                return true;
            }
            from = idx + 1;
        }
    }
}
