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
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.ShipGeom;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;

import static com.fulcrologic.jxpilot.server.sim.MoveState.*;

/**
 * Moves pool objects and ships through the map: ports of {@code Move_object}, {@code Move_player} and
 * {@code Turn_player} ({@code src/server/walls.c}), using {@link Segment} for the per-block wall tracing.
 * <p>
 * One sim tick is one C frame: a move is C's {@code FLOAT_TO_CLICK(vel)} clicks (truncated toward zero, the
 * fraction is lost exactly as in C), and velocities, friction and bounce arithmetic are C floats.
 */
public final class Mover {
    private static final int BS = Const.BLOCK_SZ;
    private static final int BC = Click.BLOCK_CLICKS;

    private final World w;
    private final Simulation sim;
    private final Segment seg;
    private final MoveState ms = new MoveState();
    private final MoveState[] hull = new MoveState[ShipShape.MAX_SHIP_PTS];
    private final float[] rx = new float[ShipShape.MAX_SHIP_PTS];
    private final float[] ry = new float[ShipShape.MAX_SHIP_PTS];

    /** Install the ball reactions to treasures (balls exist once the simulation is built). */
    void setTreasureHook(Segment.TreasureHook hook) {
        seg.treasureHook = hook;
    }

    public Mover(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.seg = new Segment(w);
        for (int i = 0; i < hull.length; i++) {
            hull[i] = new MoveState();
        }
    }

    /** C {@code VECTOR_LENGTH}: the double hypot of a float vector, stored as a float. */
    static float length(float x, float y) {
        return (float) StrictMath.hypot(x, y);
    }

    private int blockOf(int pixel, int n) {
        int b = Math.floorDiv(pixel, BS);
        return b < 0 ? 0 : (b >= n ? n - 1 : b);
    }

    // ================================================================ objects

