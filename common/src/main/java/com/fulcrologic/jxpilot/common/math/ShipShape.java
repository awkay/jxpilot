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
package com.fulcrologic.jxpilot.common.math;

import java.util.Arrays;

/**
 * A ship outline plus its special points, port of {@code src/common/shipshape.c}.
 * <p>
 * Ship-local coordinates: +x points forward along the heading, +y to the ship's left
 * (world y is up, so rotating by heading h maps (x,y) to (x cos h - y sin h, x sin h + y cos h)).
 * <p>
 * {@link #defaultShip()} is C {@code Default_ship()}. {@link #parse(String)} accepts both the
 * 3.2 keyword format {@code (SH: 15,0 -9,8 -9,-8)(MG: 15,0)...} and the old 3.1 format
 * {@code (3,1,2)(15,0)(-9,8)(-9,-8)}, filling in defaults and applying the C validity limits
 * (C {@code Parse_shape_str}); {@link #convert(String)} skips the limits (C {@code Convert_shape_str}).
 * {@link #toWireString()} is C {@code Convert_ship_2_string}.
 */
public final class ShipShape {
    public static final int MIN_SHIP_PTS = 3;
    public static final int MAX_SHIP_PTS = 24;
    public static final int MAX_GUN_PTS = 3;
    public static final int MAX_LIGHT_PTS = 3;
    public static final int MAX_RACK_PTS = 4;

    private static final ShipShape DEFAULT = new ShipShape("Default", "Unknown",
        new int[] {15, -9, -9}, new int[] {0, 8, -8},
        -9, 0, 15, 0,
        new int[0], new int[0], new int[0], new int[0], new int[0], new int[0], new int[0], new int[0],
        new int[] {-9}, new int[] {8}, new int[] {-9}, new int[] {-8},
        new int[] {15}, new int[] {0});

    public final String name;
    public final String author;
    /** Outline points. */
    public final int[] ptX;
    public final int[] ptY;
    public final int engineX;
    public final int engineY;
    public final int mainGunX;
    public final int mainGunY;
    public final int[] leftGunX;
    public final int[] leftGunY;
    public final int[] rightGunX;
    public final int[] rightGunY;
    public final int[] leftRearGunX;
    public final int[] leftRearGunY;
    public final int[] rightRearGunX;
    public final int[] rightRearGunY;
    public final int[] leftLightX;
    public final int[] leftLightY;
    public final int[] rightLightX;
    public final int[] rightLightY;
    public final int[] missileRackX;
    public final int[] missileRackY;
    /** Shield radius used by the client (C {@code Calculate_shield_radius}). */
    public final int shieldRadius;

    @SuppressWarnings("checkstyle:ParameterNumber")
    private ShipShape(String name, String author, int[] ptX, int[] ptY, int engineX, int engineY,
                      int mainGunX, int mainGunY,
                      int[] lgX, int[] lgY, int[] rgX, int[] rgY, int[] lrgX, int[] lrgY, int[] rrgX, int[] rrgY,
                      int[] llX, int[] llY, int[] rlX, int[] rlY, int[] mrX, int[] mrY) {
        this.name = name;
        this.author = author;
        this.ptX = ptX;
        this.ptY = ptY;
        this.engineX = engineX;
        this.engineY = engineY;
        this.mainGunX = mainGunX;
        this.mainGunY = mainGunY;
        this.leftGunX = lgX;
        this.leftGunY = lgY;
        this.rightGunX = rgX;
        this.rightGunY = rgY;
        this.leftRearGunX = lrgX;
        this.leftRearGunY = lrgY;
        this.rightRearGunX = rrgX;
        this.rightRearGunY = rrgY;
        this.leftLightX = llX;
        this.leftLightY = llY;
        this.rightLightX = rlX;
        this.rightLightY = rlY;
        this.missileRackX = mrX;
        this.missileRackY = mrY;
        double max = 0; // C Calculate_shield_radius works in DFLOAT: no int overflow for converted shapes
        for (int i = 0; i < ptX.length; i++) {
            max = Math.max(max, (double) ptX[i] * ptX[i] + (double) ptY[i] * ptY[i]);
        }
        int r = (int) (2.0 * Math.sqrt(max));
        this.shieldRadius = (r + 2 <= 34) ? 34 : (r + 2 - (r & 1));
    }

