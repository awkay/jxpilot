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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loader and replay set-up for the C item traces in {@code src/test/resources/ctrace/items} (patch 0003 line
 * formats, see the README there). Used by {@link CTraceItemsTest}.
 */
final class CTraceItems {
    private CTraceItems() {}

    /** One XPOBJ line. */
    record CObj(int type, int id, int cx, int cy, int prevX, int prevY, float vx, float vy, float ax, float ay,
                float mass, long life, int status, long info, long count, int dir, long fuseLife, int plRange,
                int plRadius, int[] mods, String[] extra) {
        static CObj parse(String[] c) {
            int[] mods = new int[7];
            for (int i = 0; i < 7; i++) {
                mods[i] = Integer.parseInt(c[24 + i]);
            }
            return new CObj(Integer.parseInt(c[4]), Integer.parseInt(c[5]), Integer.parseInt(c[7]),
                Integer.parseInt(c[8]), Integer.parseInt(c[9]), Integer.parseInt(c[10]), Float.parseFloat(c[11]),
                Float.parseFloat(c[12]), Float.parseFloat(c[13]), Float.parseFloat(c[14]), Float.parseFloat(c[15]),
                Long.parseLong(c[16]), (int) Long.decode(c[17]).longValue(), Long.parseLong(c[18]),
                Long.parseLong(c[19]), Integer.parseInt(c[20]), Long.parseLong(c[21]), Integer.parseInt(c[22]),
                Integer.parseInt(c[23]), mods, Arrays.copyOfRange(c, 31, c.length));
        }
    }

    /** One XPPULSE line. */
    record CPulse(int id, float x, float y, int dir, int len, int life, boolean refl) {}

    /** One XPECM line. */
    record CEcm(int id, float x, float y, int size) {}

    /** One XPITEMS line. */
    record CItems(int[] items, int used, int have, long fuel, long fuelMax, int numTanks, int current, long[] tanks,
                  int thrustLeft, int shieldLeft, int phasingLeft, int lockTagged, int lockId, float lockDist,
                  float sensorRange, int forceVisible, int damaged, int stunned, int pulses, int ecms) {
        static CItems parse(String[] c) {
            int[] it = new int[21];
            for (int i = 0; i < 21; i++) {
                it[i] = Integer.parseInt(c[4 + i]);
            }
            long[] t = new long[9];
            for (int i = 0; i < 9; i++) {
                t[i] = Long.parseLong(c[31 + i]);
            }
            return new CItems(it, (int) Long.decode(c[25]).longValue(), (int) Long.decode(c[26]).longValue(),
                Long.parseLong(c[27]), Long.parseLong(c[28]), Integer.parseInt(c[29]), Integer.parseInt(c[30]), t,
                Integer.parseInt(c[40]), Integer.parseInt(c[41]), Integer.parseInt(c[42]), Integer.parseInt(c[43]),
                Integer.parseInt(c[44]), Float.parseFloat(c[45]), Float.parseFloat(c[46]), Integer.parseInt(c[47]),
                Integer.parseInt(c[48]), Integer.parseInt(c[49]), Integer.parseInt(c[50]), Integer.parseInt(c[51]));
        }
    }

    /** Everything the trace holds for one frame of the traced player. */
    static final class CFrame {
        long frame;
        CTraceTest.Frame ship;
        CItems items;
        final List<CObj> objs = new ArrayList<>();
        final List<CPulse> pulses = new ArrayList<>();
        final List<CEcm> ecms = new ArrayList<>();
    }

    static List<CFrame> load(String name) throws IOException {
        Map<Long, CFrame> frames = new LinkedHashMap<>();
        try (InputStream in = CTraceItems.class.getResourceAsStream("/ctrace/items/" + name)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                String[] c = l.trim().split("\\s+");
                if (c.length < 3 || c[0].endsWith("_HDR")) {
                    continue;
                }
                long f;
                try {
                    f = Long.parseLong(c[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                CFrame fr = frames.computeIfAbsent(f, k -> {
                    CFrame n = new CFrame();
                    n.frame = k;
                    return n;
                });
                switch (c[0]) {
                    case "XPTRACE" -> fr.ship = CTraceTest.Frame.parse(l);
                    case "XPOBJ" -> fr.objs.add(CObj.parse(c));
                    case "XPPULSE" -> fr.pulses.add(new CPulse(Integer.parseInt(c[4]), Float.parseFloat(c[6]),
                        Float.parseFloat(c[7]), Integer.parseInt(c[8]), Integer.parseInt(c[9]),
                        Integer.parseInt(c[10]), !"0".equals(c[11])));
                    case "XPECM" -> fr.ecms.add(new CEcm(Integer.parseInt(c[4]), Float.parseFloat(c[5]),
                        Float.parseFloat(c[6]), Integer.parseInt(c[7])));
                    case "XPITEMS" -> fr.items = CItems.parse(c);
                    default -> { }
                }
            }
        }
        List<CFrame> out = new ArrayList<>();
        for (CFrame f : frames.values()) {
            if (f.ship != null && f.items != null) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * The Java world for a trace: default.xp with the C server's settings for these recordings (the C
     * lib/defaults file's SoftWalls, allowModifiers, gravity -0.14 from the command line) plus the scenario's
     * options; random item spawns off (C's are random and never reached the ship), cannons silent.
     */
    static Simulation world(String... opts) {
        try {
            Options o = new Options();
            Path p = Path.of("..", "maps", "default.xp");
            if (!p.toFile().exists()) {
                p = Path.of("maps", "default.xp");
            }
            ConfigParser.parseFile(p, OptionOrigin.MAP, o);
            o.force(Opt.GRAVITY, "-0.14");
            o.force(Opt.MAX_UNSHIELDED_WALL_BOUNCE_SPEED, "180");
            o.force(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED, "180");
            o.force(Opt.MAX_UNSHIELDED_PLAYER_WALL_BOUNCE_ANGLE, "180");
            o.force(Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE, "180");
            o.force(Opt.ALLOW_MODIFIERS, "yes");
            o.force(Opt.SPARKS_WALL_BOUNCE, "no");
            o.force(Opt.ITEM_PROB_MULT, "0");
            for (int i = 0; i + 1 < opts.length; i += 2) {
                o.force(Opt.find(opts[i]), opts[i + 1]);
            }
            Simulation s = new Simulation(new World(o, 1));
            Arrays.fill(s.world.cannonDeadTime, Integer.MAX_VALUE);
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Join the traced player on the base C used (the block of the first traced position), with C's settings. */
    static Player join(Simulation s, CFrame first) {
        World w = s.world;
        Player p = s.ships.join("Refpilot", 0);
        int bx = (first.ship.cx() >> 6) / 35;
        int by = (first.ship.cy() >> 6) / 35;
        for (Base b : w.map.bases()) {
            if (b.bx() == bx && b.by() == by) {
                p.homeBase = b.index();
            }
        }
        s.ships.goHome(p);
        s.ships.applySettings(p, 45f, 35f, 30 / 256f, 35f, 25f, 30 / 256f);
        return p;
    }
}
