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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.ShipBody;

/**
 * A player slot (C {@code player}, the subset needed for S1). Preallocated by {@link World}; reused when a
 * player leaves. Units and types follow C: positions in clicks/pixels, velocities in pixels per frame,
 * headings in RES units, fuel in C fixed point ({@code FUEL_SCALE_FACT} = 256 per fuel unit). Fields that C
 * keeps as {@code DFLOAT} (float) are {@code float} here so the physics rounds exactly like C; timers count
 * frames (one sim tick is one C frame).
 * <p>
 * The state the ship kernel reads and writes (position, velocity, turning, fuel, abilities, items, shot and key
 * state) is declared by {@link ShipBody}; this class adds the rest of the server's player.
 */
public final class Player extends ShipBody {
    /** C {@code MAX_RECORDED_SHOVES}. */
    public static final int MAX_SHOVES = 4;

    /** Slot index in {@link World#players}. */
    public final int slot;
    /**
     * C {@code GetInd[id]}: position in {@link World#order} while in the game, else -1. The visibility tables
     * ({@link #canSee}, {@link #visLastChange}) are indexed by it, as C's {@code visibility[]} by player index.
     */
    public int ind = -1;
    /** Wire id (stable while the player is in the game). */
    public int id;
    public boolean inUse;
    /** False between {@code Ships.reserve} and {@code Ships.spawn}: the slot is taken but not in the game. */
    public boolean spawned;
    /** A computer-controlled player (C {@code IS_ROBOT_PTR}); set at join, shown as 'R' on scoreboards. */
    public boolean robot;
    public final StringBuilder name = new StringBuilder(Const.MAX_CHARS);
    /**
     * C {@code realname} and {@code hostname} (server-batch2): what the client sent (cleaned as C
     * {@code Fix_real_name}/{@code Fix_host_name}), or robotRealName/robotHostName, tankRealName/tankHostName.
     */
    public final StringBuilder realName = new StringBuilder(Const.MAX_CHARS);
    public final StringBuilder hostName = new StringBuilder(Const.MAX_CHARS);
    /** The client's address as text (C {@code Get_player_addr}); empty for robots and tanks. */
    public final StringBuilder address = new StringBuilder(48);
    /**
     * The slot was used before (C: the reused player struct still holds its last occupant's team; a never-used
     * struct is calloc'd, team 0). Read by Init_player's limited-lives check ({@code Ships.initPlayer}).
     */
    public boolean structUsed;
    public int pseudoTeam;
    /**
     * C {@code mychar}: the score list marker: ' ' playing, 'P' paused, 'W' waiting for the next round
     * (limited lives), 'D' dead until the next round.
     */
    public char mychar = ' ';

    /** Respawn / self-destruct / pause countdown in frames; -1 when idle (C {@code count}). */
    public int count = -1;

    public int damaged;

    /** Fuel station being used (C {@code fs}). */
    public int fs;
    public int homeBase;
    /** Score (C keeps a float; double here, see docs/DEVIATIONS-server.md). */
    public float score;
    public int kills;
    public int deaths;
    public int life;
    /** Sounds for this player's next snapshot (C {@code pl->audio}); see {@code sim.Sounds}. */
    public final AudioQueue audio = new AudioQueue();

    public final int[] shovePusher = new int[MAX_SHOVES];
    public final long[] shoveTime = new long[MAX_SHOVES];
    public int shoveNext;

    public int wormHoleHit = -1;

    // ---- game modes (server-modes): balls, targets, race
    /** C {@code pl->ball}: handle of the ball the connector is reaching for, or -1. */
    public int ball = -1;
    /** C {@code repair_target}: the target being repaired. */
    public int repairTarget;
    /** C {@code check}: the next checkpoint to pass. */
    public int check;
    /** C {@code round}: laps started (0 before the first checkpoint). */
    public int round;
    /** C {@code time}: race frames since the first checkpoint. */
    public int time;
    /** C {@code best_lap}, {@code last_lap}, {@code last_lap_time} in frames. */
    public int bestLap;
    public int lastLap;
    public int lastLapTime;
    /** C {@code last_check_dir}: heading when the last checkpoint was passed. */
    public int lastCheckDir;
    /** Race progress changed this tick (C {@code prev_check}/{@code prev_round}: a TIMING event is due). */
    public boolean timingDirty;


