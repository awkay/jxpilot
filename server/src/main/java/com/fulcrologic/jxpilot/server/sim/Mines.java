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
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Mines: ports of {@code Place_mine}, {@code Place_moving_mine}, {@code Place_general_mine},
 * {@code Detonate_mines} and {@code Move_mine} ({@code src/server/shot.c}), {@code MineCollision} and
 * {@code Player_collides_with_mine} ({@code src/server/collision.c}).
 */
public final class Mines {
    /** C {@code MINE_MASS}. */
    static final float MINE_MASS = 30.0f;
    /** C {@code MINE_RANGE}. */
    static final double MINE_RANGE = Const.VISIBILITY_DISTANCE * 0.1;
    /** C {@code MINE_RADIUS}. */
    static final int MINE_RADIUS = 8;
    /** C {@code MINE_SPEED_FACT}. */
    static final double MINE_SPEED_FACT = 1.3;
    /** C {@code MINI_MINE_SPREAD_TIME}, {@code MINI_MINE_SPREAD_SPEED}. */
    static final int MINI_MINE_SPREAD_TIME = 18;
    static final int MINI_MINE_SPREAD_SPEED = 8;
    /** C {@code ED_MINE}. */
    static final long ED_MINE = -60 * 256;
    /** C {@code NUKE_MASS_MULT}. */
    static final int NUKE_MASS_MULT = 1;
    /** C {@code CANNON_SHOT_LIFE}'s base and spread. */
    static final int CANNON_SHOT_LIFE_BASE = 8;

    private final World w;
    private final Simulation sim;