    /** The built-in default ship (a triangle). */
    public static ShipShape defaultShip() {
        return DEFAULT;
    }

    public int numPoints() {
        return ptX.length;
    }

    /** Shapes kept by {@link #parseOrDefault} (R10: a player join parses no shape it has seen). */
    static final int CACHE_SIZE = 64;
    private static final java.util.Map<String, ShipShape> CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, ShipShape> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * Parse with the C limits, returning the default ship if the string is empty or invalid
     * (C {@code Parse_shape_str}: what the server uses for players and robots).
     * <p>
     * BEHAVIOR: results are cached by string (the {@link #CACHE_SIZE} most recently used; shapes are
     * immutable), so the same shape string returns the same instance without parsing again; thread safe.
     */
    public static ShipShape parseOrDefault(String s) {
        synchronized (CACHE) {
            ShipShape cached = CACHE.get(s);
            if (cached != null) {
                return cached;
            }
        }
        ShipShape sh = parse(s);
        ShipShape result = sh == null ? DEFAULT : sh;
        synchronized (CACHE) {
            CACHE.put(s, result);
        }
        return result;
    }

    /**
     * Parse without the C size/grid limits, returning the default ship if the string is empty or
     * invalid (C {@code Convert_shape_str}: what the C client uses for its own shape before sending it and
     * for other players' shapes received from the server).
     * <p>
     * BEHAVIOR: the keyword/old-format reading, the 3-point minimum and the defaults are as {@link #parse};
     * the size, width and inside-the-hull checks are skipped, so the old-format engine is never moved.
     * BEHAVIOR: null, empty and invalid strings give {@link #defaultShip()}.
     */
    public static ShipShape convert(String s) {
        ShipShape sh = parse(s, false);
        return sh == null ? DEFAULT : sh;
    }

    /** Rotate a ship-local point by heading (cos, sin): x component. */
    public static double rotX(double x, double y, double cos, double sin) {
        return x * cos - y * sin;
    }

    /** Rotate a ship-local point by heading (cos, sin): y component. */
    public static double rotY(double x, double y, double cos, double sin) {
        return x * sin + y * cos;
    }

    // ---------------------------------------------------------------- wire string

    /** C {@code MSG_LEN}: each half of the wire string stays below this. */
    static final int MSG_LEN = 256;

    /**
     * The two halves of C {@code Convert_ship_2_string}: {@code buf} (sent by every version) and
     * {@code ext} (the overflow, sent to and by peers newer than 3.2; a 3.2+ peer appends it to buf).
     */
    public record WireParts(String buf, String ext) {}

    /**
     * The canonical shape string, C {@code Convert_ship_2_string} for version 3.2 and up, as buf + ext.
     *
     * @see #toWireParts()
     */
    public String toWireString() {
        WireParts w = toWireParts();
        return w.buf() + w.ext();
    }

    /**
     * C {@code Convert_ship_2_string} (shipshape.c, the {@code shape_version >= 0x3200} branch).
     * <p>
     * BEHAVIOR: buf starts with {@code (SH: x,y ...)(EN: x,y)(MG: x,y)}, each point as " x,y".
     * BEHAVIOR: then, in the order LG, RG, LR, RR, LL, RL, MR, each non-empty group {@code (XX: x,y ...)} goes
     * into buf if buf stays below {@link #MSG_LEN} chars, else into ext if ext stays below it, else is dropped.
     * BEHAVIOR: buf itself is never cut (C overflows its buffer there, which a shape within the limits
     * cannot reach).
     */
    public WireParts toWireParts() {
        StringBuilder buf = new StringBuilder(MSG_LEN);
        StringBuilder ext = new StringBuilder(MSG_LEN);
        buf.append("(SH:");
        appendPoints(buf, ptX, ptY);
        buf.append(")(EN: ").append(engineX).append(',').append(engineY)
            .append(")(MG: ").append(mainGunX).append(',').append(mainGunY).append(')');
        StringBuilder tmp = new StringBuilder(MSG_LEN);
        group(buf, ext, tmp, "(LG:", leftGunX, leftGunY);
        group(buf, ext, tmp, "(RG:", rightGunX, rightGunY);
        group(buf, ext, tmp, "(LR:", leftRearGunX, leftRearGunY);
        group(buf, ext, tmp, "(RR:", rightRearGunX, rightRearGunY);
        group(buf, ext, tmp, "(LL:", leftLightX, leftLightY);
        group(buf, ext, tmp, "(RL:", rightLightX, rightLightY);
        group(buf, ext, tmp, "(MR:", missileRackX, missileRackY);
        return new WireParts(buf.toString(), ext.toString());
    }

    private static void appendPoints(StringBuilder sb, int[] xs, int[] ys) {
        for (int i = 0; i < xs.length; i++) {
            sb.append(' ').append(xs[i]).append(',').append(ys[i]);
        }
    }

    private static void group(StringBuilder buf, StringBuilder ext, StringBuilder tmp, String key, int[] xs, int[] ys) {
        if (xs.length == 0) {
            return;
        }
        tmp.setLength(0);
        tmp.append(key);
        appendPoints(tmp, xs, ys);
        tmp.append(')');
        if (buf.length() + tmp.length() < MSG_LEN) {
            buf.append(tmp);
        } else if (ext.length() + tmp.length() < MSG_LEN) {
            ext.append(tmp);
        }
    }

    // ---------------------------------------------------------------- parsing

    private static final String[] KEYS = {
        "shape:", "mainGun:", "leftGun:", "rightGun:", "leftLight:", "rightLight:",
        "engine:", "missileRack:", "name:", "author:", "leftRearGun:", "rightRearGun:"};
    private static final String[] ABBREV = {
        "SH:", "MG:", "LG:", "RG:", "LL:", "RL:", "EN:", "MR:", "NM:", "AU:", "LR:", "RR:"};
    /** C {@code char keyw[20]}: a keyword is read up to its colon, at most 19 chars before it. */
    private static final int KEYW_LEN = 20;

    private static final class Pts {
        final int max;
        final int[] x;
        final int[] y;
        int n;

        Pts(int max) {
            this.max = max;
            this.x = new int[max];
            this.y = new int[max];
        }

        void add(int px, int py) {
            if (n < max) {
                x[n] = px;
                y[n] = py;
                n++;
            }
        }

        int[] xs() { return Arrays.copyOf(x, n); }

        int[] ys() { return Arrays.copyOf(y, n); }
    }

    /** C {@code isspace} in the C locale. */
    private static boolean isCSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    private static boolean isCDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * One sscanf {@code %d} at {@code from}: C whitespace, an optional sign, at least one digit. The value
     * saturates like glibc's strtol at the long range and is then truncated to int, as glibc stores it.
     *
     * @param out receives the value in out[k]
     * @return the index after the number, or -1 if there is none
     */
    private static int scanInt(String s, int from, int[] out, int k) {
        int i = from;
        int n = s.length();
        while (i < n && isCSpace(s.charAt(i))) {
            i++;
        }
        boolean neg = false;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        int st = i;
        long a = 0;
        boolean over = false;
        while (i < n && isCDigit(s.charAt(i))) {
            int d = s.charAt(i) - '0';
            if (a > (Long.MAX_VALUE - d) / 10) {
                over = true;
            } else {
                a = a * 10 + d;
            }
            i++;
        }
        if (i == st) {
            return -1;
        }
        long v = over ? (neg ? Long.MIN_VALUE : Long.MAX_VALUE) : (neg ? -a : a);
        out[k] = (int) v;
        return i;
    }

    /** sscanf("%d,%d") at position; returns null on failure, else {x, y}. */
    private static int[] scanPair(String s, int from) {
        int[] v = new int[2];
        int i = scanInt(s, from, v, 0);
        if (i < 0 || i >= s.length() || s.charAt(i) != ',') {
            return null;
        }
        return scanInt(s, i + 1, v, 1) < 0 ? null : v;
    }

    /** sscanf(s, "(%d,%d,%d)") == 3 at the start of s; returns null on failure, else the three values. */
    static int[] scanTriple(String s) {
        if (!s.startsWith("(")) {
            return null;
        }
        int[] v = new int[3];
        int i = 1;
        for (int k = 0; k < 3; k++) {
            i = scanInt(s, i, v, k);
            if (i < 0) {
                return null;
            }
            if (k < 2) {
                if (i >= s.length() || s.charAt(i) != ',') {
                    return null;
                }
                i++;
            }
        }
        return v;
    }

    /** Parse a list of "x,y" pairs separated by spaces into pts (C loop with strchr(' ')). */
    private static void scanList(String s, Pts pts) {
        int p = 0;
        while (p >= 0) {
            while (p < s.length() && s.charAt(p) == ' ') {
                p++;
            }
            int[] xy = scanPair(s, p);
            if (xy == null) {
                break;
            }
            pts.add(xy[0], xy[1]);
            p = s.indexOf(' ', p);
        }
    }

    /** C {@code Get_shape_keyword}: the key index, {@code table.length} if unknown, -1 if not a letter. */
    private static int keyword(String k) {
        char c = k.charAt(0); // never empty: the key holds at least the char at the '(' + 1

        String[] table = (c >= 'a' && c <= 'z') ? KEYS : ((c >= 'A' && c <= 'Z') ? ABBREV : null);
        if (table == null) {
            return -1;
        }
        for (int i = 0; i < table.length; i++) {
            if (table[i].equals(k)) {
                return i;
            }
        }
        return table.length; // unknown keyword: ignored
    }

    /**
     * The end of the keyword that starts at {@code p} (C shape2wire's keyw loop): the index of its colon,
     * or -1 when there is none. C copies at most {@code KEYW_LEN - 1} chars, then tests the char at that
     * index for the colon without having compared it, so a colon at offset 19 still ends a keyword (one that
     * is then unknown, because its copy lacks the colon).
     */
    private static int keywordEnd(String str, int p) {
        for (int i = 0; p + i < str.length(); i++) {
            if (i == KEYW_LEN - 1) {
                return str.charAt(p + i) == ':' ? p + i : -1;
            }
            if (str.charAt(p + i) == ':') {
                return p + i;
            }
        }
        return -1;
    }

    /**
     * Parse a ship shape string with the C limits (C {@code Parse_shape_str} without the default).
     * <p>
     * BEHAVIOR: reads 3.2 keyword lists (full or abbreviated keywords; unknown keywords ignored; lists
     * capped at 24 points / 3 guns / 3 lights / 4 racks; the first main gun and engine win).
     * BEHAVIOR: a keyword is the text after '(' up to its colon, which must lie within 20 chars; without
     * one the next '(' is tried.
     * BEHAVIOR: reads the 3.1 "(n,left,right)(x,y)..." format (gun = point 0, engine between the lights) when
     * the first '(' is followed by an ASCII digit; the header is C's {@code sscanf("(%d,%d,%d)")}.
     * BEHAVIOR: numbers are C {@code %d}: C whitespace, optional sign, ASCII digits.
     * BEHAVIOR: missing main gun, lights, engine and rack get C's defaults.
     * BEHAVIOR: applies the C limits; any special point outside the hull rejects the shape, except a 3.1
     * engine, which moves to the first hull point on y = 0.
     *
     * @return the shape, or null if it is empty or invalid
     */
    public static ShipShape parse(String str) {
        return parse(str, true);
    }

    /** C {@code shape2wire} with {@code shapeLimits} as given; null for empty or invalid strings. */
    static ShipShape parse(String str, boolean shapeLimits) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        Pts pt = new Pts(MAX_SHIP_PTS);
        Pts lg = new Pts(MAX_GUN_PTS);
        Pts rg = new Pts(MAX_GUN_PTS);
        Pts lrg = new Pts(MAX_GUN_PTS);
        Pts rrg = new Pts(MAX_GUN_PTS);
        Pts ll = new Pts(MAX_LIGHT_PTS);
        Pts rl = new Pts(MAX_LIGHT_PTS);
        Pts mr = new Pts(MAX_RACK_PTS);
        int[] mg = null;
        int[] en = null;
        String name = null;
        String author = null;
        int version = 0;

        int p = 0;
        while ((p = str.indexOf('(', p)) >= 0) {
            p++;
            if (version == 0) {
                if (p < str.length() && isCDigit(str.charAt(p))) {
                    version = 0x3100;
                    break;
                }
                version = 0x3200;
            }
            int colon = keywordEnd(str, p);
            if (colon < 0) {
                continue;
            }
            // C drops the colon from a 19-char key; no keyword is that long, so either way it is unknown.
            String key = str.substring(p, colon + 1);
            int close = str.indexOf(')', colon + 1);
            String val = close < 0 ? str.substring(colon + 1) : str.substring(colon + 1, close);
            p = close < 0 ? str.length() : close + 1;
            switch (keyword(key)) {
                case 0 -> scanList(val, pt);
                case 1 -> {
                    if (mg == null) {
                        mg = scanPair(val, 0);
                    }
                }
                case 2 -> scanList(val, lg);
                case 3 -> scanList(val, rg);
                case 4 -> scanList(val, ll);
                case 5 -> scanList(val, rl);
                case 6 -> {
                    if (en == null) {
                        en = scanPair(val, 0);
                    }
                }
                case 7 -> scanList(val, mr);
                case 8 -> name = val;
                case 9 -> author = val;
                case 10 -> scanList(val, lrg);
                case 11 -> scanList(val, rrg);
                default -> { /* unknown keyword: ignore (future extension) */ }
            }
        }
        if (version == 0x3100) {
            // Old format: (num,left,right)(x,y)...
            int[] hdr = scanTriple(str);
            if (hdr == null || hdr[0] < MIN_SHIP_PTS || hdr[0] > MAX_SHIP_PTS
                || hdr[1] < 0 || hdr[1] >= hdr[0] || hdr[2] < 0 || hdr[2] >= hdr[0]) {
                return null;
            }
            int q = 0;
            for (int i = 0; i < hdr[0]; i++) {
                q = str.indexOf('(', q + 1);
                if (q < 0) {
                    return null;
                }
                int[] xy = scanPair(str, q + 1);
                if (xy == null) {
                    return null;
                }
                pt.add(xy[0], xy[1]);
            }
            mg = new int[] {pt.x[0], pt.y[0]};
            ll.add(pt.x[hdr[1]], pt.y[hdr[1]]);
            rl.add(pt.x[hdr[2]], pt.y[hdr[2]]);
            en = new int[] {(pt.x[hdr[1]] + pt.x[hdr[2]]) / 2, (pt.y[hdr[1]] + pt.y[hdr[2]]) / 2};
        }
        int n = pt.n;
        if (n < MIN_SHIP_PTS) {
            return null;
        }
        if (mg == null) {
            int max = 0;
            for (int i = 1; i < n; i++) {
                if (pt.x[i] > pt.x[max] || (pt.x[i] == pt.x[max] && Math.abs(pt.y[i]) < Math.abs(pt.y[max]))) {
                    max = i;
                }
            }
            mg = new int[] {pt.x[max], pt.y[max]};
        }
        if (ll.n == 0) {
            int max = 0;
            for (int i = 1; i < n; i++) {
                if (pt.y[i] > pt.y[max] || (pt.y[i] == pt.y[max] && pt.x[i] <= pt.x[max])) {
                    max = i;
                }
            }
            ll.add(pt.x[max], pt.y[max]);
        }
        if (rl.n == 0) {
            int max = 0;
            for (int i = 1; i < n; i++) {
                if (pt.y[i] < pt.y[max] || (pt.y[i] == pt.y[max] && pt.x[i] <= pt.x[max])) {
                    max = i;
                }
            }
            rl.add(pt.x[max], pt.y[max]);
        }
        if (en == null) {
            int max = 0;
            for (int i = 1; i < n; i++) {
                if (pt.x[i] < pt.x[max]) {
                    max = i;
                }
            }
            en = new int[] {pt.x[max], 0};
        }
        if (mr.n == 0) {
            mr.add(mg[0], mg[1]);
        }
        if (shapeLimits) {
            en = checkLimits(pt, mg, en, version, lg, rg, lrg, rrg, mr, ll, rl);
            if (en == null) {
                return null;
            }
        }
        return new ShipShape(name == null ? "" : name, author == null ? "" : author,
            pt.xs(), pt.ys(), en[0], en[1], mg[0], mg[1],
            lg.xs(), lg.ys(), rg.xs(), rg.ys(), lrg.xs(), lrg.ys(), rrg.xs(), rrg.ys(),
            ll.xs(), ll.ys(), rl.xs(), rl.ys(), mr.xs(), mr.ys());
    }

