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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;

/**
 * The players the server announced (PLAYER_JOIN and friends), in a fixed pool of slots addressed by
 * the wire id (u16). Holds what snapshots do not carry: name, team, engine power and afterburners
 * (client-side thrust sparks), the ship shape's engine offset, and score/lives/status.
 * Updates after warm-up do not allocate except for parsing a new ship shape.
 */
public final class Players {
    /** Maximum simultaneous players (C {@code MAX_PLAYERS} is small; robots included). */
    public static final int CAPACITY = 256;
    /** Max name length kept (C {@code MAX_NAME_LEN}). */
    public static final int MAX_NAME = 32;
    /** C {@code other->real} / {@code host} hold MAX_CHARS (80) bytes: 79 chars. */
    public static final int MAX_IDENTITY = 79;

    private final int[] slotOf = new int[0x10000];
    private final int[] idOf = new int[CAPACITY];
    private final boolean[] used = new boolean[CAPACITY];
    private final int[] team = new int[CAPACITY];
    /** server-commands: C {@code other->alliance}, the alliance mark (' ', '+', '0'..'9'). */
    private final char[] alliance = new char[CAPACITY];
    private final int[] flags = new int[CAPACITY];
    private final float[] power = new float[CAPACITY];
    private final int[] afterburners = new int[CAPACITY];
    private final int[] engineX = new int[CAPACITY];
    private final int[] engineY = new int[CAPACITY];
    private final ShipShape[] shape = new ShipShape[CAPACITY];
    private final StringBuilder[] name = new StringBuilder[CAPACITY];
    /** C {@code other->real} and {@code other->host} (ID mode), at most {@link #MAX_IDENTITY} chars each. */
    private final StringBuilder[] realName = new StringBuilder[CAPACITY];
    private final StringBuilder[] host = new StringBuilder[CAPACITY];
    private final float[] score = new float[CAPACITY];
    private final int[] lives = new int[CAPACITY];
    private final int[] status = new int[CAPACITY];
    private final boolean[] scored = new boolean[CAPACITY];
    private final int[] check = new int[CAPACITY];
    private final int[] round = new int[CAPACITY];
    private final int[] timingTick = new int[CAPACITY];
    /** C {@code other->war_id}: the robot's war or seek target, {@link Protocol#NO_ID} for none. */
    private final int[] warId = new int[CAPACITY];
    private final int[] order = new int[CAPACITY];
    private int count;
    private int selfId = Protocol.NO_ID;
    private int version;

    public Players() {
        for (int i = 0; i < CAPACITY; i++) {
            name[i] = new StringBuilder(MAX_NAME);
            realName[i] = new StringBuilder(MAX_IDENTITY);
            host[i] = new StringBuilder(MAX_IDENTITY);
        }
        java.util.Arrays.fill(slotOf, -1);
    }

    /** Shapes seen recently, matched by content without a String (R7/R10: a rejoin allocates nothing). */
    private static final int SHAPE_CACHE = 32;
    private final StringBuilder[] shapeKey = new StringBuilder[SHAPE_CACHE];
    private final ShipShape[] shapeVal = new ShipShape[SHAPE_CACHE];
    private int shapeNext;

    /**
     * BEHAVIOR: the parsed shape of {@code s}: a shape string seen among the last {@value #SHAPE_CACHE} is
     * reused without parsing or allocating; a new one is read without limits ({@link ShipShape#convert}, C
     * {@code Convert_shape_str}) and kept.
     */
    ShipShape shape(CharSequence s) {
        for (int i = 0; i < SHAPE_CACHE; i++) {
            if (shapeKey[i] != null && contentEquals(shapeKey[i], s)) {
                return shapeVal[i];
            }
        }
        ShipShape sh = ShipShape.convert(s.toString());
        int k = shapeNext;
        shapeNext = (shapeNext + 1) % SHAPE_CACHE;
        if (shapeKey[k] == null) {
            shapeKey[k] = new StringBuilder(s.length());
        }
        shapeKey[k].setLength(0);
        shapeKey[k].append(s);
        shapeVal[k] = sh;
        return sh;
    }

