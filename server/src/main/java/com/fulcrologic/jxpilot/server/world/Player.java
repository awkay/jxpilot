package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;

/**
 * A player slot (C {@code player}, subset needed for M1). Preallocated by {@link World}; reused when a
 * player leaves. Timers that C counts in frames are doubles in original frames, decremented by
 * {@code dt} each sim tick.
 */
public final class Player {
    /** C {@code MAX_RECORDED_SHOVES}. */
    public static final int MAX_SHOVES = 4;

    /** Slot index in {@link World#players}. */
    public final int slot;
    /** Wire id (stable while the player is in the game). */
    public int id;
    public boolean inUse;
    public boolean robot;
    public final StringBuilder name = new StringBuilder(Const.MAX_CHARS);
    public int team = Const.TEAM_NOT_SET;
    public int pseudoTeam;
    public ShipGeom ship = ShipGeom.defaultShip();

    // ---- position (clicks authoritative, as Objects)
    public int cx;
    public int cy;
    public int px;
    public int py;
    public int bx;
    public int by;
    public int prevX;
    public int prevY;
    public double remX;
    public double remY;

    public double vx;
    public double vy;
    public double accX;
    public double accY;
    /** C {@code velocity}: |v|. */
    public double velocity;
    public double mass;
    public double emptyMass;

    public int status;
    public int have;
    public int used;
    /** Respawn / pause / self-destruct countdown in frames; -1 when idle. */
    public double count = -1;

    public int dir;
    public double floatDir;
    public double turnVel;
    public double oldTurnVel;
    public double turnAcc;
    public double turnSpeed;
    public double turnResistance;
    public double power;
    public double powerS;
    public double turnSpeedS;
    public double turnResistanceS;

    public double fuel;
    public double fuelMax;
    public double shieldTime;
    public double damaged;
    public double stunned;
    public double forceVisible;

    public int shots;
    public int shotMax;
    /** Frame clock value of the last shot (C {@code shot_time}). */
    public double shotTime;
    /** Fuel station being used (C {@code fs}). */
    public int fs;
    public int homeBase;
    public double score;
    public int kills;
    public int deaths;
    public int life;
    public final int[] items = new int[Item.COUNT];

    public boolean locked;
    public int lockId = Obj.NO_ID;
    public double lockDistance;

    public final int[] shovePusher = new int[MAX_SHOVES];
    public final double[] shoveTime = new double[MAX_SHOVES];
    public int shoveNext;

    public int wormHoleHit = -1;
    public int wormHoleDest = -1;
    public double lastWallTouch;

    /** Control keys this tick and as last handled ({@link com.fulcrologic.jxpilot.common.net.ControlKey} mask). */
    public long keys;
    public long prevKeys;

    /** Set when score, lives or status shown on the scoreboard changed (net layer clears it). */
    public boolean scoreDirty;
    /** Set when power or afterburners changed (net layer sends PLAYER_THRUST). */
    public boolean thrustDirty;

    /** Robot brain state, owned by the robot package. */
    public Object robotData;

    public Player(int slot) {
        this.slot = slot;
    }

    /** C {@code Player_position_set_clicks}. */
    public void setClicks(int x, int y) {
        cx = x;
        cy = y;
        px = x >> Click.SHIFT;
        py = y >> Click.SHIFT;
        bx = Math.floorDiv(px, Const.BLOCK_SZ);
        by = Math.floorDiv(py, Const.BLOCK_SZ);
    }

    /** C {@code Player_position_init_pixels}. */
    public void initPixels(double x, double y) {
        setClicks(Click.fromPixels(x), Click.fromPixels(y));
        remember();
    }

    /** C {@code Player_position_remember}. */
    public void remember() {
        prevX = px;
        prevY = py;
    }

    /** True if {@code status & (PLAYING|PAUSE|GAME_OVER|KILLED) == PLAYING}: alive and flying. */
    public boolean isAlive() {
        return (status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER | Obj.KILLED)) == Obj.PLAYING;
    }

    /** Both shield bits used (C {@code BIT(used, HAS_SHIELD|HAS_EMERGENCY_SHIELD) == both}). */
    public boolean fullyShielded() {
        return (used & Has.BOTH_SHIELDS) == Has.BOTH_SHIELDS;
    }

    /** Afterburners in effect for thrust ({@code MAX_AFTERBURNER} with emergency thrust). */
    public int afterburners() {
        return (used & Has.EMERGENCY_THRUST) != 0 ? 15 : items[Item.AFTERBURNER.ordinal()];
    }
}