    /**
     * Move a pool object one tick (C {@code Move_object}).
     * <p>
     * BEHAVIOR: remembers the previous position first.
     * BEHAVIOR: far from walls ({@code walldist > 2} and the step shorter than {@code ((d-2)*35)>>1} px)
     * the object moves freely and wraps.
     * BEHAVIOR: otherwise it is traced through the blocks; a crash calls {@link Crashes#objectCrashed}.
     * BEHAVIOR: on a wall bounce: life *= objectWallBounceLifeFactor (truncated); sparks not made by a ship
     * bounce lose owner immunity; faster than maxObjectWallBounceSpeed kills it; velocity and remaining
     * movement are multiplied by objectWallBrakeFactor.
     * BEHAVIOR: five consecutive steps without progress are CRASH_UNKNOWN.
     */
    public void moveObject(int h) {
        Objects o = w.objects;
        Params p = w.params;
        o.remember(h);
        int tx = Click.fromPixels(o.vx[h]);
        int ty = Click.fromPixels(o.vy[h]);
        int dist = w.wallDist(blockOf(o.px[h], w.width), blockOf(o.py[h], w.height));
        if (dist > 2) {
            int max = ((dist - 2) * BS) >> 1;
            if ((float) (max * max) >= o.vx[h] * o.vx[h] + o.vy[h] * o.vy[h]) {
                o.setClicks(h, w.wrapXClick(o.cx[h] + tx), w.wrapYClick(o.cy[h] + ty));
                o.touchCell(h);
                return;
            }
        }
        int type = o.type[h];
        ms.pl = null;
        ms.obj = h;
        ms.edgeWrap = w.wrap;
        ms.edgeBounce = p.edgeBounce;
        ms.wallBounce = (p.objBounceMask & type) != 0;
        ms.cannonCrashes = (p.objCannonMask & type) != 0;
        ms.targetCrashes = (p.objTargetMask & type) != 0;
        ms.treasureCrashes = (p.objTreasureMask & type) != 0;
        ms.wormholeWarps = true;
        ms.phased = type == Obj.BALL && sim.balls.phased(h);
        ms.team = o.team[h];
        ms.targetTeam = type == Obj.BALL ? ballTeam(h) : o.team[h];
        ms.status = o.status[h];
        ms.type = type;
        ms.start(o.cx[h], o.cy[h], o.vx[h], o.vy[h], tx, ty, o.missileDir[h]);

        int nothingDone = 0;
        boolean posUpdate = false;
        for (;;) {
            seg.move(ms);
            if ((ms.doneX | ms.doneY) == 0) {
                posUpdate |= (ms.crash | ms.bounce) != 0;
                if (ms.crash != NOT_A_CRASH) {
                    break;
                }
                if (ms.bounce != NOT_A_BOUNCE && ms.bounce != BOUNCE_EDGE) {
                    if (type != Obj.BALL) {
                        o.life[h] = (long) ((float) o.life[h] * p.objectWallBounceLifeFactor); // C walls.c:2000 float product
                    }
                    if (o.life[h] <= 0) {
                        break;
                    }
                    if ((o.status[h] & Obj.FROMBOUNCE) == 0 && type == Obj.SPARK) {
                        o.status[h] &= ~Obj.OWNERIMMUNE;
                    }
                    if (ms.velX * ms.velX + ms.velY * ms.velY
                        > p.maxObjectWallBounceSpeed * p.maxObjectWallBounceSpeed) {
                        o.life[h] = 0;
                        break;
                    }
                    ms.velX *= p.objectWallBrakeFactor;
                    ms.velY *= p.objectWallBrakeFactor;
                    ms.todoX = (int) (ms.todoX * p.objectWallBrakeFactor);
                    ms.todoY = (int) (ms.todoY * p.objectWallBrakeFactor);
                }
                if (++nothingDone >= 5) {
                    ms.crash = CRASH_UNKNOWN;
                    break;
                }
            } else {
                ms.posX += ms.doneX;
                ms.posY += ms.doneY;
                nothingDone = 0;
            }
            if ((ms.todoX | ms.todoY) == 0) {
                break;
            }
        }
        if (ms.edgeWrap) {
            if (ms.posX < 0) {
                ms.posX += w.clickWidth;
            }
            if (ms.posX >= w.clickWidth) {
                ms.posX -= w.clickWidth;
            }
            if (ms.posY < 0) {
                ms.posY += w.clickHeight;
            }
            if (ms.posY >= w.clickHeight) {
                ms.posY -= w.clickHeight;
            }
        }
        o.setClicks(h, ms.posX, ms.posY);
        o.vx[h] = ms.velX;
        o.vy[h] = ms.velY;
        o.missileDir[h] = ms.dir;
        if (ms.crash != NOT_A_CRASH) {
            sim.crashes.objectCrashed(h, ms);
        }
        if (posUpdate) {
            o.remember(h);
        }
        o.touchCell(h);
    }

    /** A ball's team for its target test: its owner's team, else TEAM_NOT_SET (walls.c Move_segment). */
    private int ballTeam(int h) {
        int owner = w.modes.ballOwner[h];
        Player pl = owner == Obj.NO_ID ? null : w.byId(owner);
        return pl != null ? pl.team : Const.TEAM_NOT_SET;
    }

    // ================================================================ players

