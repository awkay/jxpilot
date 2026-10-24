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
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Player locks and visibility: ports of {@code Player_lock_allowed}, {@code Player_lock_closest},
 * {@code team_dead} and the lock keys of {@code Handle_keyboard} ({@code src/server/event.c}),
 * {@code Compute_sensor_range} and Go_home's visibility reset ({@code src/server/player.c}), the visibility
 * loop of {@code Update_objects} (update.c:938-956) and the HUD lock test of {@code Frame_status}
 * ({@code src/server/frame.c}), which also decides {@code LOCK_VISIBLE}.
 */
public final class Locks {
    /** C {@code UPDATE_RATE} of the visibility loop. */
    static final int UPDATE_RATE = 100;
    /** C {@code ENERGY_RANGE_FACTOR}. */
    static final double ENERGY_RANGE_FACTOR = 2.5 / 256;

    private final World w;
    /** C {@code minVisibilityDistance}, {@code maxVisibilityDistance} and {@code EnergyRangeFactor}. */
    private double minVisibility;
    private double maxVisibility;
    private double energyRangeFactor;
    /** C {@code Compute_sensor_range}'s {@code static int init}. */
    private boolean init;

    public Locks(World w) {
        this.w = w;
    }

    /**
     * C {@code Compute_sensor_range}'s first-call block (player.c:230-248), run by the first sensor-range
     * computation.
     * <p>
     * BEHAVIOR: the limits become pixels (VISIBILITY_DISTANCE and the world diagonal for 0, else blocks *
     * BLOCK_SZ) and, as C writes them back into the option variables, {@code /get} shows those pixel values from
     * now on; the energy factor makes the starting fuel and sensors reach exactly the minimum.
     */
    private void init() {
        init = true;
        double min = w.options.getDouble(Opt.MIN_VISIBILITY_DISTANCE);
        double max = w.options.getDouble(Opt.MAX_VISIBILITY_DISTANCE);
        minVisibility = min <= 0.0 ? (float) Const.VISIBILITY_DISTANCE : (float) ((float) min * Const.BLOCK_SZ);
        maxVisibility = max <= 0.0 ? (float) w.params.hypotenuse : (float) ((float) max * Const.BLOCK_SZ);
        w.options.replaceValue(Opt.MIN_VISIBILITY_DISTANCE, Float.toString((float) minVisibility));
        w.options.replaceValue(Opt.MAX_VISIBILITY_DISTANCE, Float.toString((float) maxVisibility));
        int fuel0 = w.items.initial[Item.FUEL.ordinal()];
        if (fuel0 > 0.0) {
            float f = (float) (minVisibility / (fuel0 * (1.0 + (w.items.initial[Item.SENSOR.ordinal()] * 0.25))));
            energyRangeFactor = (float) (f / 256);
        } else {
            energyRangeFactor = (float) ENERGY_RANGE_FACTOR;
        }
    }

    /**
     * After /set minVisibilityDistance (C tuner_dummy): C's variable holds the new value as given. Before the first
     * sensor-range computation {@link #init} still scales it (blocks); after it, it is compared with ranges as
     * pixels, and EnergyRangeFactor keeps its first value.
     * <p>
     * BEHAVIOR: the minimum becomes {@code v} pixels, whatever its sign (until {@link #init} replaces it).
     */
    public void tuneMinVisibility(double v) {
        minVisibility = v;
    }

    /**
     * After /set maxVisibilityDistance: as {@link #tuneMinVisibility}.
     * <p>
     * BEHAVIOR: the maximum becomes {@code v} pixels, whatever its sign (until {@link #init} replaces it).
     */
    public void tuneMaxVisibility(double v) {
        maxVisibility = v;
    }