    /**
     * The C {@code shapeLimits} block: size, width and the inside-the-hull checks.
     *
     * @return the engine (moved for a 3.1 shape), or null if the shape is invalid
     */
    private static int[] checkLimits(Pts pt, int[] mg, int[] en, int version, Pts... special) {
        if (!sizeLimitsOk(pt.x, pt.y, pt.n) || !widthOk(pt.x, pt.y, pt.n)) {
            return null;
        }
        byte[][] grid = outsideGrid(pt.x, pt.y, pt.n);
        if (outside(grid, mg[0], mg[1])) {
            return null;
        }
        for (Pts sp : special) {
            for (int i = 0; i < sp.n; i++) {
                if (outside(grid, sp.x[i], sp.y[i])) {
                    return null;
                }
            }
        }
        if (outside(grid, en[0], en[1])) {
            int moved = version == 0x3100 ? firstInsideOnAxis(grid) : NO_POINT;
            return moved == NO_POINT ? null : new int[] {moved, 0};
        }
        return en;
    }

    static final int NO_POINT = Integer.MIN_VALUE;

    /**
     * C shapeLimits size checks.
     * <p>
     * BEHAVIOR: needs at least one point with x &lt;= -8, one with x &gt;= 8, one with y &gt;= 8, one with y &lt;= -8,
     * and at least 3 points meeting any of those.
     * BEHAVIOR: every coordinate must be within [-15, 15].
     * BEHAVIOR: (max y - min y) + (max x - min x) over those extreme points must be at least 38.
     */
    static boolean sizeLimitsOk(int[] xs, int[] ys, int n) {
        int low = 0;
        int hi = 0;
        int left = 0;
        int right = 0;
        int count = 0;
        int lowest = 0;
        int highest = 0;
        int leftmost = 0;
        int rightmost = 0;
        for (int i = 0; i < n; i++) {
            int x = xs[i];
            int y = ys[i];
            if (y > 15 || y < -15 || x > 15 || x < -15) {
                return false;
            }
            boolean change = false;
            if (y >= 8) {
                change = true;
                left++;
                leftmost = Math.max(leftmost, y);
            }
            if (y <= -8) {
                change = true;
                right++;
                rightmost = Math.min(rightmost, y);
            }
            if (x <= -8) {
                change = true;
                low++;
                lowest = Math.min(lowest, x);
            }
            if (x >= 8) {
                change = true;
                hi++;
                highest = Math.max(highest, x);
            }
            if (change) {
                count++;
            }
        }
        return low >= 1 && hi >= 1 && left >= 1 && right >= 1 && count >= 3
            && leftmost - rightmost + highest - lowest >= 22 + 16;
    }

