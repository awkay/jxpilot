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
package com.fulcrologic.jxpilot.client.render;

import java.util.Locale;

/**
 * A font to bake: a bundled {@link Face} or a TrueType file, at a size in logical pixels. Parsed from a C
 * font resource ({@code gameFont}, {@code messageFont}, ...) by {@link #parse}. Pure value; equal specs share
 * one bake.
 *
 * @param face bundled face, or null for {@code file}
 * @param file TrueType file path, or null for {@code face}
 * @param size size in logical pixels (X pixel size)
 */
public record FontSpec(Face face, String file, float size) {
    /** Smallest size accepted from a font value. */
    public static final int MIN_SIZE = 4;
    /** Largest size accepted from a font value. */
    public static final int MAX_SIZE = 200;

    /** Families of the bundled Liberation faces. */
    public enum Family { SERIF, SANS, MONO }

    /** Bundled faces (fonts/ resources): Liberation 2.1.5 and Fira Code, all SIL OFL 1.1. */
    public enum Face {
        SERIF_REGULAR("LiberationSerif-Regular", Family.SERIF, false, false),
        SERIF_BOLD("LiberationSerif-Bold", Family.SERIF, true, false),
        SERIF_ITALIC("LiberationSerif-Italic", Family.SERIF, false, true),
        SERIF_BOLD_ITALIC("LiberationSerif-BoldItalic", Family.SERIF, true, true),
        SANS_REGULAR("LiberationSans-Regular", Family.SANS, false, false),
        SANS_BOLD("LiberationSans-Bold", Family.SANS, true, false),
        SANS_BOLD_ITALIC("LiberationSans-BoldItalic", Family.SANS, true, true),
        MONO_REGULAR("LiberationMono-Regular", Family.MONO, false, false),
        MONO_BOLD("LiberationMono-Bold", Family.MONO, true, false),
        /** JXPilot's earlier single font; never chosen for an XLFD, only by name. */
        FIRA_CODE("FiraCode-Regular", null, false, false);

        /** File name without ".ttf" (also the name {@link #parse} accepts). */
        public final String fileName;
        /** Liberation family, or null for Fira Code. */
        public final Family family;
        public final boolean bold;
        public final boolean italic;

        Face(String fileName, Family family, boolean bold, boolean italic) {
            this.fileName = fileName;
            this.family = family;
            this.bold = bold;
            this.italic = italic;
        }

        /** Classpath resource of the face. */
        public String resource() {
            return "/fonts/" + fileName + ".ttf";
        }

        /**
         * The bundled face nearest to a family and style.
         * <p>
         * BEHAVIOR: the exact face when bundled; else the same weight upright (every family has both weights
         * upright), e.g. Mono bold italic is Mono Bold and Sans italic is Sans Regular.
         */
        public static Face of(Family family, boolean bold, boolean italic) {
            Face f = find(family, bold, italic);
            return f != null ? f : find(family, bold, false);
        }

        private static Face find(Family family, boolean bold, boolean italic) {
            for (Face f : values()) {
                if (f.family == family && f.bold == bold && f.italic == italic) {
                    return f;
                }
            }
            return null;
        }

        /**
         * A bundled face by name.
         * <p>
         * BEHAVIOR: matches a face's file name ("LiberationSerif-Bold", "FiraCode-Regular") or a family
         * name, meaning its regular face ("LiberationSerif", "LiberationSans", "LiberationMono",
         * "FiraCode"), ignoring case and spaces; anything else is null.
         */
        public static Face byName(String name) {
            String n = name.replace(" ", "").toLowerCase(Locale.ROOT);
            for (Face f : values()) {
                String file = f.fileName.toLowerCase(Locale.ROOT);
                if (n.equals(file) || (n + "-regular").equals(file)) {
                    return f;
                }
            }
            return null;
        }
    }

    /**
     * A parsed font value: the spec to use and, when the value was not usable as given, why (the spec is
     * then the fallback).
     *
     * @param spec    the font to bake
     * @param problem null when the value was used, else a short reason
     */
    public record Result(FontSpec spec, String problem) {}