    /**
     * C {@code Compute_sensor_range}.
     * <p>
     * BEHAVIOR: fuel * energy factor, +25% per sensor, limited to [minVisibility, maxVisibility] (VISIBILITY_DISTANCE
     * and the world diagonal unless the options set them in blocks); the factor makes the starting fuel and sensors
     * reach exactly minVisibility.
     */
    public void sensorRange(Player pl) {
        if (!init) {
            init();
        }
        float r = (float) (pl.fuel * energyRangeFactor);
        r = (float) (r * (1.0 + (pl.items[Item.SENSOR.ordinal()] * 0.25)));
        if (r < minVisibility) {
            r = (float) minVisibility;
        }
        if (r > maxVisibility) {
            r = (float) maxVisibility;
        }
        pl.sensorRange = r;
    }

    // ================================================================ visibility

    /**
     * The visibility loop of the player loop (update.c:938-956) for player {@code pl}.
     * <p>
     * BEHAVIOR: a forced-visible {@code pl} is seen by everyone. {@code pl} sees itself and every uncloaked
     * player; for a cloaked one it re-decides when either asked for a visibility update or by chance (rfrac() *
     * 100 &lt; frames since the last change): it sees it iff rfrac() * (sensors + 1) &gt; rfrac() * (cloaks + 1).
     */
    public void updateVisibility(Player pl) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (pl.forceVisible != 0) {
                o.canSee[pl.ind] = true;
            }
            if (o == pl || (o.used & Has.CLOAKING_DEVICE) == 0) {
                pl.canSee[o.ind] = true;
            } else if (pl.updateVisibility || o.updateVisibility
                || (int) (w.rfrac() * UPDATE_RATE) < Math.abs(w.frames - pl.visLastChange[o.ind])) {
                pl.visLastChange[o.ind] = w.frames;
                pl.canSee[o.ind] = (w.rfrac() * (pl.items[Item.SENSOR.ordinal()] + 1))
                    > (w.rfrac() * (o.items[Item.CLOAK.ordinal()] + 1));
            }
        }
    }

    /**
     * Go_home's visibility reset (player.c:210-214): update requested and the last-change times toward and from
     * {@code pl} zeroed, by player index; a player not yet in the game uses the index it is about to get (C's
     * Init_player runs Go_home with ind = NumPlayers).
     */
    public void resetVisibility(Player pl) {
        pl.updateVisibility = true;
        int ind = pl.ind >= 0 ? pl.ind : w.numPlayers;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            pl.visLastChange[k] = 0;
            o.visLastChange[ind] = 0;
        }
    }

    /** C {@code visibility[].canSee} with the team, alliance and own-tank exceptions of the frame code. */
    public boolean sees(Player viewer, Player target) {
        return viewer.canSee[target.ind] || viewer == target || w.sameTeam(viewer, target) || ownsTank(viewer, target)
            || w.allied(viewer, target);
    }

    /** C {@code OWNS_TANK(i, j)}: {@code tank} is a tank released by {@code pl}. */
    public boolean ownsTank(Player pl, Player tank) {
        return tank.tank && tank.lockPlId != -1 && tank.lockPlId == pl.id;
    }

    // ================================================================ locks

    /** C {@code team_dead}: nobody of the team is playing and not game over. */
    boolean teamDead(int team) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p.team == team && (p.status & (Obj.PLAYING | Obj.GAME_OVER)) == Obj.PLAYING) {
                return false;
            }
        }
        return true;
    }

    /**
     * C {@code Player_lock_allowed}.
     * <p>
     * BEHAVIOR: never on oneself or nobody; anyone while actively playing (event.c:139-141) or outside team play;
     * otherwise (paused, waiting or game over) team mates, other teams with lockOtherTeam or when the own team is
     * dead.
     */
    public boolean lockAllowed(Player pl, Player target) {
        if (target == null || target == pl) {
            return false;
        }
        if ((pl.status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER)) == Obj.PLAYING) {
            return true;
        }
        if (!w.params.teamPlay) {
            return true;
        }
        if (w.sameTeam(pl, target)) {
            return true;
        }
        if (w.items.lockOtherTeam) {
            return true;
        }
        return teamDead(pl.team);
    }

    /**
     * Whose eyes {@code pl}'s snapshot shows (C frame.c:1314-1336, Frame_update).
     * <p>
     * BEHAVIOR: with a player lock on someone in the game, a player out of the game (GAME_OVER while PLAYING)
     * sees through the locked player; so does a paused one when the locked player is a team mate in team play
     * or allowViewing is on. Everyone else sees through their own eyes. (C's server-owner exception has no
     * equivalent: there is no owner.)
     */
    public Player viewOf(Player pl) {
        if ((pl.lockTagged & Player.LOCK_PLAYER) == 0) {
            return pl;
        }
        Player t = w.byId(pl.lockPlId);
        if (t == null) {
            return pl;
        }
        boolean over = (pl.status & (Obj.GAME_OVER | Obj.PLAYING)) == (Obj.GAME_OVER | Obj.PLAYING);
        boolean paused = (pl.status & Obj.PAUSE) != 0
            && ((w.params.teamPlay && pl.team != Const.TEAM_NOT_SET && pl.team == t.team) || w.items.allowViewing);
        return over || paused ? t : pl;
    }

    /**
     * C {@code Player_lock_closest}: lock on the closest allowed playing player farther than the current lock
     * ({@code next}), or the closest at all.
     * <p>
     * BEHAVIOR: own tanks, team mates and allies are skipped. Returns false (the lock is cleared unless {@code next})
     * when nobody qualifies.
     */
    public boolean lockClosest(Player pl, boolean next) {
        if (!next) {
            pl.lockTagged &= ~Player.LOCK_PLAYER;
        }
        Player lock = null;
        float dist = 0;
        if ((pl.lockTagged & Player.LOCK_PLAYER) != 0) {
            lock = w.byId(pl.lockPlId);
            if (lock != null) {
                dist = w.wrapLength(lock.px - pl.px, lock.py - pl.py);
            }
        }
        Player best = null;
        float bestDist = Float.MAX_VALUE;
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p == lock || (p.status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER)) != Obj.PLAYING
                || !lockAllowed(pl, p) || ownsTank(pl, p) || w.sameTeam(pl, p) || w.allied(pl, p)) {
                continue;
            }
            float l = w.wrapLength(p.px - pl.px, p.py - pl.py);
            if (l >= dist && l < bestDist) {
                bestDist = l;
                best = p;
            }
        }
        if (best == null) {
            return false;
        }
        pl.lockTagged |= Player.LOCK_PLAYER;
        pl.lockPlId = best.id;
        return true;
    }

    /**
     * C KEY_LOCK_NEXT / KEY_LOCK_PREV.
     * <p>
     * BEHAVIOR: without a valid lock it locks on the closest; otherwise it steps through the player order (wrapping)
     * to the next/previous allowed player that is not game over or paused; coming back to oneself clears the lock.
     */
    public void lockNext(Player pl, boolean prev) {
        Player cur = (pl.lockTagged & Player.LOCK_PLAYER) != 0 ? w.byId(pl.lockPlId) : null;
        int j = cur != null ? order(cur) : -1;
        if (j < 0) {
            lockClosest(pl, false);
            return;
        }
        int i = j;
        int self = order(pl);
        do {
            if (prev) {
                if (--i < 0) {
                    i = w.numPlayers - 1;
                }
            } else if (++i >= w.numPlayers) {
                i = 0;
            }
            if (i == j) {
                break;
            }
        } while (i == self || (w.playerAt(i).status & (Obj.GAME_OVER | Obj.PAUSE)) != 0
            || !lockAllowed(pl, w.playerAt(i)));
        if (i == self) {
            pl.lockTagged &= ~Player.LOCK_PLAYER;
        } else {
            pl.lockPlId = w.playerAt(i).id;
            pl.lockTagged |= Player.LOCK_PLAYER;
        }
    }

    /** C KEY_LOCK_NEXT_CLOSE: the next closer-than-current lock, else the closest. */
    public void lockNextClose(Player pl) {
        if (!lockClosest(pl, true)) {
            lockClosest(pl, false);
        }
    }

    /**
     * C KEY_TOGGLE_COMPASS.
     * <p>
     * BEHAVIOR: with a compass it toggles; turning it on keeps a valid lock on another player, else locks on the
     * closest.
     */
    public void toggleCompass(Player pl) {
        if ((pl.have & Has.COMPASS) == 0) {
            return;
        }
        pl.used ^= Has.COMPASS;
        if ((pl.used & Has.COMPASS) == 0) {
            return;
        }
        Player t = w.byId(pl.lockPlId);
        if ((pl.lockTagged & Player.LOCK_PLAYER) != 0 && w.numPlayers > 1 && t != null && order(t) > 0 && t != pl) {
            return;
        }
        lockClosest(pl, false);
    }

    /**
     * C KEY_LOAD_LOCK_1..4.
     * <p>
     * BEHAVIOR: while REPROGRAM is held the current lock is stored in the bank; otherwise a stored, allowed lock
     * is loaded.
     */
    public void loadLock(Player pl, int bank) {
        if ((pl.status & Obj.REPROGRAM) != 0) {
            if ((pl.lockTagged & Player.LOCK_PLAYER) != 0) {
                pl.lockBank[bank] = pl.lockPlId;
            }
        } else if (pl.lockBank[bank] != -1 && lockAllowed(pl, w.byId(pl.lockBank[bank]))) {
            pl.lockPlId = pl.lockBank[bank];
            pl.lockTagged |= Player.LOCK_PLAYER;
        }
    }

    /** Position of {@code p} in the player order (C index), or -1. */
    int order(Player p) {
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k) == p) {
                return k;
            }
        }
        return -1;
    }

    /**
     * The lock part of C {@code Frame_status} (frame.c:395-422), run by the host when it builds a client's
     * snapshot, on the player whose eyes the snapshot shows, with that client's view size (C
     * {@code Frame_status(conn, ind)}).
     * <p>
     * BEHAVIOR: LOCK_VISIBLE is cleared, then set when locked with the compass on and the target is within sensor
     * range (limited visibility), seen (or a team mate or own tank), playing, on radar or in view, and not at
     * distance 0.
     * BEHAVIOR: in view is C's {@code inview} with the client's view size ({@link Player#viewWidth} x
     * {@link Player#viewHeight}, from DISPLAY): the view starts {@code size / 2} (integer division) before the
     * player, so the target's wrapped offset must be in {@code (-(w/2), w - w/2)} and {@code (-(h/2), h - h/2)},
     * both ends excluded.
     */
    public void hudLock(Player pl) {
        hudLock(pl, pl.viewWidth, pl.viewHeight);
    }

    /** {@link #hudLock(Player)} with the receiving client's view size. */
    public void hudLock(Player pl, int viewWidth, int viewHeight) {
        pl.lockTagged &= ~Player.LOCK_VISIBLE;
        if ((pl.lockTagged & Player.LOCK_PLAYER) == 0 || (pl.used & Has.COMPASS) == 0) {
            return;
        }
        Player t = w.byId(pl.lockPlId);
        if (t == null) {
            return;
        }
        if ((!w.items.limitedVisibility || pl.lockDistance <= pl.sensorRange)
            && (pl.canSee[t.ind] || ownsTank(pl, t) || w.sameTeam(pl, t) || w.allied(pl, t))
            && (t.status & (Obj.PLAYING | Obj.GAME_OVER)) == Obj.PLAYING
            && (w.items.playersOnRadar || inView(pl, t, viewWidth, viewHeight))
            && pl.lockDistance != 0) {
            pl.lockTagged |= Player.LOCK_VISIBLE;
        }
    }

    /** C frame.c {@code inview} after {@code Frame_parameters} (view origin {@code pos - view_size / 2}). */
    private boolean inView(Player pl, Player t, int vw, int vh) {
        double dx = w.wrapDx(t.px - pl.px);
        double dy = w.wrapDy(t.py - pl.py);
        int hx = vw / 2;
        int hy = vh / 2;
        return dx > -hx && dx < vw - hx && dy > -hy && dy < vh - hy;
    }
}