    /**
     * C width check.
     * <p>
     * BEHAVIOR: the extent perpendicular to the line through the two farthest-apart points
     * (first such pair) must be at least 12 (truncated).
     */
    static boolean widthOk(int[] xs, int[] ys, int n) {
        int dist = 0;
        int p0 = 0;
        int p1 = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int dx = xs[i] - xs[j];
                int dy = ys[i] - ys[j];
                int d = dx * dx + dy * dy;
                if (d > dist) {
                    dist = d;
                    p0 = i;
                    p1 = j;
                }
            }
        }
        double vx = ys[p1] - ys[p0];
        double vy = xs[p0] - xs[p1];
        double len = Math.hypot(vx, vy);
        vx /= len;
        vy /= len;
        double width = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                width = Math.max(width, Math.abs(vx * (xs[i] - xs[j]) + vy * (ys[i] - ys[j])));
            }
        }
        return (int) width >= 12;
    }

    /**
     * Rasterize the outline on a 31x31 grid (index +15) and flood-fill from the border.
     * <p>
     * BEHAVIOR: returns a grid where 2 marks points outside the hull, 1 the outline and 0 the inside.
     */
    static byte[][] outsideGrid(int[] xs, int[] ys, int n) {
        byte[][] g = new byte[31][31];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            g[xs[i] + 15][ys[i] + 15] = 1;
            int dx = xs[j] - xs[i];
            int dy = ys[j] - ys[i];
            if (Math.abs(dx) >= Math.abs(dy)) {
                int from = dx > 0 ? i : j;
                int lo = Math.min(xs[i], xs[j]);
                int hiX = Math.max(xs[i], xs[j]);
                for (int x = lo + 1; x < hiX; x++) {
                    g[x + 15][ys[from] + (dy * (x - xs[from])) / dx + 15] = 1;
                }
            } else {
                int from = dy > 0 ? i : j;
                int lo = Math.min(ys[i], ys[j]);
                int hiY = Math.max(ys[i], ys[j]);
                for (int y = lo + 1; y < hiY; y++) {
                    g[xs[from] + (dx * (y - ys[from])) / dy + 15][y + 15] = 1;
                }
            }
        }
        int[] qx = new int[31 * 31];
        int[] qy = new int[31 * 31];
        int todo = 0;
        for (int y = -15; y <= 15; y++) {
            for (int x = -15; x <= 15; x += (y == -15 || y == 15) ? 1 : 30) {
                if (g[x + 15][y + 15] == 0) {
                    g[x + 15][y + 15] = 2;
                    qx[todo] = x;
                    qy[todo++] = y;
                }
            }
        }
        for (int done = 0; done < todo; done++) {
            int x = qx[done];
            int y = qy[done];
            // the four neighbours without a per-cell array (R10: this loop allocated ~100 KB per shape)
            for (int k = 0; k < 4; k++) {
                int cx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
                int cy = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (cx >= -15 && cx <= 15 && cy >= -15 && cy <= 15 && g[cx + 15][cy + 15] == 0) {
                    g[cx + 15][cy + 15] = 2;
                    qx[todo] = cx;
                    qy[todo++] = cy;
                }
            }
        }
        return g;
    }

    /**
     * C relocation of an old-format engine: the first non-outside point on y = 0 scanning x from -15.
     * <p>
     * BEHAVIOR: returns that x, or {@link #NO_POINT} if the axis is entirely outside.
     */
    static int firstInsideOnAxis(byte[][] g) {
        for (int x = -15; x <= 15; x++) {
            if (g[x + 15][15] != 2) {
                return x;
            }
        }
        return NO_POINT;
    }

    /**
     * BEHAVIOR: true if (x,y) is marked outside the hull.
     * BEHAVIOR: a coordinate of exactly 16 (the other within [-15, 16]) is inside: C's grid is
     * {@code pt[32][32]} indexed +15 and never marks row or column 31 (shipshape.c:176).
     * BEHAVIOR: any coordinate below -15 or above 16 is outside (C reads outside its grid there: undefined).
     */
    static boolean outside(byte[][] g, int x, int y) {
        if (x < -15 || x > 16 || y < -15 || y > 16) {
            return true;
        }
        return x <= 15 && y <= 15 && g[x + 15][y + 15] == 2;
    }
}
