package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Treasure;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.Rng;

/**
 * All simulation state: the map, resolved parameters, the seeded RNG, preallocated player slots and
 * object pool, and the dynamic state of map features (cannons, fuel stations, targets, wormholes,
 * teams). Pure data; the rules live in {@code server.sim}. No clock: time is {@link #tick} and the
 * original-frame clock {@link #frames}.
 */
public final class World {
    /** C {@code MAX_TOTAL_SHOTS}: object pool capacity. */
    public static final int MAX_TOTAL_SHOTS = 16384;
    /** Wire ids are 0..MAX_IDS-1 (C {@code NUM_IDS}). */
    public static final int MAX_IDS = 256;

    public final Options options;
    public final WorldMap map;
    public final Params params;
    public final int width;
    public final int height;
    public final int pixelWidth;
    public final int pixelHeight;
    public final int clickWidth;
    public final int clickHeight;
    public final boolean wrap;

    /** Simulation RNG (C {@code rfrac()}/{@code randomMT()}). */
    public final Rng rng;
    /** Scratch RNG reseeded per burst expansion. */
    public final Rng burstRng = new Rng(0);
    /** Wall distance in half blocks, see {@link WallDist}. */
    public final byte[] wallDist;

    public final Player[] players;
    /** Slots of players in the game, in join order (C {@code Players[0..NumPlayers)}). */
    public final int[] order;
    public int numPlayers;
    private final boolean[] idUsed = new boolean[MAX_IDS];
    private int pseudoTeamNext;

    public final Objects objects;
    public final Events events = new Events();

    // ---- dynamic map state
    public final double[] cannonDeadTime;
    public final double[] fuelLevel;
    public final double[] targetDamage;
    public final double[] targetDeadTime;
    public final int[] wormLastDest;
    public final double[] wormCountdown;

    // ---- teams (C World.teams)
    public final double[] teamScore = new double[Const.MAX_TEAMS];
    public final int[] teamMembers = new int[Const.MAX_TEAMS];
    public final int[] teamRobots = new int[Const.MAX_TEAMS];
    public final int[] teamNumTreasures = new int[Const.MAX_TEAMS];
    public final int[] teamTreasuresLeft = new int[Const.MAX_TEAMS];
    public final int[] teamNumEmptyTreasures = new int[Const.MAX_TEAMS];
    public final boolean[] teamScoreDirty = new boolean[Const.MAX_TEAMS];

    /** Sim tick counter. */
    public int tick;
    /** Original-frame clock (C {@code frame_loops}): advances by {@code dt} per tick. */
    public double frames;
    public int numRobots;

    public World(Options options, WorldMap map, Params params, int seed) {
        this.options = options;
        this.map = map;
        this.params = params;
        this.width = map.width();
        this.height = map.height();
        this.pixelWidth = map.pixelWidth();
        this.pixelHeight = map.pixelHeight();
        this.clickWidth = pixelWidth << Click.SHIFT;
        this.clickHeight = pixelHeight << Click.SHIFT;
        this.wrap = map.edgeWrap();
        this.rng = new Rng(seed);
        this.wallDist = WallDist.compute(map);
        int cap = Math.max(1, map.bases().size());
        players = new Player[cap];
        for (int i = 0; i < cap; i++) {
            players[i] = new Player(i);
        }
        order = new int[cap];
        objects = new Objects(MAX_TOTAL_SHOTS);

        cannonDeadTime = new double[map.cannons().size()];
        fuelLevel = new double[map.fuels().size()];
        java.util.Arrays.fill(fuelLevel, Const.START_STATION_FUEL);
        targetDamage = new double[map.targets().size()];
        java.util.Arrays.fill(targetDamage, Const.TARGET_DAMAGE);
        targetDeadTime = new double[map.targets().size()];
        wormLastDest = new int[map.wormholes().size()];
        java.util.Arrays.fill(wormLastDest, -1);
        wormCountdown = new double[map.wormholes().size()];
        if (map.teamPlay()) {
            countTreasures();
        }
    }

    /** Per-team treasure counters (C {@code map.c:892-903}). */
    private void countTreasures() {
        for (Treasure t : map.treasures()) {
            if (t.team() == Const.TEAM_NOT_SET) {
                continue;
            }
            teamNumTreasures[t.team()]++;
            if (!t.empty()) {
                teamTreasuresLeft[t.team()]++;
            } else {
                teamNumEmptyTreasures[t.team()]++;
            }
        }
    }

    /** C {@code rfrac()}: uniform in [0, 1). */
    public double rfrac() {
        return rng.nextFloat();
    }

