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
package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.math.XMath;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Port of {@code Grok_map}, {@code Find_base_direction}, {@code Find_closest_team} and
 * {@code Find_base_order} ({@code src/server/map.c}). {@link #build()} orchestrates; the steps are
 * small static functions tested on their own.
 */
final class MapLoader {
    /** C prints at most this many row errors of each kind, then "And so on...". */
    static final int MAX_ROW_ERRORS = 5;

    private final Options opts;
    int w;
    int h;
    String name;
    String author;
    boolean edgeWrap;
    boolean teamPlay;
    boolean timing;
    byte[] blocks;
    int[] itemIds;
    double[] gravX;
    double[] gravY;
    final List<Base> bases = new ArrayList<>();
    final List<FuelStation> fuels = new ArrayList<>();
    final List<Cannon> cannons = new ArrayList<>();
    final List<Target> targets = new ArrayList<>();
    final List<Treasure> treasures = new ArrayList<>();
    final List<GravitySource> gravs = new ArrayList<>();
    final List<Wormhole> wormholes = new ArrayList<>();
    final List<Checkpoint> checkpoints = new ArrayList<>();
    final List<Concentrator> itemConcs = new ArrayList<>();
    final List<Concentrator> asteroidConcs = new ArrayList<>();
    int[] baseOrder = new int[0];
    final int[] teamBases = new int[Const.MAX_TEAMS];
    int numTeamBases;
    final List<String> warnings = new ArrayList<>();

    /** Checkpoint letter occurrences (C {@code NumChecks}). */
    int numChecks;
    private int checkAX;
    private int checkAY;

    /** {bx, by, team, digit} per base until directions are known. */
    private final List<int[]> baseData = new ArrayList<>();

    MapLoader(Options opts) {
        this.opts = opts;
    }

    /**
     * Build the map.
     * <p>
     * BEHAVIOR: missing mapData throws IllegalArgumentException.
     * BEHAVIOR: width/height outside [1, MAX_MAP_SIZE] throws IllegalArgumentException.
     * BEHAVIOR: extraBorder adds 2 to each dimension.
     * BEHAVIOR: teamPlay together with timing turns teamPlay off (with a warning).
     * BEHAVIOR: timing without checkpoints turns timing off (with a warning).
     * BEHAVIOR: base order is computed only in race mode.
     */
    WorldMap build() {
        int mw = opts.getInt(Opt.MAP_WIDTH);
        int mh = opts.getInt(Opt.MAP_HEIGHT);
        String data = opts.mapData();
        if (data == null) {
            throw new IllegalArgumentException("No mapData (random map generation is not supported)");
        }
        if (mw < 1 || mw > Const.MAX_MAP_SIZE || mh < 1 || mh > Const.MAX_MAP_SIZE) {
            throw new IllegalArgumentException("mapWidth or mapHeight exceeds map size limit [1, "
                + Const.MAX_MAP_SIZE + "]: " + mw + "x" + mh);
        }
        boolean extraBorder = opts.getBool(Opt.EXTRA_BORDER);
        w = extraBorder ? mw + 2 : mw;
        h = extraBorder ? mh + 2 : mh;
        name = opts.getString(Opt.MAP_NAME);
        author = opts.getString(Opt.MAP_AUTHOR);
        edgeWrap = opts.getBool(Opt.EDGE_WRAP);
        teamPlay = opts.getBool(Opt.TEAM_PLAY);
        timing = opts.getBool(Opt.TIMING);
        if (teamPlay && timing) {
            warnings.add("Cannot teamplay while in race mode -- ignoring teamplay");
            teamPlay = false;
        }

        char[] grid = readGrid(data, w, h, extraBorder, warnings);
        convert(grid);
        if (wormholesInconsistent(wormholes)) {
            removeWormholes();
        }
        if (timing && numChecks == 0) {
            warnings.add("No checkpoints found while race mode (timing) was set. Turning off race mode.");
            timing = false;
        }
        gravX = new double[w * h];
        gravY = new double[w * h];
        computeGravity(opts, w, h, edgeWrap, blocks, gravs, gravX, gravY);
        freezeBases();
        if (teamPlay) {
            assignTeams();
        }
        if (timing) {
            baseOrder = baseOrderByDistanceTo(checkAX, checkAY);
        }
        return new WorldMap(this);
    }

    // ---------------------------------------------------------------- grid reading

    /**
     * Port of the character reading loop in Grok_map: fills a {@code w*h} grid indexed
     * {@code [y*w + x]}, first text row at y = h-1.
     * <p>
     * BEHAVIOR: a row shorter than w (newline or end of data) is padded with ' ' and reported
     * as "Not enough map data on map data line N" (N = 1-based row from the top).
     * BEHAVIOR: a row longer than w is truncated and reported as "Map data contains extraneous
     * characters on line N".
     * BEHAVIOR: after {@link #MAX_ROW_ERRORS} reports of a kind one "And so on..." is added, then nothing.
     * BEHAVIOR: missing rows are all spaces (each reported).
     * BEHAVIOR: with extraBorder the outermost ring is 'x' and data fills the inside.
     */
    static char[] readGrid(String md, int w, int h, boolean extraBorder, List<String> warnings) {
        char[] grid = new char[w * h];
        int[] missing = {0, 0};
        int[] extra = {0, 0};
        int x = -1;
        int y = h - 1;
        int s = 0;
        int n = md.length();
        while (y >= 0) {
            x++;
            int c;
            if (extraBorder && (x == 0 || x == w - 1 || y == 0 || y == h - 1)) {
                if (x >= w) {
                    x = -1;
                    y--;
                    continue;
                }
                c = 'x';
            } else {
                c = s < n ? md.charAt(s) : '\n';
                boolean atEnd = s >= n;
                if (c == '\n' && x < w) {
                    rowError(warnings, missing, "Not enough map data on map data line ", h - y);
                    c = ' ';
                } else if (!atEnd) {
                    s++;
                }
            }
            if (x >= w || c == '\n') {
                if (c != '\n') {
                    rowError(warnings, extra, "Map data contains extraneous characters on line ", h - y);
                    while (s < n && md.charAt(s++) != '\n') {
                        // skip rest of line
                    }
                }
                y--;
                x = -1;
                continue;
            }
            grid[y * w + x] = (char) c;
        }
        return grid;
    }

    /** counter = {reports so far, last line reported}; a line is reported once (C prev_line_num). */
    private static void rowError(List<String> warnings, int[] counter, String msg, int line) {
        if (line == counter[1]) {
            return;
        }
        counter[1] = line;
        int k = ++counter[0];
        if (k <= MAX_ROW_ERRORS) {
            warnings.add(msg + line);
        } else if (k == MAX_ROW_ERRORS + 1) {
            warnings.add("And so on...");
        }
    }

    // ---------------------------------------------------------------- conversion

    /**
     * Convert map chars to block types and objects, x-major then y ascending (C order).
     * <p>
     * BEHAVIOR: each char maps to its block type and object (see {@link BlockType} and the records).
     * BEHAVIOR: letters A-Z become checkpoints only in race mode, else space.
     * BEHAVIOR: other chars become space and are listed once in an "Unknown map characters" warning.
     * BEHAVIOR: bases get team = digit ('_' = 0) in team play, else TEAM_NOT_SET.
     * BEHAVIOR: a map without bases gets a warning.
     */
    private void convert(char[] grid) {
        blocks = new byte[w * h];
        itemIds = new int[w * h];
        TreeSet<Character> unknownChars = new TreeSet<>();
        int[] checkX = new int[Const.MAX_CHECKS];
        int[] checkY = new int[Const.MAX_CHECKS];
        boolean[] checkSet = new boolean[Const.MAX_CHECKS];
        int[] checkCount = new int[Const.MAX_CHECKS];

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int i = y * w + x;
                char c = grid[i];
                itemIds[i] = -1;
                byte t;
                switch (c) {
                    case ' ', '.' -> t = BlockType.SPACE;
                    case 'x' -> t = BlockType.FILLED;
                    case 's' -> t = BlockType.REC_LU;
                    case 'a' -> t = BlockType.REC_RU;
                    case 'w' -> t = BlockType.REC_LD;
                    case 'q' -> t = BlockType.REC_RD;
                    case 'r' -> t = cannon(i, x, y, Dir.UP, 0.5, 0.333);
                    case 'd' -> t = cannon(i, x, y, Dir.LEFT, 0.667, 0.5);
                    case 'f' -> t = cannon(i, x, y, Dir.RIGHT, 0.333, 0.5);
                    case 'c' -> t = cannon(i, x, y, Dir.DOWN, 0.5, 0.667);
                    case '#' -> {
                        t = BlockType.FUEL;
                        itemIds[i] = fuels.size();
                        fuels.add(new FuelStation(fuels.size(), x, y, Const.TEAM_NOT_SET));
                    }
                    case '*', '^' -> {
                        t = BlockType.TREASURE;
                        itemIds[i] = treasures.size();
                        treasures.add(new Treasure(treasures.size(), x, y, Const.TEAM_NOT_SET, c == '^'));
                    }
                    case '!' -> {
                        t = BlockType.TARGET;
                        itemIds[i] = targets.size();
                        targets.add(new Target(targets.size(), x, y, Const.TEAM_NOT_SET));
                    }
                    case '%' -> {
                        t = BlockType.ITEM_CONCENTRATOR;
                        itemIds[i] = itemConcs.size();
                        itemConcs.add(new Concentrator(itemConcs.size(), x, y));
                    }
                    case '&' -> {
                        t = BlockType.ASTEROID_CONCENTRATOR;
                        itemIds[i] = asteroidConcs.size();
                        asteroidConcs.add(new Concentrator(asteroidConcs.size(), x, y));
                    }
                    case '$' -> t = BlockType.BASE_ATTRACTOR;
                    case '_', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                        t = BlockType.BASE;
                        itemIds[i] = baseData.size();
                        int digit = c == '_' ? -1 : c - '0';
                        int team = Const.TEAM_NOT_SET;
                        if (teamPlay) {
                            team = Math.max(0, digit);
                            if (++teamBases[team] == 1) {
                                numTeamBases++;
                            }
                        }
                        baseData.add(new int[] {x, y, team, digit});
                    }
                    case '+' -> t = grav(i, x, y, BlockType.POS_GRAV, -Const.GRAVS_POWER);
                    case '-' -> t = grav(i, x, y, BlockType.NEG_GRAV, Const.GRAVS_POWER);
                    case '>' -> t = grav(i, x, y, BlockType.CWISE_GRAV, Const.GRAVS_POWER);
                    case '<' -> t = grav(i, x, y, BlockType.ACWISE_GRAV, -Const.GRAVS_POWER);
                    case 'i' -> t = grav(i, x, y, BlockType.UP_GRAV, Const.GRAVS_POWER);
                    case 'm' -> t = grav(i, x, y, BlockType.DOWN_GRAV, -Const.GRAVS_POWER);
                    case 'k' -> t = grav(i, x, y, BlockType.RIGHT_GRAV, Const.GRAVS_POWER);
                    case 'j' -> t = grav(i, x, y, BlockType.LEFT_GRAV, -Const.GRAVS_POWER);
                    case '@', '(', ')' -> {
                        t = BlockType.WORMHOLE;
                        itemIds[i] = wormholes.size();
                        Wormhole.Type wt = c == '@' ? Wormhole.Type.NORMAL
                            : (c == '(' ? Wormhole.Type.IN : Wormhole.Type.OUT);
                        wormholes.add(new Wormhole(wormholes.size(), x, y, wt));
                    }
                    case 'z' -> t = BlockType.FRICTION;
                    case 'b' -> t = BlockType.DECOR_FILLED;
                    case 'h' -> t = BlockType.DECOR_LU;
                    case 'g' -> t = BlockType.DECOR_RU;
                    case 'y' -> t = BlockType.DECOR_LD;
                    case 't' -> t = BlockType.DECOR_RD;
                    default -> {
                        t = BlockType.SPACE;
                        if (c >= 'A' && c <= 'Z') {
                            if (timing) {
                                checkX[c - 'A'] = x;
                                checkY[c - 'A'] = y;
                                checkSet[c - 'A'] = true;
                                checkCount[c - 'A']++;
                                numChecks++;
                                t = BlockType.CHECK;
                                itemIds[i] = c - 'A';
                            }
                        } else {
                            unknownChars.add(c);
                        }
                    }
                }
                blocks[i] = t;
            }
        }
        for (int k = 0; k < Const.MAX_CHECKS; k++) {
            if (checkSet[k]) {
                checkpoints.add(new Checkpoint(k, checkX[k], checkY[k]));
            }
        }
        // C positions check[0] is letter A; a missing A leaves it at (0,0) (static zero init).
        checkAX = checkX[0];
        checkAY = checkY[0];
        if (numChecks > 0) {
            checkpointWarnings(checkCount, warnings);
        }
        if (!unknownChars.isEmpty()) {
            StringBuilder sb = new StringBuilder("Unknown map characters treated as space:");
            for (char c : unknownChars) {
                sb.append(" '").append(c).append('\'');
            }
            warnings.add(sb.toString());
        }
        if (baseData.isEmpty()) {
            warnings.add("WARNING: map has no bases!");
        }
    }

    private byte cannon(int i, int x, int y, int dir, double fx, double fy) {
        itemIds[i] = cannons.size();
        cannons.add(new Cannon(cannons.size(), x, y, dir, (x + fx) * Const.BLOCK_SZ, (y + fy) * Const.BLOCK_SZ,
            Const.TEAM_NOT_SET));
        return BlockType.CANNON;
    }

    private byte grav(int i, int x, int y, byte type, double force) {
        itemIds[i] = gravs.size();
        gravs.add(new GravitySource(gravs.size(), x, y, type, force));
        return type;
    }

    /**
     * C wormhole consistency rule.
     * <p>
     * BEHAVIOR: with normal wormholes, inconsistent iff normal + out &lt; 2.
     * BEHAVIOR: without normal ones but with in-holes, inconsistent iff there is no out-hole.
     * BEHAVIOR: with only out-holes, inconsistent iff there is at least one.
     */
    static boolean wormholesInconsistent(List<Wormhole> holes) {
        int norm = 0;
        int in = 0;
        int out = 0;
        for (Wormhole wh : holes) {
            switch (wh.type()) {
                case NORMAL -> norm++;
                case IN -> in++;
                default -> out++;
            }
        }
        if (norm > 0) {
            return norm + out < 2;
        }
        if (in > 0) {
            return out < 1;
        }
        return out > 0;
    }

    private void removeWormholes() {
        warnings.add("Inconsistent use of wormholes, removing them.");
        for (Wormhole wh : wormholes) {
            int i = wh.by() * w + wh.bx();
            blocks[i] = BlockType.SPACE;
            itemIds[i] = -1;
        }
        wormholes.clear();
    }

    // ---------------------------------------------------------------- gravity

    /** Global then local gravity from options (C {@code Compute_gravity}). */
    static void computeGravity(Options opts, int w, int h, boolean wrap, byte[] blocks,
                               List<GravitySource> gravs, double[] gx, double[] gy) {
        GravityField.global(w, h, wrap, opts.getDouble(Opt.GRAVITY), opts.getDouble(Opt.GRAVITY_ANGLE),
            opts.getBool(Opt.GRAVITY_POINT_SOURCE), opts.getPosX(Opt.GRAVITY_POINT), opts.getPosY(Opt.GRAVITY_POINT),
            opts.getBool(Opt.GRAVITY_CLOCKWISE), opts.getBool(Opt.GRAVITY_ANTICLOCKWISE), gx, gy);
        GravityField.addLocal(w, h, wrap, blocks, gravs, gx, gy);
    }

    // ---------------------------------------------------------------- bases

    /**
     * Base heading from gravity and adjacent {@code $} attractors (C {@code Find_base_direction}).
     * <p>
     * BEHAVIOR: with zero gravity the heading is UP.
     * BEHAVIOR: otherwise the heading points against gravity, rounded to the nearest of the four
     * axis directions (ties round counter-clockwise, as C's integer rounding).
     * BEHAVIOR: attractors are considered in order up, down, right, left; the first one found wins
     * unless a later one matches the gravity heading.
     */
    static int baseDirection(double gx, double gy, boolean up, boolean down, boolean right, boolean left) {
        final int res = Const.RES;
        int dir;
        if (gx == 0.0 && gy == 0.0) {
            dir = Dir.UP;
        } else {
            dir = (int) XMath.findDir(-gx, -gy);
            dir = ((dir + res / 8) / (res / 4)) * (res / 4);
            dir = dir & (res - 1);
        }
        int att = -1;
        if (up) {
            att = Dir.UP;
        }
        if (down && (att == -1 || dir == Dir.DOWN)) {
            att = Dir.DOWN;
        }
        if (right && (att == -1 || dir == Dir.RIGHT)) {
            att = Dir.RIGHT;
        }
        if (left && (att == -1 || dir == Dir.LEFT)) {
            att = Dir.LEFT;
        }
        return att != -1 ? att : dir;
    }

    /** Attractor at a neighbour; across the world edge only when wrapping. */
    private boolean attractor(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) {
            if (!edgeWrap) {
                return false;
            }
            x = Math.floorMod(x, w);
            y = Math.floorMod(y, h);
        }
        return blocks[y * w + x] == BlockType.BASE_ATTRACTOR;
    }

    private void freezeBases() {
        for (int k = 0; k < baseData.size(); k++) {
            int[] b = baseData.get(k);
            int x = b[0];
            int y = b[1];
            int dir = baseDirection(gravX[y * w + x], gravY[y * w + x],
                attractor(x, y + 1), attractor(x, y - 1), attractor(x + 1, y), attractor(x - 1, y));
            bases.add(new Base(k, x, y, dir, b[2], b[3]));
        }
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == BlockType.BASE_ATTRACTOR) {
                blocks[i] = BlockType.SPACE;
            }
        }
    }

    // ---------------------------------------------------------------- teams

    /**
     * Team of the base nearest to block (bx, by) (C {@code Find_closest_team}).
     * <p>
     * BEHAVIOR: bases without a team are ignored; with none left the result is TEAM_NOT_SET.
     * BEHAVIOR: distance is measured in pixels, wrapped across world edges when {@code wrap}.
     * BEHAVIOR: on equal distance the earlier base wins.
     */
    static int closestTeam(List<Base> bases, int bx, int by, int w, int h, boolean wrap) {
        int team = Const.TEAM_NOT_SET;
        double closest = Double.MAX_VALUE;
        double pw = w * (double) Const.BLOCK_SZ;
        double ph = h * (double) Const.BLOCK_SZ;
        for (Base b : bases) {
            if (b.team() == Const.TEAM_NOT_SET) {
                continue;
            }
            double l = XMath.wrapDistance(b.bx() * (double) Const.BLOCK_SZ, b.by() * (double) Const.BLOCK_SZ,
                bx * (double) Const.BLOCK_SZ, by * (double) Const.BLOCK_SZ, pw, ph, wrap);
            if (l < closest) {
                team = b.team();
                closest = l;
            }
        }
        return team;
    }

    private int closest(int bx, int by, String what) {
        int team = closestTeam(bases, bx, by, w, h, edgeWrap);
        if (team == Const.TEAM_NOT_SET) {
            warnings.add("Couldn't find a matching team for the " + what + ".");
        }
        return team;
    }

    /** Team play: treasures, targets, fuel (and cannons with teamCannons) belong to the closest team. */
    private void assignTeams() {
        treasures.replaceAll(t -> new Treasure(t.index(), t.bx(), t.by(), closest(t.bx(), t.by(), "treasure"),
            t.empty()));
        targets.replaceAll(t -> new Target(t.index(), t.bx(), t.by(), closest(t.bx(), t.by(), "target")));
        if (opts.getBool(Opt.TEAM_CANNONS)) {
            cannons.replaceAll(c -> new Cannon(c.index(), c.bx(), c.by(), c.dir(), c.x(), c.y(),
                closest(c.bx(), c.by(), "cannon")));
        }
        fuels.replaceAll(f -> new FuelStation(f.index(), f.bx(), f.by(), closest(f.bx(), f.by(), "fuelstation")));
    }

    /**
     * Checkpoint sanity warnings (C silently uses whatever it finds).
     * <p>
     * BEHAVIOR: warns when 'A' is missing (the race then starts from block (0,0), as in C).
     * BEHAVIOR: warns for each letter used more than once (the last occurrence in C order wins).
     * BEHAVIOR: warns when letters are not contiguous from 'A'.
     */
    static void checkpointWarnings(int[] count, List<String> warnings) {
        if (count[0] == 0) {
            warnings.add("Checkpoint A is missing; the race starts from block (0,0)");
        }
        int last = -1;
        boolean gap = false;
        for (int k = 0; k < count.length; k++) {
            if (count[k] > 1) {
                warnings.add("Checkpoint " + (char) ('A' + k) + " appears " + count[k] + " times");
            }
            if (count[k] > 0) {
                if (k != last + 1) {
                    gap = true;
                }
                last = k;
            }
        }
        if (gap) {
            warnings.add("Checkpoint letters are not contiguous from A");
        }
    }

    private int[] baseOrderByDistanceTo(int ax, int ay) {
        double[] dist = new double[bases.size()];
        double pw = w * (double) Const.BLOCK_SZ;
        double ph = h * (double) Const.BLOCK_SZ;
        for (int i = 0; i < dist.length; i++) {
            Base b = bases.get(i);
            dist[i] = XMath.wrapDistance(ax * (double) Const.BLOCK_SZ, ay * (double) Const.BLOCK_SZ,
                b.bx() * (double) Const.BLOCK_SZ, b.by() * (double) Const.BLOCK_SZ, pw, ph, edgeWrap);
        }
        return stableOrder(dist);
    }

    /**
     * Indices sorted by ascending value, ties in original order (C {@code Find_base_order} insertion).
     * <p>
     * BEHAVIOR: returns a permutation ordering {@code dist} ascending.
     * BEHAVIOR: equal values keep their original relative order.
     */
    static int[] stableOrder(double[] dist) {
        int n = dist.length;
        int[] order = new int[n];
        double[] sorted = new double[n];
        for (int i = 0; i < n; i++) {
            int j = 0;
            while (j < i && sorted[j] <= dist[i]) {
                j++;
            }
            for (int k = i - 1; k >= j; k--) {
                order[k + 1] = order[k];
                sorted[k + 1] = sorted[k];
            }
            order[j] = i;
            sorted[j] = dist[i];
        }
        return order;
    }

    // ---------------------------------------------------------------- debug

    /**
     * Canonical map char for a block (inverse of {@link #convert}).
     * <p>
     * BEHAVIOR: returns the char that decodes to the same block and object; '?' for unknown codes.
     */
    static char charFor(WorldMap m, int bx, int by) {
        int t = m.block(bx, by);
        int id = m.itemId(bx, by);
        return switch (t) {
            case BlockType.SPACE -> ' ';
            case BlockType.FILLED -> 'x';
            case BlockType.REC_LU -> 's';
            case BlockType.REC_RU -> 'a';
            case BlockType.REC_LD -> 'w';
            case BlockType.REC_RD -> 'q';
            case BlockType.CANNON -> switch (m.cannons().get(id).dir()) {
                case Dir.UP -> 'r';
                case Dir.LEFT -> 'd';
                case Dir.RIGHT -> 'f';
                default -> 'c';
            };
            case BlockType.FUEL -> '#';
            case BlockType.TREASURE -> m.treasures().get(id).empty() ? '^' : '*';
            case BlockType.TARGET -> '!';
            case BlockType.ITEM_CONCENTRATOR -> '%';
            case BlockType.ASTEROID_CONCENTRATOR -> '&';
            case BlockType.BASE -> {
                int d = m.bases().get(id).teamDigit();
                yield d < 0 ? '_' : (char) ('0' + d);
            }
            case BlockType.POS_GRAV -> '+';
            case BlockType.NEG_GRAV -> '-';
            case BlockType.CWISE_GRAV -> '>';
            case BlockType.ACWISE_GRAV -> '<';
            case BlockType.UP_GRAV -> 'i';
            case BlockType.DOWN_GRAV -> 'm';
            case BlockType.RIGHT_GRAV -> 'k';
            case BlockType.LEFT_GRAV -> 'j';
            case BlockType.WORMHOLE -> switch (m.wormholes().get(id).type()) {
                case NORMAL -> '@';
                case IN -> '(';
                case OUT -> ')';
            };
            case BlockType.CHECK -> (char) ('A' + id);
            case BlockType.FRICTION -> 'z';
            case BlockType.DECOR_FILLED -> 'b';
            case BlockType.DECOR_LU -> 'h';
            case BlockType.DECOR_RU -> 'g';
            case BlockType.DECOR_LD -> 'y';
            case BlockType.DECOR_RD -> 't';
            default -> '?';
        };
    }
}
