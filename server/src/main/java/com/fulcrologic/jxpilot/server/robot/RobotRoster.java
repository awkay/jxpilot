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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.server.world.ShipGeom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The robot personalities a server picks from (C {@code Robots[]}/{@code MAX_ROBOTS}, {@code robot.c}), with
 * their usage counts and pre-built ship geometry (so creating a robot never allocates).
 * <p>
 * {@link #defaults()} is the XPilot 4.5.5 robots file {@code lib/robots}, which the C server reads by default
 * ({@code robotFile} defaults to {@code LIBDIR/robots}). It has the same 38 robots as the compiled-in
 * {@code DefaultRobots[]} table of robot.c, but five ship shapes differ (Droidion, Slugger, Chrome Star,
 * Sparky, Cobra); the C reference harness uses the file, so we do too.
 */
public final class RobotRoster {
    /** C {@code DEFAULT_ROBOT_TYPE}. */
    public static final String DEFAULT_TYPE = "default";
    /** C {@code MAX_NAME_LEN} (pack.h): name and type buffers hold 15 characters. */
    static final int MAX_NAME_LEN = 16;
    /** C {@code MSG_LEN}: the {@code para:} buffer. */
    static final int PARA_LEN = 256;
    /** C {@code MAX_CHARS}: {@code robot_t.config}. */
    static final int CONFIG_LEN = 80;
    /** C {@code 2*MSG_LEN}: the {@code ship:} buffer. */
    static final int SHIP_LEN = 512;
    /** C {@code ship_buf} default before the first {@code ship:} line. */
    static final String DEFAULT_SHIP = "(15,0)(-9,8)(-9,-8)";

    private static final RobotDef[] LIB_ROBOTS = {
        new RobotDef(DEFAULT_TYPE, "Mad Max", "94 20", "(15,8,7)(15,0)(7,1)(7,2)(2,4)(-1,11)(-3,11)(-2,3)(-8,6)(-8,-6)(-2,-3)(-3,-11)(-1,-11)(2,-4)(7,-2)(7,-1)"),
        new RobotDef(DEFAULT_TYPE, "Blackie", "10 90", "(16,6,10)(15,0)(6,2)(-2,3)(-1,4)(-2,5)(-10,8)(-13,8)(-13,1)(-15,0)(-13,-1)(-13,-8)(-10,-8)(-2,-5)(-1,-4)(-2,-3)(6,-2)"),
        new RobotDef(DEFAULT_TYPE, "Kryten", "70 40", "(4,1,3)(15,0)(0,8)(-8,0)(0,-8)"),
        new RobotDef(DEFAULT_TYPE, "Marvin", "30 70", "(15,4,5)(10,0)(10,7)(5,14)(-5,14)(-10,7)(-10,-7)(-5,-14)(5,-14)(10,-7)(10,0)(5,5)(2,7)(5,0)(2,-7)(5,-5)"),
        new RobotDef(DEFAULT_TYPE, "R2D2", "50 60", "(15,8,9)(15,0)(14,1)(-1,2)(-2,9)(0,10)(-4,10)(-7,2)(-8,2)(-8,-2)(-7,-2)(-4,-10)(0,-10)(-2,-9)(-1,-2)(14,-1)"),
        new RobotDef(DEFAULT_TYPE, "C3PO", "60 50", "(16,1,15)(10,0)(0,5)(0,15)(15,10)(0,15)(-15,10)(0,15)(0,5)(-7,0)(0,-5)(0,-15)(-15,-10)(0,-15)(15,-10)(0,-15)(0,-5)"),
        new RobotDef(DEFAULT_TYPE, "K9", "50 50", "(14,0,5)(15,0)(15,5)(5,5)(5,-5)(15,-5)(15,0)(-15,0)(-15,5)(5,5)(5,-5)(-15,-5)(-15,-8)(-15,8)(-15,0)"),
        new RobotDef(DEFAULT_TYPE, "Robby", "45 55", "(5,2,3)(15,0)(0,12)(-9,8)(-9,-8)(0,-12)"),
        new RobotDef(DEFAULT_TYPE, "Mickey", "05 95", "(15,6,7)(5,-1)(8,-5)(7,-9)(4,-11)(-1,-10)(-5,-6)(-8,-10)(-8,10)(-5,6)(-1,10)(4,11)(7,9)(8,5)(5,1)(0,0)"),
        new RobotDef(DEFAULT_TYPE, "Hermes", "15 85", "(16,12,11)(10,1)(12,8)(-11,8)(-10,3)(-7,0)(-5,2)(-7,0)(-10,-1)(-10,-3)(-13,-4)(-13,-7)(-15,-8)(-15,-13)(-5,-5)(-2,-4)(5,-2)"),
        new RobotDef(DEFAULT_TYPE, "Pan", "60 60", "(14,6,5)(15,-1)(15,0)(5,0)(5,-1)(5,9)(-15,9)(-15,-4)(-5,-7)(-3,-8)(-7,-8)(-5,-7)(5,-4)(5,-1)(-15,-1)"),
        new RobotDef(DEFAULT_TYPE, "Azurion", "40 30", "(6,2,4)(15,0)(0,2)(-9,8)(-3,0)(-9,-8)(0,-2)"),
        new RobotDef(DEFAULT_TYPE, "Droidion", "60 30", "(7,2,4)(10,0)(5,9)(-6,9)(-11,0)(-6,-9)(5,-9)(10,0)"),
        new RobotDef(DEFAULT_TYPE, "Terminator", "80 40", "(6,2,4)(15,0)(0,2)(-9,8)(-3,0)(-9,-8)(0,-2)"),
        new RobotDef(DEFAULT_TYPE, "Sniper", "30 90", "(15,6,9)(15,0)(4,2)(-2,8)(-4,7)(-3,2)(-8,5)(-8,2)(-6,1)(-6,-1)(-8,-2)(-8,-5)(-3,-2)(-4,-7)(-2,-8)(4,-2)"),
        new RobotDef(DEFAULT_TYPE, "Slugger", "40 40", "(16,8,7)(15,0)(13,1)(3,2)(-1,8)(-3,8)(-3,2)(-5,2)(-8,5)(-8,-5)(-5,-2)(-3,-2)(-3,-8)(-1,-8)(3,-2)(13,-1)(15,0)"),
        new RobotDef(DEFAULT_TYPE, "Uzi", "95 5", "(16,9,8)(15,3)(7,3)(7,-8)(3,-8)(3,1)(-2,1)(-3,-1)(-5,-1)(-14,-5)(-15,2)(-3,4)(-1,8)(0,6)(13,6)(14,8)(15,6)"),
        new RobotDef(DEFAULT_TYPE, "Capone", "80 50", "(15,9,6)(14,0)(2,2)(0,8)(1,8)(-3,8)(-3,2)(-8,4)(-7,1)(-7,-1)(-8,-4)(-3,-2)(-3,-8)(1,-8)(0,-8)(2,-2)"),
        new RobotDef(DEFAULT_TYPE, "Tanx", "40 70", "(16,7,8)(15,1)(2,0)(1,-2)(6,-3)(6,-5)(3,-8)(-10,-8)(-13,-6)(-13,-3)(-10,-2)(-11,2)(-7,2)(-7,8)(-7,2)(1,2)(2,1)"),
        new RobotDef(DEFAULT_TYPE, "Chrome Star", "60 60", "(6,1,4)(10,0)(-10,6)(2,-10)(2,10)(-10,-6)(10,0)"),
        new RobotDef(DEFAULT_TYPE, "Bully", "80 10", "(15,6,9)(11,0)(12,-3)(9,-3)(8,-2)(-5,-5)(-9,-11)(-14,-14)(-5,-3)(-5,3)(-14,14)(-9,11)(-5,5)(8,2)(9,3)(12,3)"),
        new RobotDef(DEFAULT_TYPE, "Metal Hero", "40 45", "(16,7,9)(15,5)(12,-2)(9,-2)(10,-1)(-8,-1)(-4,-1)(1,-3)(-13,-9)(-9,0)(-15,8)(1,3)(-4,1)(-8,1)(-8,-1)(-8,1)(11,1)"),
        new RobotDef(DEFAULT_TYPE, "Aurora", "60 55", "(16,5,11)(15,0)(-1,3)(-3,5)(-3,9)(7,10)(-12,10)(-6,9)(-6,4)(-8,0)(-6,-4)(-6,-9)(-12,-10)(7,-10)(-3,-9)(-3,-5)(-1,-3)"),
        new RobotDef(DEFAULT_TYPE, "Dalt Wisney", "30 75", "(16,10,6)(14,0)(7,-4)(0,-1)(-5,-4)(2,-8)(0,-10)(-14,-10)(-5,-7)(-14,0)(-5,7)(-14,10)(0,10)(2,8)(-5,4)(0,1)(7,4)"),
        new RobotDef(DEFAULT_TYPE, "Psycho", "65 55", "(11,5,6)(8,0)(5,8)(3,12)(0,15)(0,0)(-8,3)(-8,-3)(0,0)(0,-15)(3,-12)(5,-8)"),
        new RobotDef(DEFAULT_TYPE, "Gorgon", "30 40", "(15,7,8)(15,0)(5,2)(3,8)(2,2)(-9,2)(-10,4)(-12,2)(-14,4)(-14,-4)(-12,-2)(-10,-4)(-9,-2)(2,-2)(3,-8)(5,-2)"),
        new RobotDef(DEFAULT_TYPE, "Pompel", "50 50", "(15,7,8)(15,0)(14,4)(10,5)(5,2)(-7,3)(-7,6)(5,8)(-9,8)(-9,-8)(5,-8)(-7,-6)(-7,-3)(5,-2)(10,-5)(14,-4)"),
        new RobotDef(DEFAULT_TYPE, "Pilt", "50 50", "(16,8,7)(15,0)(13,-2)(9,-3)(3,-3)(-3,-3)(-5,-2)(-13,-2)(-15,-3)(-15,3)(-13,2)(-5,2)(-3,3)(-3,-8)(-3,8)(-3,3)(8,3)"),
        new RobotDef(DEFAULT_TYPE, "Sparky", "20 40", "(19,11,10)(15,-8)(6,-5)(7,-4)(1,-2)(2,-1)(-4,0)(-3,2)(-9,5)(-8,9)(-9,5)(-15,8)(-15,2)(-8,0)(-9,-2)(-3,-3)(-4,-4)(3,-5)(2,-7)(15,-8)"),
        new RobotDef(DEFAULT_TYPE, "Cobra", "85 60", "(17,5,11)(8,0)(8,-6)(6,-8)(0,-7)(5,-6)(-14,-4)(5,-2)(0,-1)(5,0)(0,1)(5,2)(-14,4)(5,6)(0,7)(6,8)(8,6)(8,0)"),
        new RobotDef(DEFAULT_TYPE, "Falcon", "70 20", "(16,5,6)(14,2)(14,4)(2,10)(-5,10)(-10,8)(-12,3)(-12,-3)(-10,-8)(-5,-10)(9,-11)(10,-8)(7,-8)(14,-4)(14,-2)(4,-2)(4,2)"),
        new RobotDef(DEFAULT_TYPE, "Boson", "25 35", "(16,11,12)(15,0)(10,-5)(4,-8)(7,-2)(7,2)(4,8)(6,0)(4,-8)(-10,-8)(-10,8)(-10,-8)(-15,-7)(-15,7)(-10,8)(4,8)(10,5)"),
        new RobotDef(DEFAULT_TYPE, "Blazy", "40 40", "(12,4,8)(4,0)(2,4)(-5,11)(10,12)(-8,12)(-4,6)(-2,0)(-4,-6)(-8,-12)(10,-12)(-5,-11)(2,-4)"),
        new RobotDef(DEFAULT_TYPE, "Pixie", "15 93", "(13,6,7)(15,0)(7,4)(11,1)(-4,3)(3,5)(-7,10)(-9,2)(-9,-2)(-7,-10)(3,-5)(-4,-3)(11,-1)(7,-4)"),
        new RobotDef(DEFAULT_TYPE, "Wimpy", "5 98", "(16,9,7)(3,0)(6,5)(8,10)(5,11)(1,10)(-1,8)(-4,9)(-8,6)(-5,0)(-8,-6)(-4,-9)(-1,-8)(1,-10)(5,-11)(8,-10)(6,-5)"),
        new RobotDef(DEFAULT_TYPE, "Bonnie", "30 40", "(17,9,6)(15,3)(5,3)(5,1)(4,-1)(0,-1)(-2,-8)(-8,-8)(-5,3)(-6,6)(-8,7)(-7,8)(-4,7)(10,7)(12,8)(14,8)(15,7)(15,3)"),
        new RobotDef(DEFAULT_TYPE, "Clyde", "40 45", "(16,5,11)(14,0)(5,5)(6,2)(0,2)(0,8)(-13,8)(-13,4)(-4,4)(-6,0)(-4,-4)(-13,-4)(-13,-8)(0,-8)(0,-2)(6,-2)(5,-5)"),
        new RobotDef(DEFAULT_TYPE, "Neuro", "70 70", "(16,7,5)(12,-7)(12,-12)(5,-12)(2,-10)(1,-5)(-9,-4)(-11,2)(-8,8)(-3,11)(3,11)(9,8)(11,2)(13,0)(12,-3)(12,-7)(7,-7)"),
    };

    private final RobotDef[] defs;
    private final ShipGeom[] ships;
    /** C {@code robot_t.used}: how often each robot has been picked. */
    final int[] used;

    RobotRoster(RobotDef[] defs) {
        this.defs = defs.clone();
        this.ships = new ShipGeom[defs.length];
        this.used = new int[defs.length];
        for (int i = 0; i < defs.length; i++) {
            ShipShape s = ShipShape.parse(defs[i].shape());
            ships[i] = s == null ? ShipGeom.defaultShip() : new ShipGeom(s);
        }
    }

    /** The 38 robots of XPilot 4.5.5 {@code lib/robots}. */
    public static RobotRoster defaults() {
        return new RobotRoster(LIB_ROBOTS);
    }

    /**
     * C {@code Parse_robot_file} on the file text.
     * <p>
     * BEHAVIOR: lines starting with {@code name:}, {@code ship:}, {@code type:}, {@code para:} set that field
     * (leading and trailing white space removed, truncated to the C buffer: 15, 511, 15 and 255 characters;
     * the config is then cut to 79 as C {@code strcpy}s it into {@code config[MAX_CHARS]}).
     * BEHAVIOR: an empty line or the end ends a record; a record is kept only if it had a {@code name:} line.
     * BEHAVIOR: fields keep their value into the next record (C reuses its buffers): type starts
     * {@code default}, para empty, ship {@code (15,0)(-9,8)(-9,-8)}.
     * BEHAVIOR: a file without any named record gives {@link #defaults()} (C falls back to its table).
     */
    public static RobotRoster parse(CharSequence text) {
        List<RobotDef> out = new ArrayList<>();
        String name = "";
        String ship = DEFAULT_SHIP;
        String type = DEFAULT_TYPE;
        String para = "";
        boolean gotName = false;
        String[] lines = text.toString().split("\n", -1);
        for (int i = 0; i <= lines.length; i++) {
            boolean end = i == lines.length || lines[i].isEmpty();
            if (!end) {
                String l = lines[i];
                if (l.startsWith("name:")) {
                    name = value(l, MAX_NAME_LEN);
                    gotName = true;
                } else if (l.startsWith("ship:")) {
                    ship = value(l, SHIP_LEN);
                } else if (l.startsWith("type:")) {
                    type = value(l, MAX_NAME_LEN);
                } else if (l.startsWith("para:")) {
                    para = value(l, PARA_LEN);
                }
            }
            if (end && gotName) {
                gotName = false;
                out.add(new RobotDef(type, name, cut(para, CONFIG_LEN), ship));
            }
        }
        if (out.isEmpty()) {
            return defaults();
        }
        return new RobotRoster(out.toArray(new RobotDef[0]));
    }

    /** Value after the colon: leading white space skipped, cut to {@code size - 1}, trailing white space removed. */
    private static String value(String line, int size) {
        int p = line.indexOf(':') + 1;
        while (p < line.length() && Character.isWhitespace(line.charAt(p))) {
            p++;
        }
        String v = cut(line.substring(p), size);
        int e = v.length();
        while (e > 0 && Character.isWhitespace(v.charAt(e - 1))) {
            e--;
        }
        return v.substring(0, e);
    }

    private static String cut(String s, int size) {
        return s.length() >= size ? s.substring(0, size - 1) : s;
    }

    /**
     * Read a robots file (edge: file I/O).
     * <p>
     * BEHAVIOR: an unreadable file gives {@link #defaults()} (C: {@code fopen} failure).
     */
    public static RobotRoster load(Path file) {
        try {
            return parse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            return defaults();
        }
    }

    /** Number of robots (C {@code MAX_ROBOTS}). */
    public int size() {
        return defs.length;
    }

    /** Robot {@code i}. */
    public RobotDef def(int i) {
        return defs[i];
    }

    /** Ship geometry of robot {@code i}: its parsed shape, or the default ship if the shape is invalid. */
    public ShipGeom ship(int i) {
        return ships[i];
    }

    /** How often robot {@code i} has been picked. */
    public int used(int i) {
        return used[i];
    }
}
