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
package com.fulcrologic.jxpilot.client.game.options;

import java.util.Locale;

/**
 * The values of every {@link ClientOption}: C's defaults, then the resource file and command line
 * ({@link #apply}), then the CONFIG panel ({@link #setFlag}, {@link #setInteger}, {@link #setReal}). Reads are
 * array lookups (allocation free, safe in the render loop); every change bumps {@link #version()}, so a consumer
 * can notice it with one int compare per frame.
 * <p>
 * Parsing follows C's {@code Get_*_resource}: a bool is true iff the text starts with "true", "on" or "yes"
 * (any case, C {@code ON}); ints and floats are clamped to the option's range. Unlike C (which reads garbage as
 * 0), a number that does not parse is reported and leaves the value unchanged.
 */
public final class ClientOptions {
    private static final ClientOption[] ALL = ClientOption.values();

    private final boolean[] flags = new boolean[ALL.length];
    private final double[] numbers = new double[ALL.length];
    private final String[] texts = new String[ALL.length];
    private final boolean[] given = new boolean[ALL.length];
    private int version;
    /** Set by {@link #parse} when it stored a value. */
    private boolean stored;

    /** BEHAVIOR: every option at C's default ({@link ClientOption#defaultText}), none {@link #given}. */
    public ClientOptions() {
        for (ClientOption o : ALL) {
            String p = parse(o, o.defaultText, null);
            if (p != null) {
                throw new IllegalStateException(o.resource + " default: " + p);
            }
        }
        java.util.Arrays.fill(given, false);
        version = 0;
    }

    /** A copy (same values, given flags and version). */
    public ClientOptions copy() {
        ClientOptions c = new ClientOptions();
        System.arraycopy(flags, 0, c.flags, 0, flags.length);
        System.arraycopy(numbers, 0, c.numbers, 0, numbers.length);
        System.arraycopy(texts, 0, c.texts, 0, texts.length);
        System.arraycopy(given, 0, c.given, 0, given.length);
        c.version = version;
        return c;
    }

    /**
     * Set an option from resource text (a file line or a command-line value).
     * <p>
     * BEHAVIOR: BOOL is C {@code ON(text)}; STRING keeps the text as given; INT accepts an integer and FLOAT any
     * decimal number (both trimmed; an INT also accepts a decimal number and truncates it, as C's {@code atoi}
     * stops at the dot); a value outside the range is clamped and reported; text that is no number is reported
     * and changes nothing.
     * BEHAVIOR: a successful set marks the option {@link #given} and bumps {@link #version()}.
     *
     * @return null, or a problem message (without file and line)
     */
    public String apply(ClientOption o, String text) {
        stored = false;
        String p = parse(o, text, o.resource);
        if (stored) {
            given[o.ordinal()] = true;
            version++;
        }
        return p;
    }

    private String parse(ClientOption o, String text, String name) {
        int i = o.ordinal();
        switch (o.type) {
            case BOOL -> {
                flags[i] = on(text);
                stored = true;
            }
            case STRING -> {
                texts[i] = text;
                stored = true;
            }
            default -> {
                double v;
                String t = text.trim();
                try {
                    v = Double.parseDouble(t);
                } catch (NumberFormatException e) {
                    return o.resource + ": not a number: '" + text + "'";
                }
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    return o.resource + ": not a number: '" + text + "'";
                }
                if (o.type == ClientOption.Type.INT) {
                    v = (long) v;
                }
                if (o.min != o.max && (v < o.min || v > o.max)) {
                    double c = Math.max(o.min, Math.min(o.max, v));
                    numbers[i] = c;
                    stored = true;
                    return name == null ? "out of range" : o.resource + ": " + t + " is outside " + format(o, o.min)
                        + "-" + format(o, o.max) + ", using " + format(o, c);
                }
                numbers[i] = v;
                stored = true;
            }
        }
        return null;
    }

    /** BEHAVIOR: C {@code ON}: the text starts with "true", "on" or "yes", ignoring case (leading blanks skipped). */
    public static boolean on(String text) {
        String t = text.stripLeading().toLowerCase(Locale.ROOT);
        return t.startsWith("true") || t.startsWith("on") || t.startsWith("yes");
    }

    /** True iff the option was set by the file, the command line or the panel (not only C's default). */
    public boolean given(ClientOption o) {
        return given[o.ordinal()];
    }

    /** Incremented by every change. */
    public int version() {
        return version;
    }

    /** A BOOL option's value. */
    public boolean flag(ClientOption o) {
        return flags[o.ordinal()];
    }

    /** An INT option's value. */
    public int integer(ClientOption o) {
        return (int) numbers[o.ordinal()];
    }

    /** A FLOAT (or INT) option's value. */
    public double real(ClientOption o) {
        return numbers[o.ordinal()];
    }

    /** A STRING option's value (never null). */
    public String text(ClientOption o) {
        return texts[o.ordinal()];
    }

    /** BEHAVIOR: sets a BOOL option (panel); bumps the version iff the value changed; marks it given. */
    public void setFlag(ClientOption o, boolean v) {
        int i = o.ordinal();
        given[i] = true;
        if (flags[i] != v) {
            flags[i] = v;
            version++;
        }
    }

    /** BEHAVIOR: sets an INT option clamped to its range (panel); bumps the version iff the value changed. */
    public void setInteger(ClientOption o, int v) {
        setReal(o, v);
    }

    /**
     * BEHAVIOR: sets a FLOAT (INT: truncated) option clamped to its range (panel); bumps the version iff the value
     * changed; marks it given.
     */
    public void setReal(ClientOption o, double v) {
        int i = o.ordinal();
        double c = o.type == ClientOption.Type.INT ? (long) v : v;
        if (o.min != o.max) {
            c = Math.max(o.min, Math.min(o.max, c));
        }
        given[i] = true;
        if (numbers[i] != c) {
            numbers[i] = c;
            version++;
        }
    }

    /** BEHAVIOR: sets a STRING option; bumps the version iff the text changed; marks it given. */
    public void setText(ClientOption o, String v) {
        int i = o.ordinal();
        given[i] = true;
        if (!texts[i].equals(v)) {
            texts[i] = v;
            version++;
        }
    }

    /**
     * The value as C's {@code Config_save} writes it.
     * <p>
     * BEHAVIOR: BOOL "True"/"False" ({@code Config_save_bool}); INT "%d"; FLOAT "%.3f" ({@code Config_save_float});
     * STRING as is.
     */
    public String saveText(ClientOption o) {
        return switch (o.type) {
            case BOOL -> flag(o) ? "True" : "False";
            case INT -> Integer.toString(integer(o));
            case FLOAT -> String.format(Locale.ROOT, "%.3f", real(o));
            case STRING -> text(o);
        };
    }

    private static String format(ClientOption o, double v) {
        return o.type == ClientOption.Type.INT || v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