    private static boolean contentEquals(CharSequence a, CharSequence b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** BEHAVIOR: forgets every player and the self id. */
    public void clear() {
        for (int i = 0; i < count; i++) {
            int s = order[i];
            slotOf[idOf[s]] = -1;
            used[s] = false;
        }
        count = 0;
        selfId = Protocol.NO_ID;
        version++;
    }

    /** Number of players. */
    public int count() {
        return count;
    }

    /** Slot of the i-th player in join order (0..count-1). */
    public int slotAt(int i) {
        return order[i];
    }

    /** BEHAVIOR: slot of a wire id, or -1 when unknown. */
    public int slotOf(int id) {
        return slotOf[id & 0xFFFF];
    }

    /** Own player id (from PLAYER_JOIN with FLAG_SELF, or set from ACCEPT). */
    public int selfId() {
        return selfId;
    }

    public void setSelfId(int id) {
        selfId = id;
    }

    /** Incremented on every change (scoreboard re-sorts when it changes). */
    public int version() {
        return version;
    }

    /**
     * PLAYER_JOIN.
     * <p>
     * BEHAVIOR: a known id is updated in place (rejoin), else a free slot is used and appended to the
     * join order; returns the slot or -1 when the pool is full.
     * BEHAVIOR: FLAG_SELF makes this the self id.
     * BEHAVIOR: the engine offset comes from the shape read without limits as C client.c Handle_player's
     * {@code Convert_shape_str} (default ship when empty or invalid);
     * score, lives and status reset (0, -1 unlimited, 0), race progress too (C {@code other->timing = 0});
     * the alliance mark is blank; no war target (C {@code war_id = -1}); the real name and host as sent (C
     * {@code Handle_player}), each cut to {@link #MAX_IDENTITY} chars.
     */
    public int join(Msg.PlayerJoin j) {
        int s = slotOf(j.id);
        if (s < 0) {
            s = freeSlot();
            if (s < 0) {
                return -1;
            }
            used[s] = true;
            idOf[s] = j.id;
            slotOf[j.id & 0xFFFF] = s;
            order[count++] = s;
        }
        team[s] = j.team;
        alliance[s] = ' ';
        flags[s] = j.flags;
        power[s] = j.power;
        afterburners[s] = j.afterburners;
        ShipShape sh = j.shape.length() == 0 ? ShipShape.defaultShip() : shape(j.shape);
        shape[s] = sh;
        engineX[s] = sh.engineX;
        engineY[s] = sh.engineY;
        name[s].setLength(0);
        name[s].append(j.name, 0, Math.min(j.name.length(), MAX_NAME));
        score[s] = 0;
        lives[s] = -1;
        status[s] = 0;
        scored[s] = false;
        check[s] = 0;
        round[s] = 0;
        timingTick[s] = 0;
        warId[s] = Protocol.NO_ID;
        realName[s].setLength(0);
        realName[s].append(j.realName, 0, Math.min(j.realName.length(), MAX_IDENTITY));
        host[s].setLength(0);
        host[s].append(j.hostName, 0, Math.min(j.hostName.length(), MAX_IDENTITY));
        if ((j.flags & Msg.PlayerJoin.FLAG_SELF) != 0) {
            selfId = j.id;
        }
        version++;
        return s;
    }

    private int freeSlot() {
        for (int i = 0; i < CAPACITY; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * BEHAVIOR: removes the player, keeping the join order of the rest; unknown ids are ignored.
     * BEHAVIOR: every war on the leaving id ends (C Handle_leave clears {@code war_id}), even for an id not in
     * the table.
     */
    public void leave(int id) {
        for (int i = 0; i < count; i++) {
            if (warId[order[i]] == id) {
                warId[order[i]] = Protocol.NO_ID;
                version++;
            }
        }
        int s = slotOf(id);
        if (s < 0) {
            return;
        }
        slotOf[id & 0xFFFF] = -1;
        used[s] = false;
        int k = 0;
        for (int i = 0; i < count; i++) {
            if (order[i] != s) {
                order[k++] = order[i];
            }
        }
        count = k;
        version++;
    }

    /**
     * C {@code Handle_war} / {@code Handle_seek}: robot {@code id}'s target becomes {@code target}
     * ({@link Protocol#NO_ID}: none).
     * <p>
     * BEHAVIOR: a known id only; unknown ids are ignored.
     */
    public void setWar(int id, int target) {
        int s = slotOf(id);
        if (s >= 0) {
            warId[s] = target;
            version++;
        }
    }

    /**
     * C {@code Handle_player}'s real name and host (shown in ID mode).
     * <p>
     * BEHAVIOR: a known id only; each is cut to {@link #MAX_IDENTITY} chars.
     */
    public void setIdentity(int id, CharSequence real, CharSequence hostName) {
        int s = slotOf(id);
        if (s < 0) {
            return;
        }
        realName[s].setLength(0);
        realName[s].append(real, 0, Math.min(real.length(), MAX_IDENTITY));
        host[s].setLength(0);
        host[s].append(hostName, 0, Math.min(hostName.length(), MAX_IDENTITY));
        version++;
    }

    /** The player's real (login) name, empty when unknown. */
    public CharSequence realName(int slot) { return realName[slot]; }

    /** The player's host, empty when unknown. */
    public CharSequence host(int slot) { return host[slot]; }

    /** The robot's war target id, {@link Protocol#NO_ID} for none. */
    public int warId(int slot) { return warId[slot]; }

    /** BEHAVIOR: PLAYER_TEAM for a known id; unknown ids are ignored. */
    public void setTeam(int id, int t) {
        int s = slotOf(id);
        if (s >= 0) {
            team[s] = t;
            version++;
        }
    }

    /**
     * server-commands: PLAYER_ALLIANCE (C {@code Handle_score}'s alliance character) for a known id.
     * <p>
     * BEHAVIOR: sets the mark; unknown ids are ignored.
     */
    public void setAlliance(int id, char mark) {
        int s = slotOf(id);
        if (s >= 0) {
            alliance[s] = mark;
            version++;
        }
    }

    /** The alliance mark of a slot: ' ' none, '+' an ally (alliances not announced), '0'..'9' the alliance. */
    public char alliance(int slot) { return alliance[slot]; }

    /** BEHAVIOR: PLAYER_THRUST for a known id; unknown ids are ignored. */
    public void setThrust(int id, float pw, int ab) {
        int s = slotOf(id);
        if (s >= 0) {
            power[s] = pw;
            afterburners[s] = ab;
        }
    }

    /**
     * BEHAVIOR: SCORE for a known id; returns the score change, 0 for unknown ids and for the first SCORE
     * after PLAYER_JOIN (it states the score the player joined with, it is not a change).
     */
    public float setScore(int id, float sc, int lv, int st) {
        int s = slotOf(id);
        if (s < 0) {
            return 0;
        }
        float delta = scored[s] ? sc - score[s] : 0;
        scored[s] = true;
        score[s] = sc;
        lives[s] = lv;
        status[s] = st;
        version++;
        return delta;
    }

    /**
     * C {@code Handle_timing}: a player's race progress.
     * <p>
     * BEHAVIOR: for a known id whose check or round changed: both are set and {@code tick} is remembered (C
     * {@code timing_loops}: who got there first); unchanged values or unknown ids change nothing.
     */
    public void setTiming(int id, int c, int r, int tick) {
        int s = slotOf(id);
        if (s < 0 || check[s] == c && round[s] == r) {
            return;
        }
        check[s] = c;
        round[s] = r;
        timingTick[s] = tick;
        version++;
    }

    /** Next race checkpoint. */
    public int check(int slot) { return check[slot]; }

    /** Race rounds completed. */
    public int round(int slot) { return round[slot]; }

    /** C {@code other->timing}: round * MAX_CHECKS (26) + check. */
    public int timing(int slot) { return round[slot] * 26 + check[slot]; }

    /** Tick the race progress last changed (C {@code timing_loops}). */
    public int timingTick(int slot) { return timingTick[slot]; }

    public int id(int slot) { return idOf[slot]; }

    public int team(int slot) { return team[slot]; }

    /** {@link Msg.PlayerJoin} flags. */
    public int flags(int slot) { return flags[slot]; }

    public float power(int slot) { return power[slot]; }

    public int afterburners(int slot) { return afterburners[slot]; }

    /** Engine x in ship coordinates (heading 0). */
    public int engineX(int slot) { return engineX[slot]; }

    public int engineY(int slot) { return engineY[slot]; }

    /** The parsed ship shape (for drawing). */
    public ShipShape shape(int slot) { return shape[slot]; }

    public CharSequence name(int slot) { return name[slot]; }

    public float score(int slot) { return score[slot]; }

    /** Lives left, -1 = unlimited. */
    public int lives(int slot) { return lives[slot]; }

    /** {@link Msg.Score} STATUS_* bits. */
    public int status(int slot) { return status[slot]; }

    /**
     * C {@code other->mychar} for the score list.
     * <p>
     * BEHAVIOR: 'D' out of lives, 'P' paused, 'W' waiting, 'T' tank, 'R' robot, else ' ' (that priority).
     */
    public char statusChar(int slot) {
        int st = status[slot];
        if ((st & Msg.Score.STATUS_OUT_OF_LIVES) != 0) {
            return 'D';
        }
        if ((st & Msg.Score.STATUS_PAUSED) != 0 || (flags[slot] & Msg.PlayerJoin.FLAG_PAUSED) != 0) {
            return 'P';
        }
        if ((st & Msg.Score.STATUS_WAITING) != 0) {
            return 'W';
        }
        if ((flags[slot] & Msg.PlayerJoin.FLAG_TANK) != 0) {
            return 'T';
        }
        if ((flags[slot] & Msg.PlayerJoin.FLAG_ROBOT) != 0) {
            return 'R';
        }
        return ' ';
    }
}
