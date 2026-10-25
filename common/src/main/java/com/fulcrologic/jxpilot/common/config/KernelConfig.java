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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a client needs to build the ship kernel's world exactly as the server has it (protocol v2, the owner's
 * predictor; docs/latency-fixes.md §4.15 for the trade-off): the options {@code KernelWorld.of} and
 * {@code ShipParams.of} read, whether they are visible or not, and the map blocks the client's map hides (C
 * {@code Packet_map} turns hidden gravity, wormhole, concentrator and friction blocks into space or decor), as a
 * patch against the client's map. Encoded once per map load and again when an operator changes one of
 * {@link #OPTIONS}; carried as a {@code KERNEL_CONFIG} blob over the reliable channel.
 * <p>
 * Raw layout (before deflating): {@code u32 textLength, text (ISO-8859-1 config lines of OPTIONS),
 * u32 runs, runs * (u32 index, u16 length, length chars)}: runs of mapData characters that differ from the
 * client's map.
 */
public final class KernelConfig {
    private KernelConfig() {}

    /**
     * The options the kernel's world and parameters depend on. A guard test perturbs every other option on several
     * maps and requires the kernel world to stay the same.
     */
    public static final Set<Opt> OPTIONS = EnumSet.of(
        // ShipParams
        Opt.SHIP_MASS, Opt.SHOT_MASS, Opt.SHOT_SPEED, Opt.FIRE_REPEAT_RATE, Opt.EDGE_BOUNCE,
        Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED, Opt.MAX_UNSHIELDED_WALL_BOUNCE_SPEED,
        Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE, Opt.MAX_UNSHIELDED_PLAYER_WALL_BOUNCE_ANGLE,
        Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR, Opt.WALL_BOUNCE_FUEL_DRAIN_MULT, Opt.FRICTION, Opt.BLOCK_FRICTION,
        Opt.CORIOLIS, Opt.TEAM_IMMUNITY, Opt.TARGET_TEAM_COLLISION, Opt.TURN_THRUST, Opt.CLOAKED_EXHAUST,
        Opt.WORM_TIME, Opt.MIN_ITEM_MASS, Opt.FRAMES_PER_SECOND,
        // the map's structure
        Opt.MAP_WIDTH, Opt.MAP_HEIGHT, Opt.EXTRA_BORDER, Opt.EDGE_WRAP, Opt.TEAM_PLAY, Opt.TIMING,
        // gravity (MapLoader computes the per-block field)
        Opt.GRAVITY, Opt.GRAVITY_POINT, Opt.GRAVITY_ANGLE, Opt.GRAVITY_POINT_SOURCE, Opt.GRAVITY_CLOCKWISE,
        Opt.GRAVITY_ANTICLOCKWISE,
        // the motion of the ship's bullets (SHOT_MOTION)
        Opt.SHOT_LIFE, Opt.SHOTS_GRAVITY, Opt.SHOTS_WALL_BOUNCE, Opt.MAX_OBJECT_WALL_BOUNCE_SPEED,
        Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR, Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR, Opt.PREDICTABLE_BOUNCES);

    /**
     * What C {@code Move_object} reads for a player's SHOT besides the map, gravity, edgeWrap and edgeBounce (all in
     * {@link #OPTIONS} already): its life, whether it feels gravity, whether it bounces off walls (which also decides
     * whether it crashes into treasures, C {@code Move_init}), and the object wall-bounce speed limit, brake and life
     * factors; and predictableBounces. For the client's predicted own bullets (1c).
     */
    public static final Set<Opt> SHOT_MOTION = EnumSet.of(Opt.SHOT_LIFE, Opt.SHOTS_GRAVITY, Opt.SHOTS_WALL_BOUNCE,
        Opt.MAX_OBJECT_WALL_BOUNCE_SPEED, Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR, Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR,
        Opt.PREDICTABLE_BOUNCES);

    /** BEHAVIOR: true for an option in {@link #OPTIONS}. */
    public static boolean affects(Opt o) {
        return OPTIONS.contains(o);
    }

    /**
     * The raw kernel config of {@code o} against the client's map.
     * <p>
     * BEHAVIOR: every option of {@link #OPTIONS} with its current value; mapData characters differing from
     * {@code clientMapData} (same length: the client's map hides blocks character for character) as runs; a client
     * map of another length (or none) gets the whole map as one run.
     */
    public static byte[] encode(Options o, String clientMapData) {
        StringBuilder sb = new StringBuilder(2048);
        for (Opt opt : OPTIONS) {
            Options.appendValue(sb, opt.optionName(), o.getRaw(opt.optionName()));
        }
        byte[] text = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        String map = o.mapData();
        ByteArrayOutputStream runs = new ByteArrayOutputStream();
        int count = 0;
        if (map != null) {
            boolean whole = clientMapData == null || clientMapData.length() != map.length();
            int i = 0;
            while (i < map.length()) {
                if (!whole && map.charAt(i) == clientMapData.charAt(i)) {
                    i++;
                    continue;
                }
                int start = i;
                while (i < map.length() && i - start < 0xFFFF
                    && (whole || map.charAt(i) != clientMapData.charAt(i))) {
                    i++;
                }
                ByteBuffer h = ByteBuffer.allocate(6).putInt(start).putShort((short) (i - start));
                runs.writeBytes(h.array());
                runs.writeBytes(map.substring(start, i).getBytes(StandardCharsets.ISO_8859_1));
                count++;
            }
        }
        ByteBuffer out = ByteBuffer.allocate(4 + text.length + 4 + runs.size());
        out.putInt(text.length).put(text).putInt(count).put(runs.toByteArray());
        return out.array();
    }

    /**
     * The options the kernel world is built from: a fresh {@link Options} with the kernel options of {@code raw} and
     * mapData = {@code clientMapData} with the runs applied.
     * <p>
     * BEHAVIOR: throws IllegalStateException for a truncated blob or a run outside the map.
     */
    public static Options decode(byte[] raw, String clientMapData) {
        ByteBuffer in = ByteBuffer.wrap(raw);
        try {
            int textLength = in.getInt();
            byte[] text = new byte[textLength];
            in.get(text);
            Options o = new Options();
            ConfigParser.parse(new String(text, StandardCharsets.ISO_8859_1), "kernel", OptionOrigin.INIT, o);
            int count = in.getInt();
            char[] map = clientMapData == null ? new char[0] : clientMapData.toCharArray();
            for (int r = 0; r < count; r++) {
                int start = in.getInt();
                int len = in.getShort() & 0xFFFF;
                if (start < 0 || len > in.remaining()) {
                    throw new IllegalStateException("kernel map run out of range");
                }
                if (start + len > map.length) {
                    map = java.util.Arrays.copyOf(map, start + len);
                }
                for (int i = 0; i < len; i++) {
                    map[start + i] = (char) (in.get() & 0xFF);
                }
            }
            if (map.length > 0 || clientMapData != null) {
                o.force(Opt.MAP_DATA, new String(map));
            }
            return o;
        } catch (java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalStateException("truncated kernel config", e);
        }
    }
}