    /**
     * Move a ship one tick (C {@code Move_player}).
     * <p>
     * BEHAVIOR: a ship that is not playing (being carried home) just drifts with its velocity, unless
     * killed or paused; only then is the previous position updated.
     * BEHAVIOR: friction (blockFriction on FRICTION blocks unless phased) damps velocity by (1-f)^dt and
     * coriolis rotates it by {@code coriolis*dt} degrees.
     * BEHAVIOR: far from walls ({@code walldist > 3}) the ship moves freely.
     * BEHAVIOR: near walls every hull point is traced; the first crash wins; among bounces an edge bounce
     * is preferred; a wall bounce brakes, may crash for speed (CRASH_WALL_SPEED), angle
     * (CRASH_WALL_ANGLE) or fuel (CRASH_WALL_NO_FUEL), drains fuel and makes wall sparks.
     */
    public void movePlayer(Player pl) {
        Params p = w.params;
        if (!pl.isAlive()) {
            if ((pl.status & (Obj.KILLED | Obj.PAUSE)) == 0) {
                int x = w.wrapXClick(pl.cx + Click.fromPixels(pl.vx));
                int y = w.wrapYClick(pl.cy + Click.fromPixels(pl.vy));
                if (x != pl.cx || y != pl.cy) {
                    pl.remember();
                    pl.setClicks(x, y);
                }
            }
            pl.velocity = length(pl.vx, pl.vy);
            return;
        }

        applyFriction(pl, p);
        pl.remember();

        int dist = w.wallDist(blockOf(pl.px, w.width), blockOf(pl.py, w.height));
        if (dist > 3) {
            int max = ((dist - 3) * BS) >> 1;
            if (max >= pl.velocity) {
                pl.setClicks(w.wrapXClick(pl.cx + Click.fromPixels(pl.vx)),
                    w.wrapYClick(pl.cy + Click.fromPixels(pl.vy)));
                pl.velocity = length(pl.vx, pl.vy);
                return;
            }
        }
        traceShip(pl, Click.fromPixels(pl.vx), Click.fromPixels(pl.vy), p);
    }

    /**
     * Friction and coriolis (walls.c:2250-2268) in C float arithmetic:
     * {@code v' = (1 - f) * (v rotated by coriolis*RES/360 RES units)}.
     */
    private void applyFriction(Player pl, Params p) {
        float fric;
        if ((pl.used & Has.PHASING_DEVICE) != 0) {
            fric = p.friction;
        } else {
            fric = w.map.blockAt(pl.bx, pl.by) == com.fulcrologic.jxpilot.common.map.BlockType.FRICTION
                ? p.blockFriction : p.friction;
        }
        int corRes = (p.coriolis * Const.RES / 360) & (Const.RES - 1);
        float c = Trig.tcos(corRes);
        float sn = Trig.tsin(corRes);
        float ovx = pl.vx;
        float ovy = pl.vy;
        pl.vx = (1.0f - fric) * (ovx * c + ovy * sn);
        pl.vy = (1.0f - fric) * (ovy * c - ovx * sn);
    }

