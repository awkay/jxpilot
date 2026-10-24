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
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
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

    // ---- message texts as fields: resolved when the class loads, never inside a tick (no allocation)
    private static final String T_AND = Texts.of(" and ");
    private static final String T_ATE = Texts.of(" ate ");
    private static final String T_A_SHOT = Texts.of("a shot");
    private static final String T_CANNON = Texts.of("Cannon");
    private static final String T_CLUSTER_DEBRIS = Texts.of("cluster debris");
    private static final String T_CLUSTER_FLAK = Texts.of("cluster flak");
    private static final String T_CRASHED = Texts.of(" crashed.");
    private static final String T_EXPLOSION = Texts.of("[Explosion]");
    private static final String T_FROM = Texts.of(" from ");
    private static final String T_HOW_STRANGE = Texts.of(".  How strange!");
    private static final String T_HOW_STRANGE_2 = Texts.of("  How strange!");
    private static final String T_LEFT_THE_KNOWN_UNIVERSE = Texts.of(" left the known universe.");
    private static final String T_RAN_OVER = Texts.of(" ran over ");
    private static final String T_SUCCUMBED_TO_AN_EXPLOSION = Texts.of(" succumbed to an explosion");
    private static final String T_WAS_HIT_BY_CANNONFIRE = Texts.of(" was hit by cannonfire.");
    private static final String T_WAS_KILLED_BY = Texts.of(" was killed by ");

    /** C {@code (long) ED_PL_CRASH}. */
    static final long ED_PL_CRASH = (long) (-100.0 * 256);
    /** C {@code ED_SHOT_HIT}. */
    static final double ED_SHOT_HIT = -25.0 * 256;
    /** C {@code ED_SMART_SHOT_HIT}. */
    static final double ED_SMART_SHOT_HIT = -120.0 * 256;

    private final World w;
    private final Simulation sim;
    /** C {@code wreckageCollisionMayKill}. */
    private boolean wreckageMayKill;

    public Collisions(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        reloadOptions();
    }

    /** server-commands: (re)read the options this class keeps (at construction, and after {@code /set}). */
    public void reloadOptions() {
        this.wreckageMayKill = w.options.getBool(com.fulcrologic.jxpilot.common.config.Opt.WRECKAGE_COLLISION_MAY_KILL);
    }

    /**
     * C {@code in_range_acd}: did segments p1-&gt;p2 and q1-&gt;q2 (moving simultaneously) come within r?
     * <p>
     * BEHAVIOR: true if the end points are closer than r, or the closest approach during the move is.
     * BEHAVIOR: on wrapping maps a segment longer than half the world is unwrapped (it crossed the seam), then
     * C's four shifts move the other segment by one world size when only one of them crossed and the other lies
     * in the upper half (collision.c:183-201), with C's copy-paste kept for parity: both y shifts test "q crossed,
     * p did not", so a p that crossed in y never moves q. Objects on opposite sides of the seam that did not
     * cross it are not seen as close (as C).
     */
    boolean inRange(int p1x, int p1y, int p2x, int p2y, int q1x, int q1y, int q2x, int q2y, int r) {
        if (w.wrap) {
            int ww = w.pixelWidth;
            int wh = w.pixelHeight;
            // C: a segment longer than half the world crossed the seam; unwrap it.
            boolean mpx = Math.abs(p2x - p1x) > ww / 2;
            if (mpx) {
                if (p1x > p2x) {
                    p1x -= ww;
                } else {
                    p2x -= ww;
                }
            }
            boolean mpy = Math.abs(p2y - p1y) > wh / 2;
            if (mpy) {
                if (p1y > p2y) {
                    p1y -= wh;
                } else {
                    p2y -= wh;
                }
            }
            boolean mqx = Math.abs(q2x - q1x) > ww / 2;
            if (mqx) {
                if (q1x > q2x) {
                    q1x -= ww;
                } else {
                    q2x -= ww;
                }
            }
            boolean mqy = Math.abs(q2y - q1y) > wh / 2;
            if (mqy) {
                if (q1y > q2y) {
                    q1y -= wh;
                } else {
                    q2y -= wh;
                }
            }
            // C's cross-seam shifts (collision.c:183-201)
            if (mpx && !mqx && (q2x > ww / 2 || q1x > ww / 2)) {
                q1x -= ww;
                q2x -= ww;
            }
            // C bug kept for parity: this test should read mpy && !mqy (it repeats the next one)
            if (mqy && !mpy && (q2y > wh / 2 || q1y > wh / 2)) {
                q1y -= wh;
                q2y -= wh;
            }
            if (mqx && !mpx && (p2x > ww / 2 || p1x > ww / 2)) {
                p1x -= ww;
                p2x -= ww;
            }
            if (mqy && !mpy && (p2y > wh / 2 || p1y > wh / 2)) {
                p1y -= wh;
                p2y -= wh;
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
                w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(T_LEFT_THE_KNOWN_UNIVERSE);
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
            sim.balls.connect(pl);
            playerObjects(pl);
            sim.race.playerFrame(pl);
        }
    }

    /** The body of the ship-ship loop of PlayerCollision for a colliding pair. */
    private void shipsCollide(Player pl, Player o, Params p) {
        if (p.bounceWithPlayer) {
            if (!pl.fullyShielded()) {
                pl.addFuel(ED_PL_CRASH);
                sim.items.damage(pl, w.items.destroyItemInCollisionProb);
            }
            if (!o.fullyShielded()) {
                o.addFuel(ED_PL_CRASH);
                sim.items.damage(o, w.items.destroyItemInCollisionProb);
            }
            pl.forceVisible = 20;
            o.forceVisible = 20;
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
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(T_AND).append(o.name)
                .append(T_CRASHED);
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, pl.id, o.id, Msg.GameEvent.CRASH_SHIPS);
            if (!pl.tank && !o.tank) {
                float sc = Scoring.rate(o.score, pl.score) * p.crashScoreMult;
                float sc2 = Scoring.rate(pl.score, o.score) * p.crashScoreMult;
                sim.scoring.scorePlayers(pl, -sc, o.name, o, -sc2, pl.name);
            } else if (pl.tank) {
                tankCrash(pl, o, p);
            } else {
                tankCrash(o, pl, p);
            }
        } else if (oDead) {
            ranOver(pl, o, p);
        } else if (plDead) {
            ranOver(o, pl, p);
        }
    }

    /**
     * Both ships died and {@code tank} is a tank: its owner scores tankKillScoreMult from the other. As C, when both
     * are tanks the first one's owner scores (collision.c:378; C's "don't bother scoring two tanks" comment is not
     * what its code does).
     */
    private void tankCrash(Player tank, Player other, Params p) {
        Player owner = w.byId(tank.lockPlId);
        if (owner == null) {
            return;
        }
        float sc = Scoring.rate(owner.score, other.score) * p.tankKillScoreMult;
        sim.scoring.scorePlayers(owner, sc, other.name, other, -sc, tank.name);
    }

    /**
     * C's "ran over" branches of PlayerCollision.
     * <p>
     * BEHAVIOR: the winner gets the kill; a tank's score goes to its owner (unless the owner is the victim) with
     * tankKillScoreMult, a ship's with runoverKillScoreMult.
     */
    private void ranOver(Player winner, Player victim, Params p) {
        w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(winner.name).append(T_RAN_OVER)
            .append(victim.name).append('.');
        Sounds.play(w, victim.px, victim.py, SoundEvent.PLAYER_RAN_OVER_PLAYER);
        winner.kills++;
        Player scorer = winner;
        float sc;
        if (winner.tank) {
            Player owner = w.byId(winner.lockPlId);
            if (owner != null && owner != victim) {
                scorer = owner;
            }
            sc = Scoring.rate(scorer.score, victim.score) * p.tankKillScoreMult;
        } else {
            sc = Scoring.rate(winner.score, victim.score) * p.runoverKillScoreMult;
        }
        w.events.kill(winner.id, victim.id, 0f, Events.KILL_RUNOVER);
        sim.scoring.scorePlayers(scorer, sc, victim.name, victim, -sc, winner.name);
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
     * BEHAVIOR: a shielded ship (without shieldedItemPickup) only bumps items, which start to fall; own shots and
     * missiles before their fuse and confused mines pass; balls and asteroids are left to their own code.
     * BEHAVIOR: an item is picked up ({@link Items#pickup}; one that is not stays and the ship moves on); a mine
     * hit is announced and scored ({@link Mines#playerHit}) before it explodes.
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
                } else if (p.selfImmunity && pl.tank && pl.lockPlId == owner) {
                    continue;
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
            if (type == Obj.ITEM) {
                if ((pl.used & Has.SHIELD) != 0 && !w.items.shieldedItemPickup) {
                    o.status[h] |= Obj.GRAVITY;
                    deltaMv(pl, h);
                    continue;
                }
            } else if ((type & (Obj.HEAT_SHOT | Obj.SMART_SHOT | Obj.TORPEDO | Obj.SHOT | Obj.CANNON_SHOT)) != 0) {
                if (pl.id == owner && o.life[h] > o.fuseLife[h]) {
                    continue;
                }
            } else if ((type & Obj.MINE) != 0) {
                if ((o.status[h] & Obj.CONFUSED) != 0) {
                    continue;
                }
            } else if (type == Obj.BALL && sim.balls.phased(h)) {
                continue;
            }
            if (type == Obj.BALL) {
                sim.balls.playerHit(pl, h, Const.SHIP_SZ + o.plRadius[h]);
                if ((pl.status & Obj.KILLED) != 0) {
                    return;
                }
                continue;
            }
            if (type == Obj.ASTEROID) {
                sim.asteroids.playerHit(pl, h);
                sim.objPhysics.deltaMvElastic(pl, h);
                if ((pl.status & Obj.KILLED) != 0) {
                    return;
                }
                continue;
            }
            // C computes hit = (SHIP_SZ + pl_radius >= range) || in_range_acd(..., range): the same test as above
            // (C passes range, not radius), so every object that gets here hits.
            if (type == Obj.ITEM) {
                sim.items.pickup(pl, h);
                if (o.life[h] != 0) {
                    continue;
                }
            } else if (type == Obj.MINE) {
                sim.mines.playerHit(pl, h);
            } else if ((type & (Obj.DEBRIS | Obj.WRECKAGE)) != 0) {
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
     * C {@code Player_collides_with_debris}, for debris and wreckage.
     * <p>
     * BEHAVIOR: unless fully shielded the ship loses |(long)(2*mass*|v|)| fuel; at zero fuel it dies
     * ("succumbed to an explosion"): the owner (if another player) gets the kill and
     * Rate(owner, victim)*explosionKillScoreMult, else the victim loses Rate(0, score)*explosion*selfKill mults.
     * BEHAVIOR: with wreckageCollisionMayKill, wreckage also kills a ship without shield and armor, and hits
     * the armor of an unshielded ship that has armor (collision.c:1099-1102, 1133-1138).
     */
    void debris(Player pl, int h) {
        Objects o = w.objects;
        Params p = w.params;
        float v = (float) StrictMath.hypot(o.vx[h], o.vy[h]);
        long cost = Math.abs((long) (2 * o.mass[h] * v));
        if (!pl.fullyShielded()) {
            pl.addFuel(-cost);
        }
        boolean deadlyWreck = o.type[h] == Obj.WRECKAGE && wreckageMayKill && (pl.used & Has.SHIELD) == 0;
        if (pl.fuel != 0 && !(deadlyWreck && (pl.have & Has.ARMOR) == 0)) {
            if (deadlyWreck) {
                sim.ships.hitArmor(pl);
            }
            return;
        }
        pl.status |= Obj.KILLED;
        StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name)
            .append(T_SUCCUMBED_TO_AN_EXPLOSION);
        Player killer = w.byId(o.owner[h]);
        if (killer != null) {
            msg.append(T_FROM).append(killer.name);
            if (killer == pl) {
                msg.append(T_HOW_STRANGE);
            } else {
                msg.append('.');
            }
        } else {
            msg.append('.');
        }
        if (killer == null || killer == pl) {
            w.events.kill(Obj.NO_ID, pl.id, 0f, Events.KILL_EXPLOSION);
            sim.scoring.score(pl, -Scoring.rate(0, pl.score) * p.explosionKillScoreMult * p.selfKillScoreMult,
                pl.bx, pl.by, killer == null ? T_EXPLOSION : pl.name);
        } else {
            killer.kills++;
            float sc = Scoring.rate(killer.score, pl.score) * p.explosionKillScoreMult;
            w.events.kill(killer.id, pl.id, 0f, Events.KILL_EXPLOSION);
            sim.scoring.scorePlayers(killer, sc, pl.name, pl, -sc, killer.name);
        }
        o.life[h] = 0;
    }

    /**
     * C {@code Player_collides_with_killing_shot} for shots and cannon shots.
     * <p>
     * BEHAVIOR: with shields up (or armor, or for a nuclear torpedo 3 times in 4) the hit only drains fuel
     * (unless fully shielded): a missile "X ate a smart missile from Y." costs 120*256 / ((mini+1)*(power+1)) and
     * makes the ship visible for 2 frames; a shot costs 25*256 * SHOT_MULT * (relative speed^2 * |mass|) /
     * (shotSpeed^2 * shotMass) with shotHitFuelDrainUsesKineticEnergy (else 25*256 * SHOT_MULT) and SHOT_MULT
     * frames of visibility; shields drop at zero fuel; armor is used when unshielded.
     * BEHAVIOR: otherwise the ship dies: cannon fire costs Rate(CANNON_SCORE, score)/4 * mult (and gives it to
     * the cannon's team in team play), unowned shots Rate(0)*unownedKillScoreMult, own shots
     * Rate(0)*selfKillScoreMult, another player's shot Rate(killer, victim) to the killer (a kill).
     * The factor is shotKillScoreMult (clusterKillScoreMult for cluster fragments, torpedo/smart/heat
     * KillScoreMult for missiles); the description is C's Describe_shot.
     */
    void killingShot(Player pl, int h) {
        Objects o = w.objects;
        Params p = w.params;
        int type = o.type[h];
        boolean missile = (type & (Obj.TORPEDO | Obj.HEAT_SHOT | Obj.SMART_SHOT)) != 0;
        if ((pl.used & Has.SHIELD) != 0 || (pl.have & Has.ARMOR) != 0
            || (type == Obj.TORPEDO && Mods.isNuclear(o.mods[h]) && (int) (w.rfrac() >= 0.25f ? 1 : 0) != 0)) {
            switch (type) {
                case Obj.TORPEDO -> Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_EAT_TORPEDO_SHOT);
                case Obj.HEAT_SHOT -> Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_EAT_HEAT_SHOT);
                case Obj.SMART_SHOT -> Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_EAT_SMART_SHOT);
                default -> { }
            }
            if (missile) {
                Player killer = o.owner[h] != Obj.NO_ID ? w.byId(o.owner[h]) : null;
                StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(T_ATE);
                ShotNames.append(msg, type, o.status[h], o.mods[h], true);
                if (killer != null) {
                    msg.append(T_FROM).append(killer.name);
                }
                msg.append('.');
                long drain = (long) (ED_SMART_SHOT_HIT / ((Mods.mini(o.mods[h]) + 1) * (Mods.power(o.mods[h]) + 1)));
                if (!pl.fullyShielded()) {
                    pl.addFuel(drain);
                }
                pl.forceVisible += 2;
            } else if (type == Obj.SHOT || type == Obj.CANNON_SHOT) {
                Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_EAT_SHOT);
                if (!pl.fullyShielded()) {
                    float drainFactor = 1.0f;
                    if (p.shotHitFuelDrainUsesKineticEnergy) {
                        float rel = (float) StrictMath.hypot(pl.vx - o.vx[h], pl.vy - o.vy[h]);
                        drainFactor = (rel * rel * Math.abs(o.mass[h])) / (p.shotSpeed * p.shotSpeed * p.shotMass);
                    }
                    pl.addFuel((long) (ED_SHOT_HIT * drainFactor * shotMult(h)));
                }
                pl.forceVisible = (int) (pl.forceVisible + shotMult(h));
            }
            if (pl.fuel <= 0) {
                pl.used &= ~Has.SHIELD;
            }
            if ((pl.used & Has.SHIELD) == 0 && (pl.have & Has.ARMOR) != 0) {
                sim.ships.hitArmor(pl);
            }
            return;
        }
        if (!missile && type != Obj.SHOT && type != Obj.CANNON_SHOT) {
            return;
        }
        Player killer = null;
        float sc;
        StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name);
        boolean fromCannon = (o.status[h] & Obj.FROMCANNON) != 0;
        if (fromCannon) {
            Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_HIT_CANNONFIRE);
            msg.append(T_WAS_HIT_BY_CANNONFIRE);
            sc = Scoring.rate(Scoring.CANNON_SCORE, pl.score) / 4;
        } else if (o.owner[h] == Obj.NO_ID || w.byId(o.owner[h]) == null) {
            msg.append(T_WAS_KILLED_BY);
            ShotNames.append(msg, type, o.status[h], o.mods[h], true).append('.');
            sc = Scoring.rate(0, pl.score) * p.unownedKillScoreMult;
        } else {
            killer = w.byId(o.owner[h]);
            msg.append(T_WAS_KILLED_BY);
            ShotNames.append(msg, type, o.status[h], o.mods[h], true).append(T_FROM).append(killer.name)
                .append('.');
            if (killer == pl) {
                Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_SHOT_THEMSELF);
                msg.append(T_HOW_STRANGE_2);
                sc = Scoring.rate(0, pl.score) * p.selfKillScoreMult;
            } else {
                killer.kills++;
                sc = Scoring.rate(killer.score, pl.score);
            }
        }
        float factor;
        switch (type) {
            case Obj.SHOT -> factor = Mods.isCluster(o.mods[h]) ? p.clusterKillScoreMult : p.shotKillScoreMult;
            case Obj.TORPEDO -> factor = w.items.torpedoKillScoreMult;
            case Obj.SMART_SHOT -> factor = w.items.smartKillScoreMult;
            case Obj.HEAT_SHOT -> factor = w.items.heatKillScoreMult;
            default -> factor = p.shotKillScoreMult;
        }
        sc *= factor;
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, killer != null ? killer.id : Obj.NO_ID, pl.id, 0f);
        if (fromCannon) {
            sim.scoring.score(pl, -sc, pl.bx, pl.by, T_CANNON);
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

    /** C {@code SHOT_MULT}: nukeClusterDamage for nuclear cluster fragments, else 1. */
    float shotMult(int h) {
        int m = w.objects.mods[h];
        return Mods.isNuclear(m) && Mods.isCluster(m) ? w.items.nukeClusterDamage : 1.0f;
    }

    /**
     * C {@code Describe_shot(type, status, mods, 1)} for unmodified shots and cannon shots.
     * <p>
     * BEHAVIOR: "a shot"; cluster fragments are "cluster debris", cluster cannon shots "cluster flak".
     */
    static String describe(int type, int mods) {
        if ((mods & Obj.MOD_CLUSTER) == 0) {
            return T_A_SHOT;
        }
        return type == Obj.CANNON_SHOT ? T_CLUSTER_FLAK : T_CLUSTER_DEBRIS;
    }
}
