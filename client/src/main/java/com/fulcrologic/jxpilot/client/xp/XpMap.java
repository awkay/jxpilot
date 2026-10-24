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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.WorldMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Turns the C server's setup map into a JXPilot {@link WorldMap}: run-length decoding (C
 * {@code Uncompress_map}), the setup block codes back into {@code .xp} map characters (the inverse of
 * {@code Init_setup} in {@code server/netserver.c} and the character table in {@code server/map.c}),
 * and the C object numbering. The C server numbers cannons, fuel stations, targets and bases in map
 * scan order (x outer, y inner from the bottom); {@link Built} maps those numbers to the indices of
 * the {@link WorldMap} lists. Runs once per join; allocates.
 */
public final class XpMap {
    private XpMap() {}

    /** The map plus C-number to {@link WorldMap}-index tables (-1 = no such object). */
    public record Built(WorldMap map, Options options, int[] cannonIndex, int[] fuelIndex, int[] targetIndex,
                        int[] baseIndex, int unknownBlocks) {
        /** BEHAVIOR: the WorldMap index of C object {@code c} in {@code table}, -1 when out of range. */
        public static int lookup(int[] table, int c) {
            return c >= 0 && c < table.length ? table[c] : -1;
        }
    }

    /**
     * Decode the setup map data into {@code w * h} block codes, index {@code x * h + y}.
     * <p>
     * BEHAVIOR: SETUP_MAP_UNCOMPRESSED data is copied as is (it must be exactly w * h bytes).
     * BEHAVIOR: SETUP_MAP_ORDER_XY data is run-length decoded: a byte with the high bit set is followed
     * by a repeat count (any value, at least 2); other bytes stand for themselves.
     * BEHAVIOR: throws IllegalArgumentException for a count below 2, output overrun, short output or a
     * dangling count byte (C "Map compress count error" / "map uncompress error").
     */
    public static byte[] uncompress(byte[] data, int len, int order, int w, int h) {
        int size = w * h;
        byte[] out = new byte[size];
        if (order == XpProto.SETUP_MAP_UNCOMPRESSED) {
            if (len != size) {
                throw new IllegalArgumentException("uncompressed map has " + len + " bytes, expected " + size);
            }
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
        int o = 0;
        int i = 0;
        while (i < len) {
            int b = data[i++] & 0xFF;
            int count = 1;
            if ((b & XpProto.SETUP_COMPRESSED) != 0) {
                if (i >= len) {
                    throw new IllegalArgumentException("map data ends inside a run");
                }
                count = data[i++] & 0xFF;
                if (count < 2) {
                    throw new IllegalArgumentException("map compress count error " + count);
                }
                b &= ~XpProto.SETUP_COMPRESSED;
            }
            if (o + count > size) {
                throw new IllegalArgumentException("map uncompression overrun at " + i);
            }
            for (int k = 0; k < count; k++) {
                out[o++] = (byte) b;
            }
        }
        if (o != size) {
            throw new IllegalArgumentException("map uncompress error (" + o + "," + size + ")");
        }
        return out;
    }

    /**
     * The {@code .xp} character for a setup block code.
     * <p>
     * BEHAVIOR: walls, fuel, gravity, wormholes, cannons, concentrators, decorations and checkpoints get
     * their map.c characters; treasures '*', targets '!'; bases their team digit in team play, else
     * '_'; space, space-dot and unknown codes ' ' (unknown codes return 0 so callers can count them;
     * use {@code ' '}).
     */
    public static char blockChar(int code, boolean teamPlay) {
        if (code >= XpProto.SETUP_BASE_LOWEST && code <= XpProto.SETUP_BASE_HIGHEST) {
            return teamPlay ? (char) ('0' + (code - XpProto.SETUP_BASE_LOWEST) % 10) : '_';
        }
        if (code >= XpProto.SETUP_TREASURE && code < XpProto.SETUP_TREASURE + 10) {
            return '*';
        }
        if (code >= XpProto.SETUP_TARGET && code < XpProto.SETUP_TARGET + 10) {
            return '!';
        }
        if (code >= XpProto.SETUP_CHECK && code < XpProto.SETUP_CHECK + XpProto.MAX_CHECKS) {
            return (char) ('A' + code - XpProto.SETUP_CHECK);
        }
        return switch (code) {
            case XpProto.SETUP_SPACE, XpProto.SETUP_SPACE_DOT -> ' ';
            case XpProto.SETUP_FILLED, XpProto.SETUP_FILLED_NO_DRAW -> 'x';
            case XpProto.SETUP_FUEL -> '#';
            case XpProto.SETUP_REC_RU -> 'a';
            case XpProto.SETUP_REC_RD -> 'q';
            case XpProto.SETUP_REC_LU -> 's';
            case XpProto.SETUP_REC_LD -> 'w';
            case XpProto.SETUP_ACWISE_GRAV -> '<';
            case XpProto.SETUP_CWISE_GRAV -> '>';
            case XpProto.SETUP_POS_GRAV -> '+';
            case XpProto.SETUP_NEG_GRAV -> '-';
            case XpProto.SETUP_UP_GRAV -> 'i';
            case XpProto.SETUP_DOWN_GRAV -> 'm';
            case XpProto.SETUP_RIGHT_GRAV -> 'k';
            case XpProto.SETUP_LEFT_GRAV -> 'j';
            case XpProto.SETUP_WORM_NORMAL -> '@';
            case XpProto.SETUP_WORM_IN -> '(';
            case XpProto.SETUP_WORM_OUT -> ')';
            case XpProto.SETUP_CANNON_UP -> 'r';
            case XpProto.SETUP_CANNON_RIGHT -> 'f';
            case XpProto.SETUP_CANNON_DOWN -> 'c';
            case XpProto.SETUP_CANNON_LEFT -> 'd';
            case XpProto.SETUP_ITEM_CONCENTRATOR -> '%';
            case XpProto.SETUP_ASTEROID_CONCENTRATOR -> '&';
            case XpProto.SETUP_DECOR_FILLED, XpProto.SETUP_DECOR_DOT_FILLED -> 'b';
            case XpProto.SETUP_DECOR_RU, XpProto.SETUP_DECOR_DOT_RU -> 'g';
            case XpProto.SETUP_DECOR_RD, XpProto.SETUP_DECOR_DOT_RD -> 't';
            case XpProto.SETUP_DECOR_LU, XpProto.SETUP_DECOR_DOT_LU -> 'h';
            case XpProto.SETUP_DECOR_LD, XpProto.SETUP_DECOR_DOT_LD -> 'y';
            default -> 0;
        };
    }

    /**
     * {@code mapData} text: {@code h} rows of {@code w} chars, top row (C y = h - 1) first, each ended by
     * a newline.
     *
     * @param unknown receives the number of unknown block codes in {@code [0]} (drawn as space)
     */
    public static String mapText(byte[] blocks, int w, int h, boolean teamPlay, int[] unknown) {
        StringBuilder sb = new StringBuilder((w + 1) * h);
        int bad = 0;
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                char c = blockChar(blocks[x * h + y] & 0xFF, teamPlay);
                if (c == 0) {
                    bad++;
                    c = ' ';
                }
                sb.append(c);
            }
            sb.append('\n');
        }
        unknown[0] = bad;
        return sb.toString();
    }

    /**
     * Build the map and options from a complete setup.
     * <p>
     * BEHAVIOR: options carry the map size, name, author, data, edge wrap, team play, timing, limited
     * lives (and the lives count) and frames per second from the setup's mode bits.
     * BEHAVIOR: each base faces the way its setup code says ({@link #baseDir}; C guimap.c draws from it), not
     * the way the map loader re-derives it without the server's gravity and {@code $} attractors.
     * BEHAVIOR: the C-number tables order each WorldMap list by (x, y), the C scan order.
     * BEHAVIOR: throws IllegalArgumentException for undecodable map data.
     */
    public static Built build(XpSetup s) {
        byte[] blocks = uncompress(s.mapData(), s.mapDataLen, s.mapOrder, s.width, s.height);
        boolean team = (s.mode & XpProto.MODE_TEAM_PLAY) != 0;
        int[] unknown = new int[1];
        String text = mapText(blocks, s.width, s.height, team, unknown);
        Options o = new Options();
        o.force(Opt.MAP_WIDTH, Integer.toString(s.width));
        o.force(Opt.MAP_HEIGHT, Integer.toString(s.height));
        o.force(Opt.MAP_NAME, s.name.toString());
        o.force(Opt.MAP_AUTHOR, s.author.toString());
        o.force(Opt.EDGE_WRAP, yes((s.mode & XpProto.MODE_WRAP_PLAY) != 0));
        o.force(Opt.TEAM_PLAY, yes(team));
        o.force(Opt.TIMING, yes((s.mode & XpProto.MODE_TIMING) != 0));
        o.force(Opt.LIMITED_LIVES, yes((s.mode & XpProto.MODE_LIMITED_LIVES) != 0));
        o.force(Opt.WORLD_LIVES, Integer.toString(s.lives));
        o.force(Opt.FRAMES_PER_SECOND, Integer.toString(Math.max(1, s.fps)));
        o.force(Opt.MAP_DATA, text);
        WorldMap m = WorldMap.fromOptions(o);
        for (int i = 0; i < m.bases().size(); i++) {
            Base b = m.bases().get(i);
            m.setBaseDir(i, baseDir(blocks[b.bx() * s.height + b.by()] & 0xFF, b.dir()));
        }
        return new Built(m, o,
            cOrder(m.cannons(), c -> c.bx(), c -> c.by()),
            cOrder(m.fuels(), f -> f.bx(), f -> f.by()),
            cOrder(m.targets(), t -> t.bx(), t -> t.by()),
            cOrder(m.bases(), b -> b.bx(), b -> b.by()),
            unknown[0]);
    }

    /**
     * The heading of a base setup code (common/setup.h: SETUP_BASE_UP + team, then RIGHT, DOWN, LEFT by tens).
     * <p>
     * BEHAVIOR: 30..39 up, 40..49 right, 50..59 down, 60..69 left (RES units, {@link Dir}); any other code keeps
     * {@code fallback}.
     */
    static int baseDir(int code, int fallback) {
        if (code < XpProto.SETUP_BASE_LOWEST || code > XpProto.SETUP_BASE_HIGHEST) {
            return fallback;
        }
        return switch ((code - XpProto.SETUP_BASE_LOWEST) / 10) {
            case 0 -> Dir.UP;
            case 1 -> Dir.RIGHT;
            case 2 -> Dir.DOWN;
            default -> Dir.LEFT;
        };
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }

    /** BEHAVIOR: result[c] = the list index of the c-th object in (x, then y) order. */
    static <T> int[] cOrder(List<T> list, ToIntFunction<T> bx, ToIntFunction<T> by) {
        List<Integer> idx = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            idx.add(i);
        }
        idx.sort(Comparator.<Integer>comparingInt(i -> bx.applyAsInt(list.get(i)))
            .thenComparingInt(i -> by.applyAsInt(list.get(i))));
        int[] out = new int[idx.size()];
        for (int c = 0; c < out.length; c++) {
            out[c] = idx.get(c);
        }
        return out;
    }
}
