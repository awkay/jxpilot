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
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.net.DefaultsFile;
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
 * Loader and replay set-up for the C item traces in {@code src/test/resources/ctrace/items} (patch 0004 line
 * formats, and the patch 0006 lines of the replay traces; see the README there). Used by {@link CTraceItemsTest}.
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

    /**
     * One XPRAND line (patch 0006): a C {@code rfrac()} ({@code real}) or {@code randomMT()} draw, its C function and
     * {@code file:line}; {@code caller} is the C function that called {@code Make_debris} for the draws inside it,
     * and {@code first} marks the first draw of each {@code Make_debris} call (its particle count).
     */
    record CDraw(String func, String site, boolean real, long value, String caller, boolean first) {
        /** The C {@code rfrac()} value of this draw: value / 2^32, exact in a double. */
        double rfrac() {
            return value * 0x1p-32;
        }
    }

    /** One XPDEBRIS line (patch 0006): a C {@code Make_debris} call and its caller. */
    record CDebris(String caller, String site, float x, float y, float vx, float vy, int id, int team, int type,
                   float mass, long status, int color, int radius, int minDebris, int maxDebris, int minDir,
                   int maxDir, float minSpeed, float maxSpeed, int minLife, int maxLife) {
        static CDebris parse(String[] c) {
            return new CDebris(c[2], c[3], f(c[4]), f(c[5]), f(c[6]), f(c[7]), Integer.parseInt(c[8]),
                Integer.parseInt(c[9]), Integer.parseInt(c[10]), f(c[11]), Long.parseLong(c[12]),
                Integer.parseInt(c[13]), Integer.parseInt(c[14]), Integer.parseInt(c[15]), Integer.parseInt(c[16]),
                Integer.parseInt(c[17]), Integer.parseInt(c[18]), f(c[19]), f(c[20]), Integer.parseInt(c[21]),
                Integer.parseInt(c[22]));
        }
    }

    /** One XPCANNON line (patch 0006): a cannon that is not idle. */
    record CCannon(int index, int deadTime, int damaged, int tractorCount, int used, int shieldLeft,
                   int phasingLeft) {}

    /** A C {@code %a} (or decimal) float. */
    static float f(String s) {
        return (float) Double.parseDouble(s);
    }

    /** Everything the trace holds for one frame. {@code ship}/{@code items} are the first traced player's. */
    static final class CFrame {
        long frame;
        CTraceTest.Frame ship;
        CItems items;
        final List<CObj> objs = new ArrayList<>();
        final List<CPulse> pulses = new ArrayList<>();
        final List<CEcm> ecms = new ArrayList<>();
        /** Every traced player of the frame by C id, in C's order: physics, status word, name, items. */
        final Map<Integer, CTraceTest.Frame> ships = new LinkedHashMap<>();
        final Map<Integer, Integer> status = new LinkedHashMap<>();
        final Map<Integer, String> names = new LinkedHashMap<>();
        final Map<Integer, CItems> itemsOf = new LinkedHashMap<>();
        /** The C draws, Make_debris calls and keyboard updates of this frame's tick (they precede its XPTRACE). */
        final List<CDraw> draws = new ArrayList<>();
        final List<CDebris> debris = new ArrayList<>();
        /**
         * The draws of each {@link #debris} call: the particle count, then dir, dir fraction, speed and life of each
         * particle (only the count for the thrust exhaust); empty when the call made nothing.
         */
        final List<List<CDraw>> debrisDraws = new ArrayList<>();
        /** Keyboard updates of this tick: player id to its key mask (Java {@link ControlKey} bits). */
        final Map<Integer, Long> keys = new LinkedHashMap<>();
        /** Players whose client sent two keyboard updates in this tick (Java sees only the last). */
        final List<Integer> keysTwice = new ArrayList<>();
        final List<CCannon> cannons = new ArrayList<>();
        /** XPDEB: explosion particles (sparks and debris that are not owner-immune), as {@link CReplay#particle}. */
        final List<String> debs = new ArrayList<>();
        /** The Obj[] slot of each XPDEB particle, by its "cx,cy vx,vy". */
        final Map<String, Integer> debSlot = new LinkedHashMap<>();
    }

    static List<CFrame> load(String name) throws IOException {
        Map<Long, CFrame> frames = new LinkedHashMap<>();
        List<CDraw> draws = new ArrayList<>();
        List<CDebris> debris = new ArrayList<>();
        List<List<CDraw>> debrisDraws = new ArrayList<>();
        Map<Integer, Long> keys = new LinkedHashMap<>();
        List<Integer> twice = new ArrayList<>();
        String caller = null;
        boolean first = false;
        try (InputStream in = name.startsWith("/") ? java.nio.file.Files.newInputStream(Path.of(name))
            : CTraceItems.class.getResourceAsStream("/ctrace/items/" + name)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                String[] c = l.trim().split("\\s+");
                if (c.length < 3 || c[0].endsWith("_HDR")) {
                    continue;
                }
                // patch 0006 lines carry no usable frame number: they belong to the tick of the next XPTRACE
                switch (c[0]) {
                    case "XPRAND" -> {
                        // one value, or several in order (tools/xpilot-ref/compact-rand.sh)
                        boolean inDebris = "Make_debris".equals(c[1]);
                        for (int v = 4; v < c.length; v++) {
                            CDraw d = new CDraw(c[1], c[2], "f".equals(c[3]), Long.parseLong(c[v]),
                                inDebris ? caller : null, inDebris && first);
                            draws.add(d);
                            if (inDebris && !debrisDraws.isEmpty()) {
                                debrisDraws.get(debrisDraws.size() - 1).add(d);
                            }
                            first = false;
                        }
                        continue;
                    }
                    case "XPDEBRIS" -> {
                        CDebris d = CDebris.parse(c);
                        debris.add(d);
                        debrisDraws.add(new ArrayList<>());
                        caller = d.caller();
                        first = true;
                        continue;
                    }
                    case "XPKEYS" -> {
                        long mask = 0;
                        if (!"-".equals(c[3])) {
                            for (String k : c[3].split(",")) {
                                ControlKey key = ControlKey.ofXpilotId(Integer.parseInt(k));
                                if (key != null) {
                                    mask |= key.mask();
                                }
                            }
                        }
                        int id = Integer.parseInt(c[2]);
                        if (keys.containsKey(id)) {
                            twice.add(id);
                        }
                        keys.put(id, mask);
                        continue;
                    }
                    default -> { }
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
                if ("XPTRACE".equals(c[0]) && fr.ships.isEmpty()) {
                    fr.draws.addAll(draws);
                    fr.debris.addAll(debris);
                    fr.debrisDraws.addAll(debrisDraws);
                    fr.keys.putAll(keys);
                    fr.keysTwice.addAll(twice);
                    twice.clear();
                    draws.clear();
                    debris.clear();
                    debrisDraws.clear();
                    keys.clear();
                }
                switch (c[0]) {
                    case "XPTRACE" -> {
                        CTraceTest.Frame sh = CTraceTest.Frame.parse(l);
                        int id = Integer.parseInt(c[3]);
                        if (fr.ship == null) {
                            fr.ship = sh;
                        }
                        fr.ships.put(id, sh);
                        fr.status.put(id, (int) Long.decode(c[21]).longValue());
                        fr.names.put(id, String.join(" ", Arrays.copyOfRange(c, 22, c.length)));
                    }
                    case "XPDEB" -> {
                        fr.debSlot.put(c[6] + "," + c[7] + " " + f(c[8]) + "," + f(c[9]), Integer.parseInt(c[3]));
                        fr.debs.add(CReplay.particle(Integer.parseInt(c[4]), Integer.parseInt(c[5]),
                        Integer.parseInt(c[6]), Integer.parseInt(c[7]), f(c[8]), f(c[9]), f(c[10]), Long.parseLong(c[11]),
                        (int) Long.decode(c[12]).longValue()));
                    }
                    case "XPCANNON" -> fr.cannons.add(new CCannon(Integer.parseInt(c[3]), Integer.parseInt(c[4]),
                        Integer.parseInt(c[5]), Integer.parseInt(c[6]), (int) Long.decode(c[7]).longValue(),
                        Integer.parseInt(c[8]), Integer.parseInt(c[9])));
                    case "XPOBJ" -> fr.objs.add(CObj.parse(c));
                    case "XPPULSE" -> fr.pulses.add(new CPulse(Integer.parseInt(c[4]), Float.parseFloat(c[6]),
                        Float.parseFloat(c[7]), Integer.parseInt(c[8]), Integer.parseInt(c[9]),
                        Integer.parseInt(c[10]), !"0".equals(c[11])));
                    case "XPECM" -> fr.ecms.add(new CEcm(Integer.parseInt(c[4]), Float.parseFloat(c[5]),
                        Float.parseFloat(c[6]), Integer.parseInt(c[7])));
                    case "XPITEMS" -> {
                        CItems it = CItems.parse(c);
                        if (fr.items == null) {
                            fr.items = it;
                        }
                        fr.itemsOf.put(Integer.parseInt(c[3]), it);
                    }
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
     * The Java world of a replay trace, configured as the C server was: the bundled lib/defaults, then the map,
     * then the scenario's command line ({@code opts}: option name and value pairs). Nothing is silenced: item
     * spawns, cannons and every random draw follow C through {@link CDraws}.
     */
    static Simulation cworld(String map, String... opts) {
        try {
            Options o = new Options();
            DefaultsFile.applyBundled(o);
            Path p = Path.of("..", "maps", map);
            if (!p.toFile().exists()) {
                p = Path.of("maps", map);
            }
            if (!p.toFile().exists()) {
                p = Path.of("src", "test", "resources", "ctrace", "items", map); // a test map (items-arena.xp)
            }
            ConfigParser.parseFile(p, OptionOrigin.MAP, o);
            for (int i = 0; i + 1 < opts.length; i += 2) {
                o.force(Opt.find(opts[i]), opts[i + 1]);
            }
            return new Simulation(new World(o, 1));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