    /** A bundled face at {@code size}. */
    public static FontSpec of(Face face, float size) {
        return new FontSpec(face, null, size);
    }

    /**
     * BEHAVIOR: for each spec, the index of the first equal spec (its own index when none comes before it):
     * equal specs share one bake.
     */
    public static int[] shareIndex(FontSpec[] specs) {
        int[] share = new int[specs.length];
        for (int i = 0; i < specs.length; i++) {
            share[i] = i;
            for (int j = 0; j < i; j++) {
                if (specs[j].equals(specs[i])) {
                    share[i] = j;
                    break;
                }
            }
        }
        return share;
    }

    /** This font at another size. */
    public FontSpec withSize(float s) {
        return new FontSpec(face, file, s);
    }

    /**
     * Parse a font resource value.
     * <p>
     * BEHAVIOR: an XLFD ("-foundry-family-weight-slant-setwidth-style-pixels-points-resx-resy-spacing-width-
     * registry-encoding", leading fields only allowed) maps to a bundled Liberation face: the family by
     * {@link #family}; bold iff the weight names bold, black or heavy; italic iff the slant is i, o, ri or ro.
     * BEHAVIOR: the XLFD size is its pixel size when positive, else its point size / 10 when positive, else
     * the fallback's size; an XLFD with more than 14 fields is unusable.
     * BEHAVIOR: other values may end in ":SIZE" (digits): a value with a path separator or a .ttf/.otf/.ttc
     * ending is a TrueType file; a bundled name ({@link Face#byName}) is that face; the X aliases "fixed"
     * (Mono 13) and "WxH" / "WxHbold" (Mono / Mono Bold at H) are Mono faces; without ":SIZE" a file or name
     * takes the fallback's size.
     * BEHAVIOR: null, blank, unknown names and sizes outside {@link #MIN_SIZE}..{@link #MAX_SIZE} give the
     * fallback with a problem.
     */
    public static Result parse(String value, FontSpec fallback) {
        if (value == null || value.isBlank()) {
            return new Result(fallback, "no font given");
        }
        String v = value.strip();
        FontSpec spec;
        if (v.startsWith("-")) {
            spec = xlfd(v, fallback.size);
            if (spec == null) {
                return new Result(fallback, "not an XLFD font name");
            }
        } else {
            spec = named(v, fallback.size);
            if (spec == null) {
                return new Result(fallback, "unknown font");
            }
        }
        if (spec.size < MIN_SIZE || spec.size > MAX_SIZE) {
            return new Result(fallback, "size " + fmt(spec.size) + " is outside " + MIN_SIZE + ".." + MAX_SIZE);
        }
        return new Result(spec, null);
    }

    private static String fmt(float s) {
        return s == (int) s ? Integer.toString((int) s) : Float.toString(s);
    }

    private static FontSpec xlfd(String v, float defaultSize) {
        String[] f = v.split("-", -1);
        if (f.length > 15) {
            return null;
        }
        String family = field(f, 2);
        boolean bold = isBold(field(f, 3));
        boolean italic = isItalic(field(f, 4));
        String spacing = field(f, 11);
        boolean mono = spacing.equalsIgnoreCase("c") || spacing.equalsIgnoreCase("m");
        Family fam = family(family, mono, field(f, 4).equalsIgnoreCase("o"));
        int px = number(field(f, 7));
        int pt = number(field(f, 8));
        float size = px > 0 ? px : pt > 0 ? Math.round(pt / 10f) : defaultSize;
        return of(Face.of(fam, bold, italic), size);
    }

    private static String field(String[] f, int i) {
        return i < f.length ? f[i] : "*";
    }

    /** BEHAVIOR: a decimal number of 1 to 4 digits, else 0 (wildcards, empty, junk, too long). */
    static int number(String s) {
        return digits(s) ? Integer.parseInt(s) : 0;
    }

