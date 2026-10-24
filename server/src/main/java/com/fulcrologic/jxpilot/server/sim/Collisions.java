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
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Ship collisions: ports of {@code PlayerCollision}, {@code PlayerObjectCollision},
 * {@code Player_collides_with_debris}, {@code Player_collides_with_killing_shot}, {@code in_range_acd}
 * ({@code src/server/collision.c}) and {@code Delta_mv}/{@code Obj_repel} ({@code ship.c}).
 * Objects near a ship are found with the {@link com.fulcrologic.jxpilot.server.world.Grid} (C cells:
 * blocks within 4*sqrt(2), at most 500 objects). One tick is one C frame; velocities and masses are C floats.
 * The simulation skips collisions entirely during the round delay (collision.c:250).
 */
public final class Collisions {
    /** C {@code (long) ED_PL_CRASH}. */
    static final long ED_PL_CRASH = (long) (-100.0 * 256);
    /** C {@code ED_SHOT_HIT}. */
    static final double ED_SHOT_HIT = -25.0 * 256;

    private final World w;
    private final Simulation sim;

    public Collisions(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /**
     * C {@code in_range_acd}: did segments p1-&gt;p2 and q1-&gt;q2 (moving simultaneously) come within r?
     * <p>
     * BEHAVIOR: true if the end points are closer than r, or the closest approach during the move is.
     * BEHAVIOR: on wrapping maps a segment longer than half the world is unwrapped (it crossed the seam), then
     * q is moved by a world size so the end points are within half a world (C's cross-seam shifts, plus a fix
     * for objects on opposite sides of the seam that did not cross it).
     */
    boolean inRange(int p1x, int p1y, int p2x, int p2y, int q1x, int q1y, int q2x, int q2y, int r) {
        if (w.wrap) {
            int ww = w.pixelWidth;
            int wh = w.pixelHeight;
            // C: a segment longer than half the world crossed the seam; unwrap it.
            if (Math.abs(p2x - p1x) > ww / 2) {
                if (p1x > p2x) {
                    p1x -= ww;
                } else {
                    p2x -= ww;
                }
            }
            if (Math.abs(p2y - p1y) > wh / 2) {
                if (p1y > p2y) {
                    p1y -= wh;
                } else {
                    p2y -= wh;
                }
            }
            if (Math.abs(q2x - q1x) > ww / 2) {
                if (q1x > q2x) {
                    q1x -= ww;
                } else {
                    q2x -= ww;
                }
            }
            if (Math.abs(q2y - q1y) > wh / 2) {
                if (q1y > q2y) {
                    q1y -= wh;
                } else {
                    q2y -= wh;
                }
            }
            // Then move q by a world size so its end point is within half a world of p's. This subsumes C's
            // four cross-seam shifts (which only translate q, or p, by one world size; the test is translation
            // invariant) and also fixes C for objects on opposite sides of the seam that did not cross it.
            // Results differ from C only for end points exactly half a world apart.
            long ex = (long) p2x - q2x;
            if (ex > ww / 2) {
                q1x += ww;
                q2x += ww;
            } else if (ex < -ww / 2) {
                q1x -= ww;
                q2x -= ww;
            }
            long ey = (long) p2y - q2y;
            if (ey > wh / 2) {
                q1y += wh;
                q2y += wh;
            } else if (ey < -wh / 2) {
                q1y -= wh;
                q2y -= wh;
            }
        }
        long dx = (long) p2x - q2x;
        long dy = (long) p2y - q2y;
        if (dx * dx + dy * dy < (long) r * r) {
            return true;
        }
        long fac1 = -p1x + p2x + q1x - q2x;
        long fac2 = -p1y + p2y + q1y - q2y;
        long top = -(fac1 * (-p2x + q2x) + fac2 * (-p2y + q2y));
        long bot = fac1 * fac1 + fac2 * fac2;
        if (top < 0 || bot < 1 || top > bot) {
            return false;
        }
        double tmin = ((double) top) / ((double) bot);
        double fminx = -p2x + q2x + fac1 * tmin;
        double fminy = -p2y + q2y + fac2 * tmin;
        return fminx * fminx + fminy * fminy < (double) r * r;
    }

    /**
     * A cheap test that two simultaneous moves p1-&gt;p2 and q1-&gt;q2 cannot come within r (perf: it skips most
     * {@link #inRange} calls).
     * <p>
     * BEHAVIOR: true only if on one axis the (wrapped) end-point distance exceeds r plus both moves' lengths
     * on that axis; then the two never get within r of each other during the move, so {@link #inRange} would
     * also say no.
     */
    boolean farApart(int p1x, int p1y, int p2x, int p2y, int q1x, int q1y, int q2x, int q2y, int r) {
        int dx = Math.abs((int) w.wrapDx(q2x - p2x));
        if (dx > r + Math.abs(p2x - p1x) + Math.abs(q2x - q1x)) {
            return true;
        }
        int dy = Math.abs((int) w.wrapDy(q2y - p2y));
        return dy > r + Math.abs(p2y - p1y) + Math.abs(q2y - q1y);
    }

    /**
     * C {@code PlayerCollision} (without balls and checkpoints).
     * <p>
     * BEHAVIOR: only playing, unphased ships collide. A ship outside the world is killed.
     * BEHAVIOR: two ships within 2*SHIP_SZ-6 that are not team-immune: with bounces both lose fuel
     * (unless fully shielded) and repel; with crashes an unshielded ship (or one out of fuel) dies, with
     * "crashed"/"ran over" scoring (Rate * crashScoreMult / runoverKillScoreMult).
     * BEHAVIOR: then each ship collides with nearby objects ({@link #playerObjects}).
     */
    public void check() {
        Params p = w.params;
        for (int i = 0; i < w.numPlayers; i++) {
            Player pl = w.playerAt(i);
            if (!pl.isAlive()) {
                continue;
            }
            if (pl.px < 0 || pl.py < 0 || pl.px >= w.pixelWidth || pl.py >= w.pixelHeight) {
                pl.status |= Obj.KILLED;
                Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_HIT_WALL);
                w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(" left the known universe.");
                w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, pl.id, Obj.NO_ID, Msg.GameEvent.CRASH_UNIVERSE);
                sim.scoring.score(pl, -Scoring.rate(Scoring.WALL_SCORE, pl.score), pl.bx, pl.by, pl.name);
                continue;
            }
            if ((pl.used & Has.PHASING_DEVICE) != 0) {
                continue;
            }
            if (p.crashWithPlayer || p.bounceWithPlayer) {
                for (int j = i + 1; j < w.numPlayers; j++) {
                    Player o = w.playerAt(j);
                    if (!o.isAlive() || (o.used & Has.PHASING_DEVICE) != 0) {
                        continue;
                    }
                    if (!inRange(pl.prevX, pl.prevY, pl.px, pl.py, o.prevX, o.prevY, o.px, o.py,
                        2 * Const.SHIP_SZ - 6)) {
                        continue;
                    }
                    if (w.teamImmune(pl.id, o.id) || pl.pseudoTeam == o.pseudoTeam) {
                        continue;
                    }
                    Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_HIT_PLAYER);
                    shipsCollide(pl, o, p);
                }
            }
            playerObjects(pl);
        }
    }

    /** The body of the ship-ship loop of PlayerCollision for a colliding pair. */
    private void shipsCollide(Player pl, Player o, Params p) {
        if (p.bounceWithPlayer) {
            if (!pl.fullyShielded()) {
                pl.addFuel(ED_PL_CRASH);
            }
            if (!o.fullyShielded()) {
                o.addFuel(ED_PL_CRASH);
            }
            repel(pl, o, 2 * Const.SHIP_SZ);
        }
        if (!p.crashWithPlayer) {
            return;
        }
        if (pl.fuel <= 0 || ((pl.used & Has.SHIELD) == 0 && (pl.have & Has.ARMOR) == 0)) {
            pl.status |= Obj.KILLED;
        }
        if (o.fuel <= 0 || ((o.used & Has.SHIELD) == 0 && (o.have & Has.ARMOR) == 0)) {
            o.status |= Obj.KILLED;
        }
        if ((pl.used & Has.SHIELD) == 0 && (pl.have & Has.ARMOR) != 0) {
            sim.ships.hitArmor(pl);
        }
        if ((o.used & Has.SHIELD) == 0 && (o.have & Has.ARMOR) != 0) {
            sim.ships.hitArmor(o);
        }
        boolean plDead = (pl.status & Obj.KILLED) != 0;
        boolean oDead = (o.status & Obj.KILLED) != 0;
        if (oDead && plDead) {
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(" and ").append(o.name)
                .append(" crashed.");
            double sc = Scoring.rate(o.score, pl.score) * p.crashScoreMult;
            double sc2 = Scoring.rate(pl.score, o.score) * p.crashScoreMult;
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, pl.id, o.id, Msg.GameEvent.CRASH_SHIPS);
            sim.scoring.scorePlayers(pl, -sc, o.name, o, -sc2, pl.name);
        } else if (oDead) {
            ranOver(pl, o, p);
        } else if (plDead) {
            ranOver(o, pl, p);
        }
    }

    private void ranOver(Player winner, Player victim, Params p) {
        w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(winner.name).append(" ran over ")
            .append(victim.name).append('.');
        Sounds.play(w, victim.px, victim.py, SoundEvent.PLAYER_RAN_OVER_PLAYER);
        winner.kills++;
        double sc = Scoring.rate(winner.score, victim.score) * p.runoverKillScoreMult;
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, winner.id, victim.id, 0f);
        sim.scoring.scorePlayers(winner, sc, victim.name, victim, -sc, winner.name);
    }

    /**
     * C {@code Obj_repel} between two ships (C floats).
     * <p>
     * BEHAVIOR: overlap force = min(10, dist - length) along the line between them (RES-quantized direction),
     * split by the mass ratio; both record the other as a shover.
     */
    void repel(Player a, Player b, int repelDist) {
        float xd = (float) w.wrapDx(b.px - a.px);
        float yd = (float) w.wrapDy(b.py - a.py);
        float force = (float) (repelDist - StrictMath.hypot(xd, yd));
        if (force <= 0) {
            return;
        }
        force = Math.min(force, 10);
        int theta = (int) XMath.findDir(xd, yd);
        float dm = a.mass / b.mass;
        float dvx2 = Trig.tcos(theta) * force * dm;
        float dvy2 = Trig.tsin(theta) * force * dm;
        float dvx1 = -(Trig.tcos(theta) * force / dm);
        float dvy1 = -(Trig.tsin(theta) * force / dm);
        sim.ships.recordShove(a, b);
        sim.ships.recordShove(b, a);
        a.vx += dvx1;
        a.vy += dvy1;
        b.vx += dvx2;
        b.vy += dvy2;
    }

    /**
     * C {@code PlayerObjectCollision} for the S1 object kinds.
     * <p>
     * BEHAVIOR: nothing for a ship that is not alive (e.g. killed by a ship crash this frame).
     * BEHAVIOR: an object hits when its path comes within SHIP_SZ + pl_range of the ship's path.
     * BEHAVIOR: immune: own owner-immune sparks, own objects with selfImmunity, team-immune owners, paused
     * owners, unowned objects of the own team with teamImmunity, own shots before their fuse.
     * BEHAVIOR: a hit ends the object; debris drains fuel (and may kill), killing shots damage or kill, and
     * the momentum is shared (inelastic, {@link #deltaMv}).
     */
    void playerObjects(Player pl) {
        if (!pl.isAlive()) {
            return;
        }
        Objects o = w.objects;
        Params p = w.params;
        w.grid.query(pl.bx, pl.by, 4, 500);
        int n = w.grid.resultCount;
        for (int k = 0; k < n; k++) {
            int h = w.grid.result[k];
            if (o.life[h] <= 0) {
                continue;
            }
            int range = Const.SHIP_SZ + o.plRange[h];
            if (farApart(pl.prevX, pl.prevY, pl.px, pl.py, o.prevX[h], o.prevY[h], o.px[h], o.py[h], range)
                || !inRange(pl.prevX, pl.prevY, pl.px, pl.py, o.prevX[h], o.prevY[h], o.px[h], o.py[h], range)) {
                continue;
            }
            int owner = o.owner[h];
            int type = o.type[h];
            if (owner != Obj.NO_ID) {
                if (owner == pl.id) {
                    if ((type & (Obj.SPARK | Obj.MINE)) != 0 && (o.status[h] & Obj.OWNERIMMUNE) != 0) {
                        continue;
                    } else if (p.selfImmunity) {
                        continue;
                    }
                } else if (w.teamImmune(owner, pl.id)) {
                    continue;
                } else {
                    Player op = w.byId(owner);
                    if (op != null && (op.status & Obj.PAUSE) != 0) {
                        continue;
                    }
                }
            } else if (p.teamPlay && p.teamImmunity && o.team[h] == pl.team && type != Obj.BALL) {
                continue;
            }
            if ((type & (Obj.SHOT | Obj.CANNON_SHOT)) != 0 && pl.id == owner && o.life[h] > o.fuseLife[h]) {
                continue;
            }
            // C computes hit = (SHIP_SZ + pl_radius >= range) || in_range_acd(..., range): the same test as above
            // (C passes range, not radius), so every object that gets here hits.
            if (type == Obj.DEBRIS) {
                debris(pl, h);
                if ((pl.status & Obj.KILLED) != 0) {
                    return;
                }
            } else if (type == Obj.CANNON_SHOT) {
                o.mods[h] &= ~Obj.MOD_CLUSTER;
            }
            o.life[h] = 0;
            if ((type & p.killingShots) != 0) {
                killingShot(pl, h);
                if ((pl.status & Obj.KILLED) != 0) {
                    return;
                }
            }
            deltaMv(pl, h);
        }
    }

    /**
     * C {@code Delta_mv}: ship and object end with their common (inelastic) velocity.
     * <p>
     * BEHAVIOR: v = (v_ship*m_ship + v_obj*m_obj) / (m_ship + |m_obj|); a COLLISIONSHOVE object records its
     * owner as a shover.
     */
    void deltaMv(Player pl, int h) {
        Objects o = w.objects;
        float m = pl.mass + Math.abs(o.mass[h]);
        float vx = (pl.vx * pl.mass + o.vx[h] * o.mass[h]) / m;
        float vy = (pl.vy * pl.mass + o.vy[h] * o.mass[h]) / m;
        if (o.owner[h] != Obj.NO_ID && (o.status[h] & Obj.COLLISIONSHOVE) != 0) {
            Player pusher = w.byId(o.owner[h]);
            if (pusher != null && pusher != pl) {
                sim.ships.recordShove(pl, pusher);
            }
        }
        pl.vx = vx;
        pl.vy = vy;
        o.vx[h] = vx;
        o.vy[h] = vy;
    }

    /**
     * C {@code Player_collides_with_debris}.
     * <p>
     * BEHAVIOR: unless fully shielded the ship loses |(long)(2*mass*|v|)| fuel; at zero fuel it dies
     * ("succumbed to an explosion"): the owner (if another player) gets the kill and
     * Rate(owner, victim)*explosionKillScoreMult, else the victim loses Rate(0, score)*explosion*selfKill mults.
     */
    void debris(Player pl, int h) {
        Objects o = w.objects;
        Params p = w.params;
        float v = (float) StrictMath.hypot(o.vx[h], o.vy[h]);
        long cost = Math.abs((long) (2 * o.mass[h] * v));
        if (!pl.fullyShielded()) {
            pl.addFuel(-cost);
        }
        if (pl.fuel != 0) {
            return;
        }
        pl.status |= Obj.KILLED;
        StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name)
            .append(" succumbed to an explosion");
        Player killer = w.byId(o.owner[h]);
        if (killer != null) {
            msg.append(" from ").append(killer.name);
            if (killer == pl) {
                msg.append(".  How strange!");
            } else {
                msg.append('.');
            }
        } else {
            msg.append('.');
        }
        if (killer == null || killer == pl) {
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, Obj.NO_ID, pl.id, 0f);
            sim.scoring.score(pl, -Scoring.rate(0, pl.score) * p.explosionKillScoreMult * p.selfKillScoreMult,
                pl.bx, pl.by, killer == null ? "[Explosion]" : pl.name);
        } else {
            killer.kills++;
            double sc = Scoring.rate(killer.score, pl.score) * p.explosionKillScoreMult;
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, killer.id, pl.id, 0f);
            sim.scoring.scorePlayers(killer, sc, pl.name, pl, -sc, killer.name);
        }
        o.life[h] = 0;
    }

    /**
     * C {@code Player_collides_with_killing_shot} for shots and cannon shots.
     * <p>
     * BEHAVIOR: with shields up (or armor) the shot only drains fuel (unless fully shielded):
     * 25*256 * (relative speed^2 * |mass|) / (shotSpeed^2 * shotMass) with shotHitFuelDrainUsesKineticEnergy,
     * else 25*256; shields drop at zero fuel; armor is used when unshielded.
     * BEHAVIOR: otherwise the ship dies: cannon fire costs Rate(CANNON_SCORE, score)/4 * mult (and gives it to
     * the cannon's team in team play), unowned shots Rate(0)*unownedKillScoreMult, own shots
     * Rate(0)*selfKillScoreMult, another player's shot Rate(killer, victim) to the killer (a kill).
     * The factor is shotKillScoreMult (clusterKillScoreMult for cluster fragments).
     */
    void killingShot(Player pl, int h) {
        Objects o = w.objects;
        Params p = w.params;
        int type = o.type[h];
        if ((pl.used & Has.SHIELD) != 0 || (pl.have & Has.ARMOR) != 0) {
            if (type == Obj.SHOT || type == Obj.CANNON_SHOT) {
                Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_EAT_SHOT);
            }
            if (!pl.fullyShielded()) {
                float drainFactor = 1.0f;
                if (p.shotHitFuelDrainUsesKineticEnergy) {
                    float rel = (float) StrictMath.hypot(pl.vx - o.vx[h], pl.vy - o.vy[h]);
                    drainFactor = (rel * rel * Math.abs(o.mass[h])) / (p.shotSpeed * p.shotSpeed * p.shotMass);
                }
                pl.addFuel((long) (ED_SHOT_HIT * drainFactor * 1.0f));
            }
            if (pl.fuel <= 0) {
                pl.used &= ~Has.SHIELD;
            }
            if ((pl.used & Has.SHIELD) == 0 && (pl.have & Has.ARMOR) != 0) {
                sim.ships.hitArmor(pl);
            }
            return;
        }
        Player killer = null;
        double sc;
        StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name);
        boolean fromCannon = (o.status[h] & Obj.FROMCANNON) != 0;
        if (fromCannon) {
            Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_HIT_CANNONFIRE);
            msg.append(" was hit by cannonfire.");
            sc = Scoring.rate(Scoring.CANNON_SCORE, pl.score) / 4;
        } else if (o.owner[h] == Obj.NO_ID || w.byId(o.owner[h]) == null) {
            msg.append(" was killed by ").append(describe(type, o.mods[h])).append('.');
            sc = Scoring.rate(0, pl.score) * p.unownedKillScoreMult;
        } else {
            killer = w.byId(o.owner[h]);
            msg.append(" was killed by ").append(describe(type, o.mods[h])).append(" from ").append(killer.name)
                .append('.');
            if (killer == pl) {
                Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_SHOT_THEMSELF);
                msg.append("  How strange!");
                sc = Scoring.rate(0, pl.score) * p.selfKillScoreMult;
            } else {
                killer.kills++;
                sc = Scoring.rate(killer.score, pl.score);
            }
        }
        sc *= (type == Obj.SHOT && (o.mods[h] & Obj.MOD_CLUSTER) != 0) ? p.clusterKillScoreMult : p.shotKillScoreMult;
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, killer != null ? killer.id : Obj.NO_ID, pl.id, 0f);
        if (fromCannon) {
            sim.scoring.score(pl, -sc, pl.bx, pl.by, "Cannon");
            if (p.teamPlay && pl.team != o.team[h]) {
                sim.scoring.teamScore(o.team[h], sc);
            }
        } else if (killer == null || killer == pl) {
            sim.scoring.score(pl, -sc, pl.bx, pl.by, killer == null ? "" : pl.name);
        } else {
            sim.scoring.scorePlayers(killer, sc, pl.name, pl, -sc, killer.name);
        }
        pl.status |= Obj.KILLED;
    }

    /**
     * C {@code Describe_shot(type, status, mods, 1)} for unmodified shots and cannon shots.
     * <p>
     * BEHAVIOR: "a shot"; cluster fragments are "cluster debris", cluster cannon shots "cluster flak".
     */
    static String describe(int type, int mods) {
        if ((mods & Obj.MOD_CLUSTER) == 0) {
            return "a shot";
        }
        return type == Obj.CANNON_SHOT ? "cluster flak" : "cluster debris";
    }
}
