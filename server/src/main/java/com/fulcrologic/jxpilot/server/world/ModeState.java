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
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.common.map.Treasure;
import com.fulcrologic.jxpilot.common.map.WorldMap;

import java.util.Arrays;

/**
 * Dynamic state of the game modes: the extra per-object fields of C {@code ballobject} and {@code wireobject}
 * (indexed by {@link Objects} handle, valid while the handle holds a ball, asteroid or wreckage), treasure and
 * team treasure counters (C {@code treasure_t}, {@code team_t}), target damage (C {@code target_t.damage}) and
 * the asteroid list (C {@code Asteroid_list}, in creation order). Preallocated; no allocation per tick.
 */
public final class ModeState {
    /** C {@code TARGET_DAMAGE}: a target's full strength (fixed point fuel units). */
    public static final int TARGET_DAMAGE = 250 << 8;

    // ---- ballobject
    /** C {@code ball->owner}: the player who last connected to the ball, or {@link Obj#NO_ID}. */
    public final int[] ballOwner;
    /** C {@code ball->treasure}: the treasure the ball belongs to. */
    public final int[] ballTreasure;
    /** C {@code ball->length}: distance ball to player. */
    public final float[] ballLength;

    // ---- treasures (C treasure_t)
    /** C {@code treasure_t.team}: the closest team in team play, else 0 (map.c). */
    public final int[] treasureTeam;
    /** A ball rests in the treasure (C {@code have}). */
    public final boolean[] treasureHave;
    /** Times the treasure was destroyed (C {@code destroyed}). */
    public final int[] treasureDestroyed;

    // ---- teams (C team_t)
    public final int[] teamTreasures = new int[Const.MAX_TEAMS];
    public final int[] teamEmptyTreasures = new int[Const.MAX_TEAMS];
    public final int[] teamTreasuresDestroyed = new int[Const.MAX_TEAMS];
    public final int[] teamTreasuresLeft = new int[Const.MAX_TEAMS];

    // ---- targets
    /** C {@code target_t.team}: the closest team in team play, else 0 (map.c). */
    public final int[] targetTeam;
    /** C {@code target_t.damage}: strength left, {@link #TARGET_DAMAGE} when intact. */
    public final int[] targetDamage;
    /** Frame of the last change (C {@code last_change}). */
    public final long[] targetLastChange;

    // ---- asteroids (C Asteroid_list, World.asteroids.num)
    /** Asteroid handles in creation order, {@code asteroidList[0..asteroidCount)}. */
    public final int[] asteroidList;
    public int asteroidCount;
    /** C {@code World.asteroids.num}: asteroids in size-1 units (a size n asteroid counts 2^(n-1)). */
    public int asteroidNum;

    public ModeState(WorldMap map, int capacity) {
        ballOwner = new int[capacity];
        ballTreasure = new int[capacity];
        ballLength = new float[capacity];
        asteroidList = new int[capacity];
        int nt = map.treasures().size();
        treasureHave = new boolean[nt];
        treasureDestroyed = new int[nt];
        treasureTeam = new int[nt];
        for (Treasure t : map.treasures()) {
            int team = map.teamPlay() ? t.team() : 0;
            treasureTeam[t.index()] = team;
            if (!map.teamPlay() || team < 0 || team >= Const.MAX_TEAMS) {
                continue;
            }
            teamTreasures[team]++;
            if (t.empty()) {
                teamEmptyTreasures[team]++;
            } else {
                teamTreasuresLeft[team]++;
            }
        }
        targetTeam = new int[map.targets().size()];
        for (Target t : map.targets()) {
            targetTeam[t.index()] = map.teamPlay() ? t.team() : 0;
        }
        targetDamage = new int[map.targets().size()];
        targetLastChange = new long[map.targets().size()];
        Arrays.fill(targetDamage, TARGET_DAMAGE);
    }

    /**
     * Index of a handle in the asteroid list, or -1.
     * <p>
     * BEHAVIOR: linear search in creation order (C {@code List_find}).
     */
    public int asteroidIndex(int h) {
        for (int i = 0; i < asteroidCount; i++) {
            if (asteroidList[i] == h) {
                return i;
            }
        }
        return -1;
    }

    /** BEHAVIOR: appends a handle to the asteroid list (C {@code List_push_back}); false when full. */
    public boolean addAsteroid(int h) {
        if (asteroidCount == asteroidList.length) {
            return false;
        }
        asteroidList[asteroidCount++] = h;
        return true;
    }

    /** BEHAVIOR: removes a handle keeping the order of the rest (C {@code List_erase}); false if absent. */
    public boolean removeAsteroid(int h) {
        int i = asteroidIndex(h);
        if (i < 0) {
            return false;
        }
        System.arraycopy(asteroidList, i + 1, asteroidList, i, asteroidCount - i - 1);
        asteroidCount--;
        return true;
    }
}