    /** Score, lives or status changed this tick (a SCORE event is emitted at the end of the tick). */
    public boolean scoreDirty;

    // ---- alliances and operator status (server-commands: C alliance.c, command.c)
    /** C {@code ALLIANCE_NOT_SET}. */
    public static final int ALLIANCE_NOT_SET = -1;
    /** C {@code alliance}: the id of the player's alliance, or {@link #ALLIANCE_NOT_SET}. */
    public int alliance = ALLIANCE_NOT_SET;
    /** C {@code invite}: the id of the player this one has invited into an alliance, or {@link Obj#NO_ID}. */
    public int invite = Obj.NO_ID;
    /** C {@code isoperator}: operator status, given by {@code /password}. */
    public boolean isOperator;
    /** JXPilot {@code /password} brute-force guard: wrong tries since the last cooldown, its end (frames), its length. */
    public int passwordFails;
    public long passwordLockedUntil;
    public int passwordLockFrames;

    // ================================================================ items (server-items, C player fields)
    /** C {@code LOCKBANK_MAX} and {@code NUM_MODBANKS}. */
    public static final int BANKS = 4;
    /** C {@code LOCK_PLAYER}. */
    public static final int LOCK_PLAYER = 1;
    /** C {@code LOCK_VISIBLE}: the lock was shown on the HUD this frame. */
    public static final int LOCK_VISIBLE = 2;

    /** A detached fuel tank (C {@code OBJ_EXT_TANK}); its owner is {@link #lockPlId}, as in C. */
    public boolean tank;

    /** C {@code lock.tagged}: {@link #LOCK_PLAYER}, {@link #LOCK_VISIBLE}. */
    public int lockTagged;
    /** C {@code lock.pl_id}. */
    public int lockPlId;
    /** C {@code lock.distance} (a float). */
    public float lockDistance;
    /** C {@code lockbank}: stored lock ids, -1 (C NOT_CONNECTED) when empty. */
    public final int[] lockBank = new int[BANKS];

    /** C {@code modbank}. */
    public final int[] modBank = new int[BANKS];

    /**
     * C {@code connection_t.view_width} / {@code view_height}: the client's view size in world pixels, limited by
     * {@code Receive_display} (reliable DISPLAY); C DEF_VIEW_SIZE until the client sends one. Per connection in C,
     * held here because the sim's HUD lock test ({@code Locks.hudLock}) needs it; the host resets it at login.
     */
    public int viewWidth = com.fulcrologic.jxpilot.common.net.Msg.Display.DEF_VIEW_SIZE;
    public int viewHeight = com.fulcrologic.jxpilot.common.net.Msg.Display.DEF_VIEW_SIZE;

    /** C {@code sensor_range}. */
    public float sensorRange;
    /** C {@code visibility[j].canSee}, indexed by the other player's {@link #slot}. */
    public boolean[] canSee = new boolean[0];
    /** C {@code visibility[j].lastChange}, indexed by slot. */
    public long[] visLastChange = new long[0];
    /** C {@code forceVisible}: frames this ship stays visible to everyone. */
    public int forceVisible;
    /** C {@code updateVisibility}. */
    public boolean updateVisibility;

    /** C {@code num_pulses}: own laser pulses in flight. */
    public int numPulses;
    /** C {@code ecmcount}: own ECM blasts still expanding. */
    public int ecmCount;
    /** C {@code missile_rack}: next missile rack to fire from. */
    public int missileRack;

    public int emergencyThrustLeft;
    public int emergencyThrustMax;
    public int emergencyShieldLeft;
    public int emergencyShieldMax;
    public int phasingLeft;
    public int phasingMax;

    /** C {@code lose_item}: the item KEY_LOSE_ITEM drops. */
    public int loseItem;
    /** C {@code lose_item_state} (2 key down, 1 key up, negative: shown for a few more frames). */
    public int loseItemState;

    /** C autopilot saves of power, turnspeed and turnresistance. */
    public float autoPowerS;
    public float autoTurnSpeedS;
    public float autoTurnResistanceS;
    /** C {@code tractor_is_pressor}. */
    public boolean tractorIsPressor;

    public Player(int slot) {
        this.slot = slot;
    }

    /** Size the per-player visibility tables for {@code players} slots (called once by {@link World}). */
    public void initVisibility(int players) {
        canSee = new boolean[players];
        visLastChange = new long[players];
    }
}
