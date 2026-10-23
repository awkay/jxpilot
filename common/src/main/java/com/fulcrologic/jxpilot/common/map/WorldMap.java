package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.math.XMath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The decoded block map plus its special objects, built exactly as XPilot's {@code Grok_map},
 * {@code Compute_gravity} and {@code Find_base_direction} ({@code src/server/map.c}).
 * <p>
 * Coordinates: block {@code (bx, by)} covers pixels {@code [bx*35, bx*35+35) x [by*35, by*35+35)};
 * {@code by = 0} is the bottom row (the LAST line of mapData), y increases upward.
 * Object lists are in C order (x-major, then y ascending), which matters for team assignment ties.
 * <p>
 * Use {@link #load(Path)} for a map file, or {@link #fromOptions(Options)} when options were
 * already parsed (e.g. map file + defaults + command line).
 */
public final class WorldMap {
    final int width;
    final int height;
    final String name;
    final String author;
    final boolean edgeWrap;
    final boolean teamPlay;
    final boolean timing;
    final byte[] blocks;
    final int[] itemIds;
    final double[] gravX;
    final double[] gravY;
    final List<Base> bases;
    final List<FuelStation> fuels;
    final List<Cannon> cannons;
    final List<Target> targets;
    final List<Treasure> treasures;
    final List<GravitySource> gravs;
    final List<Wormhole> wormholes;
    final List<Checkpoint> checkpoints;
    final List<Concentrator> itemConcentrators;
    final List<Concentrator> asteroidConcentrators;
    final int[] baseOrder;
    final int numChecks;
    final int[] teamBases;
    final int numTeamBases;
    final List<String> warnings;

    WorldMap(MapLoader l) {
        this.width = l.w;
        this.height = l.h;
        this.name = l.name;
        this.author = l.author;
        this.edgeWrap = l.edgeWrap;
        this.teamPlay = l.teamPlay;
        this.timing = l.timing;
        this.blocks = l.blocks;
        this.itemIds = l.itemIds;
        this.gravX = l.gravX;
        this.gravY = l.gravY;
        this.bases = List.copyOf(l.bases);
        this.fuels = List.copyOf(l.fuels);
        this.cannons = List.copyOf(l.cannons);
        this.targets = List.copyOf(l.targets);
        this.treasures = List.copyOf(l.treasures);
        this.gravs = List.copyOf(l.gravs);
        this.wormholes = List.copyOf(l.wormholes);
        this.checkpoints = List.copyOf(l.checkpoints);
        this.itemConcentrators = List.copyOf(l.itemConcs);
        this.asteroidConcentrators = List.copyOf(l.asteroidConcs);
        this.baseOrder = l.baseOrder;
        this.numChecks = l.numChecks;
        this.teamBases = l.teamBases;
        this.numTeamBases = l.numTeamBases;
        this.warnings = List.copyOf(l.warnings);
    }

    /** Parse a map file (as map-origin options) and build the map. */
    public static WorldMap load(Path file) throws IOException {
        Options o = new Options();
        ConfigParser.parseFile(file, OptionOrigin.MAP, o);
        return fromOptions(o);
    }

    /**
     * Build the map from parsed options ({@code mapData}, {@code mapWidth}, {@code mapHeight},
     * {@code extraBorder}, {@code edgeWrap}, {@code teamPlay}, {@code timing}, {@code teamCannons},
     * gravity options).
     *
     * @throws IllegalArgumentException if there is no map data or the size is out of range
     *                                  (C would generate a random map instead)
     */
    public static WorldMap fromOptions(Options opts) {
        return new MapLoader(opts).build();
    }

    // ------------------------------------------------------------------ grid

    /** Width in blocks (including extraBorder). */
    public int width() { return width; }

    /** Height in blocks (including extraBorder). */
    public int height() { return height; }

    /** World width in pixels. */
    public int pixelWidth() { return width * Const.BLOCK_SZ; }

    /** World height in pixels. */
    public int pixelHeight() { return height * Const.BLOCK_SZ; }

    public String name() { return name; }

    public String author() { return author; }

    /** Effective edge wrap. */
    public boolean edgeWrap() { return edgeWrap; }

    /** Effective team play (turned off in race mode, as in C). */
    public boolean teamPlay() { return teamPlay; }

    /** Effective race mode (turned off if the map has no checkpoints). */
    public boolean timing() { return timing; }

    /** Grid index for in-range block coordinates. */
    public int index(int bx, int by) { return by * width + bx; }

    /** Block type at in-range coordinates (see {@link BlockType}). */
    public byte block(int bx, int by) { return blocks[by * width + bx]; }

    /**
     * Block type at any coordinates.
     * <p>
     * BEHAVIOR: in range returns the block; out of range wraps when {@link #edgeWrap()}, otherwise
     * returns {@link BlockType#FILLED} (the world edge is a wall).
     */
    public byte blockAt(int bx, int by) {
        if (bx < 0 || bx >= width || by < 0 || by >= height) {
            if (!edgeWrap) {
                return BlockType.FILLED;
            }
            bx = Math.floorMod(bx, width);
            by = Math.floorMod(by, height);
        }
        return blocks[by * width + bx];
    }

    /** Change a block at runtime (e.g. temporary hyperjump wormholes, destroyed targets drawn as space). */
    public void setBlock(int bx, int by, byte type) { blocks[by * width + bx] = type; }

    /**
     * Index of the object at this block in its list (base, fuel, cannon, target, treasure, gravity,
     * wormhole, concentrator), or -1 (C {@code World.itemID}).
     */
    public int itemId(int bx, int by) { return itemIds[by * width + bx]; }

    /** Direct access to the block grid (row-major, index = by*width + bx). Do not modify unless intended. */
    public byte[] blocks() { return blocks; }

    /** Gravity acceleration x at a block (global + local gravity sources), per original frame. */
    public double gravityX(int bx, int by) { return gravX[by * width + bx]; }

    /** Gravity acceleration y at a block. */
    public double gravityY(int bx, int by) { return gravY[by * width + bx]; }

    /**
     * Recompute the gravity field after gravity options changed (C tuner {@code Compute_gravity}).
     * Base directions are not changed.
     */
    public void recomputeGravity(Options opts) {
        MapLoader.computeGravity(opts, width, height, edgeWrap, blocks, gravs, gravX, gravY);
    }

    // ------------------------------------------------------------------ objects

    public List<Base> bases() { return bases; }

    public List<FuelStation> fuels() { return fuels; }

    public List<Cannon> cannons() { return cannons; }

    public List<Target> targets() { return targets; }

    public List<Treasure> treasures() { return treasures; }

    public List<GravitySource> gravitySources() { return gravs; }

    public List<Wormhole> wormholes() { return wormholes; }

    /** Checkpoints in letter order (only in race mode). */
    public List<Checkpoint> checkpoints() { return checkpoints; }

    public List<Concentrator> itemConcentrators() { return itemConcentrators; }

    public List<Concentrator> asteroidConcentrators() { return asteroidConcentrators; }

    /** Number of checkpoint letter occurrences (C {@code NumChecks}; duplicates count). */
    public int numChecks() { return numChecks; }

    /** Race mode start order: base indices sorted by distance to checkpoint A (empty if not racing). */
    public int[] baseOrder() { return baseOrder.clone(); }

    /** Number of bases owned by a team (team play only). */
    public int teamBaseCount(int team) { return teamBases[team]; }

    /** Number of teams that have at least one base (C {@code NumTeamBases}). */
    public int numTeamBases() { return numTeamBases; }

    /** Non-fatal problems found while decoding (short/long rows, unknown chars...). */
    public List<String> warnings() { return warnings; }

    // ------------------------------------------------------------------ helpers

    /** Wrap-aware x difference in pixels (C {@code WRAP_DX}); unchanged when the map does not wrap. */
    public double wrapDx(double dx) {
        return XMath.wrapDelta(dx, pixelWidth(), edgeWrap);
    }

    /** Wrap-aware y difference in pixels (C {@code WRAP_DY}); unchanged when the map does not wrap. */
    public double wrapDy(double dy) {
        return XMath.wrapDelta(dy, pixelHeight(), edgeWrap);
    }

    /** Wrap-aware distance for a pixel offset (C {@code Wrap_length}). */
    public double wrapLength(double dx, double dy) {
        return Math.hypot(wrapDx(dx), wrapDy(dy));
    }

    /**
     * Team of the base closest to a block (C {@code Find_closest_team}), or
     * {@link Const#TEAM_NOT_SET} when no base has a team. Ties go to the earliest base.
     */
    public int findClosestTeam(int bx, int by) {
        return MapLoader.closestTeam(bases, bx, by, width, height, edgeWrap);
    }

    /** Render the block grid as text (top row first) using canonical map chars. Debug aid. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (int by = height - 1; by >= 0; by--) {
            for (int bx = 0; bx < width; bx++) {
                sb.append(MapLoader.charFor(this, bx, by));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