    /** BEHAVIOR: true iff {@code s} is 1 to 4 decimal digits. */
    static boolean digits(String s) {
        if (s.isEmpty() || s.length() > 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** BEHAVIOR: an XLFD weight is bold iff it contains "bold", "black" or "heavy" (any case). */
    static boolean isBold(String weight) {
        String w = weight.toLowerCase(Locale.ROOT);
        return w.contains("bold") || w.contains("black") || w.contains("heavy");
    }

    /** BEHAVIOR: an XLFD slant is italic iff it is i, o, ri or ro (any case). */
    static boolean isItalic(String slant) {
        String s = slant.toLowerCase(Locale.ROOT);
        return s.equals("i") || s.equals("o") || s.equals("ri") || s.equals("ro");
    }

    /**
     * The Liberation family for an XLFD family.
     * <p>
     * BEHAVIOR: families naming mono, fixed, courier, clean, typewriter or terminal are MONO; then any naming
     * "sans" is SANS; then times, century, schoolbook, serif, utopia, charter, palatino, bookman, bright
     * (lucidabright) or georgia are SERIF; then helvetica, lucida, arial, avant(garde), gothic or verdana are
     * SANS (any case).
     * BEHAVIOR: a wildcard or unknown family is MONO for spacing c or m; else SANS for slant o (X's oblique
     * faces are the sans ones: helvetica, lucida); else SERIF.
     */
    static Family family(String family, boolean monoSpacing, boolean oblique) {
        String f = family.toLowerCase(Locale.ROOT);
        if (any(f, "mono", "fixed", "courier", "clean", "typewriter", "terminal")) {
            return Family.MONO;
        }
        if (f.contains("sans")) {
            return Family.SANS;
        }
        if (any(f, "times", "century", "schoolbook", "serif", "utopia", "charter", "palatino", "bookman", "bright",
            "georgia")) {
            return Family.SERIF;
        }
        if (any(f, "helvetica", "lucida", "arial", "avant", "gothic", "verdana")) {
            return Family.SANS;
        }
        return monoSpacing ? Family.MONO : oblique ? Family.SANS : Family.SERIF;
    }

    private static boolean any(String s, String... words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static FontSpec named(String v, float defaultSize) {
        String base = v;
        float size = -1;
        int colon = v.lastIndexOf(':');
        if (colon > 0 && digits(v.substring(colon + 1))) {
            base = v.substring(0, colon);
            size = Integer.parseInt(v.substring(colon + 1));
        }
        if (isPath(base)) {
            return new FontSpec(null, base, size >= 0 ? size : defaultSize);
        }
        Face face = Face.byName(base);
        if (face != null) {
            return of(face, size >= 0 ? size : defaultSize);
        }
        FontSpec alias = xAlias(base.toLowerCase(Locale.ROOT));
        if (alias != null && size >= 0) {
            return alias.withSize(size);
        }
        return alias;
    }

    /** BEHAVIOR: a path has a '/' or '\' or ends in .ttf, .otf or .ttc (any case). */
    static boolean isPath(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || l.endsWith(".ttf") || l.endsWith(".otf")
            || l.endsWith(".ttc");
    }

    /** X core font aliases: "fixed" is 6x13; "WxH" and "WxHbold" (or "WxHB") are fixed fonts of height H. */
    private static FontSpec xAlias(String a) {
        if (a.equals("fixed")) {
            return of(Face.MONO_REGULAR, 13);
        }
        int x = a.indexOf('x');
        if (x <= 0 || number(a.substring(0, x)) == 0) {
            return null;
        }
        String rest = a.substring(x + 1);
        boolean bold = rest.endsWith("bold") || rest.endsWith("b");
        String h = bold ? rest.substring(0, rest.length() - (rest.endsWith("bold") ? 4 : 1)) : rest;
        int height = number(h);
        return height > 0 ? of(bold ? Face.MONO_BOLD : Face.MONO_REGULAR, height) : null;
    }
}