    public Mines(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /**
     * C {@code Place_mine} (KEY_DROP_MINE).
     * <p>
     * BEHAVIOR: nothing without mines, or shielded/phased without shieldedMining. With minMineSpeed it throws
     * a moving mine instead; otherwise a still mine at the ship.
     */
    public void placeMine(Player pl) {
        if (pl.items[Item.MINE.ordinal()] <= 0
            || ((pl.used & (Has.SHIELD | Has.PHASING_DEVICE)) != 0 && !w.items.shieldedMining)) {
            return;
        }
        if (w.items.minMineSpeed > 0) {
            placeMovingMine(pl);
            return;
        }
        placeGeneral(pl, pl.team, 0, pl.px, pl.py, 0.0, 0.0, pl.mods);
    }

    /**
     * C {@code Place_moving_mine} (KEY_DETACH_MINE).
     * <p>
     * BEHAVIOR: as {@link #placeMine}, but a GRAVITY mine with the ship's velocity, raised to minMineSpeed when
     * slower (along the heading when nearly still).
     */
    public void placeMovingMine(Player pl) {
        if (pl.items[Item.MINE.ordinal()] <= 0
            || ((pl.used & (Has.SHIELD | Has.PHASING_DEVICE)) != 0 && !w.items.shieldedMining)) {
            return;
        }
        float vx = pl.vx;
        float vy = pl.vy;
        float min = w.items.minMineSpeed;
        if (min > 0 && pl.velocity < min) {
            if (pl.velocity >= 1) {
                vx *= (min / pl.velocity);
                vy *= (min / pl.velocity);
            } else {
                vx = min * Trig.tcos(pl.dir);
                vy = min * Trig.tsin(pl.dir);
            }
        }
        placeGeneral(pl, pl.team, Obj.GRAVITY, pl.px, pl.py, vx, vy, pl.mods);
    }

    /**
     * C {@code Place_general_mine} for a player ({@code pl}) or none (cannons, rogue mines).
     * <p>
     * BEHAVIOR: nothing when the pool lacks room for the minis or the (wrapped) position is outside the world.
     * BEHAVIOR: life is (int)(rfrac*FPS) for a killed player, CANNON_SHOT_LIFE from a cannon, else mineLife or
     * MINE_LIFETIME (5000 + randomMT()&amp;255). Velocity needs cluster, spread needs mini; nuclear needs
     * nukeMinSmarts &gt; 0.
     * BEHAVIOR: a nuclear mine uses nukeMinMines mines (all with FULLNUCLEAR) and has that many times the mass; a
     * player with fewer gets "You need at least N mines to drop/throw ...". A cannon mine weighs 0.6 of a mine.
     * BEHAVIOR: a player pays ED_MINE plus the cluster drain, or gets "You need at least N fuel units to ..."; with
     * baseMineRange, mining within that many blocks of another non-immune player's base is refused ("No base
     * mining!"). A nuke launch is announced to all with NUKE_LAUNCH; a single mine plays DROP_MINE (or
     * DROP_MOVING_MINE when thrown) at the player.
     * BEHAVIOR: mini+1 OWNERIMMUNE blue mines share mass and life, fuse = mineFuseTime; minis spread out
     * (MINI_MINE_SPREAD_SPEED / (spread+1), slowing to zero over MINI_MINE_SPREAD_TIME) around the heading; all get
     * the thrown velocity * 1.3; pl_range = MINE_RANGE / minis, pl_radius = MINE_RADIUS.
     */
    public void placeGeneral(Player pl, int team, int status, double x, double y, double vx, double vy, int mods) {
        Objects o = w.objects;
        ItemParams ip = w.items;
        if (o.count() + Mods.mini(mods) >= o.capacity) {
            return;
        }
        if (w.wrap) {
            if (x < 0) {
                x += w.pixelWidth;
            } else if (x >= w.pixelWidth) {
                x -= w.pixelWidth;
            }
            if (y < 0) {
                y += w.pixelHeight;
            } else if (y >= w.pixelHeight) {
                y -= w.pixelHeight;
            }
        }
        if (x < 0 || x >= w.pixelWidth || y < 0 || y >= w.pixelHeight) {
            return;
        }
        int life;
        if (pl != null && (pl.status & Obj.KILLED) != 0) {
            life = (int) (w.rfrac() * w.params.fps);
        } else if ((status & Obj.FROMCANNON) != 0) {
            life = CANNON_SHOT_LIFE_BASE + Integer.remainderUnsigned(w.randomMT(), 24);
        } else {
            life = ip.mineLife != 0 ? ip.mineLife : 5000 + (w.randomMT() & 255);
        }
        if (!Mods.isCluster(mods)) {
            mods = Mods.withVelocity(mods, 0);
        }
        if (Mods.mini(mods) == 0) {
            mods = Mods.withSpread(mods, 0);
        }
        if (ip.nukeMinSmarts <= 0) {
            mods = Mods.withNuclear(mods, Mods.nuclear(mods) & ~Mods.NUCLEAR);
        }
        float mass;
        int used;
        if (Mods.isNuclear(mods)) {
            if (pl != null) {
                used = Mods.isFullNuclear(mods) ? pl.items[Item.MINE.ordinal()] : ip.nukeMinMines;
                if (pl.items[Item.MINE.ordinal()] < ip.nukeMinMines) {
                    StringBuilder m = w.events.playerMessage(Msg.Text.KIND_GAME, Obj.NO_ID, pl.id)
                        .append("You need at least ").append(ip.nukeMinMines).append(" mines to ")
                        .append((status & Obj.GRAVITY) != 0 ? "throw" : "drop").append(' ');
                    ShotNames.append(m, Obj.MINE, status, mods, false).append('!');
                    return;
                }
            } else {
                used = ip.nukeMinMines;
            }
            mass = MINE_MASS * used * NUKE_MASS_MULT;
        } else {
            mass = (status & Obj.FROMCANNON) != 0 ? (float) (MINE_MASS * 0.6) : MINE_MASS;
            used = 1;
        }
        if (pl != null) {
            long drain = ED_MINE;
            if (Mods.isCluster(mods)) {
                drain += (long) (Missiles.clusterMassShots(mass, w.params.shotMass) * Shots.ED_SHOT_F);
            }
            if (pl.fuel < -drain) {
                StringBuilder m = w.events.playerMessage(Msg.Text.KIND_GAME, Obj.NO_ID, pl.id)
                    .append("You need at least ").append((-drain) >> 8).append(" fuel units to ")
                    .append((status & Obj.GRAVITY) != 0 ? "throw" : "drop").append(' ');
                ShotNames.append(m, Obj.MINE, status, mods, false).append('!');
                return;
            }
            if (ip.baseMineRange != 0 && baseMined(pl, x, y)) {
                w.events.playerMessage(Msg.Text.KIND_GAME, Obj.NO_ID, pl.id).append("No base mining!");
                return;
            }
            pl.addFuel(drain);
            pl.items[Item.MINE.ordinal()] -= used;
            if (used > 1) {
                StringBuilder m = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(" has ")
                    .append((status & Obj.GRAVITY) != 0 ? "thrown" : "dropped").append(' ');
                ShotNames.append(m, Obj.MINE, status, mods, false).append('!');
                Sounds.playAll(w, SoundEvent.NUKE_LAUNCH);
            } else {
                Sounds.play(w, pl.px, pl.py, (status & Obj.GRAVITY) != 0 ? SoundEvent.DROP_MOVING_MINE
                    : SoundEvent.DROP_MINE);
            }
        }
        int minis = Mods.mini(mods) + 1;
        status |= Obj.OWNERIMMUNE;
        for (int i = 0; i < minis; i++) {
            int h = o.alloc();
            if (h < 0) {
                break;
            }
            o.type[h] = Obj.MINE;
            o.color[h] = Records.ObjColor.BLUE;
            o.info[h] = ip.mineFuseTime;
            o.status[h] = status;
            o.owner[h] = pl != null ? pl.id : Obj.NO_ID;
            o.team[h] = team;
            o.mineOwner[h] = o.owner[h];
            o.initPixels(h, x, y);
            float mvx;
            float mvy;
            if (minis > 1) {
                int space = Const.RES / minis;
                float spread = (float) (Mods.spread(mods) + 1);
                int dir = (i * space) + space / 2 + (minis - 2) * (Const.RES / 2) + (pl != null ? pl.dir : 0);
                dir += (int) ((w.rfrac() - 0.5f) * space * 0.5f);
                dir = Trig.mod(dir);
                mvx = MINI_MINE_SPREAD_SPEED * Trig.tcos(dir) / spread;
                mvy = MINI_MINE_SPREAD_SPEED * Trig.tsin(dir) / spread;
                o.spreadLeft[h] = MINI_MINE_SPREAD_TIME;
                o.accX[h] = -mvx / MINI_MINE_SPREAD_TIME;
                o.accY[h] = -mvy / MINI_MINE_SPREAD_TIME;
            } else {
                mvx = mvy = 0;
                o.accX[h] = o.accY[h] = 0;
                o.spreadLeft[h] = 0;
            }
            o.vx[h] = (float) (mvx + vx * MINE_SPEED_FACT);
            o.vy[h] = (float) (mvy + vy * MINE_SPEED_FACT);
            o.mass[h] = mass / minis;
            o.life[h] = life / minis;
            o.mods[h] = mods;
            o.plRange[h] = (int) (MINE_RANGE / minis);
            o.plRadius[h] = MINE_RADIUS;
        }
    }

    /** The baseMineRange test: within that many blocks of another non-team-immune, non-tank player's base. */
    private boolean baseMined(Player pl, double x, double y) {
        int r = w.items.baseMineRange;
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p == pl || w.teamImmune(p.id, pl.id) || p.tank || p.homeBase < 0) {
                continue;
            }
            Base b = w.map.bases().get(p.homeBase);
            int dx = (int) (x / Const.BLOCK_SZ - b.bx());
            int dy = (int) (y / Const.BLOCK_SZ - b.by());
            if (dx * dx + dy * dy <= r * r) {
                return true;
            }
        }
        return false;
    }

    /**
     * C {@code Detonate_mines} (KEY_DETONATE_MINES): the closest mine the player controls explodes.
     * <p>
     * BEHAVIOR: nothing while phased; reprogrammed mines belong to whoever reprogrammed them.
     */
    public void detonate(Player pl) {
        if ((pl.used & Has.PHASING_DEVICE) != 0) {
            return;
        }
        Objects o = w.objects;
        int closest = -1;
        double minDist = w.params.hypotenuse + 1;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if ((o.type[h] & Obj.MINE) == 0 || o.owner[h] != pl.id) {
                continue;
            }
            float dist = w.wrapLength(pl.px - o.px[h], pl.py - o.py[h]);
            if (dist < minDist) {
                minDist = dist;
                closest = h;
            }
        }
        if (closest != -1) {
            o.life[closest] = 0;
        }
    }

    /**
     * C {@code Move_mine}, before the mine moves.
     * <p>
     * BEHAVIOR: confusion counts down and ends at 0; a fused owner-immune mine counts its fuse down and loses
     * owner immunity at 0; a mini mine's spread acceleration stops when its spread time is over.
     */
    public void move(int h) {
        Objects o = w.objects;
        if ((o.status[h] & Obj.CONFUSED) != 0 && --o.counter[h] <= 0) {
            o.status[h] &= ~Obj.CONFUSED;
        }
        if ((o.status[h] & Obj.OWNERIMMUNE) != 0 && o.info[h] != 0) {
            if (--o.info[h] <= 0) {
                o.status[h] &= ~Obj.OWNERIMMUNE;
            }
        }
        if (Mods.mini(o.mods[h]) != 0 && o.spreadLeft[h]-- <= 0) {
            o.accX[h] = 0;
            o.accY[h] = 0;
        }
    }

    /**
     * C {@code MineCollision}: with mineShotDetonateDistance, a shot or missile passing within that distance
     * (plus its radius) of a mine destroys both.
     * <p>
     * BEHAVIOR: nothing when mineShotDetonateDistance is 0; each live mine is checked against the shots near it
     * (blocks within 4, at most 300 objects) and stops at the first hit.
     */
    public void collide() {
        int dist = w.items.mineShotDetonateDistance;
        if (dist == 0) {
            return;
        }
        Objects o = w.objects;
        int types = Obj.SHOT | Obj.TORPEDO | Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.CANNON_SHOT;
        for (int k = 0; k < o.count(); k++) {
            int m = o.active[k];
            if (o.type[m] != Obj.MINE || o.life[m] <= 0) {
                continue;
            }
            w.grid.query(o.px[m] / Const.BLOCK_SZ, o.py[m] / Const.BLOCK_SZ, 4, 300);
            int n = w.grid.resultCount;
            for (int j = 0; j < n; j++) {
                int h = w.grid.result[j];
                if ((o.type[h] & types) == 0 || o.life[h] <= 0) {
                    continue;
                }
                if (!sim.collisions.inRange(o.prevX[m], o.prevY[m], o.px[m], o.py[m], o.prevX[h], o.prevY[h],
                    o.px[h], o.py[h], dist + o.plRadius[h])) {
                    continue;
                }
                o.life[h] = 0;
                o.life[m] = 0;
                break;
            }
        }
    }

    /**
     * C {@code Player_collides_with_mine}: the message and the score of a mine hit (the explosion follows when
     * the mine's life ends).
     * <p>
     * BEHAVIOR: an unowned, never-owned mine: "X hit a mine." without scoring; a mine controlled by its layer:
     * "X hit a mine dropped  by L." (C's format gives two spaces); a mine whose layer left: "X hit a mine
     * reprogrammed by R." (R "some jerk" when unowned); otherwise "... dropped  by L and reprogrammed by R.".
     * BEHAVIOR: PLAYER_HIT_MINE plays at the victim first.
     * The controller (if any, even the victim) scores Rate(controller, victim) * mineScoreMult from the victim.
     */
    public void playerHit(Player pl, int h) {
        Objects o = w.objects;
        int id = o.owner[h];
        int layer = o.mineOwner[h];
        Player killer = null;
        Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_HIT_MINE);
        StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(" hit ");
        ShotNames.append(msg, Obj.MINE, o.status[h], o.mods[h], true);
        String how = (o.status[h] & Obj.GRAVITY) != 0 ? "thrown " : "dropped ";
        if (id == Obj.NO_ID && layer == Obj.NO_ID) {
            msg.append('.');
        } else if (layer == id) {
            killer = w.byId(layer);
            msg.append(' ').append(how).append(" by ").append(killer != null ? killer.name : "").append('.');
        } else {
            Player rep = id != Obj.NO_ID ? w.byId(id) : null;
            killer = rep;
            if (layer == Obj.NO_ID) {
                msg.append(" reprogrammed by ");
            } else {
                Player l = w.byId(layer);
                msg.append(' ').append(how).append(" by ").append(l != null ? l.name : "")
                    .append(" and reprogrammed by ");
            }
            msg.append(rep != null ? rep.name : "some jerk").append('.');
        }
        if (killer != null) {
            double sc = Scoring.rate(killer.score, pl.score) * w.items.mineScoreMult;
            sim.scoring.scorePlayers(killer, sc, pl.name, pl, -sc, killer.name);
        }
    }
}
