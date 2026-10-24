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
package com.fulcrologic.jxpilot.client.viewer;

/**
 * Parsed command line of {@code ClientMain}.
 * <pre>
 *   --view-map MAP         free-flying map viewer on MAP, a bundled map name or a map file (Tab cycles the
 *                          bundled maps, or the maps in the file's directory)
 *   --screenshot FILE.png  render, save a PNG of the framebuffer and exit
 *   --frames N             frames to render before the screenshot (default 3)
 *   --warmup N             frames excluded from the reported statistics and allocation (default frames / 2)
 *   --gpu-timer            measure GPU time per frame with timer queries (costs CPU time on some drivers)
 *   --demo                 animate fake ships, shots, particles and every other object over the map
 *   --size WxH             window size in logical pixels (default 1280x800)
 *   --zoom Z               initial zoom (default 1)
 *   --filled               filled walls instead of XPilot's default outlines
 *   --at X,Y               initial camera centre in world pixels (default: first base, else map centre)
 *   --no-vsync             render as fast as possible (for measuring frame rate)
 *   --alt-scale-factor F   C altScaleFactor: the zoom preset (1/F) the S key swaps to (default 2.0)
 *   --font FILE            TrueType font for all text (default: the bundled Fira Code)
 *   --font-size PX         font size in logical pixels (default 13), baked at the display's pixel ratio
 *   --demo-round-delay N   demo: a round delay of N frames counting down from the start
 *   --demo-damaged N       demo: N frames of ECM damage on the own ship from the start
 * </pre>
 *
 * @param viewMap    map name or file (resolved by MapLocator), or null
 * @param screenshot PNG path, or null
 */
public record LaunchOptions(String viewMap, String screenshot, int frames, int width, int height,
                            double zoom, boolean filled, boolean hasCentre, double centreX, double centreY,
                            boolean vsync, int warmup, boolean gpuTimer, boolean demo, double altScaleFactor,
                            String fontFile, double fontSize, int demoRoundDelay, int demoDamaged) {

    /** Default font size in logical pixels. */
    public static final double DEFAULT_FONT_SIZE = 13;

    /**
     * First measured frame: {@code --warmup} if given, else half the frames.
     * <p>
     * BEHAVIOR: frames before it are excluded from the reported timing and allocation.
     */
    public int measureFrom() {
        return warmup >= 0 ? warmup : frames / 2;
    }

    /**
     * Parse arguments.
     * <p>
     * BEHAVIOR: unknown flags, missing values and malformed numbers throw IllegalArgumentException
     * with a message naming the flag; frames, sizes and zoom must be positive.
     * BEHAVIOR: {@code --warmup} must be at least 0 and below {@code --frames}; -1 (absent) means default.
     * BEHAVIOR: {@code --alt-scale-factor} and {@code --font-size} must be positive; the demo countdowns
     * not negative.
     */
    public static LaunchOptions parse(String[] args) {
        String viewMap = null;
        String screenshot = null;
        int frames = 3;
        int width = 1280;
        int height = 800;
        double zoom = 1;
        boolean filled = false;
        boolean hasCentre = false;
        double cx = 0;
        double cy = 0;
        boolean vsync = true;
        int warmup = -1;
        boolean gpuTimer = false;
        boolean demo = false;
        double altScaleFactor = 2.0;
        String fontFile = null;
        double fontSize = DEFAULT_FONT_SIZE;
        int demoRoundDelay = 0;
        int demoDamaged = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--view-map" -> viewMap = value(args, ++i, a);
                case "--screenshot" -> screenshot = value(args, ++i, a);
                case "--frames" -> frames = positiveInt(value(args, ++i, a), a);
                case "--size" -> {
                    String v = value(args, ++i, a);
                    int x = v.indexOf('x');
                    if (x < 0) {
                        throw new IllegalArgumentException(a + " expects WxH: " + v);
                    }
                    width = positiveInt(v.substring(0, x), a);
                    height = positiveInt(v.substring(x + 1), a);
                }
                case "--zoom" -> zoom = positive(value(args, ++i, a), a);
                case "--filled" -> filled = true;
                case "--no-vsync" -> vsync = false;
                case "--gpu-timer" -> gpuTimer = true;
                case "--demo" -> demo = true;
                case "--alt-scale-factor" -> altScaleFactor = positive(value(args, ++i, a), a);
                case "--font" -> fontFile = value(args, ++i, a);
                case "--font-size" -> fontSize = positive(value(args, ++i, a), a);
                case "--demo-round-delay" -> demoRoundDelay = positiveInt(value(args, ++i, a), a, 0);
                case "--demo-damaged" -> demoDamaged = positiveInt(value(args, ++i, a), a, 0);
                case "--warmup" -> warmup = positiveInt(value(args, ++i, a), a, 0);
                case "--at" -> {
                    String v = value(args, ++i, a);
                    int c = v.indexOf(',');
                    if (c < 0) {
                        throw new IllegalArgumentException(a + " expects X,Y: " + v);
                    }
                    cx = number(v.substring(0, c), a);
                    cy = number(v.substring(c + 1), a);
                    hasCentre = true;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        if (warmup >= frames) {
            throw new IllegalArgumentException("--warmup must be less than --frames: " + warmup + " >= " + frames);
        }
        return new LaunchOptions(viewMap, screenshot, frames, width, height, zoom, filled, hasCentre, cx, cy, vsync,
            warmup, gpuTimer, demo, altScaleFactor, fontFile, fontSize, demoRoundDelay, demoDamaged);
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }

    private static int positiveInt(String v, String flag) {
        return positiveInt(v, flag, 1);
    }

    private static int positiveInt(String v, String flag, int min) {
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects an integer: " + v);
        }
        if (n < min) {
            throw new IllegalArgumentException(flag + (min > 0 ? " must be positive: " : " must not be negative: ") + n);
        }
        return n;
    }

    private static double positive(String v, String flag) {
        double d = number(v, flag);
        if (!(d > 0)) {
            throw new IllegalArgumentException(flag + " must be positive: " + d);
        }
        return d;
    }

    private static double number(String v, String flag) {
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects a number: " + v);
        }
    }
}
