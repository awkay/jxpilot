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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import static com.fulcrologic.jxpilot.server.sim.MoveState.*;

/**
 * Consequences of wall crashes: ports of {@code Object_crash}, {@code Player_crash} and
 * {@code Cannon_dies} ({@code src/server/walls.c}). Targets are solid but take no damage in S1, and
 * treasures/balls are not simulated.
 */
public final class Crashes {
    /** Shoves older than this many frames don't count (walls.c:2147). */
    static final int SHOVE_WINDOW = 20;

    private final World w;
    private final Simulation sim;
    private final Player[] pushers = new Player[Player.MAX_SHOVES];
    private final int[] pushCount = new int[Player.MAX_SHOVES];

    public Crashes(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /**
     * C {@code Object_crash}.
     * <p>
     * BEHAVIOR: wormhole crashes (and NOT_A_CRASH, C's default case) do nothing; every other crash ends the
     * object's life.
     * BEHAVIOR: an item hitting a cannon is added to its stock; anything else destroys an unshielded cannon
     * ({@link #cannonDies}), or uses up one of its armors.
     */
    public void objectCrashed(int h, MoveState ms) {
        if (ms.crash == CRASH_WORMHOLE || ms.crash == NOT_A_CRASH) {
            return;
        }
        w.objects.life[h] = 0;
        if (ms.crash == CRASH_CANNON) {
            if (w.objects.type[h] == Obj.ITEM) {
                sim.cannonWeapons.addItem(ms.cannon, w.objects.info[h], w.objects.counter[h]);
            } else if ((w.cannonItems.used[ms.cannon] & Has.EMERGENCY_SHIELD) == 0) {
                if (w.cannonItems.item(ms.cannon, com.fulcrologic.jxpilot.common.Item.ARMOR) > 0) {
                    w.cannonItems.add(ms.cannon, com.fulcrologic.jxpilot.common.Item.ARMOR, -1);
                } else {
                    cannonDies(ms.cannon, w.objects.owner[h]);
                }
            }
        }
    }

    /**
     * C {@code Player_crash}.
     * <p>
     * BEHAVIOR: a wormhole crash starts warping (WARPING, wormHoleHit); NOT_A_CRASH does nothing (C logs an
     * error).
     * BEHAVIOR: a cannon crash destroys the cannon; a fully shielded ship (shield + emergency shield) survives
     * it and gets the cannon points.
     * BEHAVIOR: other crashes kill the ship (KILLED) with a message ("head first" for hull point 0). Shovers
     * of the last 20 frames share Rate(pusher, victim) * shoveKillScoreMult and the last one gets the
     * kill; the victim loses Rate(average pusher score, score) * shoveKillScoreMult. Without shovers the
     * victim loses Rate(WALL_SCORE, score).
     * BEHAVIOR: the GAME_EVENT comes before the score changes it causes: CRASH(victim, NO_ID, sub-type) without
     * shovers, else KILL(last pusher, victim, sub-type) ({@link #crashKind}).
     */
    public void playerCrashed(Player pl, MoveState ms, int pt) {
        String how;
        switch (ms.crash) {
            case NOT_A_CRASH -> {
                return;
            }
            case CRASH_WORMHOLE -> {
                pl.status |= Obj.WARPING;
                pl.wormHoleHit = ms.wormhole;
                return;
            }
            case CRASH_WALL -> how = " against a wall";
            case CRASH_WALL_SPEED -> how = " against a wall";
            case CRASH_WALL_NO_FUEL -> how = " against a wall";
            case CRASH_WALL_ANGLE -> how = " against a wall";
            case CRASH_TARGET -> how = " against a target";
            case CRASH_TREASURE -> how = " against a treasure";
            case CRASH_CANNON -> {
                boolean shielded = pl.fullyShielded();
                if ((w.cannonItems.used[ms.cannon] & Has.EMERGENCY_SHIELD) == 0) {
                    cannonDies(ms.cannon, shielded ? pl.id : Obj.NO_ID);
                }
                if (shielded) {
                    return;
                }
                how = " against a cannon";
            }
            case CRASH_UNIVERSE -> how = null;
            default -> how = " into a programming error";
        }
        pl.status |= Obj.KILLED;
        StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID);
        msg.append(pl.name).append(verb(ms.crash));
        if (how == null) {
            msg.append(" the known universe");
        }
        if (pt == 0) {
            msg.append(" head first");
        }
        if (how != null) {
            msg.append(how);
        }
        String hud = hud(ms.crash);
        int kind = crashKind(ms.crash);
        int num = collectPushers(pl);
        if (num == 0) {
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, pl.id, Obj.NO_ID, kind);
            sim.scoring.score(pl, -Scoring.rate(Scoring.WALL_SCORE, pl.score), pl.bx, pl.by, hud);
            msg.append('.');
            return;
        }
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, pushers[num - 1].id, pl.id, kind);
        int total = 0;
        double totalScore = 0;
        for (int i = 0; i < num; i++) {
            total += pushCount[i];
            totalScore += pushCount[i] * pushers[i].score;
        }
        int average = (int) (totalScore / total);
        for (int i = 0; i < num; i++) {
            Player pusher = pushers[i];
            msg.append(i == 0 ? " with help from " : (i < num - 1 ? ", " : " and ")).append(pusher.name);
            double sc = pushCount[i] * Scoring.rate(pusher.score, pl.score) * w.params.shoveKillScoreMult / total;
            sim.scoring.score(pusher, sc, pl.bx, pl.by, pl.name);
            if (i >= num - 1) {
                pusher.kills++;
            }
        }
        sim.scoring.score(pl, -Scoring.rate(average, pl.score) * w.params.shoveKillScoreMult, pl.bx, pl.by,
            "[Shove]");
        msg.append('.');
    }

    /** BEHAVIOR: the {@code Msg.GameEvent} crash sub-type of a crash code. */
    static int crashKind(int crash) {
        return switch (crash) {
            case CRASH_WALL, CRASH_WALL_SPEED, CRASH_WALL_NO_FUEL, CRASH_WALL_ANGLE -> Msg.GameEvent.CRASH_WALL;
            case CRASH_TARGET -> Msg.GameEvent.CRASH_TARGET;
            case CRASH_TREASURE -> Msg.GameEvent.CRASH_TREASURE;
            case CRASH_CANNON -> Msg.GameEvent.CRASH_CANNON;
            case CRASH_UNIVERSE -> Msg.GameEvent.CRASH_UNIVERSE;
            default -> Msg.GameEvent.CRASH_UNKNOWN;
        };
    }

    /** BEHAVIOR: the score popup label C gives each crash kind (walls.c Player_crash {@code hudmsg}). */
    static String hud(int crash) {
        return switch (crash) {
            case CRASH_WALL, CRASH_WALL_SPEED, CRASH_WALL_NO_FUEL, CRASH_WALL_ANGLE -> "[Wall]";
            case CRASH_TARGET -> "[Target]";
            case CRASH_TREASURE -> "[Treasure]";
            case CRASH_CANNON -> "[Cannon]";
            case CRASH_UNIVERSE -> "[Universe]";
            default -> "[Bug]";
        };
    }

    private static String verb(int crash) {
        return switch (crash) {
            case CRASH_WALL -> " crashed";
            case CRASH_WALL_NO_FUEL -> " smacked";
            case CRASH_WALL_ANGLE -> " was trashed";
            case CRASH_UNIVERSE -> " left";
            case CRASH_UNKNOWN -> " slammed";
            default -> " smashed";
        };
    }

    /**
     * Distinct pushers among the shove records of the last {@link #SHOVE_WINDOW} frames, in record order,
     * with their counts (walls.c:2142-2165). Returns the number of pushers.
     */
    int collectPushers(Player pl) {
        int num = 0;
        for (int i = 0; i < Player.MAX_SHOVES; i++) {
            int id = pl.shovePusher[i];
            if (id == Obj.NO_ID || pl.shoveTime[i] < w.frames - SHOVE_WINDOW) {
                continue;
            }
            Player pusher = w.byId(id);
            if (pusher == null) {
                continue;
            }
            int j = 0;
            while (j < num && pushers[j] != pusher) {
                j++;
            }
            if (j == num) {
                pushers[num] = pusher;
                pushCount[num] = 0;
                num++;
            }
            pushCount[j]++;
        }
        return num;
    }

    /**
     * C {@code Cannon_dies}: the cannon is dead for cannonDeadTime frames, its block becomes SPACE and it
     * explodes into 20 + (int)(rfrac*20) debris aimed out of its face (dir +- RES*0.2, speed 20-50, life
     * 8-68 capped by shotLife).
     * <p>
     * BEHAVIOR: with cannonPoints &gt; 0 and a killer: in team play with teamCannons the cannon's team loses
     * cannonPoints; the killer gains cannonPoints if their score is at most cannonMaxScore and they are not on
     * the cannon's team.
     * BEHAVIOR: a dead cannon dies only once.
     *
     * @param killerId the player who gets the credit, or NO_ID
     */
    public void cannonDies(int index, int killerId) {
        if (index < 0 || w.cannonDeadTime[index] > 0) {
            return;
        }
        Cannon c = w.map.cannons().get(index);
        w.cannonDeadTime[index] = w.params.cannonDeadTime;
        w.map.setBlock(c.bx(), c.by(), BlockType.SPACE);
        sim.cannonWeapons.throwItems(index);
        sim.cannonWeapons.init(index);
        int x = (int) c.x();
        int y = (int) c.y();
        sim.debris.make(x, y, 0f, 0f, Obj.NO_ID, c.team(), Obj.DEBRIS, 4.5f, Obj.GRAVITY, Records.ObjColor.RED,
            Records.BurstKind.DEBRIS, 6, 20, 40, (int) (c.dir() - (Const.RES * 0.2)), (int) (c.dir() + (Const.RES * 0.2)),
            20, 50, 8, 68);
        Player pl = w.byId(killerId);
        if (pl == null || w.params.cannonPoints <= 0) {
            return;
        }
        if (w.params.teamPlay && w.params.teamCannons) {
            sim.scoring.teamScore(c.team(), -w.params.cannonPoints);
        }
        if (pl.score <= w.params.cannonMaxScore && !(w.params.teamPlay && pl.team == c.team())) {
            sim.scoring.score(pl, w.params.cannonPoints, c.bx(), c.by(), "");
        }
    }
}
