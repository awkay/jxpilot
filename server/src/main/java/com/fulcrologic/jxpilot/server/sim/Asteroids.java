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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Concentrator;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ModeParams;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Asteroids: a port of {@code src/server/asteroid.c} ({@code Make_asteroid}, {@code Place_asteroid},
 * {@code Asteroid_update}, {@code Break_asteroid}) and of {@code AsteroidCollision} and
 * {@code Player_collides_with_asteroid} ({@code collision.c}), in C float arithmetic. Asteroids are pool
 * objects of type {@link Obj#ASTEROID} kept in creation order in {@link ModeState#asteroidList}; their size,
 * rotation, spin and shape are the C {@code wireobject} fields in {@link ModeState}.
 */
public final class Asteroids {
    /** C {@code ASTEROID_MAX_SIZE}. */
    public static final int MAX_SIZE = 4;
    /** C {@code ASTEROID_DUST_MASS}. */
    static final double DUST_MASS = 0.25;
    /** C {@code ASTEROID_DUST_FACT}. */
    static final double DUST_FACT = 1 / (1 + 2 / DUST_MASS);
    /** C {@code ASTEROID_DELTA_DIR}. */
    static final int DELTA_DIR = Const.RES / 8;
    /** C {@code ASTEROID_LIFE}: frames before an asteroid breaks by itself. */
    public static final int LIFE = 1000;
    /** C {@code ASTEROID_MIN_DIST}. */
    static final int MIN_DIST = 5 * Const.BLOCK_SZ;
    /** C {@code ED_PL_CRASH}. */
    static final double ED_PL_CRASH = -100.0 * 256;

    /** C {@code SPACE_BLOCKS} (walls.c) as {@code 1 << block type} bits. */
    static final int SPACE_BLOCKS = BlockType.bit(BlockType.SPACE) | BlockType.bit(BlockType.BASE)
        | BlockType.bit(BlockType.WORMHOLE) | BlockType.bit(BlockType.POS_GRAV) | BlockType.bit(BlockType.NEG_GRAV)
        | BlockType.bit(BlockType.CWISE_GRAV) | BlockType.bit(BlockType.ACWISE_GRAV)
        | BlockType.bit(BlockType.UP_GRAV) | BlockType.bit(BlockType.DOWN_GRAV)
        | BlockType.bit(BlockType.RIGHT_GRAV) | BlockType.bit(BlockType.LEFT_GRAV)
        | BlockType.bit(BlockType.DECOR_LU) | BlockType.bit(BlockType.DECOR_LD) | BlockType.bit(BlockType.DECOR_RU)
        | BlockType.bit(BlockType.DECOR_RD) | BlockType.bit(BlockType.DECOR_FILLED) | BlockType.bit(BlockType.CHECK)
        | BlockType.bit(BlockType.ITEM_CONCENTRATOR) | BlockType.bit(BlockType.FRICTION)
        | BlockType.bit(BlockType.ASTEROID_CONCENTRATOR);
    /** Blocks an asteroid may appear in (Place_asteroid). */
    static final int PLACE_BLOCKS = (SPACE_BLOCKS & ~(BlockType.bit(BlockType.BASE) | BlockType.bit(BlockType.WORMHOLE)))
        | BlockType.bit(BlockType.FRICTION);

    private final World w;
    private final Simulation sim;
    /**
     * C {@code AsteroidCollision}'s {@code damage}: declared once per call and not reset for every object (a
     * mine leaves the previous value), so it is kept across the loop as in C.
     */
    private float damage;
    /** C {@code AsteroidCollision}'s {@code sound}: like {@link #damage}, once set it stays set for the call. */
    private boolean sound;

    /** {@link #mass} by size 1..{@link #MAX_SIZE}. */
    private final float[] masses = new float[MAX_SIZE + 1];

    public Asteroids(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        for (int size = 1; size <= MAX_SIZE; size++) {
            masses[size] = (float) ((w.params.shipMass * 3) * StrictMath.pow(2.0 + DUST_MASS, size - 1.0));
        }
    }

    /** C {@code ASTEROID_MASS(size)}: (ShipMass * 3) * 2.25^(size-1), a float (precomputed: pow allocates). */
    float mass(int size) {
        return masses[size];
    }

    /** C {@code ASTEROID_RADIUS(size)}: 0.8 * SHIP_SZ * size. */
    static double radius(int size) {
        return 0.8 * Const.SHIP_SZ * size;
    }

    /** C {@code ASTEROID_HITS(size)}. */
    static int hits(int size) {
        return 1 << (size - 1);
    }

    /** C {@code ASTEROID_FUEL_HIT(fuel, size)} for a double fuel. */
    static double fuelHit(double fuel, int size) {
        return (fuel * LIFE) / (256 * 25 * hits(size));
    }

    // ================================================================ creation

    /**
     * C {@code Make_asteroid}.
     * <p>
     * BEHAVIOR: nothing with a full pool or a size outside 1..4; the position wraps on wrapping maps and must
     * be in the world.
     * BEHAVIOR: C tests the block <em>type</em> against block <em>bits</em> here, so it refuses a position whose
     * block type has bit 2 set (types 4-7, 12-15, 20-23, 28: REC_LD, REC_RU, REC_RD, FUEL, rotating gravity,
     * WORMHOLE, TREASURE, ...), and in a REC_LU block a position in its solid half; walls (FILLED) and targets
     * are not refused (C quirk, kept).
     * BEHAVIOR: the asteroid gets velocity (tcos, tsin)(dir) * speed, mass ASTEROID_MASS(size), life 1000
     * (fuse 999), spin 0.02-0.07, a random rotation and shape, radius 0.8*SHIP_SZ*size, GRAVITY; it joins the
     * asteroid list and the count grows by 2^(size-1).
     *
     * @return the handle or -1
     */
    public int make(double x, double y, int size, int dir, float speed) {
        Objects o = w.objects;
        if (o.count() >= o.capacity || size < 1 || size > MAX_SIZE) {
            return -1;
        }
        float fx = (float) x;
        float fy = (float) y;
        if (w.wrap) {
            if (fx < 0) {
                fx += w.pixelWidth;
            } else if (fx >= w.pixelWidth) {
                fx -= w.pixelWidth;
            }
            if (fy < 0) {
                fy += w.pixelHeight;
            } else if (fy >= w.pixelHeight) {
                fy -= w.pixelHeight;
            }
        }
        if (fx < 0 || fx >= w.pixelWidth || fy < 0 || fy >= w.pixelHeight) {
            return -1;
        }
        int bx = (int) (fx / Const.BLOCK_SZ);
        int by = (int) (fy / Const.BLOCK_SZ);
        int type = w.map.block(bx, by);
        int cMask = BlockType.bit(BlockType.FILLED) | BlockType.bit(BlockType.FUEL) | BlockType.bit(BlockType.TARGET)
            | BlockType.bit(BlockType.TREASURE);
        if ((type & cMask) != 0) {
            return -1;
        }
        // C then checks the four diagonal types; REC_LD, REC_RU and REC_RD were already refused above (their
        // type values have bit 2 set), so only REC_LU remains: its solid half is below the diagonal.
        if (type == BlockType.REC_LU && fx - bx * Const.BLOCK_SZ < fy - by * Const.BLOCK_SZ) {
            return -1;
        }
        int h = o.alloc();
        ModeState m = w.modes;
        o.color[h] = Records.ObjColor.WHITE;
        o.owner[h] = Obj.NO_ID;
        o.team[h] = Const.TEAM_NOT_SET;
        o.type[h] = Obj.ASTEROID;
        o.initPixels(h, fx, fy);
        o.vx[h] = Trig.tcos(dir) * speed;
        o.vy[h] = Trig.tsin(dir) * speed;
        o.mass[h] = mass(size);
        o.life[h] = LIFE;
        o.turnSpeed[h] = (float) (0.02 + w.rfrac() * 0.05);
        o.rotation[h] = (int) (w.rfrac() * Const.RES);
        o.size[h] = size;
        o.info[h] = (int) (w.rfrac() * 256);
        int r = (int) radius(size);
        o.plRange[h] = r;
        o.plRadius[h] = r;
        o.fuseLife[h] = o.life[h] - 1;
        o.status[h] = Obj.GRAVITY;
        // the list holds the whole pool, so (unlike C's list malloc) adding cannot fail
        m.addAsteroid(h);
        m.asteroidNum += 1 << (size - 1);
        return h;
    }

    /**
     * C {@code Place_asteroid}.
     * <p>
     * BEHAVIOR: with asteroid concentrators, with probability asteroidConcentratorProb one is picked and the
     * asteroid appears within asteroidConcentratorRadius blocks of it (positions off a non-wrapping world are
     * retried); otherwise anywhere.
     * BEHAVIOR: up to 10 tries for a space-like block (not a base or wormhole) at least 5 blocks from every
     * human ship; then a size 1-4 asteroid in a random direction at speed 8-18.
     */
    void place() {
        ModeParams mp = w.modeParams;
        Concentrator con = null;
        int ncon = w.map.asteroidConcentrators().size();
        if (ncon > 0 && w.rfrac() < mp.asteroidConcentratorProb) {
            con = w.map.asteroidConcentrators().get((int) (w.rfrac() * ncon));
        }
        int px = 0;
        int py = 0;
        boolean okay = false;
        for (int count = 0; !okay; count++) {
            if (count >= 10) {
                return;
            }
            if (con != null) {
                int dir = (int) (w.rfrac() * Const.RES);
                int dist = (int) (w.rfrac() * ((mp.asteroidConcentratorRadius * Const.BLOCK_SZ) + 1));
                px = (int) ((con.bx() + 0.5) * Const.BLOCK_SZ + dist * Trig.tcos(dir));
                py = (int) ((con.by() + 0.5) * Const.BLOCK_SZ + dist * Trig.tsin(dir));
                if (w.wrap) {
                    if (px < 0) {
                        px += w.pixelWidth;
                    }
                    if (py < 0) {
                        py += w.pixelHeight;
                    }
                    if (px > w.pixelWidth) {
                        px -= w.pixelWidth;
                    }
                    if (py > w.pixelHeight) {
                        py -= w.pixelHeight;
                    }
                }
                if (px < 0 || px >= w.pixelWidth || py < 0 || py >= w.pixelHeight) {
                    continue;
                }
            } else {
                px = (int) (w.rfrac() * w.pixelWidth);
                py = (int) (w.rfrac() * w.pixelHeight);
            }
            int bx = px / Const.BLOCK_SZ;
            int by = py / Const.BLOCK_SZ;
            if ((BlockType.bit(w.map.block(bx, by)) & PLACE_BLOCKS) != 0) {
                okay = true;
                for (int k = 0; k < w.numPlayers; k++) {
                    Player pl = w.playerAt(k);
                    if (pl.robot) {
                        continue;
                    }
                    int dpx = (int) w.wrapDx(px - pl.px);
                    int dpy = (int) w.wrapDy(py - pl.py);
                    if (dpx * dpx + dpy * dpy < MIN_DIST * MIN_DIST) {
                        okay = false;
                        break;
                    }
                }
            }
        }
        int size = (int) (1 + w.rfrac() * MAX_SIZE);
        int dir = (int) (w.rfrac() * Const.RES);
        float speed = (float) (8 + w.rfrac() * 10);
        make(px, py, size, dir, speed);
    }

    // ================================================================ per frame

    /**
     * C {@code Asteroid_update}, right after the object loop.
     * <p>
     * BEHAVIOR: with more asteroids (in size-1 units) than allowed, live asteroids are marked to die (and so
     * break) in list order, only size-1 ones lowering the count, until it fits.
     * BEHAVIOR: live asteroids rotate (rotation += (int)(spin*RES) mod RES), then move ({@link Mover#moveObject}).
     * BEHAVIOR: with asteroidProb, while there is room for a largest asteroid, one is placed with probability
     * 1/chance per frame.
     */
    public void update() {
        ModeState m = w.modes;
        ModeParams mp = w.modeParams;
        Objects o = w.objects;
        if (m.asteroidCount > 0) {
            int num = m.asteroidNum;
            if (num > mp.asteroidMax) {
                for (int i = 0; i < m.asteroidCount; i++) {
                    int h = m.asteroidList[i];
                    if (o.life[h] > 0) {
                        o.life[h] = 0;
                        if (o.size[h] == 1) {
                            num--;
                        }
                    }
                    if (num <= mp.asteroidMax) {
                        break;
                    }
                }
            }
            for (int i = 0; i < m.asteroidCount; i++) {
                int h = m.asteroidList[i];
                if (o.life[h] > 0) {
                    o.rotation[h] = (o.rotation[h] + (int) (o.turnSpeed[h] * Const.RES)) % Const.RES;
                }
            }
            for (int i = 0; i < m.asteroidCount; i++) {
                int h = m.asteroidList[i];
                if (o.life[h] > 0) {
                    sim.mover.moveObject(h);
                }
            }
        }
        if (mp.asteroidChance > 0) {
            int incr = 1 << (MAX_SIZE - 1);
            if (m.asteroidNum + incr < mp.asteroidMax && (w.rfrac() * mp.asteroidChance) < 1.0f) {
                place();
            }
        }
    }

    // ================================================================ breaking

    /**
     * C {@code Break_asteroid}, from {@code Delete_shot} before the asteroid is freed.
     * <p>
     * BEHAVIOR: size 1: wreckage (pieces of mass/20..mass/3, total mass/2, at most 10) and 20-50 red debris of
     * half the mass, both slow (5-10) and short-lived (3-10).
     * BEHAVIOR: bigger: two asteroids one size smaller, split RES/4 off the flight direction at their radius,
     * flying RES/32-RES/16 to either side at (1 - dust) * speed / tcos(angle); the dust (mass * dust fraction)
     * becomes wreckage and debris like a size 1 asteroid.
     * BEHAVIOR: with asteroidMaxItems and probability asteroidItemProb, 1..asteroidMaxItems items drop at the
     * asteroid (C Choose_random_item, Make_item): a random kind, the asteroid's velocity plus up to 10 in a random
     * direction (as C ints), GRAVITY, RANDOM_ITEM with randomItemProb, a random pack size. With no item
     * probabilities (C indexes past its item table) that item is skipped after its draws.
     * BEHAVIOR: the asteroid leaves the list and the count shrinks by 2^(size-1).
     */
    public void broken(int h) {
        Objects o = w.objects;
        ModeState m = w.modes;
        ModeParams mp = w.modeParams;
        int size = o.size[h];
        // C's asteroid->pos: whole pixels (CLICK_TO_PIXEL shifts), passed on as DFLOAT
        float x = o.px[h];
        float y = o.py[h];
        float vx = o.vx[h];
        float vy = o.vy[h];
        if (size == 1) {
            float mass = o.mass[h] / 2;
            sim.wreckage.make(x, y, vx, vy, Obj.NO_ID, Const.TEAM_NOT_SET, mass / 20, mass / 3, mass, Obj.GRAVITY,
                Records.ObjColor.WHITE, 10, 0, Const.RES - 1, 5, 10, 3, 10);
            sim.debris.make(x, y, vx, vy, -1, Const.TEAM_NOT_SET, Obj.DEBRIS, mass, Obj.GRAVITY,
                Records.ObjColor.RED, Records.BurstKind.DEBRIS, 8, 20, 50, 0, Const.RES - 1, 5, 10, 3, 10);
        } else {
            float speed = (float) StrictMath.hypot(vx, vy);
            int dir = (int) XMath.findDir(vx, vy);
            float mass3 = (float) (o.mass[h] * DUST_FACT);
            int dir1 = (int) (dir - DELTA_DIR / 4 - (w.rfrac() * DELTA_DIR / 4)) & (Const.RES - 1);
            int dir2 = (int) (dir + DELTA_DIR / 4 + (w.rfrac() * DELTA_DIR / 4)) & (Const.RES - 1);
            float speed1 = (float) ((speed * (1 - DUST_FACT)) / Trig.tcos(Math.abs(dir - dir1)));
            float speed2 = (float) ((speed * (1 - DUST_FACT)) / Trig.tcos(Math.abs(dir2 - dir)));
            int splitDir = (dir - Const.RES / 4) & (Const.RES - 1);
            double radius = radius(size - 1);
            int x1 = (int) w.wrapXPixel(x + Trig.tcos(splitDir) * radius);
            int y1 = (int) w.wrapYPixel(y + Trig.tsin(splitDir) * radius);
            int x2 = (int) w.wrapXPixel(x - Trig.tcos(splitDir) * radius);
            int y2 = (int) w.wrapYPixel(y - Trig.tsin(splitDir) * radius);
            make(x1, y1, size - 1, dir1, speed1);
            make(x2, y2, size - 1, dir2, speed2);
            sim.wreckage.make(x, y, vx, vy, Obj.NO_ID, Const.TEAM_NOT_SET, mass3 / 20, mass3 / 3, mass3 / 2, Obj.GRAVITY,
                Records.ObjColor.WHITE, 10, 0, Const.RES - 1, 5, 10, 3, 10);
            sim.debris.make(x, y, vx, vy, -1, Const.TEAM_NOT_SET, Obj.DEBRIS, mass3 / 2, Obj.GRAVITY,
                Records.ObjColor.RED, Records.BurstKind.DEBRIS, 8, 20, 50, 0, Const.RES - 1, 5, 10, 3, 10);
        }
        if (mp.asteroidMaxItems > 0 && w.rfrac() < mp.asteroidItemProb) {
            int nitems = (int) (w.rfrac() * mp.asteroidMaxItems) + 1;
            for (int i = 0; i < nitems; i++) {
                int item = sim.items.chooseRandom();
                int itemDir = (int) (w.rfrac() * Const.RES);
                float itemSpeed = (float) (w.rfrac() * 10);
                float ivx = vx + itemSpeed * Trig.tcos(itemDir);
                float ivy = vy + itemSpeed * Trig.tsin(itemDir);
                int status = Obj.GRAVITY;
                if (w.rfrac() < w.items.randomItemProb) {
                    status |= Obj.RANDOM_ITEM;
                }
                if (item >= Item.COUNT) {
                    continue;
                }
                sim.items.make((int) x, (int) y, (int) ivx, (int) ivy, item, sim.items.packSize(item), status);
            }
        }
        Sounds.play(w, x, y, SoundEvent.ASTEROID_BREAK);
        m.asteroidNum -= 1 << (size - 1);
        m.removeAsteroid(h);
    }

    // ================================================================ collisions

    /**
     * C {@code Player_collides_with_asteroid}, followed by the elastic bounce (Delta_mv_elastic) the caller
     * does in C.
     * <p>
     * BEHAVIOR: the asteroid loses ASTEROID_FUEL_HIT(ED_PL_CRASH, size) life (at least down to 0); breaking it
     * (life 0) gives the ship asteroidPoints while its score is at most asteroidMaxScore.
     * BEHAVIOR: unless fully shielded the ship loses |(long)(2 * asteroid mass * |asteroid v|)| fuel.
     * BEHAVIOR: with asteroidCollisionMayKill a ship out of fuel or unshielded without armor dies ("NAME
     * smashed into an asteroid." when faster than it, else "NAME was hit by an asteroid.", Rate(0, score) *
     * unownedKillScoreMult, "[Asteroid]"); an unshielded armored ship uses armor.
     */
    public void playerHit(Player pl, int h) {
        Objects o = w.objects;
        ModeParams mp = w.modeParams;
        int size = o.size[h];
        float v = (float) StrictMath.hypot(o.vx[h], o.vy[h]);
        long cost = Math.abs((long) (2 * o.mass[h] * v));
        o.life[h] = (long) (o.life[h] + fuelHit(ED_PL_CRASH, size));
        if (o.life[h] < 0) {
            o.life[h] = 0;
        }
        if (o.life[h] == 0 && mp.asteroidPoints > 0 && pl.score <= mp.asteroidMaxScore) {
            sim.scoring.score(pl, mp.asteroidPoints, o.px[h] / Const.BLOCK_SZ, o.py[h] / Const.BLOCK_SZ, "");
        }
        if (!pl.fullyShielded()) {
            pl.addFuel(-cost);
        }
        if (mp.asteroidCollisionMayKill
            && (pl.fuel == 0 || ((pl.used & Has.SHIELD) == 0 && (pl.have & Has.ARMOR) == 0))) {
            pl.status |= Obj.KILLED;
            StringBuilder msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name);
            msg.append(pl.velocity > v ? " smashed into an asteroid." : " was hit by an asteroid.");
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, Obj.NO_ID, pl.id, 0f);
            sim.scoring.score(pl, -(Scoring.rate(0, pl.score) * w.params.unownedKillScoreMult), pl.bx, pl.by,
                "[Asteroid]");
            return;
        }
        if (mp.asteroidCollisionMayKill && (pl.used & Has.SHIELD) == 0 && (pl.have & Has.ARMOR) != 0) {
            sim.ships.hitArmor(pl);
        }
    }

    /**
     * C {@code AsteroidCollision}: each live asteroid in list order against nearby objects whose paths came
     * within both radii.
     * <p>
     * BEHAVIOR: skipped: unowned items, debris, sparks and wreckage not from a cannon; itself; balls connected to
     * a phased ship; other asteroids while this one is still overlapping after breaking (life &gt; fuse).
     * BEHAVIOR: a ball bounces off (and dies with treasureCollisionDestroys), damage ED_BALL_HIT; another
     * asteroid takes ASTEROID_FUEL_HIT(|2*m*v| of this one), damage |2*m*v| of the other, an elastic bounce and
     * fuse = life - 1; sparks are absorbed (no damage); debris and wreckage absorbed with |2*m*v| damage; an
     * unconfused mine dies (damage unchanged from the previous object, as C); shots: ED_SHOT_HIT; missiles:
     * ED_SMART_SHOT_HIT (no modifiers yet); anything else is absorbed without damage.
     * BEHAVIOR: a live asteroid past its fuse loses ASTEROID_FUEL_HIT(damage) life (at least to 0); breaking it
     * gives asteroidPoints to the object's owner (a ball's owner) while their score is at most asteroidMaxScore.
     */
    public void collide() {
        Objects o = w.objects;
        ModeState m = w.modes;
        ModeParams mp = w.modeParams;
        damage = 0;
        sound = false;
        for (int i = 0; i < m.asteroidCount; i++) {
            int ast = m.asteroidList[i];
            if (o.life[ast] <= 0) {
                continue;
            }
            w.grid.query(o.px[ast] / Const.BLOCK_SZ, o.py[ast] / Const.BLOCK_SZ, o.plRadius[ast] / Const.BLOCK_SZ + 1,
                300);
            int n = w.grid.resultCount;
            for (int j = 0; j < n; j++) {
                int h = w.grid.result[j];
                if (o.life[h] <= 0) {
                    continue;
                }
                int type = o.type[h];
                if ((type & (Obj.ITEM | Obj.DEBRIS | Obj.SPARK | Obj.WRECKAGE)) != 0 && o.owner[h] == Obj.NO_ID
                    && (o.status[h] & Obj.FROMCANNON) == 0) {
                    continue;
                }
                if (type == Obj.ASTEROID && o.life[ast] > o.fuseLife[ast]) {
                    continue;
                }
                if (h == ast) {
                    continue;
                }
                if (type == Obj.BALL && sim.balls.phased(h)) {
                    continue;
                }
                int radius = o.plRadius[ast] + o.plRadius[h];
                if (!sim.collisions.inRange(o.prevX[ast], o.prevY[ast], o.px[ast], o.py[ast], o.prevX[h], o.prevY[h],
                    o.px[h], o.py[h], radius)) {
                    continue;
                }
                switch (type) {
                    case Obj.BALL -> {
                        sim.objPhysics.repel(ast, h, radius);
                        if (mp.treasureCollisionDestroys) {
                            o.life[h] = 0;
                        }
                        damage = (float) Balls.ED_BALL_HIT;
                        sound = true;
                    }
                    case Obj.ASTEROID -> {
                        double hit = Math.abs(2 * o.mass[ast] * StrictMath.hypot(o.vx[ast], o.vy[ast]));
                        o.life[h] = (long) (o.life[h] - fuelHit(hit, o.size[h]));
                        damage = (float) -Math.abs(2 * o.mass[h] * StrictMath.hypot(o.vx[h], o.vy[h]));
                        sim.objPhysics.deltaMvElastic(ast, h);
                        o.fuseLife[h] = o.life[h] - 1;
                        sound = true;
                    }
                    case Obj.SPARK -> {
                        o.life[h] = 0;
                        sim.objPhysics.deltaMv(ast, h);
                        damage = 0;
                    }
                    case Obj.DEBRIS, Obj.WRECKAGE -> {
                        o.life[h] = 0;
                        damage = (float) -Math.abs(2 * o.mass[h] * StrictMath.hypot(o.vx[h], o.vy[h]));
                        sim.objPhysics.deltaMv(ast, h);
                    }
                    case Obj.MINE -> {
                        if ((o.status[h] & Obj.CONFUSED) == 0) {
                            o.life[h] = 0;
                        }
                    }
                    case Obj.SHOT, Obj.CANNON_SHOT -> {
                        o.life[h] = 0;
                        sim.objPhysics.deltaMv(ast, h);
                        damage = (float) Targets.ED_SHOT_HIT;
                        sound = true;
                    }
                    case Obj.SMART_SHOT, Obj.TORPEDO, Obj.HEAT_SHOT -> {
                        o.life[h] = 0;
                        sim.objPhysics.deltaMv(ast, h);
                        damage = (float) (Targets.ED_SMART_SHOT_HIT
                            / ((sim.targets.mini(h) + 1) * (sim.targets.power(h) + 1)));
                        sound = true;
                    }
                    default -> {
                        sim.objPhysics.deltaMv(ast, h);
                        damage = 0;
                    }
                }
                if (o.life[ast] > 0) {
                    if (o.life[ast] <= o.fuseLife[ast]) {
                        // C: long += float (DFLOAT damage), so the sum is a float
                        o.life[ast] = (long) ((float) o.life[ast] + (damage * LIFE) / (256 * 25 * hits(o.size[ast])));
                    }
                    if (sound) {
                        Sounds.play(w, o.px[ast], o.py[ast], SoundEvent.ASTEROID_HIT);
                    }
                    if (o.life[ast] < 0) {
                        o.life[ast] = 0;
                    }
                    if (o.life[ast] == 0 && mp.asteroidPoints > 0) {
                        int ownerId = type == Obj.BALL ? m.ballOwner[h] : o.owner[h];
                        Player pl = ownerId == Obj.NO_ID ? null : w.byId(ownerId);
                        if (pl != null && pl.score <= mp.asteroidMaxScore) {
                            sim.scoring.score(pl, mp.asteroidPoints, o.px[ast] / Const.BLOCK_SZ,
                                o.py[ast] / Const.BLOCK_SZ, "");
                        }
                    }
                }
            }
        }
    }
}