    /** Player in the k-th position of {@link #order}. */
    public Player playerAt(int k) {
        return players[order[k]];
    }

    /**
     * Player with a wire id, or null.
     * <p>
     * BEHAVIOR: returns null for NO_ID and unknown ids.
     */
    public Player byId(int id) {
        for (int k = 0; k < numPlayers; k++) {
            Player p = players[order[k]];
            if (p.id == id) {
                return p;
            }
        }
        return null;
    }

    /**
     * Reserve a free slot and id and append it to {@link #order}.
     * <p>
     * BEHAVIOR: returns null when every slot (one per base) is taken.
     * BEHAVIOR: ids are the lowest unused id; the slot's previous state is left for the caller to reset.
     */
    public Player addPlayer() {
        for (Player p : players) {
            if (!p.inUse) {
                int id = 0;
                while (idUsed[id]) {
                    id++;
                }
                idUsed[id] = true;
                p.inUse = true;
                p.id = id;
                p.pseudoTeam = pseudoTeamNext++;
                order[numPlayers++] = p.slot;
                return p;
            }
        }
        return null;
    }

    /**
     * Remove a player from the game.
     * <p>
     * BEHAVIOR: the remaining players keep their relative order; the id becomes free.
     */
    public void removePlayer(Player p) {
        if (!p.inUse) {
            return;
        }
        int k = 0;
        while (order[k] != p.slot) {
            k++;
        }
        System.arraycopy(order, k + 1, order, k, numPlayers - k - 1);
        numPlayers--;
        idUsed[p.id] = false;
        p.inUse = false;
    }

    /** Wall distance (half blocks) at an in-range block. */
    public int wallDist(int bx, int by) {
        return wallDist[by * width + bx] & 0xFF;
    }

    /** C {@code WRAP_DX} on pixels. */
    public double wrapDx(double dx) {
        if (!wrap) {
            return dx;
        }
        int half = pixelWidth >> 1;
        return dx < -half ? dx + pixelWidth : (dx > half ? dx - pixelWidth : dx);
    }

    /** C {@code WRAP_DY} on pixels. */
    public double wrapDy(double dy) {
        if (!wrap) {
            return dy;
        }
        int half = pixelHeight >> 1;
        return dy < -half ? dy + pixelHeight : (dy > half ? dy - pixelHeight : dy);
    }

    /** C {@code Wrap_length}. */
    public double wrapLength(double dx, double dy) {
        return Math.hypot(wrapDx(dx), wrapDy(dy));
    }

    /** C {@code WRAP_XCLICK}. */
    public int wrapXClick(int x) {
        if (!wrap) {
            return x;
        }
        return x < 0 ? x + clickWidth : (x >= clickWidth ? x - clickWidth : x);
    }

    /** C {@code WRAP_YCLICK}. */
    public int wrapYClick(int y) {
        if (!wrap) {
            return y;
        }
        return y < 0 ? y + clickHeight : (y >= clickHeight ? y - clickHeight : y);
    }

    /** C {@code WRAP_XPIXEL}. */
    public double wrapXPixel(double x) {
        if (!wrap) {
            return x;
        }
        return x < 0 ? x + pixelWidth : (x >= pixelWidth ? x - pixelWidth : x);
    }

    /** C {@code WRAP_YPIXEL}. */
    public double wrapYPixel(double y) {
        if (!wrap) {
            return y;
        }
        return y < 0 ? y + pixelHeight : (y >= pixelHeight ? y - pixelHeight : y);
    }

    /** Gravity x at a pixel position (block clamped into the map). */
    public double gravityX(int px, int py) {
        return map.gravityX(clampBx(px), clampBy(py));
    }

    /** Gravity y at a pixel position. */
    public double gravityY(int px, int py) {
        return map.gravityY(clampBx(px), clampBy(py));
    }

    private int clampBx(int px) {
        int b = Math.floorDiv(px, Const.BLOCK_SZ);
        return b < 0 ? 0 : (b >= width ? width - 1 : b);
    }

    private int clampBy(int py) {
        int b = Math.floorDiv(py, Const.BLOCK_SZ);
        return b < 0 ? 0 : (b >= height ? height - 1 : b);
    }

    /** C {@code TEAM(i, j)}: team play and the same team. */
    public boolean sameTeam(Player a, Player b) {
        return params.teamPlay && a.team == b.team && a.team != Const.TEAM_NOT_SET;
    }

    /** Next pseudo team number (C {@code pseudo_team_no++}). */
    public int nextPseudoTeam() {
        return pseudoTeamNext++;
    }
}