    /** The hull-point loop of Move_player; after a crash or bounce the new position is remembered. */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MethodLength"})
    private void traceShip(Player pl, int tx, int ty, Params p) {
        ShipGeom ship = pl.ship;
        int n = ship.numPoints;
        MoveState mi = hull[0];
        mi.pl = pl;
        mi.obj = -1;
        mi.edgeWrap = w.wrap;
        mi.edgeBounce = p.edgeBounce;
        mi.wallBounce = true;
        mi.cannonCrashes = true;
        mi.treasureCrashes = true;
        mi.targetCrashes = true;
        mi.wormholeWarps = true;
        mi.phased = (pl.used & Has.PHASING_DEVICE) != 0;
        mi.team = pl.team;
        mi.targetTeam = pl.team;
        mi.status = pl.status;
        mi.type = Obj.PLAYER;
        for (int i = 0; i < n; i++) {
            MoveState m = hull[i];
            if (i > 0) {
                m.copyInfo(mi);
            }
            m.start(pl.cx + Click.fromPixels(ship.ptX[i][pl.dir]), pl.cy + Click.fromPixels(ship.ptY[i][pl.dir]),
                pl.vx, pl.vy, tx, ty, pl.dir);
        }
        float velX = pl.vx;
        float velY = pl.vy;
        int worst = 0;
        int nothingDone = 0;
        boolean posUpdate = false;

        for (;;) {
            MoveState m0 = hull[0];
            int cx = w.wrapXClick(m0.posX - Click.fromPixels(ship.ptX[0][m0.dir]));
            int cy = w.wrapYClick(m0.posY - Click.fromPixels(ship.ptY[0][m0.dir]));
            int bx = clampBlock(cx / BC, w.width);
            int by = clampBlock(cy / BC, w.height);
            int wd = w.wallDist(bx, by);
            if (wd > 3) {
                int maxcl = ((wd - 3) * BC) >> 1;
                int todoX = m0.todoX;
                int todoY = m0.todoY;
                int sx = todoX < 0 ? -1 : 1;
                int sy = todoY < 0 ? -1 : 1;
                int doneX;
                int doneY;
                if (maxcl >= sx * todoX && maxcl >= sy * todoY) {
                    doneX = todoX;
                    doneY = todoY;
                } else if (sx * todoX > sy * todoY) {
                    doneX = sx * maxcl;
                    doneY = todoY * maxcl / (sx * todoX);
                } else {
                    doneX = todoX * maxcl / (sy * todoY);
                    doneY = sy * maxcl;
                }
                todoX -= doneX;
                todoY -= doneY;
                for (int i = 0; i < n; i++) {
                    MoveState m = hull[i];
                    m.posX += doneX;
                    m.posY += doneY;
                    m.todoX = todoX;
                    m.todoY = todoY;
                    m.crash = NOT_A_CRASH;
                    m.bounce = NOT_A_BOUNCE;
                    if (mi.edgeWrap) {
                        if (m.posX < 0) {
                            m.posX += w.clickWidth;
                        } else if (m.posX >= w.clickWidth) {
                            m.posX -= w.clickWidth;
                        }
                        if (m.posY < 0) {
                            m.posY += w.clickHeight;
                        } else if (m.posY >= w.clickHeight) {
                            m.posY -= w.clickHeight;
                        }
                    }
                }
                nothingDone = 0;
                if ((todoX | todoY) == 0) {
                    break;
                }
                continue;
            }

            int bounce = -1;
            int crash = -1;
            for (int i = 0; i < n; i++) {
                MoveState m = hull[i];
                seg.move(m);
                posUpdate |= (m.crash | m.bounce) != 0;
                if (m.crash != NOT_A_CRASH) {
                    crash = i;
                    break;
                }
                if (m.bounce != NOT_A_BOUNCE) {
                    if (bounce == -1) {
                        bounce = i;
                    } else if (hull[bounce].bounce != BOUNCE_EDGE && m.bounce == BOUNCE_EDGE) {
                        bounce = i;
                    } else if ((hull[bounce].bounce == BOUNCE_EDGE) == (m.bounce == BOUNCE_EDGE)) {
                        if ((int) (w.rfrac() * (n - bounce)) == i) {
                            bounce = i;
                        }
                    }
                    worst = bounce;
                }
            }
            if (crash != -1) {
                worst = crash;
                break;
            } else if (bounce != -1) {
                worst = bounce;
                pl.lastWallTouch = w.frames;
                if (hull[worst].bounce != BOUNCE_EDGE && wallBounce(pl, hull[worst], p)) {
                    break;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    rx[i] = velX != 0 ? Math.abs(hull[i].todoX / velX) : 0;
                    ry[i] = velY != 0 ? Math.abs(hull[i].todoY / velY) : 0;
                }
                worst = 0;
                for (int i = 1; i < n; i++) {
                    if (rx[i] > rx[worst] || ry[i] > ry[worst]) {
                        worst = i;
                    }
                }
            }

            MoveState wm = hull[worst];
            if ((wm.doneX | wm.doneY) == 0) {
                if (++nothingDone >= 5) {
                    wm.crash = CRASH_UNKNOWN;
                    break;
                }
            } else {
                nothingDone = 0;
                wm.posX += wm.doneX;
                wm.posY += wm.doneY;
            }
            if ((wm.todoX | wm.todoY) == 0) {
                break;
            }
            velX = wm.velX;
            velY = wm.velY;
            for (int i = 0; i < n; i++) {
                if (i != worst) {
                    MoveState m = hull[i];
                    m.posX += wm.doneX;
                    m.posY += wm.doneY;
                    m.velX = velX;
                    m.velY = velY;
                    m.todoX = wm.todoX;
                    m.todoY = wm.todoY;
                    m.dir = wm.dir;
                }
            }
        }

        MoveState wm = hull[worst];
        pl.setClicks(w.wrapXClick(wm.posX - Click.fromPixels(ship.ptX[worst][pl.dir])),
            w.wrapYClick(wm.posY - Click.fromPixels(ship.ptY[worst][pl.dir])));
        pl.vx = wm.velX;
        pl.vy = wm.velY;
        pl.velocity = length(pl.vx, pl.vy);
        if (wm.crash != NOT_A_CRASH) {
            sim.crashes.playerCrashed(pl, wm, worst);
        }
        if (posUpdate) {
            pl.remember();
        }
    }

    private static int clampBlock(int b, int n) {
        return b < 0 ? 0 : (b >= n ? n - 1 : b);
    }

    /**
     * The wall-bounce rules of Move_player (walls.c:2405-2537) for the worst hull point.
     * <p>
     * BEHAVIOR: returns true (and sets {@code m.crash}) when the bounce is fatal: speed above the
     * (shield-dependent) limit, heading more than the allowed angle off the wall normal, or out of fuel.
     * BEHAVIOR: a fully shielded ship (shield + emergency shield) allows at least speed 100 and any angle
     * and pays no fuel.
     * BEHAVIOR: armor (when needed) raises the limits to the shielded ones and is used up.
     * BEHAVIOR: within RES/16 of the wall normal the heading is nudged by (1-brake)*delta.
     * BEHAVIOR: cost = (long)((1-brake/2) * (int)(mass-0.75*emptyMass) * ((int)speed>>2)) * (RES/2+|delta|)/RES;
     * fuel drops by cost*256*wallBounceFuelDrainMult and cost &gt; 0 makes wall sparks.
     */
    boolean wallBounce(Player pl, MoveState m, Params p) {
        float speed = length(m.velX, m.velY);
        int v = (int) speed >> 2;
        int mass = (int) (pl.mass - pl.emptyMass * 0.75f);
        float b = 1 - 0.5f * p.playerWallBrakeFactor;
        long cost = (long) (b * mass * v);
        boolean shield = (pl.used & Has.SHIELD) != 0;
        float maxSpeed = shield ? p.maxShieldedWallBounceSpeed : p.maxUnshieldedWallBounceSpeed;
        int maxAngle = shield ? p.maxShieldedAngle : p.maxUnshieldedAngle;
        if (pl.fullyShielded()) {
            if (maxSpeed < 100) {
                maxSpeed = 100;
            }
            maxAngle = Const.RES;
        }
        m.velX *= p.playerWallBrakeFactor;
        m.velY *= p.playerWallBrakeFactor;
        m.todoX = (int) (m.todoX * p.playerWallBrakeFactor);
        m.todoY = (int) (m.todoY * p.playerWallBrakeFactor);

        if (speed > maxSpeed && maxSpeed < p.maxShieldedWallBounceSpeed && !shield
            && (pl.have & Has.ARMOR) != 0) {
            maxSpeed = p.maxShieldedWallBounceSpeed;
            maxAngle = p.maxShieldedAngle;
            sim.ships.hitArmor(pl);
        }
        if (speed > maxSpeed) {
            m.crash = m.target >= 0 ? CRASH_TARGET : CRASH_WALL_SPEED;
            return true;
        }
        int wallDir = wallDir(m.bounce);
        int delta = deltaDir(pl.dir, wallDir);
        int absDelta = Math.abs(delta);
        if (absDelta > maxAngle && maxAngle < p.maxShieldedAngle && !shield && (pl.have & Has.ARMOR) != 0) {
            maxAngle = p.maxShieldedAngle;
            sim.ships.hitArmor(pl);
        }
        if (absDelta > maxAngle) {
            m.crash = m.target >= 0 ? CRASH_TARGET : CRASH_WALL_ANGLE;
            return true;
        }
        if (absDelta <= Const.RES / 16) {
            pl.floatDir += (1.0f - p.playerWallBrakeFactor) * delta;
            if (pl.floatDir >= Const.RES) {
                pl.floatDir -= Const.RES;
            } else if (pl.floatDir < 0) {
                pl.floatDir += Const.RES;
            }
        }
        cost = (cost * (Const.RES / 2 + absDelta)) / Const.RES;
        if (!pl.fullyShielded()) {
            pl.addFuel(-((cost << 8) * p.wallBounceFuelDrainMult));
            sim.items.damage(pl, w.items.wallBounceDestroyItemProb);
        }
        if (pl.fuel == 0 && p.wallBounceFuelDrainMult != 0) {
            m.crash = m.target >= 0 ? CRASH_TARGET : CRASH_WALL_NO_FUEL;
            return true;
        }
        if (cost != 0) {
            sim.debris.wallSparks(pl, wallDir, (int) (cost * p.wallBounceExplosionMult));
            Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_BOUNCED);
            if (m.target >= 0) {
                sim.targets.playerHits(m.target, pl, (long) ((cost << 8) * (p.wallBounceFuelDrainMult / 4.0)));
            }
        }
        return false;
    }

    /** BEHAVIOR: the wall normal heading for a bounce code (walls.c:2452-2461). */
    static int wallDir(int bounce) {
        return switch (bounce) {
            case BOUNCE_HOR_LO -> 4 * Const.RES / 8;
            case BOUNCE_HOR_HI -> 0;
            case BOUNCE_VER_LO -> 6 * Const.RES / 8;
            case BOUNCE_LEFT_DOWN -> Const.RES / 8;
            case BOUNCE_LEFT_UP -> 7 * Const.RES / 8;
            case BOUNCE_RIGHT_DOWN -> 3 * Const.RES / 8;
            case BOUNCE_RIGHT_UP -> 5 * Const.RES / 8;
            default -> 2 * Const.RES / 8;
        };
    }

    /** BEHAVIOR: signed shortest turn from {@code dir} to {@code wallDir} in RES units (walls.c:2462-2470). */
    static int deltaDir(int dir, int wallDir) {
        if (dir >= wallDir) {
            return (dir - wallDir <= Const.RES / 2) ? -(dir - wallDir) : (wallDir + Const.RES - dir);
        }
        return (wallDir - dir <= Const.RES / 2) ? (wallDir - dir) : -(dir + Const.RES - wallDir);
    }

    // ================================================================ turning

    /**
     * Turn the ship toward {@code (int)(floatDir + 0.5)} one RES step at a time (C {@code Turn_player}).
     * <p>
     * BEHAVIOR: not playing, or far from walls ({@code walldist > 2}): the heading is set directly.
     * BEHAVIOR: otherwise each step traces every hull point from its old to its new rotated position; a
     * wall blocks the turn (heading stays, floatDir snaps back); a crash other than leaving the universe
     * calls {@link Crashes#playerCrashed}; edge bounces are tolerated.
     * BEHAVIOR: without wrap the centre is first pushed inside the world so the turned hull fits.
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public void turnPlayer(Player pl) {
        int newDir = ((int) (pl.floatDir + 0.5f)) & (Const.RES - 1);
        if (newDir == pl.dir) {
            return;
        }
        if (!pl.isAlive() || w.wallDist(blockOf(pl.px, w.width), blockOf(pl.py, w.height)) > 2) {
            pl.dir = newDir;
            return;
        }
        Params p = w.params;
        ShipGeom ship = pl.ship;
        int n = ship.numPoints;
        MoveState mi = hull[0];
        mi.pl = pl;
        mi.obj = -1;
        mi.edgeWrap = w.wrap;
        mi.edgeBounce = p.edgeBounce;
        mi.wallBounce = true;
        mi.cannonCrashes = true;
        mi.treasureCrashes = true;
        mi.targetCrashes = true;
        mi.wormholeWarps = false;
        mi.phased = (pl.used & Has.PHASING_DEVICE) != 0;
        mi.team = pl.team;
        mi.targetTeam = pl.team;
        mi.status = pl.status;
        mi.type = Obj.PLAYER;
        for (int i = 1; i < n; i++) {
            hull[i].copyInfo(mi);
        }
        int sign;
        if (newDir > pl.dir) {
            sign = (newDir - pl.dir <= Const.RES + pl.dir - newDir) ? 1 : -1;
        } else {
            sign = (pl.dir - newDir <= Const.RES + newDir - pl.dir) ? -1 : 1;
        }
        float saltX = pl.vx > 0 ? 1e-6f : (pl.vx < 0 ? -1e-6f : 0f);
        float saltY = pl.vy > 0 ? 1e-6f : (pl.vy < 0 ? -1e-6f : 0f);
        int posX = pl.cx;
        int posY = pl.cy;
        int crash = -1;
        int nothingDone = 0;
        boolean blocked = false;
        while (pl.dir != newDir) {
            int dir = (pl.dir + sign) & (Const.RES - 1);
            if (!mi.edgeWrap) {
                posX = keepInside(posX, ship.ptX, n, dir, w.clickWidth);
                posY = keepInside(posY, ship.ptY, n, dir, w.clickHeight);
                if (posX != pl.cx || posY != pl.cy) {
                    pl.setClicks(posX, posY);
                }
            }
            for (int i = 0; i < n && !blocked; i++) {
                MoveState m = hull[i];
                int ox = posX + Click.fromPixels(ship.ptX[i][pl.dir]);
                int oy = posY + Click.fromPixels(ship.ptY[i][pl.dir]);
                int tx = posX + Click.fromPixels(ship.ptX[i][dir]) - ox;
                int ty = posY + Click.fromPixels(ship.ptY[i][dir]) - oy;
                m.start(ox, oy, (float) tx + saltX, (float) ty + saltY, tx, ty, pl.dir);
                do {
                    seg.move(m);
                    if ((m.crash | m.bounce) != 0) {
                        if (m.crash != NOT_A_CRASH) {
                            if (m.crash != CRASH_UNIVERSE) {
                                crash = i;
                            }
                            blocked = true;
                            break;
                        }
                        if (m.bounce != BOUNCE_EDGE) {
                            blocked = true;
                            break;
                        }
                        if (++nothingDone >= 5) {
                            m.crash = CRASH_UNKNOWN;
                            crash = i;
                            blocked = true;
                            break;
                        }
                    } else if ((m.doneX | m.doneY) != 0) {
                        m.posX += m.doneX;
                        m.posY += m.doneY;
                        nothingDone = 0;
                    }
                } while ((m.todoX | m.todoY) != 0);
            }
            if (blocked) {
                break;
            }
            pl.dir = dir;
        }
        if (blocked) {
            pl.floatDir = pl.dir;
            pl.lastWallTouch = w.frames;
        }
        if (crash != -1) {
            sim.crashes.playerCrashed(pl, hull[crash], crash);
        }
    }

    /** Turn_player's edge clamp (walls.c:2670-2710) for one axis. */
    private static int keepInside(int pos, float[][] pt, int n, int dir, int limit) {
        if (pos <= 22 * Click.CLICK) {
            for (int i = 0; i < n; i++) {
                if (pos + Click.fromPixels(pt[i][dir]) < 0) {
                    pos = -Click.fromPixels(pt[i][dir]);
                }
            }
        }
        if (pos >= limit - 22 * Click.CLICK) {
            for (int i = 0; i < n; i++) {
                if (pos + Click.fromPixels(pt[i][dir]) >= limit) {
                    pos = limit - 1 - Click.fromPixels(pt[i][dir]);
                }
            }
        }
        return pos;
    }
}
